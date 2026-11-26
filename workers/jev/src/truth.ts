/** Port of the Android JevTruthTable. Jev only judges; this table decides. Unit-tested. */
export const YES = 0.7;
export const NO = 0.3;
export const SIGNAL_ORDER = [
  'asks_payment', 'deadline_pressure', 'claims_organization', 'asks_open_link',
  'asks_share_code', 'asks_login_details', 'offers_money', 'moves_to_other_app', 'stranger_opener',
  'reputable_official',
] as const;
export type SignalId = (typeof SIGNAL_ORDER)[number];
export type Verdict = 'SCAM' | 'SUSPICIOUS' | 'UNSETTLED' | 'CLEAR';
export type Reading = 'YES' | 'NO' | 'UNSETTLED';
export interface TruthResult { verdict: Verdict; rule: string; unsettled: SignalId[] }
export interface TruthContext {
  senderIsContact: boolean;
  officialDomainChecksOut?: boolean;
  lookalikeDomain?: boolean;
  fromOwnedAccount?: boolean;
}

export const read = (p: number): Reading => (p >= YES ? 'YES' : p <= NO ? 'NO' : 'UNSETTLED');

type Row = { verdict: Verdict; rule: string; test: (y: Set<string>, c: TruthContext) => boolean };
const rows: Row[] = [
  { verdict: 'SCAM', rule: 'brand claim + lookalike domain', test: (y, c) => !!c.lookalikeDomain && (y.has('claims_organization') || !!c.fromOwnedAccount) },
  { verdict: 'SCAM', rule: 'code request + company claim or deadline', test: (y) => y.has('asks_share_code') && (y.has('claims_organization') || y.has('deadline_pressure')) },
  { verdict: 'SCAM', rule: 'asks for login details', test: (y) => y.has('asks_login_details') },
  { verdict: 'CLEAR', rule: 'owned account (sender and official domain match)', test: (_y, c) => !!c.fromOwnedAccount && !!c.officialDomainChecksOut && !c.lookalikeDomain },
  { verdict: 'CLEAR', rule: 'reputable organization and official domain, no unusual ask', test: (y, c) =>
    !!c.officialDomainChecksOut && !c.lookalikeDomain && y.has('reputable_official') &&
    !y.has('asks_share_code') && !y.has('asks_login_details') && !y.has('moves_to_other_app') && !y.has('stranger_opener') },
  { verdict: 'SCAM', rule: 'payment or link + deadline', test: (y) => (y.has('asks_payment') || y.has('asks_open_link')) && y.has('deadline_pressure') },
  { verdict: 'SCAM', rule: 'money offer + other app or stranger opener', test: (y) => y.has('offers_money') && (y.has('moves_to_other_app') || y.has('stranger_opener')) },
  { verdict: 'SCAM', rule: 'stranger opener + other app', test: (y) => y.has('stranger_opener') && y.has('moves_to_other_app') },
  { verdict: 'SUSPICIOUS', rule: 'one strong signal from a non-contact', test: (y, c) =>
    !c.senderIsContact && ['asks_share_code', 'asks_payment', 'offers_money', 'moves_to_other_app', 'stranger_opener'].some((s) => y.has(s)) },
  { verdict: 'SUSPICIOUS', rule: 'company claim + deadline', test: (y) => y.has('claims_organization') && y.has('deadline_pressure') },
  { verdict: 'CLEAR', rule: 'no scam combination', test: () => true },
];
const firstRow = (yes: Set<string>, c: TruthContext) => rows.find((r) => r.test(yes, c))!;

export function decide(probabilities: Record<string, number>, senderIsContactOrCtx: boolean | TruthContext): TruthResult {
  const ctx: TruthContext = typeof senderIsContactOrCtx === 'boolean' ? { senderIsContact: senderIsContactOrCtx } : senderIsContactOrCtx;
  const missing = SIGNAL_ORDER.filter((s) => probabilities[s] === undefined);
  if (missing.length) throw new Error(`Missing Jev answers: ${missing.join(', ')}`);
  const readings = Object.fromEntries(SIGNAL_ORDER.map((s) => [s, read(probabilities[s])])) as Record<SignalId, Reading>;
  const yes = new Set(SIGNAL_ORDER.filter((s) => readings[s] === 'YES'));
  const unsettled = SIGNAL_ORDER.filter((s) => readings[s] === 'UNSETTLED');
  const strict = firstRow(yes, ctx);
  const loose = firstRow(new Set([...yes, ...unsettled]), ctx);
  return strict.verdict === loose.verdict
    ? { verdict: strict.verdict, rule: strict.rule, unsettled }
    : { verdict: 'UNSETTLED', rule: `hinges on unsettled: ${unsettled.join(', ')}`.slice(0, 80), unsettled };
}
