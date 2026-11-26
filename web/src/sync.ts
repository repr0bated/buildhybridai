// Sync between the phone and the web app: Firebase Auth (Google) + Firestore under users/{uid}/...
// SyncCore is transport-agnostic (tested with an in-memory Remote); FirestoreRemote is the real transport.
import { COLLECTIONS, KEY_FIELD, SYNC_SCHEMA, type Collection, type Store } from './store';
import { validateSync } from './registry';

export interface Remote {
  subscribe(c: Collection, onDocs: (docs: any[]) => void, onError: (e: Error) => void): () => void;
  set(c: Collection, key: string, doc: any): Promise<void>;
  delete(c: Collection, key: string): Promise<void>;
}

export interface SyncStatus { state: 'off' | 'connecting' | 'idle' | 'error'; pushed: number; pulled: number; rejected: number; error?: string }

/** Turn a local document into the exact shape stored in Firestore. */
export function toRemote(c: Collection, doc: any): any {
  const out: any = { ...doc };
  if (c === 'settings') delete out.key; // doc id is "main"; the schema has no key field
  for (const k of Object.keys(out)) if (out[k] === undefined) delete out[k];
  return out;
}
export function fromRemote(c: Collection, id: string, doc: any): any {
  return c === 'settings' ? { ...doc, key: 'main' } : { ...doc, [KEY_FIELD[c]]: doc[KEY_FIELD[c]] ?? id };
}

export class SyncCore {
  status: SyncStatus = { state: 'off', pushed: 0, pulled: 0, rejected: 0 };
  private unsubs: (() => void)[] = [];
  private remoteDocs = new Map<Collection, Map<string, any>>();
  private firstSnapshot = new Set<Collection>();
  private offChange?: () => void;
  onStatus: (s: SyncStatus) => void = () => {};

  constructor(private store: Store, private remote: Remote) {}

  private emit(patch: Partial<SyncStatus>) {
    this.status = { ...this.status, ...patch };
    this.onStatus(this.status);
  }

  private async contentEnabled(): Promise<boolean> {
    return (await this.store.get('settings', 'main'))?.syncMessageContent === true;
  }

  async start() {
    this.emit({ state: 'connecting', error: undefined });
    for (const c of COLLECTIONS) {
      this.remoteDocs.set(c, new Map());
      this.unsubs.push(this.remote.subscribe(c, (docs) => void this.onRemote(c, docs), (e) => this.emit({ state: 'error', error: e.message })));
    }
    this.offChange = this.store.onChange((c, doc, origin) => {
      if (origin === 'local') void this.onLocal(c, doc);
    });
  }

  stop() {
    this.unsubs.forEach((u) => u());
    this.unsubs = [];
    this.offChange?.();
    this.firstSnapshot.clear();
    this.remoteDocs.clear();
    this.emit({ state: 'off' });
  }

  private async push(c: Collection, doc: any) {
    if (c === 'content' && !(await this.contentEnabled())) return;
    const body = toRemote(c, doc);
    const v = validateSync(SYNC_SCHEMA[c], body);
    if (!v.valid) { this.emit({ rejected: this.status.rejected + 1, error: `${c}: ${v.errors[0]}` }); return; }
    const id = c === 'settings' ? 'main' : String(doc[KEY_FIELD[c]]);
    await this.remote.set(c, id, body);
    this.remoteDocs.get(c)?.set(id, body);
    this.emit({ pushed: this.status.pushed + 1 });
  }

  private async onLocal(c: Collection, doc: any) {
    try {
      await this.push(c, doc);
      if (c === 'settings') await this.onSettingsChanged(doc);
    } catch (e) {
      this.emit({ state: 'error', error: (e as Error).message });
    }
  }

  /** Turning "Sync message content" off removes all synced content from the cloud; turning it on uploads local content. */
  private lastContentFlag: boolean | undefined;
  private async onSettingsChanged(settings: any) {
    const on = settings.syncMessageContent === true;
    if (this.lastContentFlag === on) return;
    const was = this.lastContentFlag;
    this.lastContentFlag = on;
    if (!on && was !== undefined) {
      for (const id of [...(this.remoteDocs.get('content')?.keys() ?? [])]) await this.remote.delete('content', id);
      this.remoteDocs.get('content')?.clear();
    } else if (on) {
      for (const d of await this.store.all('content', { includeDeleted: true })) await this.push('content', d);
    }
  }

  private async onRemote(c: Collection, docs: any[]) {
    try {
      const map = this.remoteDocs.get(c) ?? new Map();
      this.remoteDocs.set(c, map);
      map.clear();
      for (const item of docs) {
        const { __id, ...raw } = item;
        const id = c === 'settings' ? 'main' : raw[KEY_FIELD[c]] ?? __id;
        if (!id) continue;
        map.set(id, raw);
        const v = validateSync(SYNC_SCHEMA[c], raw);
        if (!v.valid) { this.emit({ rejected: this.status.rejected + 1, error: `remote ${c}/${id}: ${v.errors[0]}` }); continue; }
        if (await this.store.put(c, fromRemote(c, String(id), raw), 'remote')) this.emit({ pulled: this.status.pulled + 1 });
      }
      if (c === 'settings') {
        const s = await this.store.get('settings', 'main');
        if (this.lastContentFlag === undefined) this.lastContentFlag = s?.syncMessageContent === true;
        else if (s) await this.onSettingsChanged(s);
      }
      if (!this.firstSnapshot.has(c)) {
        this.firstSnapshot.add(c);
        // Upload anything we have that the cloud lacks or has older.
        for (const d of await this.store.all(c, { includeDeleted: true })) {
          const id = c === 'settings' ? 'main' : String((d as any)[KEY_FIELD[c]]);
          const r = map.get(id);
          if (!r || (r.updatedAt ?? 0) < (d as any).updatedAt) await this.push(c, d);
        }
        if (this.firstSnapshot.size === COLLECTIONS.length) this.emit({ state: 'idle' });
      }
    } catch (e) {
      this.emit({ state: 'error', error: (e as Error).message });
    }
  }
}
