import { tx } from '@/lib/i18n/tx';

const CHANNEL_LABEL: Record<string, string> = {
  portal: 'Portal', email: 'E-mail', phone: 'Phone', walk_in: 'Walk-in', whatsapp: 'WhatsApp', letter: 'Letter', other: 'Other',
};

/** Display label of a ticket channel. */
export const channelLabel = (c: string) => tx(CHANNEL_LABEL[c] ?? c);

/** Display label of a letter dispatch method. */
export const dispatchLabel = (m: string) => tx(m.replace(/_/g, ' '));
