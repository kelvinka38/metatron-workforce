const assert = require('node:assert/strict');
const test = require('node:test');

const {
  boundedFetch,
  callAnthropic,
  callGemini,
  callOllama,
  callOpenAi,
  providerList,
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
    providerCooldownMs: 25,
    providerLongCooldownMs: 100,
    providerOrder: ['gemini', 'openai', 'anthropic', 'ollama'],
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

test('configured default order is frontier-first with local ollama last', () => {
  assert.deepEqual(providerList(cfg()).map(([name]) => name),
    ['gemini', 'openai', 'anthropic', 'ollama']);
});

test('rate-limited provider is cooled down and skipped on the next request', async () => {
  const cooldowns = new Map();
  let geminiCalls = 0;
  const providers = [
    ['gemini', async () => {
      geminiCalls++;
      const error = new Error('rate limited');
      error.failureClass = 'http_429';
      throw error;
    }],
    ['ollama', async () => ({
      text: 'local-ok', model: 'local', inputTokens: 1, outputTokens: 1, endpointId: 'ollama',
    })],
  ];
  const first = await runProviderChain('prompt', providers, cfg(), { cooldowns });
  assert.equal(first.status, 200);
  assert.equal(first.body.providerUsed, 'ollama');
  assert.equal(geminiCalls, 1);

  const second = await runProviderChain('prompt', providers, cfg(), { cooldowns });
  assert.equal(second.status, 200);
  assert.equal(second.body.providerUsed, 'ollama');
  assert.equal(geminiCalls, 1, 'cooled-down provider must not be called again immediately');
  assert.equal(second.body.providerAttempts[0].failureClass, 'cooldown');
});

test('explicit provider chain falls through in the supplied order', async () => {
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
  ], cfg({ ollamaThink: undefined }), { capability: 'worker.cognition', cooldowns: new Map() });

  assert.equal(outcome.status, 200);
  assert.equal(seenThink, false);
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
