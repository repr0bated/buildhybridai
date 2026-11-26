import { describe, expect, it } from 'vitest';
import { parseItem } from '../src/jev';
import { askJev } from '../src/jev';
import { decide } from '../src/truth';
import { buildRequest, QUESTIONS_VERSION, SIGNAL_IDS } from '../src/questions';
import { readFileSync } from 'node:fs';

const fixtures = JSON.parse(readFileSync('../../functions/fixtures/jev-samples.json', 'utf8')) as {
  results: { title: string; sender: string; text: string; signals: Record<string, number> }[];
};

describe('parseItem (same rules as jevCheck)', () => {
  it('accepts a well-formed redacted item', () => {
    const r = parseItem({ redactedText: 'USPS: package on hold: https://usps-redelivery-help.top/[path]', sender: 'unknown_number' });
    expect(r.ok).toBe(true);
  });
  it('rejects empty, too long, bad sender, and unredacted PII', () => {
    expect(parseItem({ redactedText: '', sender: 'unknown_number' }).ok).toBe(false);
    expect(parseItem({ redactedText: 'x'.repeat(2001), sender: 'unknown_number' }).ok).toBe(false);
    expect(parseItem({ redactedText: 'hello', sender: 'bot' }).ok).toBe(false);
    expect(parseItem({ redactedText: 'Call +1 415 555 0100 now', sender: 'unknown_number' }).ok).toBe(false);
    expect(parseItem({ redactedText: 'Email a@b.com please', sender: 'unknown_number' }).ok).toBe(false);
  });
  it('allows already-redacted placeholders', () => {
    expect(parseItem({ redactedText: 'Hi [name], your code is [digits]. Call [number] or [email].', sender: 'short_code' }).ok).toBe(true);
  });
});

describe('TypeSafe request (generated types)', () => {
  it('builds a SystemOneRequest with every signal question', () => {
    const req = buildRequest('hello', 'unknown_number');
    expect(req.model).toBe('jev-latest');
    expect(Object.keys(req.questions).sort()).toEqual([...SIGNAL_IDS].sort());
    expect(req.state).toMatchObject({ message: 'hello' });
  });
});

describe('askJev parses a SystemOneResponse', () => {
  it('maps Noul answers and rejects a missing signal', async () => {
    const answers = Object.fromEntries(SIGNAL_IDS.map((id) => [id, { type: 'noul', noul: 0.02 }]));
    const fetchImpl: typeof fetch = async () =>
      new Response(JSON.stringify({ model: 'jev-1.14.0', answers, usage: { input_tokens: 10 } }), { status: 200 });
    const r = await askJev('k', 'hello', 'unknown_number', fetchImpl);
    expect(r).toMatchObject({ model: 'jev-1.14.0', questionsVersion: QUESTIONS_VERSION, inputTokens: 10 });
    expect(r.signals.asks_payment).toBe(0.02);
    const bad: typeof fetch = async () =>
      new Response(JSON.stringify({ model: 'jev-1.14.0', answers: { asks_payment: { type: 'noul', noul: 1 } }, usage: { input_tokens: 1 } }), { status: 200 });
    await expect(askJev('k', 'hello', 'unknown_number', bad)).rejects.toThrow(/no Noul answer/);
  });
});

describe('truth table on probed fixtures', () => {
  // Expected rows from the Android table + the stored signals (contact = false).
  const expected: Record<string, string> = {
    'Package redelivery': 'SCAM',       // link + deadline
    'Unpaid toll': 'SCAM',              // payment + deadline
    'Bank lock + code': 'SCAM',         // code + company, also login details
    'Wrong-number crypto': 'SCAM',      // money + stranger
    'Job offer': 'SCAM',                // money + other app
    'Legit reminder': 'CLEAR',
    'Store promo (Reply STOP)': 'UNSETTLED',
    'Newsletter (unsubscribe link)': 'CLEAR', // open link alone is not a strong signal
  };
  for (const row of fixtures.results) {
    it(`${row.title} → ${expected[row.title]}`, () => {
      const r = decide(row.signals, row.sender === 'saved_contact');
      expect(r.verdict, `${row.title} rule=${r.rule}`).toBe(expected[row.title]);
    });
  }
});
