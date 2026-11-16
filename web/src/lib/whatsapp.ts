/**
 * Click-to-chat WhatsApp links (https://wa.me). Opening the link hands the prefilled message to the WhatsApp app
 * (mobile / desktop) or WhatsApp Web; the user picks "send" there — nothing goes through our server.
 */

/** Country calling code for the org timezone, used to turn a local number (0772…) into international form. */
const CALLING_CODE_BY_TZ: Record<string, string> = {
  'Africa/Kampala': '256', 'Africa/Nairobi': '254', 'Africa/Dar_es_Salaam': '255', 'Africa/Kigali': '250',
  'Africa/Bujumbura': '257', 'Africa/Juba': '211', 'Africa/Addis_Ababa': '251', 'Africa/Asmara': '291',
  'Africa/Mogadishu': '252', 'Africa/Kinshasa': '243', 'Africa/Lubumbashi': '243', 'Africa/Lusaka': '260',
  'Africa/Harare': '263', 'Africa/Johannesburg': '27', 'Africa/Lagos': '234', 'Africa/Accra': '233',
  'Africa/Cairo': '20', 'Europe/London': '44', 'Asia/Kolkata': '91', 'Asia/Dubai': '971',
};

/**
 * Digits-only international number for wa.me, or null when the input has no usable number. "+256 772…" and
 * "00256 772…" keep their country code; a local "0772…" gets the org's calling code when its timezone is known.
 */
export function whatsappNumber(phone: string | null | undefined, timezone?: string | null): string | null {
  if (!phone) return null;
  const trimmed = phone.trim();
  let digits = trimmed.replace(/\D/g, '');
  if (!digits) return null;
  if (trimmed.startsWith('+')) return digits;
  if (digits.startsWith('00')) return digits.slice(2);
  const cc = timezone ? CALLING_CODE_BY_TZ[timezone] : undefined;
  if (digits.startsWith('0') && cc) digits = cc + digits.replace(/^0+/, '');
  return digits.length >= 7 ? digits : null;
}

/** wa.me link with the message prefilled; without a number WhatsApp asks which chat to send it to. */
export function whatsappUrl(text: string, phone?: string | null): string {
  return `https://wa.me/${phone ?? ''}?text=${encodeURIComponent(text)}`;
}

/** Opens WhatsApp with the message ready to send. */
export function openWhatsapp(text: string, phone?: string | null): void {
  window.open(whatsappUrl(text, phone), '_blank', 'noopener,noreferrer');
}
