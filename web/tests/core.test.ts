import { describe, expect, it } from 'vitest';
import { scan } from '../src/rules';
import { decide } from '../src/policy';
import { extractJson, validateModelText, validateValue, registry, methodByAlias } from '../src/registry';
import { analyze, parseInput } from '../src/analyze';
import type { Engine, ModelRun } from '../src/ai';
import { analyzeSubscription } from '../src/subscriptions';
import { redact } from '../src/redact';

const scam = 'USPS: Your package delivery failed due to an incorrect address. Confirm your details within 24 hours: https://usps-redelivery.top/x';

describe('rules', () => {
  it('flags a delivery scam with a look-alike link', () => {
    const r = scan({ channel: 'SMS', number: '+14155550142', body: scam });
    expect(r.flags.map((f) => f.id)).toEqual(expect.arrayContaining(['link', 'tld', 'delivery', 'lookalike']));
    expect(['HIGH', 'CRITICAL']).toContain(r.riskLevel);
    expect(r.category).toBe('IMPERSONATION');
  });
  it('leaves a normal text alone', () => {
    const r = scan({ channel: 'SMS', number: '+13105550123', body: 'Running 10 min late, want a coffee?' });
    expect(r.riskLevel).toBe('SAFE');
    expect(r.method).toBe('none');
  });
  it('flags callback-scam prefixes for a bare number', () => {
    expect(scan({ channel: 'CALL', number: '+232 76 555 0100' }).flags.some((f) => f.id === 'callback')).toBe(true);
    expect(scan({ channel: 'CALL', number: '(415) 555-0100' }).flags.length).toBe(0);
  });
});

describe('input parsing', () => {
  it('detects number, link and message', () => {
    expect(parseInput('+1 415 555 0100').channel).toBe('CALL');
    expect(parseInput('bit.ly/abc123').kind).toBe('link');
    expect(parseInput('hello there friend').kind).toBe('message');
  });
});

describe('registry + validation', () => {
  it('loads the shared registry', () => {
    expect(registry.methods.length).toBeGreaterThan(8);
    expect(methodByAlias('block_number')?.id).toBe('guardian.call.block@v1');
  });
  const good = { riskLevel: 'HIGH', category: 'PHISHING_LINK', confidence: 90, reasons: ['link'], advice: 'Do not tap', method: 'QUARANTINE_SMS' };
  it('accepts a valid verdict, including fenced JSON', () => {
    expect(validateModelText('scam_verdict', JSON.stringify(good)).valid).toBe(true);
    expect(validateModelText('scam_verdict', '```json\n' + JSON.stringify(good) + '\n```').valid).toBe(true);
    expect(extractJson('```json\n{"a":1}\n```')).toBe('{"a":1}');
  });
  it('rejects unknown methods, extra fields and bad ranges', () => {
    expect(validateModelText('scam_verdict', JSON.stringify({ ...good, method: 'WIPE_PHONE' })).valid).toBe(false);
    expect(validateModelText('scam_verdict', JSON.stringify({ ...good, extra: 1 })).valid).toBe(false);
    expect(validateModelText('scam_verdict', JSON.stringify({ ...good, confidence: 150 })).valid).toBe(false);
    expect(validateModelText('scam_verdict', 'not json').errors[0]).toMatch(/Not valid JSON/);
  });
  it('validates typed method inputs', () => {
    expect(validateValue('input', 'guardian.call.block@v1', { number: '+14155550142', scope: 'ALL', reason: 'scam' }).valid).toBe(true);
    expect(validateValue('input', 'guardian.call.block@v1', { number: 'abc', scope: 'ALL', reason: 'x' }).valid).toBe(false);
    expect(validateValue('input', 'guardian.call.block@v1', { number: '+14155550142', scope: 'EVERYTHING', reason: 'x' }).valid).toBe(false);
  });
});

describe('capability policy', () => {
  const base = { channel: 'SMS' as const, isContact: false, isTrusted: false };
  it('offers quarantine for a medium-risk text', () => {
    const d = decide({ method: 'quarantine_sms', riskLevel: 'MEDIUM', confidence: 60, reasons: ['x'], source: 'ON_DEVICE' }, base);
    expect(d.method).toBe('quarantine_sms');
    expect(d.dispatch).not.toBe('DENY');
  });
  it('falls back from block to quarantine when confidence is too low', () => {
    const d = decide({ method: 'block_number', riskLevel: 'HIGH', confidence: 50, reasons: ['x'], source: 'ON_DEVICE' }, base);
    expect(d.substituted).toBe(true);
    expect(d.method).toBe('quarantine_sms');
  });
  it('never blocks a trusted sender', () => {
    const d = decide({ method: 'block_number', riskLevel: 'CRITICAL', confidence: 99, reasons: ['x'], source: 'ON_DEVICE' }, { ...base, isTrusted: true });
    expect(d.dispatch).toBe('DENY');
  });
  it('cloud proposals are offered, never auto-run', () => {
    const d = decide({ method: 'quarantine_sms', riskLevel: 'HIGH', confidence: 95, reasons: ['x'], source: 'IN_CLOUD' }, base);
    expect(d.dispatch).toBe('OFFER');
  });
  it('turns "none" at high risk into a safe default', () => {
    const d = decide({ method: 'none', riskLevel: 'HIGH', confidence: 90, reasons: [], source: 'ON_DEVICE' }, base);
    expect(d.method).toBe('quarantine_sms');
  });
});

describe('subscriptions', () => {
  it('finds the STOP keyword and the manage link', () => {
    const s = analyzeSubscription('28227', 'Cash App: weekly summary ready. Manage alerts at https://cash.app/manage-alerts. Reply STOP to unsubscribe. Msg&data rates may apply.');
    expect(s.isSubscription).toBe(true);
    expect(s.keyword).toBe('STOP');
    expect(s.bestLink?.domain).toBe('cash.app');
    expect(s.brand).toBe('Cash App');
  });
});

describe('redaction', () => {
  it('masks emails, numbers and codes but keeps the domain', () => {
    const r = redact('Hi John, code 482913. Call +1 415 555 0100 or mail a@b.com via https://evil.top/login?x=1');
    expect(r).not.toMatch(/482913|555 0100|a@b\.com|login/);
    expect(r).toContain('evil.top');
  });
});

describe('analysis pipeline', () => {
  const verdict = { riskLevel: 'HIGH', category: 'PACKAGE_DELIVERY', confidence: 91, reasons: ['Fake redelivery link'], advice: 'Ignore it.', method: 'QUARANTINE_SMS' };
  const eng = (raw: string | Error, source: ModelRun['source'] = 'ON_DEVICE'): Engine => ({
    async triage() { if (raw instanceof Error) throw raw; return { rawText: raw, source, modelName: source === 'ON_DEVICE' ? 'Gemini Nano (Chrome built-in)' : 'gemini-3.5-flash-lite', latencyMs: 12 }; },
    async deep() { throw new Error('unused'); },
  });
  it('uses the on-device verdict and records which model ran', async () => {
    const a = await analyze(parseInput(scam, '+14155550142'), { engine: eng(JSON.stringify(verdict)) });
    expect(a.final.decidedBy).toBe('ON_DEVICE');
    expect(a.schemaValid).toBe(true);
    expect(a.policy.method).toBe('quarantine_sms');
    expect(a.steps.map((s) => s.kind)).toEqual(['rules', 'model', 'validate', 'policy']);
  });
  it('reports cloud fallback and redacts before the text leaves', async () => {
    let seen = '';
    const e: Engine = { async triage(p) { seen = p; return { rawText: JSON.stringify(verdict), source: 'IN_CLOUD', modelName: 'gemini-3.5-flash-lite', latencyMs: 1 }; }, async deep() { throw new Error('x'); } };
    const a = await analyze(parseInput('Hi Anna, call +1 415 555 0100 to verify account 123456', '+14155550142'), { engine: e, deviceReady: false });
    expect(a.final.decidedBy).toBe('IN_CLOUD');
    expect(seen).not.toMatch(/555 0100|123456|4155550142/);
    expect(a.steps[1].detail).toMatch(/redacted/);
  });
  it('rejects schema-invalid output and falls back to rules', async () => {
    const a = await analyze(parseInput(scam, '+14155550142'), { engine: eng(JSON.stringify({ ...verdict, method: 'FORMAT_DISK' })) });
    expect(a.schemaValid).toBe(false);
    expect(a.final.decidedBy).toBe('RULES');
    expect(a.steps.some((s) => s.kind === 'fallback')).toBe(true);
  });
  it('falls back to rules when the model errors', async () => {
    const a = await analyze(parseInput(scam, '+14155550142'), { engine: eng(new Error('offline')) });
    expect(a.modelError).toBe('offline');
    expect(a.final.decidedBy).toBe('RULES');
  });
});
