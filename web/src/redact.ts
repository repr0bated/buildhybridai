// Strips personal data before anything is sent to the cloud model (port of the Android Redactor).
const email = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const url = /\b(https?:\/\/)?((?:[a-z0-9-]+\.)+[a-z]{2,})(\/[^\s]*)?/gi;
const longDigits = /(?<![\d$])(?:\+?\d[\d\s().-]{6,}\d)(?![\d])/g;
const codeDigits = /\b\d{4,8}\b/g;
const greetingName = /\b(hi|hello|hey|dear)\s+([A-Z][a-z]+)/gi;

export function redact(text: string): string {
  let t = text.replace(email, '[email]');
  t = t.replace(url, (m, scheme = '', host = '', path = '') => {
    if (host.split('.').length - 1 >= 1 && (scheme || path || host.split('.').pop()!.length >= 2)) {
      return `${scheme}${host}${path.length > 1 ? '/[path]' : ''}`;
    }
    return m;
  });
  t = t.replace(longDigits, '[number]');
  t = t.replace(codeDigits, '[digits]');
  t = t.replace(greetingName, (_m, g) => `${g} [name]`);
  return t;
}

/** Numbers are reduced to country code + last two digits. */
export function redactNumber(number?: string | null): string {
  if (!number?.trim()) return 'hidden';
  if (number.includes('@')) return '[email sender]';
  const digits = number.replace(/\D/g, '');
  if (digits.length <= 6) return 'short code';
  const cc = number.startsWith('+') ? `+${digits.slice(0, digits.startsWith('1') ? 1 : 2)} ` : '';
  return `${cc}•••••${digits.slice(-2)}`;
}
