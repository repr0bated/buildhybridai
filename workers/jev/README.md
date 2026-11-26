# callguardian-jev

Cloudflare Worker (free plan) that proxies TypeSafe Jev for Call Guardian. See
[`../../docs/JEV_WORKER.md`](../../docs/JEV_WORKER.md) for the client contract.

```bash
npm install
npm test
# TYPESAFE_API_KEY must already be a Worker secret (never commit it)
npm run deploy
```
