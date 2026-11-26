/** Basic per-app fixed-window rate limit using the Cache API (free plan; no paid Rate Limiting binding). */
export async function rateLimit(appId: string, cost: number, limit: number): Promise<{ allowed: boolean; remaining: number; retryAfterSec: number }> {
  const window = Math.floor(Date.now() / 60_000);
  const url = new URL(`https://callguardian-jev.rate/${encodeURIComponent(appId)}/${window}`);
  const cache = await caches.open('jev-rate');
  const hit = await cache.match(url);
  const used = hit ? parseInt(await hit.text(), 10) || 0 : 0;
  const next = used + cost;
  if (next > limit) {
    return { allowed: false, remaining: 0, retryAfterSec: 60 - Math.floor((Date.now() % 60_000) / 1000) };
  }
  await cache.put(url, new Response(String(next), { headers: { 'Cache-Control': 'max-age=120' } }));
  return { allowed: true, remaining: Math.max(0, limit - next), retryAfterSec: 0 };
}
