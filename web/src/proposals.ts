// Which typed methods to offer for an analysis, with inputs bound from the screened event (never from model text).
import type { Analysis } from './analyze';
import { canRunOnWeb, normalizeSender } from './records';
import { methodByAlias, riskIndex, type MethodSpec } from './registry';
import { redact } from './redact';

export interface Proposal {
  alias: string;
  spec: MethodSpec;
  input: Record<string, unknown>;
  /** policy = chosen by the capability policy; user = available because you can always choose it. */
  origin: 'policy' | 'user';
  dispatch: 'AUTO' | 'OFFER' | 'DENY';
  note: string;
}

export function bindInput(alias: string, a: Analysis, eventNumber: number): Record<string, unknown> | null {
  const number = normalizeSender(a.input.number);
  const reason = (a.final.reasons[0] ?? a.final.advice ?? 'Reviewed on the web').slice(0, 200) || 'Reviewed on the web';
  switch (alias) {
    case 'block_number': return number ? { number, scope: a.input.channel === 'CALL' ? 'CALLS' : 'ALL', reason } : null;
    case 'allow_number': return number ? { number, reason } : null;
    case 'quarantine_sms': return a.input.channel === 'SMS' ? { eventId: eventNumber, risk: a.final.riskLevel, reason } : null;
    case 'report_scam': return { eventId: eventNumber, channel: a.input.channel, category: a.final.category, reason };
    case 'explain_cloud': return a.input.body ? { eventId: eventNumber, channel: a.input.channel, redactedText: redact(a.input.body).slice(0, 4000), onDeviceOpinion: `${a.final.riskLevel} ${a.final.category}`.slice(0, 200) } : null;
    case 'manage_subscription': {
      const l = a.subscription?.bestLink;
      if (!l || !number) return null;
      const warn = safetyOf(a);
      return { sender: number, url: l.url, domain: l.domain, linkKind: l.kind, chosenBy: 'RULES', suspicious: warn.suspicious, ...(warn.warning ? { warning: warn.warning } : {}), acknowledgeWarning: false, source: 'sms' };
    }
    case 'mute_sender': return a.subscription?.isSubscription && number ? { sender: number, mode: 'MUTE', source: 'sms' } : null;
    default: return null;
  }
}

import { linkSafety } from './subscriptions';
function safetyOf(a: Analysis) {
  const l = a.subscription!.bestLink!;
  return linkSafety(l, a.subscription!.brand, a.subscription!.scamRisk);
}

export function proposalsFor(a: Analysis, eventNumber: number, trusted = false): Proposal[] {
  const out: Proposal[] = [];
  const seen = new Set<string>();
  const add = (alias: string, origin: Proposal['origin'], dispatch: Proposal['dispatch'], note: string) => {
    if (seen.has(alias)) return;
    const spec = methodByAlias(alias);
    if (!spec || !canRunOnWeb(alias)) return;
    const input = bindInput(alias, a, eventNumber);
    if (!input) return;
    seen.add(alias);
    out.push({ alias, spec, input, origin, dispatch, note });
  };
  const p = a.policy;
  if (p.method !== 'none' && p.dispatch !== 'DENY') {
    add(p.method, 'policy', p.dispatch, p.dispatch === 'AUTO' ? 'The phone would run this automatically; on the web you confirm it.' : p.notes.join(' '));
  }
  const risky = riskIndex(a.final.riskLevel) >= riskIndex('MEDIUM');
  if (a.subscription?.isSubscription) {
    add('manage_subscription', 'user', 'OFFER', 'Opens the service\'s own page so you can unsubscribe yourself.');
    add('mute_sender', 'user', 'OFFER', 'Stop alerts for this sender.');
  }
  if (a.input.body) add('explain_cloud', 'user', 'OFFER', 'You choose; nothing is sent until you confirm.');
  if (risky) {
    add('report_scam', 'user', 'OFFER', 'Opens the FTC report page.');
    add('block_number', 'user', 'OFFER', 'Block this sender on every synced device.');
  } else if (!trusted) {
    add('allow_number', 'user', 'OFFER', 'Mark this sender trusted on every synced device.');
  }
  return out;
}
