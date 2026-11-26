// Verify Firebase App Check JWTs using the official JWKS. No Admin SDK (this is a Worker).
import * as jose from 'jose';

const JWKS_URL = 'https://firebaseappcheck.googleapis.com/v1/jwks';
const jwks = jose.createRemoteJWKSet(new URL(JWKS_URL));

export interface AppCheckClaims {
  appId: string;
  payload: jose.JWTPayload;
}

export async function verifyAppCheck(
  token: string | null,
  projectNumber: string,
  allowedAppIds: Set<string>,
): Promise<AppCheckClaims> {
  if (!token) throw new AuthError(401, 'missing_app_check', 'Missing X-Firebase-AppCheck header');
  let payload: jose.JWTPayload;
  try {
    const result = await jose.jwtVerify(token, jwks, {
      issuer: `https://firebaseappcheck.googleapis.com/${projectNumber}`,
      audience: `projects/${projectNumber}`,
      clockTolerance: 60,
    });
    payload = result.payload;
  } catch (e) {
    throw new AuthError(401, 'invalid_app_check', `App Check token rejected: ${(e as Error).message}`);
  }
  const aud = payload.aud;
  const auds = Array.isArray(aud) ? aud : aud ? [aud] : [];
  if (!auds.includes(`projects/${projectNumber}`)) {
    throw new AuthError(401, 'invalid_app_check', 'App Check audience does not include this project');
  }
  const appId = typeof payload.sub === 'string' ? payload.sub : '';
  if (!appId || !allowedAppIds.has(appId)) {
    throw new AuthError(403, 'app_not_allowed', 'This Firebase app is not allowed to call Jev');
  }
  return { appId, payload };
}

export class AuthError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}
