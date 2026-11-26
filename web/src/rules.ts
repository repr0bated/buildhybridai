// Tier 1: instant, offline, deterministic rules. A port of the Android HeuristicScanner (text + number
// checks; the call-only signals that need a phone, such as caller ID or repeat calls, don't apply on the web).
import type { Risk } from './registry';

export type Channel = 'CALL' | 'SMS';

export interface RuleFlag { id: string; label: string; weight: number; category?: string }
export interface RuleScan {
  score: number;
  flags: RuleFlag[];
  riskLevel: Risk;
  category: string;
  /** Registry method alias the rules propose (goes through the same policy as model output). */
  method: string;
  elapsedMs: number;
}
export interface ScanInput { channel: Channel; number?: string | null; body?: string | null; isContact?: boolean }

const urlRegex = /\b((?:https?:\/\/|www\.)[^\s]+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|info|top|xyz|icu|click|live|cc|vip|shop|buzz|link|online|site|me|co|us|io|ly|gd|gl)(?:\/[^\s]*)?)/gi;
const ipUrl = /https?:\/\/\d{1,3}(?:\.\d{1,3}){3}/i;
const shorteners = ['bit.ly', 'tinyurl', 't.co/', 'is.gd', 'rb.gy', 'cutt.ly', 'shorturl', 'goo.gl', 'ow.ly', 'tiny.cc', 's.id/'];
const riskyTlds = ['.top', '.xyz', '.icu', '.click', '.live', '.cc', '.vip', '.shop', '.buzz', '.link', '.online', '.site', '.info'];
const brands = ['usps', 'fedex', 'ups', 'dhl', 'amazon', 'apple', 'icloud', 'paypal', 'netflix', 'chase', 'wells fargo', 'bank of america', 'citi', 'irs', 'medicare', 'social security', 'dmv', 'e-zpass', 'ezpass', 'fastrak', 'sunpass', 'coinbase', 'venmo', 'zelle', 'microsoft'];
const official: Record<string, string> = {
  usps: 'usps.com', fedex: 'fedex.com', ups: 'ups.com', dhl: 'dhl.com', amazon: 'amazon.com', apple: 'apple.com', icloud: 'icloud.com',
  paypal: 'paypal.com', netflix: 'netflix.com', chase: 'chase.com', wellsfargo: 'wellsfargo.com', bankofamerica: 'bankofamerica.com',
  citi: 'citi.com', irs: 'irs.gov', medicare: 'medicare.gov', socialsecurity: 'ssa.gov', ezpass: 'e-zpassny.com', coinbase: 'coinbase.com',
  venmo: 'venmo.com', zelle: 'zellepay.com', microsoft: 'microsoft.com',
};

interface Pat { id: string; label: string; weight: number; category?: string; re: RegExp }
const patterns: Pat[] = [
  { id: 'urgency', label: 'Pressure to act fast', weight: 15, re: /\b(urgent|immediately|final (notice|warning)|within (24|48) ?h(ours)?|act now|suspend(ed)?|locked|expire[sd]?|last chance|today only)\b/i },
  { id: 'money', label: 'Asks for gift cards, wire or crypto', weight: 20, category: 'CRYPTO_INVESTMENT', re: /\b(gift ?cards?|wire transfer|western union|moneygram|bitcoin|btc|usdt|crypto|cash ?app|zelle me|venmo me)\b/i },
  { id: 'otp', label: 'Asks you to share a code or password', weight: 25, category: 'OTP_THEFT', re: /(\b(send|share|reply with|tell me|forward)\b.{0,30}\b(code|otp|pin|password|passcode)\b)|(\b(code|otp)\b.{0,40}\b(send it|share it|reply)\b)/i },
  { id: 'credentials', label: 'Asks to verify account or personal info', weight: 15, category: 'BANK_FRAUD', re: /\b(verify (your )?(account|identity|information)|confirm (your )?(details|identity|card)|update (your )?(billing|payment)|ssn|social security number)\b/i },
  { id: 'delivery', label: 'Fake delivery / redelivery story', weight: 15, category: 'PACKAGE_DELIVERY', re: /\b(package|parcel|shipment|delivery|redeliver(y)?)\b.{0,60}\b(failed|on hold|unable|pending|fee|address|reschedule)\b/i },
  { id: 'toll', label: 'Unpaid toll or fine', weight: 20, category: 'TOLL_OR_FINE', re: /\b(unpaid|outstanding|overdue)\b.{0,30}\b(toll|fine|balance|invoice|ticket)\b|\btoll (services|balance)\b/i },
  { id: 'prize', label: 'Claims you won something', weight: 15, category: 'PRIZE_OR_LOTTERY', re: /\b(you('ve| have)? won|winner|congratulations|claim (your )?(prize|reward|gift)|lottery|sweepstakes)\b/i },
  { id: 'job', label: 'Too-good-to-be-true job offer', weight: 15, category: 'JOB_OFFER', re: /\b(remote (job|position|work)|part[- ]time|recruit(er|ing)|hiring)\b.{0,80}(\$\s?\d{2,4}|per (day|hour)|daily pay)/i },
  { id: 'wrongnumber', label: 'Wrong-number style opener', weight: 10, category: 'ROMANCE_OR_WRONG_NUMBER', re: /^(hi|hello|hey)?[,! ]*(is this|are you) [a-z]+\??|\b(wrong number|long time no see|do you remember me)\b/i },
  { id: 'techsupport', label: 'Fake security / tech-support alert', weight: 15, category: 'TECH_SUPPORT', re: /\b(virus|hacked|unauthori[sz]ed (login|access|purchase|charge)|suspicious (activity|login|sign-?in)|fraud alert)\b/i },
];

// Country / area codes that are common in one-ring ("wangiri") and callback scams.
const callbackPrefixes = ['+232', '+225', '+269', '+224', '+216', '+222', '+234', '+1809', '+1829', '+1849', '+1284', '+1473', '+1876', '+1649', '+1664', '+1758'];

/** Best-effort E.164: keeps a leading +, assumes North America for 10 digits (or 11 starting with 1). */
export function e164(number?: string | null): string {
  const raw = (number ?? '').trim();
  const d = raw.replace(/\D/g, '');
  if (!d) return '';
  if (raw.startsWith('+')) return `+${d}`;
  if (d.length === 10) return `+1${d}`;
  if (d.length === 11 && d.startsWith('1')) return `+${d}`;
  return d;
}

export function hostOf(url: string): string {
  return url.replace(/^https?:\/\//i, '').replace(/^www\./i, '').split('/')[0].split('?')[0].split(':')[0].replace(/[.,)]+$/, '').toLowerCase();
}
function isOfficial(host: string, brand: string): boolean {
  const o = official[brand];
  return !!o && (host === o || host.endsWith('.' + o));
}

export function riskFromScore(score: number): Risk {
  return score >= 80 ? 'CRITICAL' : score >= 60 ? 'HIGH' : score >= 35 ? 'MEDIUM' : score >= 15 ? 'LOW' : 'SAFE';
}

export function scan(input: ScanInput): RuleScan {
  const t0 = performance.now();
  const flags: RuleFlag[] = [];
  const body = input.body ?? '';
  const lower = body.toLowerCase();

  if (input.channel === 'SMS' && body.trim()) {
    const urls = [...body.matchAll(urlRegex)].map((m) => m[0].toLowerCase());
    if (urls.length) {
      flags.push({ id: 'link', label: 'Contains a link', weight: 15, category: 'PHISHING_LINK' });
      if (urls.some((u) => shorteners.some((s) => u.includes(s)))) flags.push({ id: 'shortener', label: 'Link hidden behind a URL shortener', weight: 15, category: 'PHISHING_LINK' });
      if (ipUrl.test(body)) flags.push({ id: 'iplink', label: 'Link points to a bare IP address', weight: 20, category: 'PHISHING_LINK' });
      if (urls.some((u) => riskyTlds.some((t) => hostOf(u).endsWith(t)))) flags.push({ id: 'tld', label: 'Link uses a throwaway domain', weight: 15, category: 'PHISHING_LINK' });
      const look = urls.find((u) => {
        const h = hostOf(u);
        return brands.some((b) => { const bb = b.replace(/ /g, ''); return h.includes(bb) && !isOfficial(h, bb); });
      });
      if (look) flags.push({ id: 'lookalike', label: `Look-alike brand domain (${hostOf(look)})`, weight: 25, category: 'IMPERSONATION' });
    }
    for (const p of patterns) if (p.re.test(body)) flags.push({ id: p.id, label: p.label, weight: p.weight, category: p.category });
    const brand = brands.find((b) => new RegExp(`\\b${b.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(lower));
    if (brand && flags.some((f) => f.weight >= 15)) flags.push({ id: 'brand', label: `Uses the name "${brand.toUpperCase()}"`, weight: 10, category: 'IMPERSONATION' });
    if (input.number?.includes('@')) flags.push({ id: 'email2sms', label: 'Sent from an email address, not a phone', weight: 15 });
  }

  if (input.channel === 'CALL') {
    const n = e164(input.number);
    if (!n) flags.push({ id: 'hidden', label: 'Caller ID is hidden', weight: 20, category: 'SPOOFED_CALLER' });
    else if (callbackPrefixes.some((p) => n.startsWith(p))) {
      flags.push({ id: 'callback', label: 'Prefix often used in one-ring / callback scams', weight: 25, category: 'ROBOCALL' });
    }
  }

  if (!input.isContact && input.number && flags.length) flags.push({ id: 'unknown', label: 'Not in your contacts', weight: 5 });

  let score = Math.min(100, flags.reduce((s, f) => s + f.weight, 0));
  if (input.isContact) score = Math.floor(score / 2);
  const riskLevel = riskFromScore(score);
  const top = flags.filter((f) => f.category).sort((a, b) => b.weight - a.weight)[0];
  const category = top?.category ?? 'NONE';
  const method =
    riskLevel === 'CRITICAL' ? 'block_number'
    : riskLevel === 'HIGH' && input.channel === 'CALL' ? 'reject_call'
    : riskLevel !== 'SAFE' && riskLevel !== 'LOW' && input.channel === 'SMS' ? 'quarantine_sms'
    : 'none';
  return { score, flags, riskLevel, category, method, elapsedMs: Math.round((performance.now() - t0) * 100) / 100 };
}

export const reasonsOf = (s: RuleScan) => [...s.flags].sort((a, b) => b.weight - a.weight).map((f) => f.label);
