import Ajv, { type ValidateFunction } from 'ajv';
import bundle from './generated/schemas.json';

export type Json = Record<string, any>;

export interface MethodSpec {
  id: string;
  alias: string;
  version: number;
  title: string;
  description?: string;
  capability: string;
  execution: 'device' | 'cloud';
  channels: ('CALL' | 'SMS')[];
  minRisk: Risk;
  maxRisk?: Risk;
  minConfidence?: number;
  autoApply: boolean;
  contactSafe: boolean;
  requiresConsent?: boolean;
  proposable?: boolean;
  input: string;
  output: string;
}

export const RISKS = ['SAFE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type Risk = (typeof RISKS)[number];
export const riskIndex = (r: Risk) => RISKS.indexOf(r);

const ajv = new Ajv({ allErrors: true, strict: false, validateFormats: false });
ajv.addKeyword('x-firestore');

export const registry = bundle.registry as unknown as { registryVersion: string; methods: MethodSpec[] };
export const schemas = bundle as unknown as {
  root: Record<string, Json>;
  methods: Record<string, { input: Json; output: Json }>;
  sync: Record<string, Json>;
};

export const methodById = (id: string) => registry.methods.find((m) => m.id === id);
export const methodByAlias = (alias: string) => registry.methods.find((m) => m.alias === alias.toLowerCase());

const cache = new Map<string, ValidateFunction>();
function compiled(key: string, schema: Json): ValidateFunction {
  let v = cache.get(key);
  if (!v) {
    const { $id, ...rest } = schema; // avoid ajv $id collisions between re-registered copies
    v = ajv.compile(rest);
    cache.set(key, v);
  }
  return v;
}

export interface SchemaReport {
  schemaId: string;
  valid: boolean;
  errors: string[];
  json: string;
}

/** Small models sometimes wrap JSON in ```json fences; strip those, nothing else. */
export function extractJson(text: string): string {
  let t = text.trim();
  if (t.startsWith('```')) {
    t = t.replace(/^```(?:json|JSON)?/, '');
    t = t.slice(0, t.lastIndexOf('```') >= 0 ? t.lastIndexOf('```') : t.length);
  }
  return t.trim();
}

function report(schemaId: string, validate: ValidateFunction, raw: string): SchemaReport {
  const json = extractJson(raw);
  if (!json) return { schemaId, valid: false, errors: ['Model returned no JSON'], json };
  let value: unknown;
  try {
    value = JSON.parse(json);
  } catch (e) {
    return { schemaId, valid: false, errors: [`Not valid JSON: ${(e as Error).message.split('\n')[0]}`], json };
  }
  const ok = validate(value) as boolean;
  const errors = ok ? [] : (validate.errors ?? []).map((e) => `${e.instancePath || '(root)'} ${e.message}`).sort();
  return { schemaId, valid: ok, errors, json };
}

/** Validate raw model text against one of the root schemas (scam_verdict, deep_analysis, subscription_check). */
export function validateModelText(name: 'scam_verdict' | 'deep_analysis' | 'subscription_check', raw: string): SchemaReport {
  return report(name, compiled(name, schemas.root[name]), raw);
}

export function validateValue(kind: 'input' | 'output', methodId: string, value: unknown): { valid: boolean; errors: string[] } {
  const schema = schemas.methods[methodId]?.[kind];
  if (!schema) return { valid: false, errors: [`Unknown method ${methodId}`] };
  const v = compiled(`${methodId}:${kind}`, schema);
  const ok = v(value) as boolean;
  return { valid: ok, errors: ok ? [] : (v.errors ?? []).map((e) => `${e.instancePath || '(root)'} ${e.message}`) };
}

export function validateSync(name: string, value: unknown): { valid: boolean; errors: string[] } {
  const schema = schemas.sync[name];
  if (!schema) return { valid: false, errors: [`Unknown sync schema ${name}`] };
  const v = compiled(`sync:${name}`, schema);
  const ok = v(value) as boolean;
  return { valid: ok, errors: ok ? [] : (v.errors ?? []).map((e) => `${e.instancePath || '(root)'} ${e.message}`) };
}

/** The JSON Schema a model must follow, stripped of keywords the model APIs reject. */
export function modelSchema(name: 'scam_verdict' | 'deep_analysis' | 'subscription_check'): Json {
  const strip = (n: any): any => {
    if (Array.isArray(n)) return n.map(strip);
    if (n && typeof n === 'object') {
      const o: Json = {};
      for (const [k, v] of Object.entries(n)) {
        if (['$schema', '$id', 'title', 'x-firestore', 'format', 'readOnly'].includes(k)) continue;
        o[k] = strip(v);
      }
      return o;
    }
    return n;
  };
  return strip(schemas.root[name]);
}
