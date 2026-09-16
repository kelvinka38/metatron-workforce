const http = require('http');

const AUTH = process.env.METATRON_COGNITION_AUTH || '';
const PORT = process.env.PORT || 8091;

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(json) });
  res.end(json);
}

async function callOllama(prompt) {
  const base = process.env.OLLAMA_URL || 'http://metatron-ollama:11434';
  const model = process.env.OLLAMA_MODEL || 'llama3.2:1b';
  // Root-cause fix (2026-09-16, found live in Phase 3 production acceptance): 45s was tuned against a
  // trivial 2-word test prompt. Real Worker-cognition prompts carry full Position-constitution context
  // and can legitimately need much longer on this CPU-only host, especially with qwen3's "thinking"
  // mode and under memory pressure -- observed aborting repeatedly at 45s while Ollama was still
  // actively computing (confirmed via `ollama ps` showing 100% CPU), cascading into a full outage
  // because Gemini/OpenAI/Anthropic each happened to be degraded/exhausted at the same moment. Raised
  // to comfortably exceed HttpMetatronCognitionClient's own 120s Java-side timeout is wrong -- Ollama
  // must resolve (success or real failure) before that caller gives up, not race it. Bounded below the
  // caller's timeout with headroom for the fallback chain that follows.
  const timeoutMs = Number(process.env.OLLAMA_TIMEOUT_MS || 100000);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {

    const r = await fetch(base + '/api/generate', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ model, prompt, stream: false }),
      signal: controller.signal,
    });
    if (!r.ok) throw new Error('ollama_http_' + r.status + ':' + (await r.text()).slice(0, 300));
    const data = await r.json();
    const text = data.response || '';
    return {
      text, model,
      inputTokens: data.prompt_eval_count || 0,
      outputTokens: data.eval_count || 0,
      endpointId: 'ollama',
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function callGemini(prompt) {
  const key = process.env.GEMINI_API_KEY || '';
  if (!key) throw new Error('gemini_not_configured');
  const model = process.env.GEMINI_MODEL || 'gemini-3.7-flash';
  const r = await fetch('https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent?key=' + key, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] }),
  });
  if (!r.ok) throw new Error('gemini_http_' + r.status + ':' + (await r.text()).slice(0, 300));
  const data = await r.json();
  const text = (data.candidates && data.candidates[0] && data.candidates[0].content
    && data.candidates[0].content.parts && data.candidates[0].content.parts[0]
    && data.candidates[0].content.parts[0].text) || '';
  const usage = data.usageMetadata || {};
  return { text, model, inputTokens: usage.promptTokenCount || 0, outputTokens: usage.candidatesTokenCount || 0, endpointId: 'gemini' };
}

async function callOpenAi(prompt) {
  const key = process.env.OPENAI_API_KEY || '';
  if (!key) throw new Error('openai_not_configured');
  const model = process.env.OPENAI_MODEL || 'gpt-4.1-mini';
  const r = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + key },
    body: JSON.stringify({ model, messages: [{ role: 'user', content: prompt }] }),
  });
  if (!r.ok) throw new Error('openai_http_' + r.status + ':' + (await r.text()).slice(0, 300));
  const data = await r.json();
  const text = (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '';
  const usage = data.usage || {};
  return { text, model: data.model || model, inputTokens: usage.prompt_tokens || 0, outputTokens: usage.completion_tokens || 0, endpointId: 'openai' };
}

async function callAnthropic(prompt) {
  const key = process.env.ANTHROPIC_API_KEY || '';
  if (!key) throw new Error('anthropic_not_configured');
  const model = process.env.ANTHROPIC_MODEL || 'claude-sonnet-4-20250514';
  const r = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-api-key': key, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify({ model, max_tokens: 1024, messages: [{ role: 'user', content: prompt }] }),
  });
  if (!r.ok) throw new Error('anthropic_http_' + r.status + ':' + (await r.text()).slice(0, 300));
  const data = await r.json();
  const text = (Array.isArray(data.content) && data.content[0] && data.content[0].text) || '';
  const usage = data.usage || {};
  return { text, model: data.model || model, inputTokens: usage.input_tokens || 0, outputTokens: usage.output_tokens || 0, endpointId: 'anthropic' };
}

// PHASE 2 (2026-09-16): Ollama (Metatron-owned, self-hosted) is PRIMARY -- this is the actual
// "METATRON_OWNED cognition" path the Worker system is supposed to use, not a paid frontier call.
// Gemini/OpenAI/Anthropic remain configured as fallback only, in the same order as before, in case
// Ollama is unavailable or fails -- none of their credentials were touched or removed. Verified live
// (2026-09-16): Ollama/qwen3:8b succeeds as primary (fallbackOccurred=false), and Gemini correctly
// takes over when Ollama is stopped (fallbackOccurred=true, providerAttempts=["ollama=..."]). See
// smoke-test.sh in this directory to reproduce.
const PROVIDERS = [
  ['ollama', callOllama],
  ['gemini', callGemini],
  ['openai', callOpenAi],
  ['anthropic', callAnthropic],
];

const server = http.createServer((req, res) => {
  if (req.url === '/healthz') return send(res, 200, { status: 'ok' });
  if (req.method !== 'POST' || req.url !== '/v1/cognition') {
    return send(res, 404, { error: 'not_found' });
  }
  const auth = req.headers['authorization'] || '';
  if (!AUTH || auth !== `Bearer ${AUTH}`) {
    return send(res, 401, { error: 'unauthorized' });
  }
  let raw = '';
  req.on('data', chunk => { raw += chunk; if (raw.length > 2000000) req.destroy(); });
  req.on('end', async () => {
    let body;
    try { body = JSON.parse(raw); } catch { return send(res, 400, { error: 'invalid_json' }); }
    const requestId = String(body.requestId || '');
    const objective = String(body.objective || '');
    const context = String(body.context || '');
    const requiredOutput = String(body.requiredOutput || '');
    const evidence = Array.isArray(body.evidenceReferences) ? body.evidenceReferences : [];
    const prompt = 'Objective:\n' + objective + '\n\nContext:\n' + context
      + '\n\nRequired output:\n' + requiredOutput + '\n\nEvidence references:\n' + evidence.join('\n');

    const errors = [];
    for (let i = 0; i < PROVIDERS.length; i++) {
      const [name, fn] = PROVIDERS[i];
      const attemptStart = Date.now();
      try {
        const result = await fn(prompt);
        if (!result.text) throw new Error(name + '_empty_response');
        const latencyMs = Date.now() - attemptStart;
        return send(res, 200, {
          result: result.text,
          text: result.text,
          modelIdentity: result.model,
          model: result.model,
          endpointId: 'metatron-cognition-node-v6:' + result.endpointId,
          requestReference: requestId,
          usage: { inputTokens: result.inputTokens, outputTokens: result.outputTokens },
          // Phase 3 evidence support: makes fallback (if any) and latency visible to callers/logs
          // without requiring a separate audit call.
          providerUsed: name,
          latencyMs,
          fallbackOccurred: i > 0,
          providerAttempts: errors.slice(),
        });
      } catch (failure) {
        console.error('provider_failed', name, String((failure && failure.message) || failure));
        errors.push(name + '=' + String((failure && failure.message) || failure));
      }
    }
    return send(res, 502, { error: 'all_providers_failed', detail: errors.join(' | ').slice(0, 1500), providerAttempts: errors });
  });
});

server.listen(PORT, () => console.log('metatron-cognition-node listening on ' + PORT));
