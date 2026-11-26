import { fetchAndActivate, getRemoteConfig, getValue, type RemoteConfig } from 'firebase/remote-config';
import { getFirebaseApp } from './firebase';
import { normalizeVariant } from './prompts';

let rc: RemoteConfig | undefined;
let variant: 'A' | 'B' = 'A';

export function triagePromptVariant(): 'A' | 'B' {
  return variant;
}

export async function loadRemoteConfig(): Promise<void> {
  try {
    rc = getRemoteConfig(getFirebaseApp());
    rc.settings.minimumFetchIntervalMillis = 3_600_000;
    rc.defaultConfig = { triage_prompt_variant: 'A' };
    await fetchAndActivate(rc);
    variant = normalizeVariant(getValue(rc, 'triage_prompt_variant').asString());
  } catch {
    variant = 'A';
  }
}
