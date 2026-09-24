const http = require('http');
const https = require('https');
const { performance } = require('perf_hooks');

function positiveInt(value, fallback) {
  const parsed = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function optionalBoolean(value) {
  if (value === undefined || value === null || String(value).trim() === '') return undefined;
  const normalized = String(value).trim().toLowerCase();
  if (normalized === 'true' || normalized === '1') return true;
  if (normalized === 'false' || normalized === '0') return false;
  return undefined;
}

// Founder decision (2026-09-24): no paid LLM, CPU-only 4-vCPU / 8-GB host. qwen3:8b (~5 GB) left too little
// memory and read Worker prompts at ~20 tokens/s, so requests exceeded the 150 s Ollama budget; qwen3:4b
// (~2.5 GB) is roughly twice as fast and leaves headroom. OLLAMA_MODEL still overrides.
const DEFAULT_OLLAMA_MODEL = 'qwen3:4b';
const DEFAULT_OLLAMA_NUM_CTX = 8192;

function configFromEnv() {
  return {
    auth: process.env.METATRON_COGNITION_AUTH || '',
    port: positiveInt(process.env.PORT, 8091),
    revision: process.env.METATRON_COGNITION_REVISION || 'unknown',
    // CPU-only qwen3:4b measured 2026-09-24: ~55 prompt tokens/s, ~11 output tokens/s. A 6144-token
    // content-generation budget alone needs ~9-10 minutes, so the old 150 s paid-API-era budget timed out
    // every Worker call. Ordering stays: Ollama 600 s < node total 630 s < Workforce HTTP 660 s.
    totalTimeoutMs: positiveInt(process.env.METATRON_COGNITION_TOTAL_TIMEOUT_MS, 630000),
    ollamaTimeoutMs: positiveInt(process.env.OLLAMA_TIMEOUT_MS, 600000),
    // Free-tier Gemini is tried first (Founder decision 2026-09-24): the whole Gemini phase, across all rotated
    // models, is bounded so the local Ollama fallback still keeps most of the request budget.
    frontierTimeoutMs: positiveInt(process.env.FRONTIER_PROVIDER_TIMEOUT_MS, 180000),
    geminiModelTimeoutMs: positiveInt(process.env.GEMINI_MODEL_TIMEOUT_MS, 90000),
    // Default applied only when a request omits maxOutputTokens. Real Workforce callers (see
    // MetatronCognitionClient.Request/CognitiveOutputBudget) always send an explicit, request-aware
    // value; this default only covers callers of the raw HTTP contract that do not.
    maxOutputTokens: positiveInt(process.env.COGNITION_MAX_OUTPUT_TOKENS, 1536),
    // Hard ceiling enforced regardless of what a request asks for -- output generation must stay
    // bounded even if a caller is misconfigured or compromised.
    maxOutputTokensCeiling: positiveInt(process.env.COGNITION_MAX_OUTPUT_TOKENS_CEILING, 8192),
    ollamaThink: optionalBoolean(process.env.OLLAMA_THINK),
    // Ollama's default context is 4096 tokens; a larger Worker prompt is silently truncated. 8192 fits the
    // default qwen3:4b's KV cache on the current 4-vCPU / 8-GB host; size it to free host memory.
    ollamaNumCtx: positiveInt(process.env.OLLAMA_NUM_CTX, DEFAULT_OLLAMA_NUM_CTX),
  };
}

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(json) });
  res.end(json);
}

function classifiedError(failureClass, message) {
  const error = new Error(message || failureClass);
  error.failureClass = failureClass;
  return error;
}

async function boundedFetch(url, options, timeoutMs, provider) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), Math.max(1, timeoutMs));
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (failure) {
    if (failure && failure.name === 'AbortError') {
      throw classifiedError('timeout', provider + '_timeout');
    }
    throw classifiedError('network_error', provider + '_network_error');
  } finally {
    clearTimeout(timeout);
  }
}

// A non-streaming Ollama /api/generate sends its response headers only once generation is complete, which
// on the CPU-only node legitimately takes up to OLLAMA_TIMEOUT_MS (600 s). Node's global fetch (undici)
// enforces its own hidden 300 s headersTimeout regardless of our AbortController, so every long local call
// was cut at exactly ~300 s as network_error (production 2026-09-24, case-0e3a655f: "provider_failed ollama
// network_error 300807" while qwen3:4b was still generating). node:http has no such hidden deadline; this
// call is bounded only by timeoutMs.
function postJsonWithoutHiddenDeadline(url, payload, timeoutMs, provider) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const client = target.protocol === 'https:' ? https : http;
    const body = JSON.stringify(payload);
    let settled = false;
    let request;
    const settle = (fn, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn(value);
    };
    const timer = setTimeout(() => {
      settle(reject, classifiedError('timeout', provider + '_timeout'));
      if (request) request.destroy();
    }, Math.max(1, timeoutMs));
    request = client.request(target, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) },
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => settle(resolve, {
        status: response.statusCode,
        text: Buffer.concat(chunks).toString('utf8'),
      }));
      response.on('error', () => settle(reject, classifiedError('network_error', provider + '_network_error')));
    });
    request.on('error', () => settle(reject, classifiedError('network_error', provider + '_network_error')));
    request.end(body);
  });
}

const JSON_TEMPLATE_MARKER = 'Return ONLY JSON:';

// Derives a JSON schema from the Worker instructions' own output template, e.g.
// {"actionRef":"...","inputs":{"key":"value"},"rationale":"..."} or {"decision":"CONTINUE|COMPLETE|FAILED",...}.
// Every template key becomes required; string placeholders become strings, a pipe-separated upper-case
// placeholder becomes an enum, and an object placeholder becomes a string-valued map. Returns null when the
// prompt carries no parseable template, so the caller falls back to plain JSON mode.
function outputSchemaFromPrompt(prompt) {
  const text = String(prompt || '');
  const marker = text.lastIndexOf(JSON_TEMPLATE_MARKER);
  if (marker < 0) return null;
  const start = text.indexOf('{', marker);
  if (start < 0) return null;
  let depth = 0;
  let inString = false;
  let end = -1;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (ch === '\\') i++;
      else if (ch === '"') inString = false;
    } else if (ch === '"') inString = true;
    else if (ch === '{') depth++;
    else if (ch === '}' && --depth === 0) { end = i; break; }
  }
  if (end < 0) return null;
  let template;
  try {
    template = JSON.parse(text.slice(start, end + 1));
  } catch (_invalid) {
    return null;
  }
  if (!template || typeof template !== 'object' || Array.isArray(template)) return null;
  const properties = {};
  for (const [key, example] of Object.entries(template)) {
    if (example && typeof example === 'object' && !Array.isArray(example)) {
      properties[key] = { type: 'object', additionalProperties: { type: 'string' } };
    } else if (typeof example === 'string' && /^[A-Z][A-Z_]*(\|[A-Z][A-Z_]*)+$/.test(example)) {
      properties[key] = { type: 'string', enum: example.split('|') };
    } else {
      properties[key] = { type: 'string' };
    }
  }
  const keys = Object.keys(properties);
  if (keys.length === 0) return null;
  return { type: 'object', properties, required: keys };
}

async function callOllama(prompt, timeoutMs, cfg = configFromEnv()) {
  const base = process.env.OLLAMA_URL || 'http://metatron-ollama:11434';
  const model = process.env.OLLAMA_MODEL || DEFAULT_OLLAMA_MODEL;
  const options = { num_predict: cfg.maxOutputTokens };
  if (cfg.ollamaNumCtx > 0) options.num_ctx = cfg.ollamaNumCtx;
  const body = { model, prompt, stream: false, options };
  if (cfg.ollamaThink !== undefined) body.think = cfg.ollamaThink;
  if (cfg.ollamaJsonSchema) body.format = cfg.ollamaJsonSchema;
  else if (cfg.ollamaJsonFormat) body.format = 'json';
  const r = await postJsonWithoutHiddenDeadline(base + '/api/generate', body, timeoutMs, 'ollama');
  if (r.status < 200 || r.status >= 300) throw classifiedError('http_' + r.status, 'ollama_http_' + r.status);
  let data;
  try {
    data = JSON.parse(r.text);
  } catch (_invalid) {
    throw classifiedError('provider_error', 'ollama_invalid_json');
  }
  return {
    text: data.response || '',
    model,
    inputTokens: data.prompt_eval_count || 0,
    outputTokens: data.eval_count || 0,
    endpointId: 'ollama',
  };
}

const DEFAULT_GEMINI_MODELS = ['gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-3.5-flash-lite'];
// Retryable on another free-tier model: rate limit, overload/server error, unknown model, timeout, network.
// A 400/401/403 (bad request or key) would fail identically on every model, so it stops the rotation.
const GEMINI_ROTATE_ON = new Set(['http_404', 'http_429', 'http_500', 'http_502', 'http_503', 'http_504', 'timeout', 'network_error', 'empty_response', 'truncated']);
const GEMINI_THINKING_HEADROOM_TOKENS = 8192;
const GEMINI_MAX_OUTPUT_TOKENS = 65536;

function geminiModels() {
  const configured = String(process.env.GEMINI_MODELS || '').split(',').map((m) => m.trim()).filter(Boolean);
  const primary = String(process.env.GEMINI_MODEL || '').trim();
  const ordered = [...(primary ? [primary] : []), ...(configured.length ? configured : DEFAULT_GEMINI_MODELS)];
  return [...new Set(ordered)];
}

async function callGeminiModel(model, key, prompt, timeoutMs, cfg) {
  const base = process.env.GEMINI_API_BASE_URL || 'https://generativelanguage.googleapis.com/v1beta/models';
  // Gemini 3.x flash models think before answering, and thinking tokens count against maxOutputTokens: with
  // the Worker's 1536-token selection budget the visible JSON was cut mid-object (production 2026-09-24,
  // case-11a08089 VERIFY x3: '{ "actionRef": "workspace.test.run", ... "rationale": "Run governed').
  // Free-tier output costs nothing, so thinking gets its own headroom on top of the requested answer budget.
  const generationConfig = {
    maxOutputTokens: Math.min(cfg.maxOutputTokens + (cfg.geminiThinkingHeadroomTokens ?? GEMINI_THINKING_HEADROOM_TOKENS),
      GEMINI_MAX_OUTPUT_TOKENS),
  };
  if (cfg.jsonOutput) generationConfig.responseMimeType = 'application/json';
  const r = await boundedFetch(base + '/' + model + ':generateContent?key=' + encodeURIComponent(key), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig }),
  }, timeoutMs, 'gemini');
  if (!r.ok) throw classifiedError('http_' + r.status, 'gemini_http_' + r.status);
  const data = await r.json();
  const parts = (data.candidates && data.candidates[0] && data.candidates[0].content
    && data.candidates[0].content.parts) || [];
  const text = parts.filter((part) => part && typeof part.text === 'string' && !part.thought)
    .map((part) => part.text).join('');
  const finishReason = data.candidates && data.candidates[0] && data.candidates[0].finishReason;
  if (finishReason === 'MAX_TOKENS') throw classifiedError('truncated', 'gemini_truncated_' + model);
  if (!text) throw classifiedError('empty_response', 'gemini_empty_response');
  const usage = data.usageMetadata || {};
  return { text, model, inputTokens: usage.promptTokenCount || 0, outputTokens: usage.candidatesTokenCount || 0, endpointId: 'gemini' };
}

// Free-tier models share one key but have separate quotas and capacity, so a 429/503 on one model says
// nothing about the next (metatron-core rotates the same way). Tries each configured model in order within
// timeoutMs (the whole Gemini phase); each model attempt is bounded by geminiModelTimeoutMs.
async function callGemini(prompt, timeoutMs, cfg = configFromEnv()) {
  const key = process.env.GEMINI_API_KEY || '';
  if (!key) throw classifiedError('not_configured', 'gemini_not_configured');
  const phaseDeadline = performance.now() + Math.max(1, timeoutMs);
  let lastFailure = classifiedError('not_configured', 'gemini_no_models');
  for (const model of geminiModels()) {
    const remaining = Math.floor(phaseDeadline - performance.now());
    if (remaining <= 0) break;
    const attemptMs = Math.max(1, Math.min(cfg.geminiModelTimeoutMs || remaining, remaining));
    try {
      return await callGeminiModel(model, key, prompt, attemptMs, cfg);
    } catch (failure) {
      lastFailure = failure;
      const klass = failureClass(failure);
      console.error('gemini_model_failed', model, klass);
      if (!GEMINI_ROTATE_ON.has(klass)) throw failure;
    }
  }
  throw lastFailure;
}

// Founder rule (2026-09-24): no LLM that requires paid credit. OpenAI and Anthropic are credit-billed and
// are therefore not part of this chain at all (not merely unconfigured), matching the ratified
// WORKER_ORIGINATED_PAID_EXTERNAL_INFERENCE = 0 / "no paid frontier credentials in the Cognition Node"
// contract (docs/ARCHITECTURE/INTELLIGENCE/12). Gemini stays only as a free-tier fallback and is skipped
// when GEMINI_API_KEY is unset.
// Founder decision (2026-09-24, revised): free provider first, local Ollama only when it is unavailable.
// Free-tier Gemini (rotated across models) answers Worker cognition far faster and more reliably than
// qwen3:4b on the 4-vCPU CPU-only host; Ollama remains the no-network, no-quota fallback. No credit-billed
// provider is ever configured.
function providerList() {
  return [
    ['gemini', callGemini],
    ['ollama', callOllama],
  ];
}

function failureClass(error) {
  if (error && error.failureClass) return String(error.failureClass);
  if (error && error.name === 'AbortError') return 'timeout';
  return 'provider_error';
}

async function runProviderChain(prompt, providers = providerList(), cfg = configFromEnv(), requestOptions = {}) {
  const started = performance.now();
  const deadline = started + cfg.totalTimeoutMs;
  const attempts = [];

  for (let i = 0; i < providers.length; i++) {
    const remainingMs = Math.floor(deadline - performance.now());
    if (remainingMs <= 0) {
      return { status: 504, body: { error: 'cognition_deadline_exhausted', providerAttempts: attempts } };
    }

    const [name, fn] = providers[i];
    const configuredTimeoutMs = name === 'ollama' ? cfg.ollamaTimeoutMs : cfg.frontierTimeoutMs;
    const timeoutMs = Math.max(1, Math.min(configuredTimeoutMs, remainingMs));
    const attemptStart = performance.now();
    try {
      // Worker cognition always parses a JSON object (GeneralCognitiveWorkerBrain.completeObject). Even with
      // thinking off, qwen3:4b sometimes answered in prose ("We are given a complex objective ...") and the
      // step failed with "cognitive provider returned no JSON object" (production 2026-09-24, case-394285d9
      // PREPARE). Ollama's JSON mode constrains decoding to a valid JSON value.
      // JSON mode alone still let qwen3:4b return valid JSON without the required keys ("cognitive response
      // missing actionRef", production 2026-09-24, case-eff6d8e2 PRODUCE x3). When the Worker instructions carry
      // their code-owned "Return ONLY JSON: {...}" template, Ollama gets that shape as a JSON schema so decoding
      // is constrained to exactly those keys.
      const workerCognition = name === 'ollama' && requestOptions.capability === 'worker.cognition';
      const providerConfig = name === 'gemini' && requestOptions.capability === 'worker.cognition'
        ? { ...cfg, jsonOutput: true }
        : workerCognition
        ? {
          ...cfg,
          ollamaThink: cfg.ollamaThink === undefined ? false : cfg.ollamaThink,
          ollamaJsonFormat: true,
          ollamaJsonSchema: outputSchemaFromPrompt(prompt),
        }
        : cfg;
      const result = await fn(prompt, timeoutMs, providerConfig);
      if (!result.text) throw classifiedError('empty_response', name + '_empty_response');
      const totalLatencyMs = Math.max(0, Math.round(performance.now() - started));
      return {
        status: 200,
        body: {
          result: result.text,
          text: result.text,
          modelIdentity: result.model,
          model: result.model,
          endpointId: 'metatron-cognition-node:' + cfg.revision + ':' + result.endpointId,
          usage: { inputTokens: result.inputTokens, outputTokens: result.outputTokens },
          providerUsed: name,
          latencyMs: totalLatencyMs,
          providerLatencyMs: Math.max(0, Math.round(performance.now() - attemptStart)),
          fallbackOccurred: i > 0,
          providerAttempts: attempts,
          revision: cfg.revision,
        },
      };
    } catch (failure) {
      const durationMs = Math.max(0, Math.round(performance.now() - attemptStart));
      const klass = failureClass(failure);
      console.error('provider_failed', name, klass, durationMs);
      attempts.push({ provider: name, durationMs, failureClass: klass });
      if (performance.now() >= deadline) {
        return { status: 504, body: { error: 'cognition_deadline_exhausted', providerAttempts: attempts, revision: cfg.revision } };
      }
    }
  }

  return { status: 502, body: { error: 'all_providers_failed', providerAttempts: attempts, revision: cfg.revision } };
}

function buildPrompt(body) {
  const objective = String(body.objective || '');
  const context = String(body.context || '');
  const requiredOutput = String(body.requiredOutput || '');
  const evidence = Array.isArray(body.evidenceReferences) ? body.evidenceReferences : [];
  return 'Objective:\n' + objective + '\n\nContext:\n' + context
    + '\n\nRequired output:\n' + requiredOutput + '\n\nEvidence references:\n' + evidence.join('\n');
}

function createServer(cfg = configFromEnv()) {
  return http.createServer((req, res) => {
    if (req.url === '/healthz') {
      return send(res, 200, {
        status: 'ok',
        revision: cfg.revision,
        model: process.env.OLLAMA_MODEL || DEFAULT_OLLAMA_MODEL,
        totalTimeoutMs: cfg.totalTimeoutMs,
        ollamaTimeoutMs: cfg.ollamaTimeoutMs,
        frontierTimeoutMs: cfg.frontierTimeoutMs,
        maxOutputTokens: cfg.maxOutputTokens,
        maxOutputTokensCeiling: cfg.maxOutputTokensCeiling,
        workerCognitionOllamaThink: cfg.ollamaThink === undefined ? false : cfg.ollamaThink,
        providerOrder: providerList().map(([name]) => name),
      });
    }
    if (req.method !== 'POST' || req.url !== '/v1/cognition') return send(res, 404, { error: 'not_found' });
    const auth = req.headers.authorization || '';
    if (!cfg.auth || auth !== 'Bearer ' + cfg.auth) return send(res, 401, { error: 'unauthorized' });

    let raw = '';
    let tooLarge = false;
    req.on('data', chunk => {
      raw += chunk;
      if (raw.length > 2000000) tooLarge = true;
    });
    req.on('end', async () => {
      if (tooLarge) return send(res, 413, { error: 'request_too_large' });
      let body;
      try { body = JSON.parse(raw); } catch { return send(res, 400, { error: 'invalid_json' }); }
      const requestId = String(body.requestId || '');
      const requestedMaxOutputTokens = positiveInt(body.maxOutputTokens, cfg.maxOutputTokens);
      const effectiveCfg = {
        ...cfg,
        maxOutputTokens: Math.min(requestedMaxOutputTokens, cfg.maxOutputTokensCeiling),
      };
      const outcome = await runProviderChain(
        buildPrompt(body), providerList(), effectiveCfg, { capability: String(body.capability || '') });
      if (outcome.status === 200) outcome.body.requestReference = requestId;
      return send(res, outcome.status, outcome.body);
    });
  });
}

if (require.main === module) {
  const cfg = configFromEnv();
  createServer(cfg).listen(cfg.port, () => {
    console.log('metatron-cognition-node listening on ' + cfg.port + ' revision=' + cfg.revision);
  });
}

module.exports = {
  geminiModels,
  outputSchemaFromPrompt,
  postJsonWithoutHiddenDeadline,
  boundedFetch,
  buildPrompt,
  callGemini,
  callOllama,
  configFromEnv,
  createServer,
  providerList,
  runProviderChain,
};
