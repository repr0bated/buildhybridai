import { getToken } from 'firebase/app-check';
import { getAppCheckInstance, getFirebaseApp } from './firebase';
import { decide, type TruthResult } from './jev-truth';
export type SenderKind = 'saved_contact' | 'unknown_number' | 'short_code' | 'hidden';

export const JEV_URL = 'https://callguardian-jev.reprobate.workers.dev';
const KEY = 'cg.jevEnabled';

export const isJevEnabled = () => localStorage.getItem(KEY) === '1';
export const setJevEnabled = (on: boolean) => localStorage.setItem(KEY, on ? '1' : '0');

export function senderKind(number?: string | null): SenderKind {
  const n = (number ?? '').replace(/\D/g, '');
  if (!n) return 'hidden';
  if (n.length >= 4 && n.length <= 6) return 'short_code';
  return 'unknown_number';
}

export interface JevResult {
  model: string;
  questionsVersion: string;
  signals: Record<string, number>;
  inputTokens: number;
  table: TruthResult;
}

export async function jevCheck(redactedText: string, sender: SenderKind): Promise<JevResult> {
  getFirebaseApp();
  const ac = getAppCheckInstance();
  if (!ac) throw new Error('App Check is not initialized');
  const token = await getToken(ac, false);
  const res = await fetch(`${JEV_URL}/v1/check`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Firebase-AppCheck': token.token },
    body: JSON.stringify({ redactedText, sender }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.message || body.error || `Jev HTTP ${res.status}`);
  return { ...body, table: decide(body.signals, sender === 'saved_contact') };
}

export async function jevCheckBatch(items: { redactedText: string; sender: SenderKind }[]): Promise<(JevResult | { error: string })[]> {
  getFirebaseApp();
  const ac = getAppCheckInstance();
  if (!ac) throw new Error('App Check is not initialized');
  const token = await getToken(ac, false);
  const res = await fetch(`${JEV_URL}/v1/check:batch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Firebase-AppCheck': token.token },
    body: JSON.stringify({ items }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.message || body.error || `Jev HTTP ${res.status}`);
  return (body.results as any[]).map((r) => {
    if (!r?.ok) return { error: r?.error || 'failed' };
    return { model: r.model, questionsVersion: r.questionsVersion, signals: r.signals, inputTokens: r.inputTokens, table: decide(r.signals, false) };
  });
}
