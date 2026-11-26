// Hybrid inference through Firebase AI Logic (Firebase JS SDK, Gemini Developer API backend).
//  - triage: PREFER_ON_DEVICE -> Gemini Nano in Chrome (Prompt API), falls back to cloud Gemini.
//  - deep analysis: ONLY_IN_CLOUD, redacted input only, only when the user asks.
import { getAI, getGenerativeModel, GoogleAIBackend, InferenceMode, InferenceSource, type GenerativeModel } from 'firebase/ai';
import { getFirebaseApp } from './firebase';
import config from './firebase-config.json';
import { modelSchema } from './registry';
import { FALLBACK, startTrace } from './telemetry';

export interface ModelRun {
  rawText: string | null;
  source: 'ON_DEVICE' | 'IN_CLOUD';
  modelName: string;
  latencyMs: number;
  fallbackReason?: string;
}

export type DeviceAvailability = 'available' | 'downloadable' | 'downloading' | 'unavailable' | 'unsupported';

/** What the analysis pipeline needs from a model; tests supply a fake. */
export interface Engine {
  triage(prompt: string, opts?: { onDeviceOnly?: boolean }): Promise<ModelRun>;
  deep(prompt: string): Promise<ModelRun>;
  /** Cloud-only structured call for subscription link choice etc. (not used yet) */
}

export const NANO_LABEL = 'Gemini Nano (Chrome built-in)';

/** Chrome's built-in Prompt API, if present. */
export async function deviceAvailability(): Promise<DeviceAvailability> {
  const LM = (globalThis as any).LanguageModel;
  if (!LM?.availability) return 'unsupported';
  try {
    const a = await LM.availability({ expectedInputs: [{ type: 'text', languages: ['en'] }], expectedOutputs: [{ type: 'text', languages: ['en'] }] });
    return a as DeviceAvailability;
  } catch {
    return 'unavailable';
  }
}

const cloudModel = config.cloudModel;
const io = { expectedInputs: [{ type: 'text', languages: ['en'] }], expectedOutputs: [{ type: 'text', languages: ['en'] }] } as any;

export class FirebaseEngine implements Engine {
  private ai = getAI(getFirebaseApp(), { backend: new GoogleAIBackend() });
  private triageModels = new Map<string, GenerativeModel>();
  private deepModel?: GenerativeModel;

  private triageModel(onDeviceOnly: boolean): GenerativeModel {
    const key = String(onDeviceOnly);
    let m = this.triageModels.get(key);
    if (!m) {
      const schema = modelSchema('scam_verdict');
      m = getGenerativeModel(this.ai, {
        mode: onDeviceOnly ? InferenceMode.ONLY_ON_DEVICE : InferenceMode.PREFER_ON_DEVICE,
        onDeviceParams: { createOptions: io, promptOptions: { responseConstraint: schema } },
        inCloudParams: {
          model: cloudModel,
          generationConfig: { temperature: 0.1, maxOutputTokens: 400, responseMimeType: 'application/json', responseJsonSchema: schema },
        },
      });
      this.triageModels.set(key, m);
    }
    return m;
  }

  /** Must be called from a user gesture (button click). Resolves when the Nano download is done. */
  async prepareDevice(onProgress?: (fraction: number) => void): Promise<void> {
    await this.triageModel(false).initializeDeviceModel(onProgress);
  }

  async triage(prompt: string, opts: { onDeviceOnly?: boolean } = {}): Promise<ModelRun> {
    const t0 = performance.now();
    const prefer = !!opts.onDeviceOnly ? 'nano' : 'nano';
    const started = await startTrace(prefer);
    const result = await this.triageModel(!!opts.onDeviceOnly).generateContent(prompt);
    const source = result.response.inferenceSource === InferenceSource.ON_DEVICE ? 'ON_DEVICE' : 'IN_CLOUD';
    started.stop(source === 'ON_DEVICE' ? 'nano' : 'cloud');
    return {
      rawText: result.response.text(),
      source,
      modelName: source === 'ON_DEVICE' ? NANO_LABEL : cloudModel,
      latencyMs: Math.round(performance.now() - t0),
      fallbackReason: source === 'IN_CLOUD' ? FALLBACK.nano_unavailable : FALLBACK.none,
    };
  }

  async deep(prompt: string): Promise<ModelRun> {
    if (!this.deepModel) {
      const schema = modelSchema('deep_analysis');
      this.deepModel = getGenerativeModel(this.ai, {
        mode: InferenceMode.ONLY_IN_CLOUD,
        inCloudParams: { model: cloudModel, generationConfig: { temperature: 0.2, maxOutputTokens: 900, responseMimeType: 'application/json', responseJsonSchema: schema } },
      });
    }
    const t0 = performance.now();
    const started = await startTrace('cloud');
    const result = await this.deepModel.generateContent(prompt);
    started.stop();
    return { rawText: result.response.text(), source: 'IN_CLOUD', modelName: cloudModel, latencyMs: Math.round(performance.now() - t0) };
  }
}
