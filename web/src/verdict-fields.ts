// Local fill for required scam_verdict fields, matching Android VerdictFields.
// Omitted or invalid reputable_official / held_account values are filled before schema validation.
import type { ScamVerdict } from './generated/types';
import type { Channel, RuleScan } from './rules';
import type { Risk } from './registry';

export const YES = 'YES';
export const NO = 'NO';
export const UNKNOWN = 'UNKNOWN';
const VALUES = new Set([YES, NO, UNKNOWN]);

export function normalize(raw: unknown): 'YES' | 'NO' | 'UNKNOWN' | null {
  if (raw == null) return null;
  const s = String(raw).trim().toUpperCase().replace(/ /g, '_');
  if (!s || s === 'NULL' || s === 'NONE' || s === 'NO_ANSWER' || s === '—' || s === '-') return null;
  if (s === YES || s === 'TRUE' || s === 'Y') return YES;
  if (s === NO || s === 'FALSE' || s === 'N') return NO;
  if (s === UNKNOWN) return UNKNOWN;
  return null;
}

export function localReputableOfficial(rules: RuleScan): 'YES' | 'NO' | 'UNKNOWN' {
  const ids = new Set(rules.flags.map((f) => f.id));
  if (ids.has('lookalike')) return NO;
  return UNKNOWN;
}

export function localHeldAccount(isTrusted: boolean): 'YES' | 'NO' | 'UNKNOWN' {
  return isTrusted ? YES : NO;
}

/** Insert local answers for omitted or invalid reputable_official / held_account. */
export function fillOmitted(rawText: string, reputableOfficial: string, heldAccount: string): string {
  const t = rawText.trim();
  const fenced = t.match(/```(?:json)?\s*([\s\S]*?)```/i);
  const raw = (fenced ? fenced[1] : t).trim();
  let obj: Record<string, unknown>;
  try {
    obj = JSON.parse(raw);
  } catch {
    return rawText;
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return rawText;
  return JSON.stringify({
    ...obj,
    reputable_official: normalize(obj.reputable_official) ?? reputableOfficial,
    held_account: normalize(obj.held_account) ?? heldAccount,
  });
}

export interface ModelSignals {
  riskLevel: Risk;
  category: string;
  method: string;
  advice: string;
}

/** Map the model's compact verdict token to the risk / method the policy expects. */
export function modelSignals(v: ScamVerdict, rules: RuleScan, channel: Channel): ModelSignals {
  const category = rules.category && rules.category !== 'NONE' ? rules.category : 'OTHER';
  switch (v.verdict) {
    case 'SCAM':
      return {
        riskLevel: 'HIGH',
        category,
        method: channel === 'CALL' ? 'reject_call' : 'quarantine_sms',
        advice: "Don't tap links or share codes. Use official channels.",
      };
    case 'SUSPICIOUS':
      return { riskLevel: 'MEDIUM', category, method: 'none', advice: 'Be careful until this is settled.' };
    case 'SUBSCRIPTION':
      return { riskLevel: 'LOW', category: 'NONE', method: 'none', advice: 'You can text STOP after confirming, or manage the subscription if a link is present.' };
    case 'CODE':
      return { riskLevel: 'SAFE', category: 'NONE', method: 'none', advice: "No action needed. Don't share this code with anyone." };
    case 'OWNED':
      return { riskLevel: 'SAFE', category: 'NONE', method: 'none', advice: 'This looks like a message from an account you own.' };
    default:
      return { riskLevel: 'SAFE', category: 'NONE', method: 'none', advice: 'No scam patterns found.' };
  }
}

export function isTriState(value: string): boolean {
  return VALUES.has(value);
}
