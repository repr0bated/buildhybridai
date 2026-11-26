import './style.css';
import { FirebaseEngine, deviceAvailability, type DeviceAvailability } from './ai';
import { analyze, deepAnalyze, parseInput, type Analysis } from './analyze';
import { proposalsFor, type Proposal } from './proposals';
import { normalizeSender, runMethod, saveSubscription, saveVerdict, senderKey, type Ctx } from './records';
import { renderReceipt } from './receipt-view';
import { registry, schemas } from './registry';
import { buildConfirmForm } from './schemaui';
import { loadSettings, saveSettings, type Settings } from './settings';
import { Store } from './store';
import { SyncCore, type SyncStatus } from './sync';
import { isJevEnabled, setJevEnabled, jevCheck, senderKind, type JevResult } from './jev';
import { redact } from './redact';

const store = new Store();
const app = document.getElementById('app')!;
let engine: FirebaseEngine | null = null;
let deviceState: DeviceAvailability = 'unsupported';
let settings: Settings;
let deviceId = '';
let tab: 'check' | 'receipts' | 'lists' | 'subs' | 'settings' = 'check';
let current: { a: Analysis; verdictId: string; eventNumber: number; proposals: Proposal[]; jev?: JevResult | { error: string } } | null = null;
let busy = '';
let unlocked = false;
let sync: SyncCore | null = null;
let syncStatus: SyncStatus = { state: 'off', pushed: 0, pulled: 0, rejected: 0 };
let user: { uid: string; email: string | null; name: string | null } | null = null;
let downloadPct: number | null = null;
let authError = '';

const SAMPLES: [string, string, string][] = [
  ['USPS scam', 'USPS: Your package could not be delivered due to an incorrect address. Confirm your details within 24 hours to avoid return: https://usps-redelivery.top/track', '+14155550142'],
  ['Toll scam', 'Final notice: you have an unpaid toll balance of $12.40. Pay now to avoid a $50 late fee: bit.ly/3tollpay', '+19175550199'],
  ['Subscription', 'Cash App: Your weekly summary is ready. Manage alerts at https://cash.app/manage-alerts. Reply STOP to unsubscribe. Msg&data rates may apply.', '28227'],
  ['Normal text', 'Hey, running 10 min late. Want me to grab you a coffee?', '+13105550123'],
  ['Number only', '+232 76 555 0100', ''],
];

const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
const h = (html: string) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild as HTMLElement; };
const ctx = (): Ctx => ({ store, deviceId, engine, open: (u) => { const w = window.open(u, '_blank', 'noopener,noreferrer'); return !!w; } });

async function isPrivate(kind: string, id: string) {
  const f = await store.get('privateFlags', `${kind}_${id}`);
  return !!f && f.isPrivate && !f.deleted;
}
async function setPrivate(kind: 'receipt' | 'verdict', id: string, on: boolean) {
  await store.put('privateFlags', { key: `${kind}_${id}`, kind, itemId: id, isPrivate: on, updatedAt: Date.now(), deviceId });
}

// ------------------------------------------------------------------ render

function riskBadge(r: string) { return `<span class="badge risk-${r}">${r}</span>`; }

function header(): string {
  const dev: Record<DeviceAvailability, [string, string]> = {
    available: ['ok', 'Gemini Nano · ready'], downloadable: ['warn', 'Gemini Nano · not downloaded'], downloading: ['warn', 'Gemini Nano · downloading'],
    unavailable: ['bad', 'Gemini Nano · unavailable (cloud fallback)'], unsupported: ['bad', 'Gemini Nano · not in this browser (cloud fallback)'],
  };
  const [cls, label] = dev[deviceState];
  const s = syncStatus;
  const syncChip = user ? `<span class="chip ${s.state === 'error' ? 'bad' : s.state === 'idle' ? 'ok' : 'warn'}">sync ${esc(s.state)} · ${esc(user.email ?? user.uid.slice(0, 6))}</span>` : '<span class="chip">sync off</span>';
  return `<header class="top"><h1><span class="cur">▍</span>call guardian<small>web · registry ${esc(registry.registryVersion)}</small></h1>
  <div class="chips"><span class="chip ${cls}">${label}${downloadPct !== null ? ` ${Math.round(downloadPct * 100)}%` : ''}</span>
  ${deviceState === 'downloadable' || deviceState === 'downloading' ? '<button class="btn" id="prep">Download Nano</button>' : ''}
  ${syncChip}
  ${user ? '<button class="btn" id="signout">Sign out</button>' : '<button class="btn" id="signin">Sign in with Google to sync</button>'}</div></header>`;
}

function tabs(): string {
  const t: [typeof tab, string][] = [['check', 'Check'], ['receipts', 'Receipts'], ['lists', 'Block / trust'], ['subs', 'Subscriptions'], ['settings', 'Settings']];
  return `<nav class="tabs" role="tablist">${t.map(([k, l]) => `<button role="tab" data-tab="${k}" aria-selected="${tab === k}">${l}</button>`).join('')}</nav>`;
}

async function render() {
  const body = tab === 'check' ? await checkView() : tab === 'receipts' ? await receiptsView() : tab === 'lists' ? await listsView() : tab === 'subs' ? await subsView() : await settingsView();
  app.innerHTML = header() + (authError ? `<div class="notice">${esc(authError)}</div>` : '') + tabs();
  app.append(body);
  app.append(h(`<footer>Call Guardian for the web · personal project of Jeremy Hobson · Apache-2.0 · hybrid AI via Firebase AI Logic · types, validation and forms generated from the JSON Schema registry (${registry.methods.length} methods).</footer>`));
  wire();
}

async function checkView(): Promise<HTMLElement> {
  const root = h('<div></div>');
  root.append(h(`<section class="card"><h2>check a text, number or link</h2>
    <div class="row"><label for="txt">Paste it here</label><textarea id="txt" rows="5" placeholder="Paste a text message, a phone number, or a link…"></textarea></div>
    <div class="row"><label for="sender">Sender number (optional)</label><input type="text" id="sender" placeholder="+1 415 555 0100" /></div>
    <div class="flex"><button class="btn primary" id="go" ${busy ? 'disabled' : ''}>${busy ? esc(busy) : 'Analyze'}</button><span class="muted">Runs rules first, then Gemini Nano in Chrome; falls back to cloud Gemini.</span></div>
    <div class="samples">${SAMPLES.map((s, i) => `<button data-sample="${i}">${esc(s[0])}</button>`).join('')}</div></section>`));
  if (current) root.append(await resultView(current));
  return root;
}

async function resultView(c: NonNullable<typeof current>): Promise<HTMLElement> {
  const { a } = c;
  const f = a.final;
  const root = h('<div></div>');
  const priv = await isPrivate('verdict', c.verdictId);
  const sub = a.subscription?.isSubscription ? `<p class="notice">Looks like a subscription text${a.subscription.keyword ? ` — opt out by texting <b>${esc(a.subscription.keyword)}</b> from your phone` : ''}.${a.subscription.bestLink ? ` A ${esc(a.subscription.bestLink.kind.toLowerCase().replace('_', ' '))} link points to <b>${esc(a.subscription.bestLink.domain)}</b>.` : ''}</p>` : '';
  const verdict = h(`<section class="card ${priv && !unlocked ? 'blurred' : ''}"><h2>verdict</h2>
    <div class="flex">${riskBadge(f.riskLevel)}<strong>${esc(f.category.replace(/_/g, ' '))}</strong><span class="muted">confidence ${f.confidence}% · decided by ${esc(f.decidedBy.toLowerCase().replace('_', '-'))}</span><span class="grow"></span>
    <label class="flex"><input type="checkbox" id="priv" ${priv ? 'checked' : ''}/> private</label></div>
    <div class="rowtext"><ul>${f.reasons.map((r) => `<li>${esc(r)}</li>`).join('')}</ul><p>${esc(f.advice)}</p>${sub}</div></section>`);
  root.append(verdict);
  const steps = h(`<section class="card"><h2>how this was decided</h2><ol class="steps">${a.steps.map((s) => `<li class="${s.ok ? '' : s.kind === 'model' ? 'warn' : 'bad'}"><strong>${esc(s.title)}</strong><span>${esc(s.detail)}</span></li>`).join('')}</ol></section>`);
  root.append(steps);
  if (c.jev) {
    if ('error' in c.jev) {
      root.append(h(`<section class="card"><h2>jev second opinion</h2><p class="err">${esc(c.jev.error)}</p></section>`));
    } else {
      const j = c.jev;
      const sigs = Object.entries(j.signals).map(([k,v]) => `<li><span class="muted">${esc(k)}</span> ${Number(v).toFixed(2)}</li>`).join('');
      root.append(h(`<section class="card"><h2>jev second opinion</h2>
        <div class="flex">${riskBadge(j.table.verdict === 'SCAM' ? 'CRITICAL' : j.table.verdict === 'SUSPICIOUS' ? 'HIGH' : j.table.verdict === 'UNSETTLED' ? 'MEDIUM' : 'SAFE')}<strong>${esc(j.table.verdict)}</strong>
        <span class="muted">${esc(j.table.rule)} · ${esc(j.model)} · questions ${esc(j.questionsVersion)}</span></div>
        <p class="muted">TypeSafe Jev judged each scam signal; the truth table in this app decided the row. Only a redacted copy of the text left the browser.</p>
        <ul>${sigs}</ul></section>`));
    }
  } else if (isJevEnabled() && a.input.body) {
    root.append(h('<section class="card"><h2>jev second opinion</h2><p class="muted">Asking TypeSafe Jev…</p></section>'));
  }
  const props = h('<section class="card"><h2>proposed typed methods</h2></section>');
  if (!c.proposals.length) props.append(h('<p class="muted">Nothing to do: no action proposed.</p>'));
  for (const p of c.proposals) {
    const box = h(`<div class="proposal"><header><strong>${esc(p.spec.title)} <span class="muted">${esc(p.spec.id)}</span></strong><span class="tag">${p.origin === 'policy' ? 'policy · ' + p.dispatch : 'your choice'}</span></header><p class="muted">${esc(p.spec.description ?? '')} ${esc(p.note)}</p><div class="slot"></div><button class="btn" data-open="${p.alias}">Review &amp; confirm…</button></div>`);
    props.append(box);
  }
  root.append(props);
  return root;
}

async function receiptsView(): Promise<HTMLElement> {
  const root = h('<div></div>');
  const rs = (await store.all('receipts')).sort((a, b) => b.createdAt - a.createdAt);
  root.append(h(`<section class="card"><h2>receipts (${rs.length})</h2><div class="flex"><span class="muted">Every typed method that ran, stored in IndexedDB${user ? ' and synced to your account' : ''}.</span><span class="grow"></span><button class="btn" id="unlock">${unlocked ? 'Lock private items' : 'Reveal private items'}</button></div></section>`));
  for (const r of rs) {
    const priv = await isPrivate('receipt', r.id);
    const content = await store.get('content', r.id);
    const card = renderReceipt(r, content, { hidden: priv && !unlocked });
    const bar = h(`<div class="flex"><label class="flex"><input type="checkbox" data-priv-receipt="${esc(r.id)}" ${priv ? 'checked' : ''}/> private</label></div>`);
    card.append(bar);
    root.append(card);
  }
  if (!rs.length) root.append(h('<p class="muted">No receipts yet. Analyze something and confirm a method.</p>'));
  return root;
}

async function listsView(): Promise<HTMLElement> {
  const rules = (await store.all('senders')).sort((a, b) => b.updatedAt - a.updatedAt);
  const root = h(`<section class="card"><h2>block / trust / mute lists (${rules.length})</h2><p class="muted">Shared with your phone through sync.</p></section>`);
  for (const r of rules) {
    root.append(h(`<div class="list-row"><span><span class="tag">${esc(r.list)}</span> ${esc(r.number)} <span class="muted">${esc(r.scope)} · from ${esc(r.origin)}</span></span><button class="btn" data-rm="${esc(r.key)}">Remove</button></div>`));
  }
  if (!rules.length) root.append(h('<p class="muted">Empty.</p>'));
  return root;
}

async function subsView(): Promise<HTMLElement> {
  const subs = (await store.all('subscriptions')).sort((a, b) => (b.lastDate ?? 0) - (a.lastDate ?? 0));
  const root = h(`<section class="card"><h2>subscriptions (${subs.length})</h2><p class="muted">Texts that offer an opt-out. STOP is sent from your phone; links open the service's own page.</p></section>`);
  for (const s of subs) {
    const link = s.linkUrl ? `<a href="${esc(s.linkUrl)}" target="_blank" rel="noopener noreferrer">${esc(s.linkDomain)}</a>` : '—';
    const stop = s.keyword ? ` · <a href="sms:${esc(s.sender)}?body=${esc(s.keyword)}">text ${esc(s.keyword)}</a>` : '';
    root.append(h(`<div class="list-row"><span><strong>${esc(s.brand ?? s.sender)}</strong> <span class="muted">${esc(s.sender)} · ${s.count} text(s) · ${esc(s.status)}</span><br/>link: ${link}${stop}</span></div>`));
  }
  if (!subs.length) root.append(h('<p class="muted">None yet.</p>'));
  return root;
}

async function settingsView(): Promise<HTMLElement> {
  const root = h('<div></div>');
  root.append(h(`<section class="card"><h2>sync</h2>
    <p>${user ? `Signed in as <b>${esc(user.email ?? user.uid)}</b>. Pushed ${syncStatus.pushed}, pulled ${syncStatus.pulled}${syncStatus.rejected ? `, rejected ${syncStatus.rejected}` : ''}${syncStatus.error ? ` · <span class="err">${esc(syncStatus.error)}</span>` : ''}.` : 'Sign in with Google to sync receipts, verdicts, block/trust lists, subscriptions and settings with your phone. Your data is stored under your own account only.'}</p>
    <label class="toggle"><input type="checkbox" id="syncContent" ${settings.syncMessageContent ? 'checked' : ''}/><span><b>Sync message content</b><br/><span class="muted">Off by default. When off, message text, redacted text and model-written reasons never leave this browser. Turning it off deletes synced content from the cloud.</span></span></label></section>`));
  root.append(h(`<section class="card"><h2>analysis</h2>
    <label class="toggle"><input type="checkbox" id="onDeviceOnly" ${settings.onDeviceOnly ? 'checked' : ''}/><span><b>On-device only</b><br/><span class="muted">Never use cloud Gemini for the verdict. If Nano isn't ready the check fails instead of falling back.</span></span></label>
    <label class="toggle"><input type="checkbox" id="autoBlock" ${settings.autoBlockEnabled ? 'checked' : ''}/><span><b>Offer auto-block for obvious scams</b><br/><span class="muted">Confidence threshold ${settings.autoBlockThreshold}%.</span></span></label>
    <label class="toggle"><input type="checkbox" id="jevOn" ${isJevEnabled() ? 'checked' : ''}/><span><b>Jev second opinion</b><br/><span class="muted">Off by default. The default judge is Firebase AI Logic (Gemini Nano in Chrome, then cloud Gemini). Turn this on to also send a redacted copy to TypeSafe Jev through the Cloudflare Worker (the TypeSafe key never sits in this app).</span></span></label></section>`));
  root.append(h('<section class="card"><h2>data</h2><div class="flex"><button class="btn" id="export">Export receipts (JSON)</button><button class="btn" id="wipe">Delete local data</button></div></section>'));
  return root;
}

// ------------------------------------------------------------------ actions

function wire() {
  app.querySelectorAll<HTMLButtonElement>('[data-tab]').forEach((b) => b.addEventListener('click', () => { tab = b.dataset.tab as typeof tab; void render(); }));
  const $ = (id: string) => document.getElementById(id);
  $('go')?.addEventListener('click', () => void doAnalyze());
  app.querySelectorAll<HTMLButtonElement>('[data-sample]').forEach((b) => b.addEventListener('click', () => {
    const s = SAMPLES[Number(b.dataset.sample)];
    (document.getElementById('txt') as HTMLTextAreaElement).value = s[1];
    (document.getElementById('sender') as HTMLInputElement).value = s[2];
  }));
  $('prep')?.addEventListener('click', async () => {
    engine ??= new FirebaseEngine();
    try { await engine.prepareDevice((p) => { downloadPct = p; void render(); }); } catch (e) { authError = `Nano download: ${(e as Error).message}`; }
    downloadPct = null; deviceState = await deviceAvailability(); void render();
  });
  $('signin')?.addEventListener('click', async () => {
    authError = '';
    try { const { signInWithGoogle } = await import('./sync-firebase'); await signInWithGoogle(); } catch (e) { authError = `Sign-in failed: ${(e as Error).message}`; void render(); }
  });
  $('signout')?.addEventListener('click', async () => { const m = await import('./sync-firebase'); await m.signOut(); });
  $('priv')?.addEventListener('change', (e) => { if (current) void setPrivate('verdict', current.verdictId, (e.target as HTMLInputElement).checked).then(render); });
  $('unlock')?.addEventListener('click', () => { unlocked = !unlocked; void render(); });
  app.querySelectorAll<HTMLInputElement>('[data-priv-receipt]').forEach((i) => i.addEventListener('change', () => void setPrivate('receipt', i.dataset.privReceipt!, i.checked).then(render)));
  app.querySelectorAll<HTMLButtonElement>('[data-rm]').forEach((b) => b.addEventListener('click', async () => { await store.remove('senders', b.dataset.rm!, deviceId); void render(); }));
  app.querySelectorAll<HTMLButtonElement>('[data-open]').forEach((b) => b.addEventListener('click', () => openConfirm(b)));
  $('syncContent')?.addEventListener('change', async (e) => {
    const on = (e.target as HTMLInputElement).checked;
    if (on && !confirm('Sync message content?\n\nMessage text and model-written reasons will be stored in your private account in Firestore and appear on your other signed-in devices. You can turn this off at any time, which deletes it from the cloud.')) { (e.target as HTMLInputElement).checked = false; return; }
    settings = await saveSettings(store, { syncMessageContent: on }); void render();
  });
  $('onDeviceOnly')?.addEventListener('change', async (e) => { settings = await saveSettings(store, { onDeviceOnly: (e.target as HTMLInputElement).checked }); });
  $('jevOn')?.addEventListener('change', (e) => { setJevEnabled((e.target as HTMLInputElement).checked); });
  $('autoBlock')?.addEventListener('change', async (e) => { settings = await saveSettings(store, { autoBlockEnabled: (e.target as HTMLInputElement).checked }); });
  $('export')?.addEventListener('click', async () => {
    const data = JSON.stringify({ receipts: await store.all('receipts') }, null, 2);
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([data], { type: 'application/json' })); a.download = 'callguardian-receipts.json'; a.click();
  });
  $('wipe')?.addEventListener('click', async () => { if (confirm('Delete all receipts, lists and settings stored in this browser? Synced data in your account is not deleted.')) { await store.clearAll(); location.reload(); } });
}

async function doAnalyze() {
  const text = (document.getElementById('txt') as HTMLTextAreaElement).value;
  const sender = (document.getElementById('sender') as HTMLInputElement).value;
  if (!text.trim()) return;
  const input = parseInput(text, sender);
  busy = 'Analyzing…'; await render();
  try {
    engine ??= new FirebaseEngine();
    deviceState = await deviceAvailability();
    const num = normalizeSender(input.number);
    const rule = num ? await store.get('senders', senderKey(num)) : undefined;
    const a = await analyze(input, {
      engine, settings, deviceReady: deviceState === 'available',
      isTrusted: rule?.list === 'TRUST' && !rule.deleted, isBlocked: rule?.list === 'BLOCK' && !rule.deleted,
    });
    const eventNumber = await store.nextEventNumber();
    const { id } = await saveVerdict(ctx(), a, eventNumber);
    await saveSubscription(ctx(), a);
    current = { a, verdictId: id, eventNumber, proposals: proposalsFor(a, eventNumber, rule?.list === 'TRUST') };
    if (isJevEnabled() && input.body) {
      try {
        current.jev = await jevCheck(redact(input.body), senderKind(input.number));
      } catch (e) {
        current.jev = { error: (e as Error).message };
      }
    }
    authError = '';
  } catch (e) {
    authError = `Analysis failed: ${(e as Error).message}`;
  }
  busy = ''; tab = 'check'; await render();
  (document.getElementById('txt') as HTMLTextAreaElement | null)?.setAttribute('data-done', '1');
}

function openConfirm(btn: HTMLButtonElement) {
  if (!current) return;
  const p = current.proposals.find((x) => x.alias === btn.dataset.open)!;
  const slot = btn.parentElement!.querySelector('.slot') as HTMLElement;
  slot.innerHTML = '';
  const form = buildConfirmForm(p.spec.id, schemas.methods[p.spec.id].input, p.input);
  slot.append(h('<h4>Confirm — generated from the input schema</h4>'), form.element);
  const msg = h('<p class="err"></p>');
  const go = h('<button type="button" class="btn primary">Confirm and run</button>') as HTMLButtonElement;
  go.addEventListener('click', async () => {
    const v = form.validate();
    if (!v.valid) { msg.textContent = v.errors.join('; '); return; }
    go.disabled = true; msg.textContent = '';
    const res = await runMethod(ctx(), p.spec, form.values(), { analysis: current!.a, verdictId: current!.verdictId, source: 'USER', dispatch: p.dispatch });
    slot.innerHTML = '';
    slot.append(renderReceipt(res.receipt, await store.get('content', res.receipt.id)));
    if (res.error) slot.append(h(`<p class="err">${esc(res.error)}</p>`));
    btn.remove();
  });
  slot.append(msg, go);
  btn.hidden = true;
}

// ------------------------------------------------------------------ boot

async function boot() {
  deviceId = await store.deviceId();
  settings = await loadSettings(store);
  deviceState = await deviceAvailability();
  store.onChange((c, _d, origin) => { if (origin === 'remote' && (c === 'receipts' || c === 'senders' || c === 'subscriptions' || c === 'settings')) { if (c === 'settings') void loadSettings(store).then((s) => (settings = s)); if (!busy) void render(); } });
  await render();
  const { watchAuth, FirestoreRemote } = await import('./sync-firebase');
  const { loadRemoteConfig } = await import('./remote-config');
  void loadRemoteConfig();
  let stopAlerts: (() => void) | null = null;
  watchAuth((u) => {
    user = u;
    sync?.stop(); sync = null;
    stopAlerts?.(); stopAlerts = null;
    if (u) {
      sync = new SyncCore(store, new FirestoreRemote(u.uid));
      sync.onStatus = (s) => { syncStatus = s; void render(); };
      void sync.start();
      void (async () => {
        const { watchCompanionAlerts, showCompanionNotification, requestCompanionPermission } = await import('./companion-alerts');
        await requestCompanionPermission();
        stopAlerts = watchCompanionAlerts(u.uid, (a) => {
          showCompanionNotification(a);
        });
      })();
    } else syncStatus = { state: 'off', pushed: 0, pulled: 0, rejected: 0 };
    void render();
  });
}
void boot();
