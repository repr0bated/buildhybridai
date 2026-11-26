// Confirmation forms and receipt views generated from the method JSON Schemas (no per-method UI code).
// Schema keywords drive the UI: title/description -> labels, enum -> select, boolean -> checkbox,
// readOnly -> fixed value, format epoch-millis/phone -> formatting, min/max/pattern -> input constraints.
import type { Json } from './registry';
import { validateValue } from './registry';

const el = <K extends keyof HTMLElementTagNameMap>(tag: K, props: Partial<HTMLElementTagNameMap[K]> & { class?: string } = {}, ...kids: (Node | string)[]) => {
  const e = document.createElement(tag);
  const { class: cls, ...rest } = props as any;
  if (cls) e.className = cls;
  Object.assign(e, rest);
  for (const k of kids) e.append(k);
  return e;
};

export const labelOf = (name: string, p: Json) => p.title ?? name.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase());

export function formatValue(p: Json, v: unknown): string {
  if (v === null || v === undefined) return '—';
  if (p?.format === 'epoch-millis' && typeof v === 'number') return new Date(v).toLocaleString();
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (Array.isArray(v)) return v.map((x) => formatValue(p?.items ?? {}, x)).join('; ') || '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

export interface ConfirmForm {
  element: HTMLFormElement;
  /** Current values, typed from the schema. */
  values(): Record<string, unknown>;
  /** Validates against the input schema using ajv. */
  validate(): { valid: boolean; errors: string[] };
}

/** Builds the confirmation form for a method input schema, prefilled with values bound from the analysis. */
export function buildConfirmForm(methodId: string, schema: Json, bound: Record<string, unknown>): ConfirmForm {
  const form = el('form', { class: 'confirm' });
  const getters: Record<string, () => unknown> = {};
  for (const [name, p] of Object.entries<Json>(schema.properties ?? {})) {
    const row = el('div', { class: 'row' });
    const label = el('label', {}, labelOf(name, p));
    label.htmlFor = `f-${name}`;
    row.append(label);
    const value = bound[name] ?? p.default;
    const types: string[] = Array.isArray(p.type) ? p.type : [p.type];
    let input: HTMLElement;
    if (p.readOnly) {
      input = el('output', { class: 'fixed', id: `f-${name}` }, formatValue(p, value));
      getters[name] = () => value;
    } else if (p.enum) {
      const sel = el('select', { id: `f-${name}` });
      for (const o of p.enum) sel.append(el('option', { value: String(o), selected: o === value }, String(o)));
      input = sel;
      getters[name] = () => sel.value;
    } else if (types.includes('boolean')) {
      const cb = el('input', { type: 'checkbox', id: `f-${name}`, checked: !!value });
      input = cb;
      getters[name] = () => cb.checked;
    } else if (types.includes('integer') || types.includes('number')) {
      const n = el('input', { type: 'number', id: `f-${name}`, value: value === undefined ? '' : String(value) });
      if (p.minimum !== undefined) n.min = String(p.minimum);
      if (p.maximum !== undefined) n.max = String(p.maximum);
      input = n;
      getters[name] = () => (n.value === '' ? undefined : Number(n.value));
    } else if (types.includes('array') || types.includes('object')) {
      input = el('output', { class: 'fixed', id: `f-${name}` }, formatValue(p, value));
      getters[name] = () => value;
    } else {
      const long = (p.maxLength ?? 0) > 120;
      const t = long ? el('textarea', { id: `f-${name}`, rows: 3, value: String(value ?? '') }) : el('input', { type: 'text', id: `f-${name}`, value: String(value ?? '') });
      if (p.maxLength) (t as HTMLInputElement).maxLength = p.maxLength;
      if (p.pattern) (t as HTMLInputElement).pattern = p.pattern;
      input = t;
      getters[name] = () => (t.value === '' && !(schema.required ?? []).includes(name) ? undefined : t.value);
    }
    row.append(input);
    if (p.description) row.append(el('small', {}, p.description));
    form.append(row);
  }
  const values = () => {
    const o: Record<string, unknown> = {};
    for (const [k, g] of Object.entries(getters)) { const v = g(); if (v !== undefined) o[k] = v; }
    return o;
  };
  return { element: form, values, validate: () => validateValue('input', methodId, values()) };
}

/** Read-only view of any typed object (receipt input / output) using the schema's titles and formats. */
export function renderObject(schema: Json | undefined, value: Record<string, unknown> | null | undefined): HTMLElement {
  const dl = el('dl', { class: 'kv' });
  if (!value) { dl.append(el('dd', {}, '—')); return dl; }
  for (const [k, v] of Object.entries(value)) {
    const p = schema?.properties?.[k] ?? {};
    dl.append(el('dt', {}, labelOf(k, p)), el('dd', {}, formatValue(p, v)));
  }
  return dl;
}
