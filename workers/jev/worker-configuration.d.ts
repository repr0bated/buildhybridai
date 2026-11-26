interface Env {
  TYPESAFE_API_KEY: string;
  FIREBASE_PROJECT_NUMBER: string;
  ALLOWED_APP_IDS: string;
  RATE_LIMIT_PER_MINUTE: string;
  BATCH_MAX: string;
}
interface ExportedHandler<Env = unknown> {
  fetch?: (request: Request, env: Env, ctx: { waitUntil(p: Promise<unknown>): void }) => Response | Promise<Response>;
}
