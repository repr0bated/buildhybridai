import { beforeEach, describe, expect, it } from 'vitest';
import { Store } from '../src/store';
import { analyze, parseInput } from '../src/analyze';
import { proposalsFor } from '../src/proposals';
import { runMethod, saveSubscription, saveVerdict, splitInput, normalizeSender, senderKey, type Ctx } from '../src/records';
import { methodByAlias, schemas, validateSync } from '../src/registry';
import { buildConfirmForm, renderObject } from '../src/schemaui';
import { renderReceipt } from '../src/receipt-view';
import { SyncCore, toRemote, type Remote } from '../src/sync';
import { loadSettings, saveSettings } from '../src/settings';
import { COLLECTIONS, type Collection } from '../src/store';
import { readFileSync } from 'node:fs';

let n = 0;
const fresh = () => new Store(`t${++n}-${Math.random()}`);
const verdict = JSON.stringify({ verdict: 'SCAM', confidence: 92, reasons: ['Look-alike link'], reputable_official: 'NO', held_account: 'NO' });
const engine = { async triage() { return { rawText: verdict, source: 'ON_DEVICE' as const, modelName: 'Gemini Nano (Chrome built-in)', latencyMs: 5 }; }, async deep() { throw new Error('no'); } };
const scam = 'USPS: Your package delivery failed. Confirm your details within 24 hours: https://usps-redelivery.top/x';

async function setup() {
  const store = fresh();
  const deviceId = await store.deviceId();
  const ctx: Ctx = { store, deviceId, engine, open: () => true };
  const a = await analyze(parseInput(scam, '+1 (415) 555-0142'), { engine });
  const num = await store.nextEventNumber();
  const { id } = await saveVerdict(ctx, a, num);
  return { store, ctx, a, num, id, deviceId };
}

describe('receipts in IndexedDB', () => {
  it('runs a typed method, validates both ends, and stores a receipt without message text', async () => {
    const { ctx, a, num, id, store } = await setup();
    const props = proposalsFor(a, num);
    const block = props.find((p) => p.alias === 'block_number')!;
    expect(block.input.number).toBe('+14155550142');
    const res = await runMethod(ctx, block.spec, block.input, { analysis: a, verdictId: id, source: 'USER', dispatch: 'OFFER' });
    expect(res.status).toBe('OK');
    expect(res.output).toMatchObject({ number: '+14155550142', previouslyBlocked: false });
    const stored = await store.get('receipts', res.receipt.id);
    expect(stored?.methodId).toBe('guardian.call.block@v1');
    expect(JSON.stringify(stored)).not.toContain('Look-alike link');
    expect(stored?.input).not.toHaveProperty('reason');
    expect((await store.get('content', res.receipt.id))?.inputText).toHaveProperty('reason');
    const rule = await store.get('senders', senderKey('+14155550142'));
    expect(rule).toMatchObject({ list: 'BLOCK', origin: 'web' });
    expect((await store.get('verdicts', id))?.userAction).toBe('block_number');
    expect(validateSync('sync_receipt', stored).valid).toBe(true);
  });
  it('refuses invalid input and records a FAILED receipt', async () => {
    const { ctx, a, id, store } = await setup();
    const res = await runMethod(ctx, methodByAlias('block_number')!, { number: 'nope', scope: 'ALL', reason: 'x' }, { analysis: a, verdictId: id, source: 'USER', dispatch: 'OFFER' });
    expect(res.status).toBe('FAILED');
    expect((await store.all('senders')).length).toBe(0);
  });
  it('marks phone-only methods as denied on the web', async () => {
    const { ctx, a, id } = await setup();
    const res = await runMethod(ctx, methodByAlias('unsubscribe_sms')!, { recipient: '28227', keyword: 'STOP', messagesFromSender: 1, reason: 'sub', source: 'sms' }, { analysis: a, verdictId: id, source: 'USER', dispatch: 'OFFER' });
    expect(res.status).toBe('DENIED');
  });
  it('keeps sender rules as tombstones when removed', async () => {
    const { store, deviceId } = await setup();
    await store.put('senders', { key: '+1', number: '+14155550142', list: 'TRUST', scope: 'ALL', origin: 'web', createdAt: 1, updatedAt: 1, deviceId } as any);
    await store.remove('senders', '+1', deviceId);
    expect((await store.all('senders')).length).toBe(0);
    expect((await store.all('senders', { includeDeleted: true }))[0].deleted).toBe(true);
  });
});

describe('schema-generated UI', () => {
  it('builds a confirmation form from the input schema and validates it', () => {
    const id = 'guardian.call.block@v1';
    const f = buildConfirmForm(id, schemas.methods[id].input, { number: '+14155550142', scope: 'ALL', reason: 'scam' });
    document.body.append(f.element);
    expect(f.element.querySelector('select')?.querySelectorAll('option').length).toBe(3); // scope enum
    expect(f.element.querySelector('output')?.textContent).toBe('+14155550142'); // readOnly number
    expect(f.validate().valid).toBe(true);
    (f.element.querySelector('#f-reason') as HTMLInputElement).value = '';
    expect(f.validate().valid).toBe(false);
  });
  it('renders a receipt from the method schemas, formatting epoch-millis', async () => {
    const { ctx, a, num, id } = await setup();
    const block = proposalsFor(a, num).find((p) => p.alias === 'block_number')!;
    const res = await runMethod(ctx, block.spec, block.input, { analysis: a, verdictId: id, source: 'USER', dispatch: 'OFFER' });
    const el = renderReceipt(res.receipt, await ctx.store.get('content', res.receipt.id));
    expect(el.textContent).toContain('Blocked at');
    expect(el.textContent).toContain('guardian.call.block@v1');
    expect(renderObject(schemas.methods['guardian.call.block@v1'].output, { previouslyBlocked: true }).textContent).toContain('Was already blocked');
  });
});

describe('subscriptions', () => {
  it('stores a subscription group with the best link and keyword, no message text', async () => {
    const store = fresh();
    const ctx: Ctx = { store, deviceId: await store.deviceId(), engine: null };
    const a = await analyze(parseInput('Cash App: weekly summary. Manage alerts at https://cash.app/manage-alerts. Reply STOP to unsubscribe. Msg&data rates may apply.', '28227'), { engine: null });
    const s = await saveSubscription(ctx, a);
    expect(s).toMatchObject({ key: 'web_paste_28227', keyword: 'STOP', linkDomain: 'cash.app', status: 'ACTIVE' });
    expect(JSON.stringify(s)).not.toContain('weekly summary');
    const props = proposalsFor(a, 1);
    expect(props.map((p) => p.alias)).toEqual(expect.arrayContaining(['manage_subscription', 'mute_sender']));
  });
});

// ---------------------------------------------------------------- sync
class MemRemote implements Remote {
  docs = new Map<string, Map<string, any>>();
  subs = new Map<string, Set<(d: any[]) => void>>();
  constructor() { for (const c of COLLECTIONS) { this.docs.set(c, new Map()); this.subs.set(c, new Set()); } }
  private fire(c: Collection) { const d = [...this.docs.get(c)!.entries()].map(([id, v]) => ({ ...v, __id: id })); this.subs.get(c)!.forEach((f) => f(d)); }
  subscribe(c: Collection, cb: (d: any[]) => void) { this.subs.get(c)!.add(cb); queueMicrotask(() => this.fire(c)); return () => this.subs.get(c)!.delete(cb); }
  async set(c: Collection, id: string, doc: any) { this.docs.get(c)!.set(id, doc); this.fire(c); }
  async delete(c: Collection, id: string) { this.docs.get(c)!.delete(id); this.fire(c); }
}
const settle = () => new Promise((r) => setTimeout(r, 30));

describe('sync', () => {
  it('pushes receipts/verdicts/lists but NOT message content by default', async () => {
    const { store, ctx, a, num, id } = await setup();
    const remote = new MemRemote();
    const core = new SyncCore(store, remote);
    await core.start(); await settle();
    expect(remote.docs.get('content')!.size).toBe(0);
    expect(remote.docs.get('verdicts')!.size).toBe(1); // initial upload of what existed
    const block = proposalsFor(a, num).find((p) => p.alias === 'block_number')!;
    await runMethod(ctx, block.spec, block.input, { analysis: a, verdictId: id, source: 'USER', dispatch: 'OFFER' });
    await settle();
    expect(remote.docs.get('receipts')!.size).toBe(1);
    expect(remote.docs.get('senders')!.size).toBe(1);
    expect(remote.docs.get('content')!.size).toBe(0);
    const all = JSON.stringify([...remote.docs.values()].flatMap((m) => [...m.values()]));
    expect(all).not.toContain('delivery failed');
    expect(all).not.toContain('Look-alike link');
    for (const [c, m] of remote.docs) for (const d of m.values()) {
      const name = ({ receipts: 'sync_receipt', verdicts: 'sync_verdict', senders: 'sync_sender_rule', subscriptions: 'sync_subscription', privateFlags: 'sync_private_flag', content: 'sync_content', settings: 'sync_settings' } as any)[c];
      expect(validateSync(name, d), `${c} ${JSON.stringify(d)}`).toMatchObject({ valid: true });
    }
    core.stop();
  });

  it('uploads content only after opt-in, and deletes it from the cloud when turned off', async () => {
    const { store } = await setup();
    const remote = new MemRemote();
    const core = new SyncCore(store, remote);
    await core.start(); await settle();
    await saveSettings(store, { syncMessageContent: true }); await settle();
    expect(remote.docs.get('content')!.size).toBeGreaterThan(0);
    expect(JSON.stringify([...remote.docs.get('content')!.values()])).toContain('delivery failed');
    expect(remote.docs.get('settings')!.get('main')).not.toHaveProperty('key');
    await saveSettings(store, { syncMessageContent: false }); await settle();
    expect(remote.docs.get('content')!.size).toBe(0);
    expect((await store.all('content')).length).toBeGreaterThan(0); // still on this device
    core.stop();
  });

  it('pulls newer remote documents (last writer wins) and ignores stale or invalid ones', async () => {
    const store = fresh();
    const dev = await store.deviceId();
    await store.put('senders', { key: '+1555', number: '+1555', list: 'BLOCK', scope: 'ALL', origin: 'web', createdAt: 1, updatedAt: 100, deviceId: dev });
    const remote = new MemRemote();
    remote.docs.get('senders')!.set('+1555', { key: '+1555', number: '+1555', list: 'TRUST', scope: 'ALL', origin: 'android', createdAt: 1, updatedAt: 200, deviceId: 'android1' });
    remote.docs.get('senders')!.set('+1666', { key: '+1666', number: '+1666', list: 'BLOCK', scope: 'ALL', origin: 'android', createdAt: 1, updatedAt: 50, deviceId: 'android1' });
    remote.docs.get('senders')!.set('bad', { key: 'bad', list: 'EVIL' });
    const core = new SyncCore(store, remote);
    await core.start(); await settle();
    expect((await store.get('senders', '+1555'))?.list).toBe('TRUST');
    expect((await store.get('senders', '+1666'))?.list).toBe('BLOCK');
    expect(await store.get('senders', 'bad')).toBeUndefined();
    expect(core.status.rejected).toBe(1);
    core.stop();
  });

  it('syncs private flags and settings both ways', async () => {
    const store = fresh();
    const remote = new MemRemote();
    const core = new SyncCore(store, remote);
    await core.start(); await settle();
    await store.put('privateFlags', { key: 'receipt_x-1', kind: 'receipt', itemId: 'x-1', isPrivate: true, updatedAt: Date.now(), deviceId: 'w' });
    await settle();
    expect(remote.docs.get('privateFlags')!.get('receipt_x-1')).toMatchObject({ isPrivate: true });
    await remote.set('settings', 'main', { syncMessageContent: false, autoBlockEnabled: false, updatedAt: Date.now() + 1000, deviceId: 'android1' });
    await settle();
    expect((await loadSettings(store)).autoBlockEnabled).toBe(false);
    core.stop();
  });

  it('settings never include a key field remotely', () => {
    expect(toRemote('settings', { key: 'main', syncMessageContent: false })).toEqual({ syncMessageContent: false });
    expect(normalizeSender('(415) 555-0142')).toBe('4155550142');
    expect(splitInput({ number: '1', reason: 'r', warning: 'w' }).clean).toEqual({ number: '1' });
  });
});

describe('generated firestore.rules', () => {
  const rules = readFileSync('firestore.rules', 'utf8');
  it('scopes everything to the signed-in owner and denies the rest', () => {
    expect(rules).toContain('request.auth.uid == uid');
    expect(rules).toContain('match /{document=**} { allow read, write: if false; }');
    for (const c of ['receipts', 'verdicts', 'senders', 'subscriptions', 'privateFlags', 'content', 'settings', 'companionAlerts']) expect(rules).toContain(`match /users/{uid}/${c}/`);
  });
  it('gates the content collection on settings/main.syncMessageContent', () => {
    const block = rules.slice(rules.indexOf('match /users/{uid}/content/'));
    expect(block.slice(0, block.indexOf('\n    }\n'))).toContain('contentSyncOn(uid)');
    expect(rules.match(/contentSyncOn\(uid\) &&/g)?.length).toBe(1);
  });
});
