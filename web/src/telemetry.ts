// Firebase Performance traces and Analytics events for triage.
// Parameters are a closed set only: never message text or phone numbers.
import { getAnalytics, isSupported as analyticsSupported, logEvent } from 'firebase/analytics';
import { getPerformance, trace as perfTrace } from 'firebase/performance';
import { getFirebaseApp } from './firebase';

export const SOURCE = { nano: 'nano', cloud: 'cloud', local: 'local' } as const;
export type VerdictSourceToken = (typeof SOURCE)[keyof typeof SOURCE];

export const FALLBACK = {
  none: 'none',
  on_device_failed: 'on_device_failed',
  background: 'background',
  rate_limited: 'rate_limited',
  error9: 'error9',
  own_key: 'own_key',
  nano_unavailable: 'nano_unavailable',
} as const;

const PHONE = /^\+?\d[\d\s().-]{6,}$/;

export function safeParam(value: string | null | undefined, allowed?: Set<string> | readonly string[]): string | null {
  const v = value?.trim() ?? '';
  if (!v || v.length > 80) return null;
  if (v.includes('\n') || v.includes('\r') || v.includes(' ') || v.includes('\t')) return null;
  if (PHONE.test(v)) return null;
  if (/[A-Za-z]/.test(v) && (v.match(/\d/g)?.length ?? 0) >= 7) return null;
  if (allowed && !Array.from(allowed as string[]).includes(v)) return null;
  return v;
}

export function sourceOf(raw: string | undefined): VerdictSourceToken {
  if (raw === 'ON_DEVICE') return SOURCE.nano;
  if (raw === 'IN_CLOUD') return SOURCE.cloud;
  return SOURCE.local;
}

export async function startTrace(kind: 'nano' | 'cloud'): Promise<{ stop: (source?: 'nano' | 'cloud') => void }> {
  const name = kind === 'nano' ? 'inference_nano' : 'inference_cloud';
  try {
    const perf = getPerformance(getFirebaseApp());
    const t = perfTrace(perf, name);
    t.start();
    return {
      stop(source?: 'nano' | 'cloud') {
        try {
          t.putAttribute('source', source ?? kind);
          t.stop();
        } catch {
          /* ignore */
        }
      },
    };
  } catch {
    return { stop() {} };
  }
}

export async function logVerdict(source: string, category: string, fallbackReason: string = FALLBACK.none): Promise<void> {
  const src = safeParam(source, [SOURCE.nano, SOURCE.cloud, SOURCE.local]);
  const cat = safeParam(category);
  const fb = safeParam(fallbackReason, Object.values(FALLBACK)) ?? FALLBACK.none;
  if (!src || !cat) return;
  try {
    if (!(await analyticsSupported())) return;
    logEvent(getAnalytics(getFirebaseApp()), 'verdict', {
      source: src,
      category: cat,
      fallback_reason: fb,
    });
  } catch {
    /* ignore */
  }
}
