/**
 * TypeSafe Jev second-opinion proxy for Call Guardian.
 * Same contract as functions/jevCheck, plus a batch endpoint for startup processing.
 * The TypeSafe API key is a Worker secret; callers send a Firebase App Check JWT.
 */
import { askJev, parseItem, type CheckItem, type CheckOk, type SenderKind } from './jev';
import { verifyAppCheck, AuthError } from './appcheck';
import { rateLimit } from './rate-limit';

const CORS_ALLOW = [
  'https://callguardian-hybrid.web.app',
  'https://callguardian-hybrid.firebaseapp.com',
  'http://localhost:5173',
  'http://127.0.0.1:5173',
];

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === 'OPTIONS') return cors(request, new Response(null, { status: 204 }));
    const url = new URL(request.url);
    if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/health')) {
      return cors(request, json(200, { ok: true, service: 'callguardian-jev', questionsVersion: (await import('./questions')).QUESTIONS_VERSION }));
    }
    if (request.method !== 'POST') return cors(request, json(405, { error: 'method_not_allowed' }));

    try {
      const allowed = new Set(env.ALLOWED_APP_IDS.split(',').map((s) => s.trim()).filter(Boolean));
      const token = request.headers.get('X-Firebase-AppCheck') ?? request.headers.get('x-firebase-appcheck');
      const { appId } = await verifyAppCheck(token, env.FIREBASE_PROJECT_NUMBER, allowed);

      if (url.pathname === '/v1/check:batch' || url.pathname === '/batch') {
        return cors(request, await handleBatch(request, env, appId));
      }
      if (url.pathname === '/v1/check' || url.pathname === '/jevCheck' || url.pathname === '/') {
        return cors(request, await handleOne(request, env, appId, url.pathname === '/jevCheck'));
      }
      return cors(request, json(404, { error: 'not_found' }));
    } catch (e) {
      if (e instanceof AuthError) return cors(request, json(e.status, { error: e.code, message: e.message }));
      return cors(request, json(500, { error: 'internal', message: (e as Error).message.slice(0, 200) }));
    }
  },
} satisfies ExportedHandler<Env>;

async function handleOne(request: Request, env: Env, appId: string, callable: boolean): Promise<Response> {
  const rl = await rateLimit(appId, 1, Number(env.RATE_LIMIT_PER_MINUTE) || 60);
  if (!rl.allowed) return json(429, { error: 'rate_limited', message: 'Too many Jev checks from this app' }, { 'Retry-After': String(rl.retryAfterSec) });
  let body: unknown;
  try { body = await request.json(); } catch { return json(400, errBody(callable, 'invalid-argument', 'Body must be JSON')); }
  const item = parseItem(unwrapCallable(body));
  if (!item.ok) return json(400, errBody(callable, 'invalid-argument', item.error));
  if (!env.TYPESAFE_API_KEY) return json(500, errBody(callable, 'unavailable', 'TypeSafe key is not configured'));
  try {
    const result = await askJev(env.TYPESAFE_API_KEY, item.value.redactedText, item.value.sender);
    return json(200, callable ? { result } : result);
  } catch (e) {
    return json(503, errBody(callable, 'unavailable', (e as Error).message.slice(0, 200)));
  }
}

async function handleBatch(request: Request, env: Env, appId: string): Promise<Response> {
  let body: any;
  try { body = await request.json(); } catch { return json(400, { error: 'invalid-argument', message: 'Body must be JSON' }); }
  const raw = Array.isArray(body) ? body : body?.items;
  if (!Array.isArray(raw)) return json(400, { error: 'invalid-argument', message: 'Body must be { items: [...] } or an array' });
  const max = Number(env.BATCH_MAX) || 20;
  if (raw.length < 1) return json(400, { error: 'invalid-argument', message: 'items must not be empty' });
  if (raw.length > max) return json(400, { error: 'invalid-argument', message: `items must have at most ${max} entries` });
  const parsed: ({ ok: true; value: CheckItem } | { ok: false; error: string })[] = raw.map((x) => parseItem(x));
  const cost = parsed.length;
  const rl = await rateLimit(appId, cost, Number(env.RATE_LIMIT_PER_MINUTE) || 60);
  if (!rl.allowed) return json(429, { error: 'rate_limited', message: 'Too many Jev checks from this app' }, { 'Retry-After': String(rl.retryAfterSec) });
  if (!env.TYPESAFE_API_KEY) return json(500, { error: 'unavailable', message: 'TypeSafe key is not configured' });
  const results: (CheckOk | { ok: false; error: string })[] = [];
  // Sequential: keeps TypeSafe load and Worker subrequests predictable on the free plan.
  for (const p of parsed) {
    if (!p.ok) { results.push({ ok: false, error: p.error }); continue; }
    try {
      const r = await askJev(env.TYPESAFE_API_KEY, p.value.redactedText, p.value.sender);
      results.push({ ok: true, ...r });
    } catch (e) {
      results.push({ ok: false, error: (e as Error).message.slice(0, 200) });
    }
  }
  return json(200, { results });
}

function unwrapCallable(body: unknown): unknown {
  if (body && typeof body === 'object' && 'data' in (body as any) && (body as any).data && typeof (body as any).data === 'object') {
    return (body as any).data;
  }
  return body;
}
function errBody(callable: boolean, status: string, message: string) {
  return callable ? { error: { status, message } } : { error: status, message };
}

function json(status: number, body: unknown, extra: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json; charset=utf-8', ...extra } });
}
function cors(request: Request, res: Response): Response {
  const origin = request.headers.get('Origin') ?? '';
  const h = new Headers(res.headers);
  if (CORS_ALLOW.includes(origin)) {
    h.set('Access-Control-Allow-Origin', origin);
    h.set('Vary', 'Origin');
  }
  h.set('Access-Control-Allow-Headers', 'Content-Type, X-Firebase-AppCheck');
  h.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  h.set('Access-Control-Max-Age', '86400');
  return new Response(res.body, { status: res.status, headers: h });
}
