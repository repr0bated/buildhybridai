import type { Store } from './store';
import type { SyncSettings } from './generated/types';

export type Settings = SyncSettings & { key: 'main' };

export async function loadSettings(store: Store): Promise<Settings> {
  const cur = await store.get('settings', 'main');
  if (cur) return cur;
  return { key: 'main', syncMessageContent: false, autoBlockEnabled: true, autoBlockThreshold: 85, onDeviceOnly: false, updatedAt: 0, deviceId: await store.deviceId() };
}

export async function saveSettings(store: Store, patch: Partial<SyncSettings>): Promise<Settings> {
  const cur = await loadSettings(store);
  const next: Settings = { ...cur, ...patch, key: 'main', updatedAt: Date.now(), deviceId: await store.deviceId() };
  await store.put('settings', next);
  return next;
}
