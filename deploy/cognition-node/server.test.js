const assert = require('node:assert/strict');
const test = require('node:test');
const http = require('node:http');

const {
  boundedFetch,
  callGemini,
  callOllama,
  configFromEnv,
  createServer,
  providerList,
  runProviderChain,
} = require('./server');

async function fakeOllama(handler, { delayMs = 0, hang = false } = {}) {
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
      if (hang) return;
      const { status, body } = handler(JSON.parse(raw), req);
      setTimeout(() => {
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end(JSON.stringify(body));
      }, delayMs);
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    url: 'http://127.0.0.1:' + server.address().port,
    close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }),
  };
}

// Captures every Ollama /api/generate payload on a real local server and points OLLAMA_URL at it.
async function fakeOllamaCapturing(bodies, body = { response: 'ok', prompt_eval_count: 1, eval_count: 1 }) {
  const originalUrl = process.env.OLLAMA_URL;
  const ollama = await fakeOllama((payload) => {
    bodies.push(payload);
    return { status: 200, body };
  });
  process.env.OLLAMA_URL = ollama.url;
  return {
    close: async () => {
      if (originalUrl === undefined) delete process.env.OLLAMA_URL; else process.env.OLLAMA_URL = originalUrl;
      await ollama.close();
    },
  };
}

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

test('production chain holds no credit-billed provider: ollama then free-tier gemini only', () => {
  assert.deepEqual(providerList().map(([name]) => name), ['ollama', 'gemini']);
});

test('provider order falls back from ollama to gemini', async () => {
  const seen = [];
  const outcome = await runProviderChain('prompt', [
    ['ollama', async () => {
      seen.push('ollama');
      const error = new Error('ollama failed');
      error.failureClass = 'timeout';
      throw error;
    }],
    ['gemini', async () => {
      seen.push('gemini');
      return { text: 'ok', model: 'm', inputTokens: 1, outputTokens: 1, endpointId: 'gemini' };
    }],
  ], cfg());

  assert.equal(outcome.status, 200);
  assert.deepEqual(seen, ['ollama', 'gemini']);
  assert.equal(outcome.body.providerUsed, 'gemini');
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
  ], cfg({ totalTimeoutMs: 500 }));

  assert.equal(outcome.status, 502);
  assert.equal(outcome.body.error, 'all_providers_failed');
  assert.equal(outcome.body.providerAttempts.length, 2);
  assert.deepEqual(outcome.body.providerAttempts.map(a => a.provider), ['ollama', 'gemini']);
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
  const ollama = await fakeOllama((payload) => {
    bodies.push(payload);
    return { status: 200, body: { response: 'ok', prompt_eval_count: 1, eval_count: 1 } };
  });
  process.env.OLLAMA_URL = ollama.url;
  try {
    const c = cfg({ maxOutputTokens: 256 });
    await callOllama('p', 2000, c);
    await callGemini('p', 50, c);
    await callOllama('p', 2000, cfg({ maxOutputTokens: 256, ollamaNumCtx: 8192 }));
  } finally {
    global.fetch = originalFetch;
    process.env = originalEnv;
    await ollama.close();
  }

  assert.equal(bodies[0].options.num_predict, 256);
  assert.equal(bodies[0].options.num_ctx, undefined, 'a config without ollamaNumCtx sends no num_ctx');
  assert.equal(bodies[1].generationConfig.maxOutputTokens, 256);
  assert.equal(bodies[2].options.num_ctx, 8192, 'OLLAMA_NUM_CTX sizes the Ollama context window');
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
  const bodies = [];
  const ollama = await fakeOllamaCapturing(bodies);
  const server = createServer(cfg({ auth: 'test', maxOutputTokens: 256, maxOutputTokensCeiling: 8192, totalTimeoutMs: 5000, ollamaTimeoutMs: 4000 }));
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
    await ollama.close();
  }
});

test('a requested maxOutputTokens above the enforced ceiling is clamped, never applied verbatim', async () => {
  const bodies = [];
  const ollama = await fakeOllamaCapturing(bodies);
  const server = createServer(cfg({ auth: 'test', maxOutputTokens: 256, maxOutputTokensCeiling: 8192, totalTimeoutMs: 5000, ollamaTimeoutMs: 4000 }));
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
    await ollama.close();
  }
});

test('a request that omits maxOutputTokens falls back to the configured server default', async () => {
  const bodies = [];
  const ollama = await fakeOllamaCapturing(bodies);
  const server = createServer(cfg({ auth: 'test', maxOutputTokens: 1536, maxOutputTokensCeiling: 8192, totalTimeoutMs: 5000, ollamaTimeoutMs: 4000 }));
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
    await ollama.close();
  }
});

test('ollama provider leaves thinking unchanged outside worker cognition unless explicitly configured', async () => {
  const bodies = [];
  const ollama = await fakeOllamaCapturing(bodies, { response: 'ok' });
  try {
    await callOllama('p', 2000, cfg({ ollamaThink: undefined }));
    await callOllama('p', 2000, cfg({ ollamaThink: false }));
  } finally {
    await ollama.close();
  }
  assert.equal(Object.hasOwn(bodies[0], 'think'), false);
  assert.equal(bodies[1].think, false);
});

test('default Ollama model and context fit the CPU-only 8 GB host', async () => {
  const originalEnv = { ...process.env };
  delete process.env.OLLAMA_MODEL;
  delete process.env.OLLAMA_NUM_CTX;
  const bodies = [];
  const ollama = await fakeOllamaCapturing(bodies);
  try {
    const result = await callOllama('p', 2000, configFromEnv());
    assert.equal(result.model, 'qwen3:4b');
  } finally {
    await ollama.close();
    process.env = originalEnv;
  }
  const body = bodies[0];
  assert.equal(body.model, 'qwen3:4b');
  assert.equal(body.options.num_ctx, 8192);
});

test('default timeouts fit CPU-only local inference and stay inside the Workforce caller budget', () => {
  const originalEnv = { ...process.env };
  delete process.env.OLLAMA_TIMEOUT_MS;
  delete process.env.METATRON_COGNITION_TOTAL_TIMEOUT_MS;
  try {
    const c = configFromEnv();
    assert.equal(c.ollamaTimeoutMs, 600000);
    assert.equal(c.totalTimeoutMs, 630000);
    assert.ok(c.ollamaTimeoutMs < c.totalTimeoutMs && c.totalTimeoutMs < 660000,
      'Ollama < node total < Workforce METATRON_COGNITION_HTTP_TIMEOUT_MS (660000)');
  } finally {
    process.env = originalEnv;
  }
});

test('ollama transport never goes through global fetch and its hidden 300 s headers timeout', async () => {
  // Production 2026-09-24 (case-0e3a655f): every long qwen3:4b call failed as
  // "provider_failed ollama network_error 300807" -- undici's fetch gave up waiting for the headers of a
  // non-streaming /api/generate after 300 s although OLLAMA_TIMEOUT_MS was 600 s. Here global fetch fails the
  // way undici does on HeadersTimeoutError; Ollama itself answers (late) and the call must still succeed.
  const originalFetch = global.fetch;
  const originalUrl = process.env.OLLAMA_URL;
  global.fetch = async () => { throw new TypeError('fetch failed'); };
  const ollama = await fakeOllama(() => ({
    status: 200,
    body: { response: 'generated after a long CPU run', prompt_eval_count: 1705, eval_count: 2402 },
  }), { delayMs: 150 });
  process.env.OLLAMA_URL = ollama.url;
  try {
    const result = await callOllama('p', 5000, cfg());
    assert.equal(result.text, 'generated after a long CPU run');
    assert.equal(result.outputTokens, 2402);
  } finally {
    global.fetch = originalFetch;
    if (originalUrl === undefined) delete process.env.OLLAMA_URL; else process.env.OLLAMA_URL = originalUrl;
    await ollama.close();
  }
});

test('ollama call is still bounded by its own timeout and classifies failures', async () => {
  const originalUrl = process.env.OLLAMA_URL;
  const hanging = await fakeOllama(() => ({ status: 200, body: {} }), { hang: true });
  const failing = await fakeOllama(() => ({ status: 500, body: { error: 'boom' } }));
  try {
    process.env.OLLAMA_URL = hanging.url;
    await assert.rejects(callOllama('p', 60, cfg()), (e) => e.failureClass === 'timeout');
    process.env.OLLAMA_URL = failing.url;
    await assert.rejects(callOllama('p', 2000, cfg()), (e) => e.failureClass === 'http_500');
    process.env.OLLAMA_URL = 'http://127.0.0.1:1';
    await assert.rejects(callOllama('p', 2000, cfg()), (e) => e.failureClass === 'network_error');
  } finally {
    if (originalUrl === undefined) delete process.env.OLLAMA_URL; else process.env.OLLAMA_URL = originalUrl;
    await hanging.close();
    await failing.close();
  }
});

test('worker.cognition asks Ollama for JSON mode; other capabilities do not', async () => {
  // Production 2026-09-24 (case-394285d9 PREPARE): qwen3:4b answered worker cognition in prose
  // ("We are given a complex objective ...") and every attempt failed as "no JSON object".
  const seen = [];
  const ollama = ['ollama', async (_prompt, _timeoutMs, providerCfg) => {
    seen.push(providerCfg.ollamaJsonFormat === true);
    return { text: '{}', model: 'qwen3:4b', inputTokens: 1, outputTokens: 1, endpointId: 'ollama' };
  }];
  await runProviderChain('prompt', [ollama], cfg(), { capability: 'worker.cognition' });
  await runProviderChain('prompt', [ollama], cfg(), { capability: 'institutional.chat' });
  assert.deepEqual(seen, [true, false]);

  const bodies = [];
  const server = await fakeOllamaCapturing(bodies);
  try {
    await callOllama('p', 2000, cfg({ ollamaJsonFormat: true }));
    await callOllama('p', 2000, cfg());
  } finally {
    await server.close();
  }
  assert.equal(bodies[0].format, 'json');
  assert.equal(Object.hasOwn(bodies[1], 'format'), false);
});
