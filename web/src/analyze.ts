// The analysis pipeline: rules -> on-device/cloud model (schema-validated) -> capability policy -> proposals.
// Every step is recorded so the UI can show exactly how the verdict was decided.
import type { Engine, ModelRun } from './ai';
import { decide, type MethodProposal, type PolicyDecision } from './policy';
import * as prompts from './prompts';
import { redact, redactNumber } from './redact';
import { reasonsOf, scan, e164, type Channel, type RuleScan } from './rules';
import { methodByAlias, validateModelText, type Risk } from './registry';
import { analyzeSubscription, type SubscriptionInfo } from './subscriptions';
import type { DeepAnalysis, ScamVerdict } from './generated/types';

export interface ParsedInput { channel: Channel; number: string | null; body: string | null; kind: 'message' | 'number' | 'link' }

const phoneOnly = /^[+()\d\s.-]{4,24}$/;
const linkOnly = /^(?:https?:\/\/|www\.)\S+$|^(?:[a-z0-9-]+\.)+[a-z]{2,}(?:\/\S*)?$/i;

/** Decide what the user pasted: a text message, a phone number, or a link. */
export function parseInput(text: string, sender = ''): ParsedInput {
  const t = text.trim();
  const s = sender.trim();
  if (!s && phoneOnly.test(t) && t.replace(/\D/g, '').length >= 4) return { channel: 'CALL', number: t, body: null, kind: 'number' };
  if (linkOnly.test(t) && !/\s/.test(t)) return { channel: 'SMS', number: s || null, body: t, kind: 'link' };
  return { channel: 'SMS', number: s || null, body: t, kind: 'message' };
}

export type StepKind = 'rules' | 'model' | 'validate' | 'policy' | 'fallback';
export interface Step { kind: StepKind; title: string; detail: string; ok: boolean }

export interface Final {
  riskLevel: Risk;
  category: string;
  confidence: number;
  reasons: string[];
  advice: string;
  decidedBy: 'RULES' | 'ON_DEVICE' | 'IN_CLOUD';
  proposedMethod: string; // alias
}

export interface Analysis {
  input: ParsedInput;
  rules: RuleScan;
  run: ModelRun | null;
  modelError: string | null;
  schemaValid: boolean | null;
  schemaErrors: string[];
  final: Final;
  policy: PolicyDecision;
  rulesPolicy: PolicyDecision;
  subscription: SubscriptionInfo | null;
  steps: Step[];
  at: number;
}

export interface AnalyzeOptions {
  engine: Engine | null;
  isTrusted?: boolean;
  isBlocked?: boolean;
  settings?: { autoBlockEnabled?: boolean; autoBlockThreshold?: number; onDeviceOnly?: boolean };
  /** True when Chrome's on-device model is ready. When false the request will go to the cloud, so the text is redacted first. */
  deviceReady?: boolean;
}

export async function analyze(input: ParsedInput, opts: AnalyzeOptions): Promise<Analysis> {
  const steps: Step[] = [];
  const settings = opts.settings ?? {};
  const rules = scan({ channel: input.channel, number: input.number, body: input.body });
  steps.push({
    kind: 'rules', ok: true, title: `Rules · ${rules.riskLevel} (score ${rules.score})`,
    detail: rules.flags.length ? reasonsOf(rules).join('; ') : 'No scam patterns matched.',
  });

  const ctx = { channel: input.channel, isContact: false, isTrusted: !!opts.isTrusted, autoBlockEnabled: settings.autoBlockEnabled, autoBlockThreshold: settings.autoBlockThreshold };
  const rulesPolicy = decide({ method: rules.method, riskLevel: rules.riskLevel, confidence: rules.score, reasons: reasonsOf(rules), source: 'RULES' }, ctx);

  const subscription = input.channel === 'SMS' && input.body ? analyzeSubscription(input.number ?? '', input.body) : null;
  if (subscription?.isSubscription) {
    steps.push({ kind: 'rules', ok: true, title: 'Subscription text', detail: `Signals: ${subscription.signals.join(', ')}${subscription.keyword ? `; opt-out keyword ${subscription.keyword}` : ''}${subscription.bestLink ? `; link ${subscription.bestLink.domain}` : ''}.` });
  }

  let run: ModelRun | null = null;
  let modelError: string | null = null;
  let schemaValid: boolean | null = null;
  let schemaErrors: string[] = [];
  let verdict: ScamVerdict | null = null;

  const worth = input.channel === 'CALL' ? true : !!input.body;
  if (opts.engine && worth) {
    try {
      const willLeave = opts.deviceReady === false && !settings.onDeviceOnly;
      const prompt = prompts.triage(
        input.channel,
        willLeave ? redactNumber(input.number) : input.number ?? 'unknown',
        willLeave && input.body ? redact(input.body) : input.body,
        rules,
      );
      run = await opts.engine.triage(prompt, { onDeviceOnly: !!settings.onDeviceOnly });
      steps.push({
        kind: 'model', ok: true,
        title: run.source === 'ON_DEVICE' ? `On-device model ran · ${run.modelName}` : `Cloud model ran · ${run.modelName}`,
        detail: run.source === 'ON_DEVICE'
          ? `Answered locally in ${run.latencyMs} ms. The text never left this browser.`
          : `On-device Gemini Nano wasn't available here, so Firebase AI Logic fell back to cloud Gemini (${run.latencyMs} ms)${opts.deviceReady === false ? '; personal data was redacted before it was sent' : ''}.`,
      });
      const report = validateModelText('scam_verdict', run.rawText ?? '');
      schemaValid = report.valid;
      schemaErrors = report.errors;
      steps.push({ kind: 'validate', ok: report.valid, title: report.valid ? 'Output matches scam_verdict.schema.json' : 'Output rejected by scam_verdict.schema.json', detail: report.valid ? 'Valid typed verdict; its proposed method goes to the policy.' : report.errors.join('; ') });
      if (report.valid) verdict = JSON.parse(report.json) as ScamVerdict;
    } catch (e) {
      modelError = (e as Error).message ?? String(e);
      steps.push({ kind: 'model', ok: false, title: 'Model unavailable', detail: modelError });
    }
  } else if (!opts.engine) {
    steps.push({ kind: 'model', ok: false, title: 'Model skipped', detail: 'No model engine configured; using rules only.' });
  }

  let final: Final;
  let policy: PolicyDecision;
  if (verdict && run) {
    const method = verdict.method.toLowerCase();
    final = {
      riskLevel: verdict.riskLevel, category: verdict.category, confidence: verdict.confidence,
      reasons: verdict.reasons, advice: verdict.advice, decidedBy: run.source, proposedMethod: method,
    };
    policy = decide({ method, riskLevel: verdict.riskLevel, confidence: verdict.confidence, reasons: verdict.reasons, source: run.source }, ctx);
  } else {
    final = {
      riskLevel: rules.riskLevel, category: rules.category, confidence: rules.score, reasons: reasonsOf(rules),
      advice: rules.riskLevel === 'SAFE' ? 'No scam patterns found.' : 'Treat with caution: do not tap links or share codes.',
      decidedBy: 'RULES', proposedMethod: rules.method,
    };
    policy = rulesPolicy;
    if (run || modelError) steps.push({ kind: 'fallback', ok: true, title: 'Falling back to the rules verdict', detail: 'The model result could not be used, so the deterministic rules decide.' });
  }
  if (opts.isBlocked) steps.push({ kind: 'policy', ok: true, title: 'Sender is on your block list', detail: 'This sender was blocked earlier (synced list).' });
  steps.push({
    kind: 'policy', ok: policy.dispatch !== 'DENY',
    title: `Policy · ${policy.dispatch}${policy.method !== 'none' ? ` · ${policy.method}` : ''}`,
    detail: policy.notes.join(' '),
  });
  return { input, rules, run, modelError, schemaValid, schemaErrors, final, policy, rulesPolicy, subscription, steps, at: Date.now() };
}

export interface DeepResult { run: ModelRun; valid: boolean; errors: string[]; value: DeepAnalysis | null; redacted: string }

/** Opt-in cloud deep analysis of the redacted text. */
export async function deepAnalyze(engine: Engine, a: Analysis): Promise<DeepResult> {
  const redacted = redact(a.input.body ?? '');
  const p = prompts.deep(a.input.channel, redactNumber(a.input.number), a.input.body ? redacted : null, `${a.final.riskLevel} ${a.final.category}: ${a.final.reasons.join('; ')}`);
  const run = await engine.deep(p);
  const report = validateModelText('deep_analysis', run.rawText ?? '');
  return { run, valid: report.valid, errors: report.errors, value: report.valid ? (JSON.parse(report.json) as DeepAnalysis) : null, redacted };
}

/** Which registry methods the web app can run itself (the rest are phone-only). */
export const WEB_METHODS = ['block_number', 'allow_number', 'quarantine_sms', 'report_scam', 'explain_cloud', 'manage_subscription', 'mute_sender'] as const;
export const isWebMethod = (alias: string) => (WEB_METHODS as readonly string[]).includes(alias) && !!methodByAlias(alias);
export { e164 };
