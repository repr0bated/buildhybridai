/** Port of the Android JevTruthTable. Jev only judges; this table decides. Unit-tested. */
export const YES = 0.7;
export const NO = 0.3;
export const SIGNAL_ORDER = [
  'asks_payment', 'deadline_pressure', 'claims_organization', 'asks_open_link',
  'asks_share_code', 'asks_login_details', 'offers_money', 'moves_to_other_app', 'stranger_opener',
] as const;
export type SignalId = (typeof SIGNAL_ORDER)[number];
export type Verdict = 'SCAM' | 'SUSPICIOUS' | 'UNSETTLED' | 'CLEAR';
export type Reading = 'YES' | 'NO' | 'UNSETTLED';
export interface TruthResult { verdict: Verdict; rule: string; unsettled: SignalId[] }

export const read = (p: number): Reading => (p >= YES ? 'YES' : p <= NO ? 'NO' : 'UNSETTLED');

type Row = { verdict: Verdict; rule: string; test: (y: Set<string>, contact: boolean) => boolean };
const rows: Row[] = [
  { verdict: 'SCAM', rule: 'code request + company claim or deadline', test: (y) => y.has('asks_share_code') && (y.has('claims_organization') || y.has('deadline_pressure')) },
  { verdict: 'SCAM', rule: 'asks for login details', test: (y) => y.has('asks_login_details') },
  { verdict: 'SCAM', rule: 'payment or link + deadline', test: (y) => (y.has('asks_payment') || y.has('asks_open_link')) && y.has('deadline_pressure') },
  { verdict: 'SCAM', rule: 'money offer + other app or stranger opener', test: (y) => y.has('offers_money') && (y.has('moves_to_other_app') || y.has('stranger_opener')) },
  { verdict: 'SCAM', rule: 'stranger opener + other app', test: (y) => y.has('stranger_opener') && y.has('moves_to_other_app') },
  { verdict: 'SUSPICIOUS', rule: 'one strong signal from a non-contact', test: (y, contact) =>
    !contact && ['asks_share_code', 'asks_payment', 'offers_money', 'moves_to_other_app', 'stranger_opener'].some((s) => y.has(s)) },
  { verdict: 'SUSPICIOUS', rule: 'company claim + deadline', test: (y) => y.has('claims_organization') && y.has('deadline_pressure') },
  { verdict: 'CLEAR', rule: 'no scam combination', test: () => true },
];
const firstRow = (yes: Set<string>, contact: boolean) => rows.find((r) => r.test(yes, contact))!;

export function decide(probabilities: Record<string, number>, senderIsContact: boolean): TruthResult {
  const missing = SIGNAL_ORDER.filter((s) => probabilities[s] === undefined);
  if (missing.length) throw new Error(`Missing Jev answers: ${missing.join(', ')}`);
  const readings = Object.fromEntries(SIGNAL_ORDER.map((s) => [s, read(probabilities[s])])) as Record<SignalId, Reading>;
  const yes = new Set(SIGNAL_ORDER.filter((s) => readings[s] === 'YES'));
  const unsettled = SIGNAL_ORDER.filter((s) => readings[s] === 'UNSETTLED');
  const strict = firstRow(yes, senderIsContact);
  const loose = firstRow(new Set([...yes, ...unsettled]), senderIsContact);
  return strict.verdict === loose.verdict
    ? { verdict: strict.verdict, rule: strict.rule, unsettled }
    : { verdict: 'UNSETTLED', rule: `hinges on unsettled: ${unsettled.join(', ')}`.slice(0, 80), unsettled };
}
