import { initializeApp, type FirebaseApp } from 'firebase/app';
import { initializeAppCheck, ReCaptchaEnterpriseProvider, type AppCheck } from 'firebase/app-check';
import config from './firebase-config.json';

let app: FirebaseApp | undefined;
let appCheck: AppCheck | undefined;
export const getAppCheckInstance = () => appCheck;

/** App Check is attached but not enforced for this project; it makes cloud calls attributable to this web app. */
export function getFirebaseApp(): FirebaseApp {
  if (app) return app;
  app = initializeApp(config.firebase);
  if (typeof window !== 'undefined') {
    const host = location.hostname;
    const local = host === 'localhost' || host === '127.0.0.1';
    try {
      if (local) {
        // Debug provider for local development: token comes from ?appcheck=<token> or the console (register it in App Check).
        const t = new URLSearchParams(location.search).get('appcheck') ?? localStorage.getItem('cg.appcheckDebug');
        (self as any).FIREBASE_APPCHECK_DEBUG_TOKEN = t || true;
        if (t) localStorage.setItem('cg.appcheckDebug', t);
        appCheck = initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(config.appCheckSiteKey), isTokenAutoRefreshEnabled: true });
      } else {
        appCheck = initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(config.appCheckSiteKey), isTokenAutoRefreshEnabled: true });
      }
    } catch (e) {
      console.warn('App Check unavailable', e);
    }
  }
  return app;
}
