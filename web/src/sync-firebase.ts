import { GoogleAuthProvider, getAuth, onAuthStateChanged, signInWithPopup, signOut as fbSignOut, type User } from 'firebase/auth';
import { collection, deleteDoc, doc, initializeFirestore, onSnapshot, persistentLocalCache, setDoc, type Firestore } from 'firebase/firestore';
import { getFirebaseApp } from './firebase';
import type { Collection } from './store';
import type { Remote } from './sync';

export interface AuthUser { uid: string; email: string | null; name: string | null }

let db: Firestore | undefined;
export const getDb = () => (db ??= initializeFirestore(getFirebaseApp(), { localCache: persistentLocalCache() }));

export class FirestoreRemote implements Remote {
  constructor(private uid: string) {}
  private col(c: Collection) { return collection(getDb(), 'users', this.uid, c); }
  subscribe(c: Collection, onDocs: (docs: any[]) => void, onError: (e: Error) => void) {
    return onSnapshot(this.col(c), (snap) => onDocs(snap.docs.map((d) => ({ ...d.data(), __id: d.id }))), (e) => onError(e));
  }
  async set(c: Collection, key: string, body: any) { await setDoc(doc(this.col(c), key), body); }
  async delete(c: Collection, key: string) { await deleteDoc(doc(this.col(c), key)); }
}

const toUser = (u: User): AuthUser => ({ uid: u.uid, email: u.email, name: u.displayName });
export function watchAuth(cb: (u: AuthUser | null) => void): () => void {
  return onAuthStateChanged(getAuth(getFirebaseApp()), (u) => cb(u ? toUser(u) : null));
}
export async function signInWithGoogle(): Promise<AuthUser> {
  const cred = await signInWithPopup(getAuth(getFirebaseApp()), new GoogleAuthProvider());
  return toUser(cred.user);
}
export const signOut = () => fbSignOut(getAuth(getFirebaseApp()));
