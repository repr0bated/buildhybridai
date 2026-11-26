import { describe, expect, it } from 'vitest';
import { FALLBACK, safeParam, sourceOf } from '../src/telemetry';
import { normalizeVariant, triage } from '../src/prompts';
import { scan } from '../src/rules';

describe('telemetry params', () => {
  it('maps sources and keeps fallbacks in a closed set', () => {
    expect(sourceOf('ON_DEVICE')).toBe('nano');
    expect(sourceOf('IN_CLOUD')).toBe('cloud');
    expect(sourceOf('RULES')).toBe('local');
    expect(Object.values(FALLBACK)).toEqual(expect.arrayContaining(['none', 'on_device_failed', 'background', 'error9', 'nano_unavailable']));
  });

  it('never accepts phone numbers or message text as params', () => {
    expect(safeParam('+15551234567')).toBeNull();
    expect(safeParam('555-123-4567')).toBeNull();
    expect(safeParam('Your USPS package is waiting at the depot')).toBeNull();
    expect(safeParam('click here\nhttps://example.com')).toBeNull();
    expect(safeParam('nano', ['nano', 'cloud', 'local'])).toBe('nano');
    expect(safeParam('nano', ['cloud'])).toBeNull();
    expect(safeParam('on_device_failed', ['none', 'error9'])).toBeNull();
  });
});

describe('triage prompt variants', () => {
  it('variant B is distinct and still asks required questions', () => {
    const rules = scan({ channel: 'SMS', number: 'sender', body: 'hello' });
    const a = triage('SMS', 'sender', 'hello', rules, 'A');
    const b = triage('SMS', 'sender', 'hello', rules, 'B');
    expect(normalizeVariant('A')).toBe('A');
    expect(normalizeVariant('b')).toBe('B');
    expect(normalizeVariant('nope')).toBe('A');
    expect(a).not.toBe(b);
    expect(b).toContain('Variant B');
    for (const p of [a, b]) {
      expect(p).toContain('reputable_official');
      expect(p).toContain('held_account');
    }
  });
});
