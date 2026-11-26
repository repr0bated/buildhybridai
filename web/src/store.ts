// Local database (IndexedDB). Every collection stores documents in exactly the shape of its sync schema,
// so syncing is a straight copy. Message text lives only in the `content` collection.
import type { SyncReceipt, SyncVerdict, SyncSenderRule, SyncSubscription, SyncPrivateFlag, SyncContent, SyncSettings } from './generated/types';

export const COLLECTIONS = ['receipts', 'verdicts', 'senders', 'subscriptions', 'privateFlags', 'content', 'settings'] as const;
export type Collection = (typeof COLLECTIONS)[number];

export interface DocMap {
  receipts: SyncReceipt;
  verdicts: SyncVerdict;
  senders: SyncSenderRule;
  subscriptions: SyncSubscription;
  privateFlags: SyncPrivateFlag;
  content: SyncContent;
  settings: SyncSettings & { key: 'main' };
}
export const KEY_FIELD: Record<Collection, string> = {
  receipts: 'id', verdicts: 'id', senders: 'key', subscriptions: 'key', privateFlags: 'key', content: 'itemId', settings: 'key',
};
/** sync schema name for each collection */
export const SYNC_SCHEMA: Record<Collection, string> = {
  receipts: 'sync_receipt', verdicts: 'sync_verdict', senders: 'sync_sender_rule', subscriptions: 'sync_subscription',
  privateFlags: 'sync_private_flag', content: 'sync_content', settings: 'sync_settings',
};

export type ChangeOrigin = 'local' | 'remote';
export type ChangeListener = (c: Collection, doc: any, origin: ChangeOrigin) => void;

const DB_NAME = 'callguardian-web';
const DB_VERSION = 1;

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
}

export class Store {
  private dbp: Promise<IDBDatabase>;
  private listeners = new Set<ChangeListener>();

  constructor(name = DB_NAME, factory: IDBFactory = indexedDB) {
    this.dbp = new Promise((resolve, reject) => {
      const open = factory.open(name, DB_VERSION);
      open.onupgradeneeded = () => {
        const db = open.result;
        for (const c of COLLECTIONS) db.createObjectStore(c, { keyPath: KEY_FIELD[c] });
        db.createObjectStore('meta', { keyPath: 'k' });
      };
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => reject(open.error);
    });
  }

  onChange(l: ChangeListener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  async get<C extends Collection>(c: C, key: string): Promise<DocMap[C] | undefined> {
    const db = await this.dbp;
    return req(db.transaction(c).objectStore(c).get(key));
  }

  async all<C extends Collection>(c: C, opts: { includeDeleted?: boolean } = {}): Promise<DocMap[C][]> {
    const db = await this.dbp;
    const rows = (await req(db.transaction(c).objectStore(c).getAll())) as any[];
    return (opts.includeDeleted ? rows : rows.filter((r) => !r.deleted)) as DocMap[C][];
  }

  /** Writes a document. Remote writes only win if they are newer (last writer wins). Returns true if stored. */
  async put<C extends Collection>(c: C, doc: DocMap[C], origin: ChangeOrigin = 'local'): Promise<boolean> {
    const db = await this.dbp;
    const key = (doc as any)[KEY_FIELD[c]] as string;
    if (origin === 'remote') {
      const cur = (await req(db.transaction(c).objectStore(c).get(key))) as any;
      if (cur && cur.updatedAt >= (doc as any).updatedAt) return false;
    }
    await req(db.transaction(c, 'readwrite').objectStore(c).put(doc));
    for (const l of this.listeners) l(c, doc, origin);
    return true;
  }

  /** Soft delete: keep a tombstone so other devices learn about it. */
  async remove<C extends Collection>(c: C, key: string, deviceId: string): Promise<void> {
    const cur = await this.get(c, key);
    if (!cur) return;
    await this.put(c, { ...(cur as any), deleted: true, updatedAt: Date.now(), deviceId });
  }

  async purge(c: Collection, key: string): Promise<void> {
    const db = await this.dbp;
    await req(db.transaction(c, 'readwrite').objectStore(c).delete(key));
  }

  async clearAll(): Promise<void> {
    const db = await this.dbp;
    for (const c of [...COLLECTIONS, 'meta'] as string[]) await req(db.transaction(c, 'readwrite').objectStore(c).clear());
  }

  private async meta<T>(k: string): Promise<T | undefined> {
    const db = await this.dbp;
    return (await req(db.transaction('meta').objectStore('meta').get(k)))?.v;
  }
  private async setMeta(k: string, v: unknown): Promise<void> {
    const db = await this.dbp;
    await req(db.transaction('meta', 'readwrite').objectStore('meta').put({ k, v }));
  }

  /** Stable random id for this browser profile, used as `deviceId` in every document it writes. */
  async deviceId(): Promise<string> {
    let id = await this.meta<string>('deviceId');
    if (!id) {
      id = 'web' + Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => b.toString(16).padStart(2, '0')).join('');
      await this.setMeta('deviceId', id);
    }
    return id;
  }

  /** Monotonic local counter for event ids. */
  async nextEventNumber(): Promise<number> {
    const n = ((await this.meta<number>('eventCounter')) ?? 0) + 1;
    await this.setMeta('eventCounter', n);
    return n;
  }
}
