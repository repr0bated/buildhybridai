import type { SyncReceipt, SyncContent } from './generated/types';
import { schemas, type Json } from './registry';
import { renderObject } from './schemaui';

/** A receipt card generated from the method's input/output schemas. */
export function renderReceipt(r: SyncReceipt, content?: SyncContent, opts: { hidden?: boolean } = {}): HTMLElement {
  const s = schemas.methods[r.methodId];
  const card = document.createElement('article');
  card.className = `receipt st-${r.status.toLowerCase()}${opts.hidden ? ' blurred' : ''}`;
  const h = document.createElement('header');
  const sched = new Date(r.createdAt).toLocaleString();
  h.innerHTML = '';
  const t = document.createElement('strong');
  t.textContent = `${r.methodId} · ${r.status}`;
  const m = document.createElement('span');
  m.className = 'muted';
  m.textContent = `${r.origin === 'web' ? 'web' : 'phone'} · ${r.decidedBy.toLowerCase().replace('_', '-')}${r.model ? ' · ' + r.model : ''} · ${sched}`;
  h.append(t, m);
  card.append(h);
  const body = document.createElement('div');
  body.className = 'receipt-body';
  const merged = { ...(r.input as Record<string, unknown>), ...((content?.inputText as Record<string, unknown>) ?? {}) };
  const inH = document.createElement('h4'); inH.textContent = 'Input';
  body.append(inH, renderObject(s?.input as Json, merged));
  const outH = document.createElement('h4'); outH.textContent = 'Output';
  body.append(outH, renderObject(s?.output as Json, r.output as Record<string, unknown> | null));
  if (content?.reasons?.length) {
    const rh = document.createElement('h4'); rh.textContent = 'Notes';
    const ul = document.createElement('ul');
    for (const x of content.reasons) { const li = document.createElement('li'); li.textContent = x; ul.append(li); }
    body.append(rh, ul);
  }
  card.append(body);
  return card;
}
