const http = require('http');
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

function configFromEnv() {
  return {
    auth: process.env.METATRON_COGNITION_AUTH || '',
    port: positiveInt(process.env.PORT, 8091),
    revision: process.env.METATRON_COGNITION_REVISION || 'unknown',
    totalTimeoutMs: positiveInt(process.env.METATRON_COGNITION_TOTAL_TIMEOUT_MS, 210000),
    ollamaTimeoutMs: positiveInt(process.env.OLLAMA_TIMEOUT_MS, 150000),
    frontierTimeoutMs: positiveInt(process.env.FRONTIER_PROVIDER_TIMEOUT_MS, 18000),
    // Default applied only when a request omits maxOutputTokens. Real Workforce callers (see
    // MetatronCognitionClient.Request/CognitiveOutputBudget) always send an explicit, request-aware
    // value; this default only covers callers of the raw HTTP contract that do not.
    maxOutputTokens: positiveInt(process.env.COGNITION_MAX_OUTPUT_TOKENS, 1536),
    // Hard ceiling enforced regardless of what a request asks for -- output generation must stay
    // bounded even if a caller is misconfigured or compromised.
    maxOutputTokensCeiling: positiveInt(process.env.COGNITION_MAX_OUTPUT_TOKENS_CEILING, 8192),
    ollamaThink: optionalBoolean(process.env.OLLAMA_THINK),
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

async function callOllama(prompt, timeoutMs, cfg = configFromEnv()) {
  const base = process.env.OLLAMA_URL || 'http://metatron-ollama:11434';
  const model = process.env.OLLAMA_MODEL || 'llama3.2:1b';
  const body = { model, prompt, stream: false, options: { num_predict: cfg.maxOutputTokens } };
  if (cfg.ollamaThink !== undefined) body.think = cfg.ollamaThink;
  const r = await boundedFetch(base + '/api/generate', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  }, timeoutMs, 'ollama');
  if (!r.ok) throw classifiedError('http_' + r.status, 'ollama_http_' + r.status);
  const data = await r.json();
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

async function callOpenAi(prompt, timeoutMs, cfg = configFromEnv()) {
  const key = process.env.OPENAI_API_KEY || '';
  if (!key) throw classifiedError('not_configured', 'openai_not_configured');
  const model = process.env.OPENAI_MODEL || 'gpt-4.1-mini';
  const url = process.env.OPENAI_API_URL || 'https://api.openai.com/v1/chat/completions';
  const r = await boundedFetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + key },
    body: JSON.stringify({ model, max_tokens: cfg.maxOutputTokens, messages: [{ role: 'user', content: prompt }] }),
  }, timeoutMs, 'openai');
  if (!r.ok) throw classifiedError('http_' + r.status, 'openai_http_' + r.status);
  const data = await r.json();
  const text = (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '';
  const usage = data.usage || {};
  return { text, model: data.model || model, inputTokens: usage.prompt_tokens || 0, outputTokens: usage.completion_tokens || 0, endpointId: 'openai' };
}

async function callAnthropic(prompt, timeoutMs, cfg = configFromEnv()) {
  const key = process.env.ANTHROPIC_API_KEY || '';
  if (!key) throw classifiedError('not_configured', 'anthropic_not_configured');
  const model = process.env.ANTHROPIC_MODEL || 'claude-sonnet-4-20250514';
  const url = process.env.ANTHROPIC_API_URL || 'https://api.anthropic.com/v1/messages';
  const r = await boundedFetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-api-key': key, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify({ model, max_tokens: cfg.maxOutputTokens, messages: [{ role: 'user', content: prompt }] }),
  }, timeoutMs, 'anthropic');
  if (!r.ok) throw classifiedError('http_' + r.status, 'anthropic_http_' + r.status);
  const data = await r.json();
  const text = (Array.isArray(data.content) && data.content[0] && data.content[0].text) || '';
  const usage = data.usage || {};
  return { text, model: data.model || model, inputTokens: usage.input_tokens || 0, outputTokens: usage.output_tokens || 0, endpointId: 'anthropic' };
}

function providerList() {
  return [
    ['ollama', callOllama],
    ['gemini', callGemini],
    ['openai', callOpenAi],
    ['anthropic', callAnthropic],
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
        model: process.env.OLLAMA_MODEL || 'llama3.2:1b',
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
  boundedFetch,
  buildPrompt,
  callAnthropic,
  callGemini,
  callOllama,
  callOpenAi,
  configFromEnv,
  createServer,
  providerList,
  runProviderChain,
};
