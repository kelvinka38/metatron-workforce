const assert = require('node:assert/strict');
const test = require('node:test');
const http = require('node:http');

const {
  boundedFetch,
  callAnthropic,
  callGemini,
  callOllama,
  callOpenAi,
  createServer,
  runProviderChain,
} = require('./server');

function cfg(overrides = {}) {
  return {
    auth: 'test',
    port: 0,
    revision: 'test-sha',
    totalTimeoutMs: 120,
    ollamaTimeoutMs: 80,
    frontierTimeoutMs: 50,
    maxOutputTokens: 256,
    ollamaThink: undefined,
    ...overrides,
  };
}

test('bounded fetch aborts a hanging provider call', async () => {
  const originalFetch = global.fetch;
  global.fetch = async (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => {
      const error = new Error('aborted');
      error.name = 'AbortError';
      reject(error);
    }, { once: true });
  });
  try {
    await assert.rejects(
      () => boundedFetch('https://example.invalid', { method: 'POST' }, 10, 'gemini'),
      failure => failure.failureClass === 'timeout' && failure.message === 'gemini_timeout');
  } finally {
    global.fetch = originalFetch;
  }
});

test('provider order remains ollama then gemini then openai then anthropic', async () => {
  const seen = [];
  const fail = name => async () => {
    seen.push(name);
    const error = new Error(name + ' failed');
    error.failureClass = 'test_failure';
    throw error;
  };
  const outcome = await runProviderChain('prompt', [
    ['ollama', fail('ollama')],
    ['gemini', fail('gemini')],
    ['openai', async () => {
      seen.push('openai');
      return { text: 'ok', model: 'm', inputTokens: 1, outputTokens: 1, endpointId: 'openai' };
    }],
    ['anthropic', fail('anthropic')],
  ], cfg());

  assert.equal(outcome.status, 200);
  assert.deepEqual(seen, ['ollama', 'gemini', 'openai']);
  assert.equal(outcome.body.providerUsed, 'openai');
  assert.equal(outcome.body.fallbackOccurred, true);
});

test('whole-request deadline clamps provider timeout and returns deterministic 504', async () => {
  const timeouts = [];
  const provider = async (_prompt, timeoutMs) => {
    timeouts.push(timeoutMs);
    await new Promise(resolve => setTimeout(resolve, timeoutMs + 8));
    const error = new Error('timed out');
    error.failureClass = 'timeout';
    throw error;
  };
  const outcome = await runProviderChain('prompt', [
    ['ollama', provider],
    ['gemini', provider],
  ], cfg({ totalTimeoutMs: 35, ollamaTimeoutMs: 100, frontierTimeoutMs: 100 }));

  assert.equal(outcome.status, 504);
  assert.equal(outcome.body.error, 'cognition_deadline_exhausted');
  assert.ok(timeouts[0] <= 35 && timeouts[0] > 0);
  assert.equal(timeouts.length, 1);
});

test('completed provider chain before deadline returns deterministic 502', async () => {
  const fail = async () => {
    const error = new Error('no');
    error.failureClass = 'http_503';
    throw error;
  };
  const outcome = await runProviderChain('prompt', [
    ['ollama', fail],
    ['gemini', fail],
    ['openai', fail],
    ['anthropic', fail],
  ], cfg({ totalTimeoutMs: 500 }));

  assert.equal(outcome.status, 502);
  assert.equal(outcome.body.error, 'all_providers_failed');
  assert.equal(outcome.body.providerAttempts.length, 4);
  assert.deepEqual(outcome.body.providerAttempts.map(a => a.provider), ['ollama', 'gemini', 'openai', 'anthropic']);
});

test('all provider payloads enforce configured output token cap', async () => {
  const originalFetch = global.fetch;
  const originalEnv = { ...process.env };
  const bodies = [];
  global.fetch = async (_url, options) => {
    bodies.push(JSON.parse(options.body));
    return {
      ok: true,
      json: async () => ({
        response: 'ok',
        prompt_eval_count: 1,
        eval_count: 1,
        candidates: [{ content: { parts: [{ text: 'ok' }] } }],
        usageMetadata: { promptTokenCount: 1, candidatesTokenCount: 1 },
        choices: [{ message: { content: 'ok' } }],
        usage: { prompt_tokens: 1, completion_tokens: 1, input_tokens: 1, output_tokens: 1 },
        content: [{ text: 'ok' }],
        model: 'test',
      }),
    };
  };
  process.env.GEMINI_API_KEY = 'x';
  process.env.OPENAI_API_KEY = 'x';
  process.env.ANTHROPIC_API_KEY = 'x';
  try {
    const c = cfg({ maxOutputTokens: 256 });
    await callOllama('p', 50, c);
    await callGemini('p', 50, c);
    await callOpenAi('p', 50, c);
    await callAnthropic('p', 50, c);
  } finally {
    global.fetch = originalFetch;
    process.env = originalEnv;
  }

  assert.equal(bodies[0].options.num_predict, 256);
  assert.equal(bodies[1].generationConfig.maxOutputTokens, 256);
  assert.equal(bodies[2].max_tokens, 256);
  assert.equal(bodies[3].max_tokens, 256);
});

test('worker.cognition disables ollama thinking by default', async () => {
  let seenThink;
  const outcome = await runProviderChain('prompt', [
    ['ollama', async (_prompt, _timeoutMs, providerCfg) => {
      seenThink = providerCfg.ollamaThink;
      return { text: 'ok', model: 'qwen3:8b', inputTokens: 1, outputTokens: 1, endpointId: 'ollama' };
    }],
  ], cfg({ ollamaThink: undefined }), { capability: 'worker.cognition' });

  assert.equal(outcome.status, 200);
  assert.equal(seenThink, false);
});

function postCognitionRequest(server, body) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body);
    const req = http.request({
      hostname: '127.0.0.1',
      port: server.address().port,
      path: '/v1/cognition',
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(payload),
        authorization: 'Bearer test',
      },
    }, res => {
      let raw = '';
      res.on('data', chunk => { raw += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(raw) }));
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

test('a request-supplied maxOutputTokens overrides the server default and reaches the provider call', async () => {
  // Root-cause fix: previously the single global COGNITION_MAX_OUTPUT_TOKENS ceiling (256) applied
  // identically to every cognition request, truncating any response whose JSON had to carry generated
  // source/work-product content (e.g. workspace.file.write's "content"). Workforce now sends a
  // request-aware maxOutputTokens (see CognitiveOutputBudget on the Java side); the cognition-node must
  // honor it per request rather than always falling back to its own default.
  const originalFetch = global.fetch;
  const bodies = [];
  global.fetch = async (_url, options) => {
    bodies.push(JSON.parse(options.body));
    return { ok: true, json: async () => ({ response: 'ok', prompt_eval_count: 1, eval_count: 1 }) };
  };
  const server = createServer(cfg({ auth: 'test', maxOutputTokens: 256, maxOutputTokensCeiling: 8192 }));
  await new Promise(resolve => server.listen(0, resolve));
  try {
    const outcome = await postCognitionRequest(server, {
      requestId: 'req-content-generation',
      capability: 'worker.cognition',
      objective: 'write file',
      context: 'context',
      requiredOutput: 'strict json',
      maxOutputTokens: 6144,
    });
    assert.equal(outcome.status, 200);
    assert.equal(bodies[0].options.num_predict, 6144);
  } finally {
    server.close();
    global.fetch = originalFetch;
  }
});

test('a requested maxOutputTokens above the enforced ceiling is clamped, never applied verbatim', async () => {
  const originalFetch = global.fetch;
  const bodies = [];
  global.fetch = async (_url, options) => {
    bodies.push(JSON.parse(options.body));
    return { ok: true, json: async () => ({ response: 'ok', prompt_eval_count: 1, eval_count: 1 }) };
  };
  const server = createServer(cfg({ auth: 'test', maxOutputTokens: 256, maxOutputTokensCeiling: 8192 }));
  await new Promise(resolve => server.listen(0, resolve));
  try {
    const outcome = await postCognitionRequest(server, {
      requestId: 'req-unbounded-attempt',
      capability: 'worker.cognition',
      objective: 'write file',
      context: 'context',
      requiredOutput: 'strict json',
      maxOutputTokens: 999999,
    });
    assert.equal(outcome.status, 200);
    assert.equal(bodies[0].options.num_predict, 8192,
        'the enforced ceiling must apply even when a caller requests far more');
  } finally {
    server.close();
    global.fetch = originalFetch;
  }
});

test('a request that omits maxOutputTokens falls back to the configured server default', async () => {
  const originalFetch = global.fetch;
  const bodies = [];
  global.fetch = async (_url, options) => {
    bodies.push(JSON.parse(options.body));
    return { ok: true, json: async () => ({ response: 'ok', prompt_eval_count: 1, eval_count: 1 }) };
  };
  const server = createServer(cfg({ auth: 'test', maxOutputTokens: 1536, maxOutputTokensCeiling: 8192 }));
  await new Promise(resolve => server.listen(0, resolve));
  try {
    const outcome = await postCognitionRequest(server, {
      requestId: 'req-no-budget-hint',
      capability: 'worker.cognition',
      objective: 'select action',
      context: 'context',
      requiredOutput: 'strict json',
    });
    assert.equal(outcome.status, 200);
    assert.equal(bodies[0].options.num_predict, 1536);
  } finally {
    server.close();
    global.fetch = originalFetch;
  }
});

test('ollama provider leaves thinking unchanged outside worker cognition unless explicitly configured', async () => {
  const originalFetch = global.fetch;
  const bodies = [];
  global.fetch = async (_url, options) => {
    bodies.push(JSON.parse(options.body));
    return { ok: true, json: async () => ({ response: 'ok' }) };
  };
  try {
    await callOllama('p', 50, cfg({ ollamaThink: undefined }));
    await callOllama('p', 50, cfg({ ollamaThink: false }));
  } finally {
    global.fetch = originalFetch;
  }
  assert.equal(Object.hasOwn(bodies[0], 'think'), false);
  assert.equal(bodies[1].think, false);
});
