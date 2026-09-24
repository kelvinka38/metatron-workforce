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
    frontierTimeoutMs: positiveInt(process.env.FRONTIER_PROVIDER_TIMEOUT_MS, 18000),
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

async function callOllama(prompt, timeoutMs, cfg = configFromEnv()) {
  const base = process.env.OLLAMA_URL || 'http://metatron-ollama:11434';
  const model = process.env.OLLAMA_MODEL || DEFAULT_OLLAMA_MODEL;
  const options = { num_predict: cfg.maxOutputTokens };
  if (cfg.ollamaNumCtx > 0) options.num_ctx = cfg.ollamaNumCtx;
  const body = { model, prompt, stream: false, options };
  if (cfg.ollamaThink !== undefined) body.think = cfg.ollamaThink;
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

async function callGemini(prompt, timeoutMs, cfg = configFromEnv()) {
  const key = process.env.GEMINI_API_KEY || '';
  if (!key) throw classifiedError('not_configured', 'gemini_not_configured');
  const model = process.env.GEMINI_MODEL || 'gemini-3.7-flash';
  const base = process.env.GEMINI_API_BASE_URL || 'https://generativelanguage.googleapis.com/v1beta/models';
  const r = await boundedFetch(base + '/' + model + ':generateContent?key=' + encodeURIComponent(key), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { maxOutputTokens: cfg.maxOutputTokens },
    }),
  }, timeoutMs, 'gemini');
  if (!r.ok) throw classifiedError('http_' + r.status, 'gemini_http_' + r.status);
  const data = await r.json();
  const text = (data.candidates && data.candidates[0] && data.candidates[0].content
    && data.candidates[0].content.parts && data.candidates[0].content.parts[0]
    && data.candidates[0].content.parts[0].text) || '';
  const usage = data.usageMetadata || {};
  return { text, model, inputTokens: usage.promptTokenCount || 0, outputTokens: usage.candidatesTokenCount || 0, endpointId: 'gemini' };
}

// Founder rule (2026-09-24): no LLM that requires paid credit. OpenAI and Anthropic are credit-billed and
// are therefore not part of this chain at all (not merely unconfigured), matching the ratified
// WORKER_ORIGINATED_PAID_EXTERNAL_INFERENCE = 0 / "no paid frontier credentials in the Cognition Node"
// contract (docs/ARCHITECTURE/INTELLIGENCE/12). Gemini stays only as a free-tier fallback and is skipped
// when GEMINI_API_KEY is unset.
function providerList() {
  return [
    ['ollama', callOllama],
    ['gemini', callGemini],
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
      const providerConfig = name === 'ollama'
        && requestOptions.capability === 'worker.cognition'
        && cfg.ollamaThink === undefined
        ? { ...cfg, ollamaThink: false }
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
