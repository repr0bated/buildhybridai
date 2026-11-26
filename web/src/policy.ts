// Capability policy over the method registry (port of the Android CapabilityPolicy).
// A model can only *propose* a registry method; this decides whether it may run, needs a tap, or is denied.
import { methodByAlias, registry, riskIndex, type MethodSpec, type Risk } from './registry';
import type { Channel } from './rules';

export type Source = 'RULES' | 'ON_DEVICE' | 'IN_CLOUD' | 'USER';
export type Dispatch = 'AUTO' | 'OFFER' | 'DENY';

export interface MethodProposal { method: string; riskLevel: Risk; confidence: number; reasons: string[]; source: Source }
export interface PolicyContext {
  channel: Channel;
  isContact: boolean;
  isTrusted: boolean;
  autoBlockEnabled?: boolean;
  autoBlockThreshold?: number;
  cloudAvailable?: boolean;
}
export interface PolicyDecision { proposed: string; method: string; dispatch: Dispatch; notes: string[]; substituted: boolean; spec?: MethodSpec }

export function decide(p: MethodProposal, ctx: PolicyContext): PolicyDecision {
  const notes: string[] = [];
  let method = p.method;
  if (method === 'none' && riskIndex(p.riskLevel) >= riskIndex('HIGH') && !ctx.isContact && !ctx.isTrusted) {
    method = ctx.channel === 'SMS' ? 'quarantine_sms' : 'reject_call';
    notes.push(`Risk is ${p.riskLevel} but no method was proposed; using ${method}.`);
  }
  if (method === 'none') return { proposed: p.method, method, dispatch: 'DENY', notes: [...notes, 'No method proposed.'], substituted: false };

  let dispatch = check(method, p, ctx, notes);
  if (dispatch === 'DENY' && method === 'block_number' && !ctx.isContact && !ctx.isTrusted) {
    const fb = ctx.channel === 'SMS' ? 'quarantine_sms' : 'reject_call';
    const fbNotes: string[] = [];
    const d = check(fb, p, ctx, fbNotes);
    if (d !== 'DENY') {
      notes.push(`Falling back to ${fb}.`, ...fbNotes);
      method = fb;
      dispatch = d;
    }
  }
  if (!notes.length) notes.push('Allowed by policy.');
  return { proposed: p.method, method, dispatch, notes, substituted: method !== p.method, spec: methodByAlias(method) };
}

function check(alias: string, p: MethodProposal, ctx: PolicyContext, notes: string[]): Dispatch {
  const spec = methodByAlias(alias);
  if (!spec) { notes.push(`${alias} is not in registry ${registry.registryVersion}.`); return 'DENY'; }
  const tag = alias;
  if (spec.proposable === false && p.source !== 'USER') { notes.push(`${tag} is a user-only method.`); return 'DENY'; }
  if (!spec.channels.includes(ctx.channel)) { notes.push(`${tag} does not apply to ${ctx.channel === 'SMS' ? 'texts' : 'calls'}.`); return 'DENY'; }
  if (riskIndex(p.riskLevel) < riskIndex(spec.minRisk)) { notes.push(`${tag} needs risk ≥ ${spec.minRisk} (got ${p.riskLevel}).`); return 'DENY'; }
  if (spec.maxRisk && riskIndex(p.riskLevel) > riskIndex(spec.maxRisk)) { notes.push(`${tag} only allowed up to risk ${spec.maxRisk} (got ${p.riskLevel}).`); return 'DENY'; }
  const minConf = alias === 'block_number' ? Math.max(spec.minConfidence ?? 0, ctx.autoBlockThreshold ?? 85) : spec.minConfidence ?? 0;
  if (p.source !== 'RULES' && p.confidence < minConf) { notes.push(`${tag} needs confidence ≥ ${minConf} (got ${p.confidence}).`); return 'DENY'; }
  if (!spec.contactSafe && (ctx.isContact || ctx.isTrusted)) { notes.push(`${tag} never runs against ${ctx.isTrusted ? 'a trusted sender' : 'a saved contact'}.`); return 'DENY'; }
  if (spec.capability === 'telephony.block' && !p.reasons.some((r) => r.trim())) { notes.push(`${tag} needs at least one reason.`); return 'DENY'; }
  if (spec.execution === 'cloud' && ctx.cloudAvailable === false) { notes.push(`${tag} runs in the cloud, which isn't available.`); return 'DENY'; }
  if (p.source === 'IN_CLOUD') { notes.push('Cloud proposals are offered, never auto-run.'); return 'OFFER'; }
  if (spec.requiresConsent) { notes.push(`${tag} needs your consent.`); return 'OFFER'; }
  if (!spec.autoApply) { notes.push(`${tag} is user-confirmed by design.`); return 'OFFER'; }
  if (alias === 'block_number' && ctx.autoBlockEnabled === false) { notes.push('Auto-block is off; offered instead.'); return 'OFFER'; }
  return 'AUTO';
}
