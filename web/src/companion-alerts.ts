// Web companion alerts: listen for PII-free "phone blocked a scam" docs.
// Server-side FCM is not used (that would need Cloud Functions on Blaze).
import { collection, onSnapshot, type Unsubscribe } from 'firebase/firestore';
import { getDb } from './sync-firebase';

export interface CompanionAlert {
  id: string;
  category: string;
  source: 'nano' | 'cloud' | 'local';
  action: 'block';
  createdAt: number;
}

const startedAt = Date.now();
let notified = new Set<string>();

export function watchCompanionAlerts(uid: string, onAlert: (a: CompanionAlert) => void): Unsubscribe {
  const col = collection(getDb(), 'users', uid, 'companionAlerts');
  return onSnapshot(col, (snap) => {
    for (const change of snap.docChanges()) {
      if (change.type !== 'added') continue;
      const d = change.doc.data() as CompanionAlert;
      if (!d || d['deleted'] === true || notified.has(d.id)) continue;
      if (typeof d.createdAt === 'number' && d.createdAt < startedAt - 5_000) continue;
      // Closed-set only; never render unknown fields that might hold PII.
      if (!['nano', 'cloud', 'local'].includes(d.source)) continue;
      notified.add(d.id);
      onAlert({ id: d.id, category: String(d.category ?? 'OTHER'), source: d.source, action: 'block', createdAt: d.createdAt });
    }
  }, () => { /* signed-out / offline */ });
}

export function showCompanionNotification(a: CompanionAlert): void {
  const title = 'Call Guardian blocked a scam';
  const body = `${a.category.replace(/_/g, ' ').toLowerCase()} · decided by ${a.source}`;
  if (typeof Notification === 'undefined') return;
  if (Notification.permission === 'granted') {
    try { new Notification(title, { body, tag: a.id }); } catch { /* ignore */ }
  }
}

export async function requestCompanionPermission(): Promise<void> {
  if (typeof Notification === 'undefined') return;
  if (Notification.permission === 'default') await Notification.requestPermission();
}
