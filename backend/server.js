// InkProof backend.
//
// Responsibilities:
//  - protect API keys (they live ONLY here, never in the Android app)
//  - call the AI + symbolic math engine
//  - validate and normalize structured responses
//  - rate-limit
//
// Zero runtime dependencies: plain Node 18+.

import http from 'node:http';
import { validateCheckRequest, sanitizeCheckResponse, errorResponse } from './lib/schema.js';
import { mockCheck, mockSolve } from './lib/mock.js';
import { aiCheck } from './lib/ai.js';
import { aiRecognize, mockRecognize, validateRecognizeRequest } from './lib/recognize.js';

const env = process.env;
const PORT = Number(env.PORT || 8787);
const MOCK_MODE = env.MOCK_MODE === 'true' ||
  (!env.GEMINI_API_KEY && !env.ANTHROPIC_API_KEY && !env.OPENAI_API_KEY);
const RATE_LIMIT_PER_MINUTE = Number(env.RATE_LIMIT_PER_MINUTE || 20);

// ----- simple in-memory rate limiter (per IP, sliding minute) -----
const hits = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const windowStart = now - 60_000;
  const list = (hits.get(ip) || []).filter((t) => t > windowStart);
  if (list.length >= RATE_LIMIT_PER_MINUTE) {
    hits.set(ip, list);
    return true;
  }
  list.push(now);
  hits.set(ip, list);
  return false;
}

// ----- response cache: identical request -> identical answer -----
const cache = new Map();
const CACHE_MAX = 500;
function cacheKey(action, body) {
  return `${action}|${body.question_id}|${body.content_version}|${body.solution_version}|${body.question_text}|${(body.solution_lines || []).map((l) => l.text).join('\u0001')}`;
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body)
  });
  res.end(body);
}

async function readJson(req, limit = 1_000_000) {
  return new Promise((resolve, reject) => {
    let data = '';
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('payload too large'));
        req.destroy();
        return;
      }
      data += chunk;
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(data || '{}'));
      } catch {
        reject(new Error('invalid json'));
      }
    });
    req.on('error', reject);
  });
}

async function handleCheck(req, res, action) {
  const ip = req.socket.remoteAddress || 'unknown';
  if (rateLimited(ip)) {
    send(res, 429, errorResponse('Too many checks in a short time. Please wait a moment.'));
    return;
  }

  let body;
  try {
    body = await readJson(req);
  } catch (e) {
    send(res, 400, errorResponse(`Bad request: ${e.message}.`));
    return;
  }

  const valid = validateCheckRequest(body);
  if (!valid.ok) {
    send(res, 400, errorResponse(valid.error));
    return;
  }

  const key = cacheKey(action, body);
  if (cache.has(key)) {
    send(res, 200, cache.get(key));
    return;
  }

  let result;
  if (MOCK_MODE) {
    result = action === 'solve' ? mockSolve(body) : mockCheck(body);
  } else {
    result = await aiCheck(env, body, action);
  }

  const sanitized = sanitizeCheckResponse(result) || errorResponse('Internal schema error.');
  if (sanitized.status !== 'error') {
    if (cache.size >= CACHE_MAX) cache.delete(cache.keys().next().value);
    cache.set(key, sanitized);
  }
  send(res, 200, sanitized);
}

// Explicit-action cloud fallback for handwriting recognition. The app
// only calls this when on-device recognition is uncertain.
async function handleRecognize(req, res) {
  const ip = req.socket.remoteAddress || 'unknown';
  if (rateLimited(ip)) {
    send(res, 429, errorResponse('Too many requests in a short time. Please wait a moment.'));
    return;
  }
  let body;
  try {
    body = await readJson(req, 12_000_000);
  } catch (e) {
    send(res, 400, errorResponse(`Bad request: ${e.message}.`));
    return;
  }
  const valid = validateRecognizeRequest(body);
  if (!valid.ok) {
    send(res, 400, errorResponse(valid.error));
    return;
  }
  const result = MOCK_MODE ? mockRecognize() : await aiRecognize(env, body, valid.mime);
  send(res, result.status === 'error' ? 502 : 200, result);
}

export function createServer() {
  return http.createServer(async (req, res) => {
    try {
      if (req.method === 'GET' && req.url === '/api/health') {
        send(res, 200, {
          ok: true,
          service: 'inkproof-backend',
          mock_mode: MOCK_MODE,
          ai_configured: Boolean(
            env.GEMINI_API_KEY || env.ANTHROPIC_API_KEY || env.OPENAI_API_KEY
          ),
          ai_provider: env.GEMINI_API_KEY ? 'gemini'
            : env.ANTHROPIC_API_KEY ? 'anthropic'
            : env.OPENAI_API_KEY ? 'openai'
            : 'mock',
          wolfram_configured: Boolean(env.WOLFRAM_APP_ID)
        });
        return;
      }
      if (req.method === 'POST' && req.url === '/api/check') {
        await handleCheck(req, res, 'check');
        return;
      }
      if (req.method === 'POST' && req.url === '/api/solve') {
        await handleCheck(req, res, 'solve');
        return;
      }
      if (req.method === 'POST' && req.url === '/api/recognize') {
        await handleRecognize(req, res);
        return;
      }
      send(res, 404, errorResponse('Not found.'));
    } catch (e) {
      send(res, 500, errorResponse('Internal server error.'));
    }
  });
}

if (process.argv[1] && process.argv[1].endsWith('server.js')) {
  createServer().listen(PORT, '0.0.0.0', () => {
    console.log(`InkProof backend listening on :${PORT} (mock_mode=${MOCK_MODE})`);
  });
}
