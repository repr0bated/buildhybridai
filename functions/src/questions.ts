/**
 * Every word Jev sees lives in this module: the state layout and the atomic Noul questions about
 * scam signals. Jev only judges; the decision (truth table, 0.7 / 0.3 thresholds, the "unsettled"
 * row) lives in the app, in JevTruthTable.kt, where it is unit tested.
 *
 * Question ids are not sent to the model. They must match the signal properties in
 * app/src/main/assets/schemas/methods/guardian.jev.check.v1.output.json.
 */
import type { components } from "./typesafe.gen";

type NoulQuestion = components["schemas"]["NoulQuestion"];
export type SystemOneRequest = components["schemas"]["SystemOneRequest"];

export const QUESTIONS_VERSION = "2026.11.1";
export const MODEL = "jev-latest";

export type SenderKind = "saved_contact" | "unknown_number" | "short_code" | "hidden";

export const SIGNALS = {
  asks_payment: {
    type: "noul",
    instructions: "Does `message` ask the reader to pay money?",
    criteria: {
      true: "The reader is told to pay, settle a balance, or send money",
      false: "The message only mentions an amount, or asks for nothing",
    },
  },
  deadline_pressure: {
    type: "noul",
    instructions: "Does `message` give the reader a short deadline with a penalty for missing it?",
    criteria: {
      true: "Act within hours or today, or an account, package or fee is lost",
      false: "A date is mentioned for information only, with nothing lost by waiting",
    },
  },
  claims_organization: {
    type: "noul",
    instructions: "Does `message` say it comes from a named company, bank, or government agency?",
  },
  asks_open_link: {
    type: "noul",
    instructions: "Does `message` ask the reader to open a link?",
  },
  asks_share_code: {
    type: "noul",
    instructions: "Does `message` ask the reader to send back a verification code?",
    criteria: {
      true: "The reader is asked to reply with or read out a code",
      false: "The message gives a code and says not to share it, or mentions no code",
    },
  },
  asks_login_details: {
    type: "noul",
    instructions: "Does `message` ask the reader to enter or send a password or login details?",
  },
  offers_money: {
    type: "noul",
    instructions: "Does `message` offer the reader an unexpected way to make or win money?",
    criteria: {
      true: "A prize, a high-paying job, or an investment tip the reader did not ask for",
      false: "A normal receipt, refund, or account notice",
    },
  },
  moves_to_other_app: {
    type: "noul",
    instructions: "Does `message` ask the reader to continue the conversation on another app?",
    criteria: {
      true: "The reader is asked to move to WhatsApp, Telegram, Signal or a similar app",
      false: "The reader is asked to reply by text, or nothing is asked",
    },
  },
  stranger_opener: {
    type: "noul",
    instructions: "Is the sender of `message` starting a chat with someone they don't seem to know?",
    criteria: {
      true: "A 'wrong number' or 'is this still your number?' opener from a stranger",
      false: "A message that refers to an existing order, appointment or account",
    },
  },
} as const satisfies Record<string, NoulQuestion>;

export type SignalId = keyof typeof SIGNALS;
export const SIGNAL_IDS = Object.keys(SIGNALS) as SignalId[];

const SENDER_LABEL: Record<SenderKind, string> = {
  saved_contact: "a saved contact",
  unknown_number: "a number that is not in the reader's contacts",
  short_code: "a short code (an automated business sender)",
  hidden: "a hidden sender",
};

/** The state: what a person would look at to answer the questions, and nothing else. */
export function buildRequest(redactedText: string, sender: SenderKind): SystemOneRequest {
  return {
    model: MODEL,
    state: {
      message: redactedText,
      sender: SENDER_LABEL[sender],
      note: "Personal details in `message` were masked as [name], [number], [digits], [email] and [path] before it was sent.",
    },
    questions: SIGNALS as unknown as SystemOneRequest["questions"],
  };
}
