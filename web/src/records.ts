// Builds sync-shaped records (verdicts, receipts, sender rules, subscriptions, content) and runs typed methods.
import type { Analysis, DeepResult } from './analyze';
import { deepAnalyze } from './analyze';
import type { Engine } from './ai';
import { redact, redactNumber } from './redact';
import { methodByAlias, validateSync, validateValue, type MethodSpec } from './registry';
import type { Store } from './store';
import type { SyncContent, SyncReceipt, SyncSenderRule, SyncSubscription, SyncVerdict } from './generated/types';

/** Same normalization as the Android app: digits and "+" only, email-to-SMS addresses kept. */
export function normalizeSender(raw?: string | null): string | null {
  const n = raw?.trim();
  if (!n) return null;
  if (n.includes('@')) return n;
  const d = n.replace(/[^\d+]/g, '');
  return d || null;
}
/** Firestore document id for a sender: lower-case, only [a-z0-9+._@-]. */
export const senderKey = (n: string) => n.toLowerCase().replace(/[^a-z0-9+._@-]/g, '_').slice(0, 80);

/** Rule labels with the message-specific part (domain, brand) removed, so synced flags are a fixed vocabulary. */
export const genericFlag = (label: string) => label.replace(/\s*\(.*\)$/, '').replace(/^Uses the name ".*"$/, 'Uses a well-known brand name');
export const syncFlags = (labels: string[]) => [...new Set(labels.map(genericFlag))].slice(0, 20);

/** Method-input fields that can carry message-derived text. They are kept out of the synced receipt. */
export const CONTENT_INPUT_FIELDS = ['reason', 'redactedText', 'onDeviceOpinion', 'warning'] as const;

export function splitInput(input: Record<string, unknown>): { clean: Record<string, unknown>; text: Record<string, unknown> } {
  const clean: Record<string, unknown> = {};
  const text: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(input)) ((CONTENT_INPUT_FIELDS as readonly string[]).includes(k) ? text : clean)[k] = v;
  return { clean, text };
}

function assertValid(name: string, doc: unknown) {
  const r = validateSync(name, doc);
  if (!r.valid) throw new Error(`${name} invalid: ${r.errors.join('; ')}`);
}

export interface Ctx { store: Store; deviceId: string; engine?: Engine | null; open?: (url: string) => boolean }

/** Persist the screened item. Returns ids for linking receipts. */
export async function saveVerdict(ctx: Ctx, a: Analysis, eventNumber: number): Promise<{ id: string; verdict: SyncVerdict }> {
  const id = `${ctx.deviceId}-${eventNumber}`;
  const now = Date.now();
  const verdict: SyncVerdict = {
    id, channel: a.input.channel, origin: 'web', source: 'web_paste', sender: normalizeSender(a.input.number),
    riskLevel: a.final.riskLevel, category: a.final.category as SyncVerdict['category'], confidence: a.final.confidence,
    rulesScore: a.rules.score, ruleFlags: syncFlags(a.rules.flags.map((f) => f.label)),
    decidedBy: a.final.decidedBy, model: a.run?.modelName ?? null, schemaValid: a.schemaValid,
    proposedMethod: a.final.proposedMethod, finalMethod: a.policy.method, quarantined: false, userAction: null,
    receivedAt: now, updatedAt: now, deviceId: ctx.deviceId,
  };
  assertValid('sync_verdict', verdict);
  await ctx.store.put('verdicts', verdict);
  const content: SyncContent = {
    itemId: id, kind: a.input.channel === 'CALL' ? 'call' : 'sms', body: a.input.body, reasons: a.final.reasons.slice(0, 5),
    advice: a.final.advice.slice(0, 300), updatedAt: now, deviceId: ctx.deviceId,
  };
  assertValid('sync_content', content);
  await ctx.store.put('content', content);
  return { id, verdict };
}

export interface MethodResult { ok: boolean; status: SyncReceipt['status']; receipt: SyncReceipt; output?: Record<string, unknown>; error?: string; deep?: DeepResult }

/** Web handlers for the methods a browser can actually perform. */
const handlers: Record<string, (ctx: Ctx, input: any, a: Analysis | null) => Promise<{ output: Record<string, unknown>; deep?: DeepResult }>> = {
  async block_number(ctx, input) {
    const key = senderKey(input.number);
    const prev = await ctx.store.get('senders', key);
    const now = Date.now();
    const rule: SyncSenderRule = { key, number: input.number, list: 'BLOCK', scope: input.scope, origin: 'web', createdAt: prev?.createdAt ?? now, updatedAt: now, deviceId: ctx.deviceId };
    assertValid('sync_sender_rule', rule);
    await ctx.store.put('senders', rule);
    return { output: { number: input.number, scope: input.scope, previouslyBlocked: prev?.list === 'BLOCK' && !prev.deleted, blockedAt: now } };
  },
  async allow_number(ctx, input) {
    const key = senderKey(input.number);
    const prev = await ctx.store.get('senders', key);
    const now = Date.now();
    const rule: SyncSenderRule = { key, number: input.number, list: 'TRUST', scope: 'ALL', origin: 'web', createdAt: prev?.createdAt ?? now, updatedAt: now, deviceId: ctx.deviceId };
    assertValid('sync_sender_rule', rule);
    await ctx.store.put('senders', rule);
    return { output: { number: input.number, trusted: true, removedFromBlockList: prev?.list === 'BLOCK' && !prev.deleted } };
  },
  async mute_sender(ctx, input) {
    const key = senderKey(input.sender);
    const prev = await ctx.store.get('senders', key);
    const now = Date.now();
    if (input.mode === 'CLEAR') {
      if (prev) await ctx.store.remove('senders', key, ctx.deviceId);
    } else {
      const rule: SyncSenderRule = { key, number: input.sender, list: 'MUTE', scope: 'MESSAGES', origin: 'web', createdAt: prev?.createdAt ?? now, updatedAt: now, deviceId: ctx.deviceId };
      assertValid('sync_sender_rule', rule);
      await ctx.store.put('senders', rule);
    }
    return { output: { sender: input.sender, mode: input.mode, savedAt: now } };
  },
  async quarantine_sms(ctx, input, a) {
    const v = a ? (await ctx.store.all('verdicts')).find((x) => x.id.endsWith(`-${input.eventId}`)) : undefined;
    if (v) await ctx.store.put('verdicts', { ...v, quarantined: true, userAction: 'quarantine_sms', updatedAt: Date.now(), deviceId: ctx.deviceId });
    return { output: { eventId: input.eventId, quarantined: true, warningPosted: false } };
  },
  async report_scam(ctx, input) {
    const url = 'https://reportfraud.ftc.gov/';
    const opened = ctx.open ? ctx.open(url) : false;
    return { output: { eventId: input.eventId, destination: 'reportfraud.ftc.gov (and forward texts to 7726)', composerOpened: opened } };
  },
  async manage_subscription(ctx, input) {
    const opened = ctx.open ? ctx.open(input.url) : false;
    const key = `${input.source}_${senderKey(input.sender)}`;
    const sub = await ctx.store.get('subscriptions', key);
    if (sub) await ctx.store.put('subscriptions', { ...sub, status: 'MANAGE_OPENED', updatedAt: Date.now(), deviceId: ctx.deviceId });
    return { output: { url: input.url, domain: input.domain, browserOpened: opened, openedAt: Date.now() } };
  },
  async explain_cloud(ctx, input, a) {
    if (!ctx.engine || !a) throw new Error('Cloud analysis needs a model engine and an analysis.');
    const deep = await deepAnalyze(ctx.engine, a);
    if (!deep.valid || !deep.value) throw new Error(`Cloud answer rejected by deep_analysis.schema.json: ${deep.errors.join('; ')}`);
    const v = deep.value;
    return {
      deep,
      output: {
        eventId: input.eventId, riskLevel: v.riskLevel, category: v.category, confidence: v.confidence, summary: v.summary,
        tactics: v.tactics, whatToDo: v.whatToDo, proposedMethod: v.method, model: deep.run.modelName, latencyMs: deep.run.latencyMs,
      },
    };
  },
};

export const canRunOnWeb = (alias: string) => alias in handlers;

/** Validates input -> runs the handler -> validates output -> writes a receipt. Nothing runs if the input is invalid. */
export async function runMethod(
  ctx: Ctx, spec: MethodSpec, input: Record<string, unknown>,
  meta: { analysis: Analysis | null; verdictId?: string | null; source: 'USER' | 'RULES' | 'ON_DEVICE' | 'IN_CLOUD'; dispatch: 'AUTO' | 'OFFER' | 'DENY' },
): Promise<MethodResult> {
  const n = await ctx.store.nextEventNumber();
  const now = Date.now();
  const a = meta.analysis;
  let status: SyncReceipt['status'] = 'OK';
  let output: Record<string, unknown> | undefined;
  let error: string | undefined;
  let deep: DeepResult | undefined;

  const inV = handlers[spec.alias] ? validateValue('input', spec.id, input) : { valid: true, errors: [] as string[] };
  if (!handlers[spec.alias]) {
    status = 'DENIED';
    error = `${spec.title} runs on your phone, not in the browser.`;
  } else if (!inV.valid) {
    status = 'FAILED';
    error = `Input rejected by ${spec.id} schema: ${inV.errors.join('; ')}`;
  } else {
    try {
      const r = await handlers[spec.alias](ctx, input, a);
      deep = r.deep;
      const outV = validateValue('output', spec.id, r.output);
      if (!outV.valid) { status = 'FAILED'; error = `Output did not match schema: ${outV.errors.join('; ')}`; } else output = r.output;
    } catch (e) {
      status = 'FAILED';
      error = (e as Error).message;
    }
  }

  const { clean, text } = splitInput(input);
  const receipt: SyncReceipt = {
    id: `${ctx.deviceId}-r${n}`, methodId: spec.id, alias: spec.alias, status,
    channel: a?.input.channel ?? 'SMS', origin: 'web', source: 'web_paste', sender: normalizeSender(a?.input.number),
    riskLevel: a?.final.riskLevel ?? 'SAFE', category: (a?.final.category ?? 'NONE') as SyncReceipt['category'],
    confidence: a?.final.confidence ?? 0, decidedBy: (meta.source === 'USER' ? a?.final.decidedBy : meta.source) ?? 'RULES',
    model: a?.run?.modelName ?? null, ruleFlags: syncFlags(a?.rules.flags.map((f) => f.label) ?? []),
    schemaValid: a?.schemaValid ?? true, dispatch: meta.dispatch, autoApplied: false,
    input: clean as any, output: (output ?? null) as any, createdAt: now, updatedAt: now, deviceId: ctx.deviceId,
  };
  assertValid('sync_receipt', receipt);
  await ctx.store.put('receipts', receipt);
  const contentDoc: SyncContent = {
    itemId: receipt.id, kind: 'receipt', inputText: text as any,
    summary: deep?.value?.summary?.slice(0, 1200) ?? null, redactedText: deep?.redacted ?? null,
    reasons: error ? [error.slice(0, 300)] : [], updatedAt: now, deviceId: ctx.deviceId,
  };
  assertValid('sync_content', contentDoc);
  await ctx.store.put('content', contentDoc);
  if (meta.verdictId && status === 'OK') {
    const v = await ctx.store.get('verdicts', meta.verdictId);
    if (v && v.userAction !== spec.alias) await ctx.store.put('verdicts', { ...v, userAction: spec.alias, updatedAt: now, deviceId: ctx.deviceId });
  }
  return { ok: status === 'OK', status, receipt, output, error, deep };
}

/** Store the subscription group for a pasted subscription text. */
export async function saveSubscription(ctx: Ctx, a: Analysis): Promise<SyncSubscription | null> {
  const s = a.subscription;
  if (!s?.isSubscription || !a.input.number) return null;
  const sender = normalizeSender(a.input.number) ?? a.input.number;
  const key = `web_paste_${senderKey(sender)}`;
  const prev = await ctx.store.get('subscriptions', key);
  const now = Date.now();
  const doc: SyncSubscription = {
    key, sender, source: 'web_paste', brand: s.brand, count: (prev?.count ?? 0) + 1, lastDate: now,
    keyword: (s.keyword as SyncSubscription['keyword']) ?? null, linkUrl: s.bestLink?.url ?? null, linkDomain: s.bestLink?.domain ?? null,
    linkKind: s.bestLink?.kind ?? null, status: prev?.status ?? 'ACTIVE', origin: prev?.origin ?? 'web', updatedAt: now, deviceId: ctx.deviceId,
  };
  assertValid('sync_subscription', doc);
  await ctx.store.put('subscriptions', doc);
  return doc;
}

export { redact, redactNumber };
export const specFor = (alias: string) => methodByAlias(alias);
