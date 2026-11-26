// Subscription / marketing text detection (port of the Android SubscriptionDetector).
import { scan } from './rules';
import type { Risk } from './registry';

export const OPT_OUT_KEYWORDS = ['STOP', 'STOPALL', 'UNSUBSCRIBE', 'CANCEL', 'END', 'QUIT'] as const;
export type LinkKind = 'UNSUBSCRIBE' | 'MANAGE_SUBSCRIPTION' | 'OTHER';
export interface LinkCandidate { url: string; domain: string; kind: LinkKind }

const replyKeyword = /\b(?:reply|text|txt|send|respond)\s+["']?([A-Za-z]{3,11})["']?\s+(?:to\s+)?(?:stop|opt[\s-]?out|unsubscribe|cancel|end|quit|be removed|unsub)/i;
const bareStop = /\b(?:reply|text|txt)\s+["']?(STOP|STOPALL|UNSUBSCRIBE|CANCEL|END|QUIT)\b/i;
const patterns: [string, RegExp][] = [
  ['reply_stop', /\b(reply|text|txt)\s+stop\b/i],
  ['rates', /msg\s*(&|and)\s*data\s*rates/i],
  ['opt_out', /\bopt[\s-]?out\b/i],
  ['unsubscribe', /\bunsubscribe\b|\bunsub\b/i],
  ['manage', /manage\s+(your\s+)?(texts|alerts|subscriptions?|notifications|preferences)/i],
  ['help', /\breply\s+help\b/i],
  ['freq', /\b(recurring|autodialed)\s+(msgs?|messages)\b|\bmsg\s+freq/i],
];
const urlRe = /\b((?:https?:\/\/)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:\/[^\s]*)?)/gi;
const brandPrefix = /^\s*([A-Z][A-Za-z0-9&'.-]{1,24}(?:\s[A-Z][A-Za-z0-9&'-]{1,20})?)\s*[:-]/;
const shortener = new Set(['bit.ly', 'tinyurl.com', 't.co', 'ow.ly', 'is.gd', 'rb.gy', 'cutt.ly', 't.ly', 'shorturl.at']);
const riskyTlds = new Set(['top', 'xyz', 'info', 'click', 'live', 'shop', 'icu', 'buzz', 'rest', 'cfd', 'sbs', 'online', 'site']);

export const isShortCode = (s: string) => /^\d{4,6}$/.test(s);
export const textable = (s: string) => /^\+?\d{3,15}$/.test(s.trim());

export function signals(sender: string, body: string): string[] {
  const out: string[] = [];
  if (isShortCode(sender)) out.push('short_code');
  for (const [id, re] of patterns) if (re.test(body)) out.push(id);
  return out;
}
export const isSubscription = (sender: string, body: string) => signals(sender, body).some((s) => s !== 'short_code');

export function keyword(body: string): (typeof OPT_OUT_KEYWORDS)[number] | null {
  const a = replyKeyword.exec(body)?.[1]?.toUpperCase();
  if (a && (OPT_OUT_KEYWORDS as readonly string[]).includes(a)) return a as any;
  const b = bareStop.exec(body)?.[1]?.toUpperCase();
  if (b) return b as any;
  return /\bstop\b/i.test(body) ? 'STOP' : null;
}

export function links(body: string): LinkCandidate[] {
  const seen = new Set<string>();
  const out: LinkCandidate[] = [];
  for (const m of body.matchAll(urlRe)) {
    const raw = m[1].replace(/[.,)!?;]+$/, '');
    const full = /^https?:/i.test(raw) ? raw : `https://${raw}`;
    const domain = full.split('://')[1].split('/')[0].toLowerCase().replace(/^www\./, '');
    if (!domain.includes('.') || seen.has(full)) continue;
    seen.add(full);
    const idx = m.index ?? 0;
    const ctx = body.slice(Math.max(0, idx - 40), idx).toLowerCase() + full.toLowerCase();
    const kind: LinkKind = /unsub|opt-?out|optout|stop/.test(ctx) ? 'UNSUBSCRIBE' : /manage|preferences|notification|settings|subscription|alerts/.test(ctx) ? 'MANAGE_SUBSCRIPTION' : 'OTHER';
    out.push({ url: full, domain, kind });
  }
  return out;
}
export const bestLink = (l: LinkCandidate[]) => l.find((x) => x.kind === 'UNSUBSCRIBE') ?? l.find((x) => x.kind === 'MANAGE_SUBSCRIPTION') ?? null;
export const brandOf = (body: string) => brandPrefix.exec(body)?.[1]?.trim() ?? null;

const squash = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
const registrable = (d: string) => d.split('.').slice(-2).join('.');

export function linkSafety(link: LinkCandidate, brand: string | null, scamRisk: Risk): { suspicious: boolean; warning: string | null } {
  const reasons: string[] = [];
  if (scamRisk === 'MEDIUM' || scamRisk === 'HIGH' || scamRisk === 'CRITICAL') reasons.push(`The sender's texts look like a scam (${scamRisk.toLowerCase()} risk)`);
  const reg = registrable(link.domain);
  if (shortener.has(reg)) reasons.push('Shortened link hides the real site');
  const tld = link.domain.split('.').pop()!;
  if (riskyTlds.has(tld)) reasons.push(`Unusual domain ending .${tld}`);
  if (!/^https:\/\//i.test(link.url)) reasons.push('Not an https link');
  if (brand) {
    const b = squash(brand), label = squash(reg.split('.')[0]);
    if (b.length >= 3 && !label.includes(b) && !b.includes(label)) reasons.push(`Domain ${reg} doesn't match "${brand}"`);
    else if (b.length >= 3 && label !== b && label.includes(b)) reasons.push(`Look-alike domain ${reg} for "${brand}"`);
  }
  const w = reasons.join('; ').slice(0, 200);
  return { suspicious: reasons.length > 0, warning: w || null };
}

export interface SubscriptionInfo {
  isSubscription: boolean;
  signals: string[];
  keyword: string | null;
  links: LinkCandidate[];
  bestLink: LinkCandidate | null;
  brand: string | null;
  scamRisk: Risk;
}
export function analyzeSubscription(sender: string, body: string): SubscriptionInfo {
  const sig = signals(sender, body);
  const ls = links(body);
  return {
    isSubscription: sig.some((s) => s !== 'short_code'),
    signals: sig,
    keyword: keyword(body),
    links: ls.slice(0, 6),
    bestLink: bestLink(ls),
    brand: brandOf(body),
    scamRisk: scan({ channel: 'SMS', number: sender, body }).riskLevel,
  };
}
