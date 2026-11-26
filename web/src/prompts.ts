// Prompts are short on purpose: on-device requests are capped at ~9k tokens in Chrome (4k on Android).
import type { RuleScan } from './rules';
import { reasonsOf } from './rules';
import type { Channel } from './rules';

export function triage(channel: Channel, sender: string, body: string | null, rules: RuleScan): string {
  const l: string[] = [];
  l.push("You are Call Guardian, a scam detector running privately in the user's browser.");
  l.push(`Classify this ${channel === 'SMS' ? 'text message' : 'phone call'} and pick ONE method to call.`);
  l.push(
    'Methods: NONE (normal), ' + (channel === 'SMS' ? 'QUARANTINE_SMS (hide behind a warning), ' : 'REJECT_CALL (silence or reject), ') +
      'BLOCK_NUMBER (only for an obvious scam), ALLOW_NUMBER (clearly legitimate), REPORT_SCAM, EXPLAIN_CLOUD (unsure; ask for a deeper look).',
  );
  l.push('You only propose; a policy checks your choice. Be calm and specific. Reasons must quote or paraphrase the content.');
  l.push('');
  l.push(`Sender: ${sender || 'unknown'}`);
  if (rules.flags.length) l.push(`Local rule hints (may be wrong): ${reasonsOf(rules).join('; ')}`);
  if (channel === 'SMS') {
    l.push('Message:', '"""', (body ?? '').slice(0, 1500), '"""');
  } else {
    l.push('There is no transcript; judge from the caller metadata only.');
  }
  l.push('Respond with JSON only.');
  return l.join('\n');
}

export function deep(channel: Channel, redactedSender: string, redactedBody: string | null, onDeviceSummary: string | null): string {
  const l: string[] = [];
  l.push('You are a fraud analyst helping an ordinary phone user. Personal data has been redacted as [name], [number], [digits], [email], [path].');
  l.push(`Explain clearly whether this ${channel === 'SMS' ? 'text message' : 'phone call'} is a scam, which manipulation tactics it uses, and exactly what the user should do next.`);
  l.push('Never tell the user to tap the link or call the number in the message. Recommend official channels instead.');
  l.push('Also pick ONE method to offer the user: NONE, BLOCK_NUMBER, ALLOW_NUMBER, REJECT_CALL, QUARANTINE_SMS, REPORT_SCAM or SAVE_CONTACT.');
  l.push('', `Sender: ${redactedSender}`);
  if (onDeviceSummary) l.push(`First opinion: ${onDeviceSummary}`);
  if (redactedBody) l.push('Message:', '"""', redactedBody.slice(0, 4000), '"""');
  l.push('Respond with JSON only.');
  return l.join('\n');
}
