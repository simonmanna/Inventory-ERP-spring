import { tx } from '@/lib/i18n/tx';

/** Quick reactions and the composer's emoji picker. */
export const QUICK_REACTIONS = ['👍', '❤️', '😂', '🎉', '👀', '✅'];
export const EMOJIS = [
  '😀', '😁', '😂', '🙂', '😉', '😊', '😍', '😎', '🤔', '😐', '😴', '😢', '😡', '🙏', '👏', '🙌',
  '👍', '👎', '👌', '💪', '👀', '🎉', '🔥', '⭐', '✅', '❌', '⚠️', '❤️', '💡', '📌', '📦', '🚚',
  '💰', '📈', '📉', '⏰', '☕', '🍰', '🍽️', '🛠️',
];

export function initialsOf(name: string | null | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  return ((parts[0][0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
}

export function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60_000);
  if (m < 1) return tx('now');
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}d`;
  return new Date(iso).toLocaleDateString([], { day: 'numeric', month: 'short' });
}

export function dayKey(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

export function dayLabel(iso: string): string {
  const d = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (dayKey(iso) === dayKey(today.toISOString())) return tx('Today');
  if (dayKey(iso) === dayKey(yesterday.toISOString())) return tx('Yesterday');
  return d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
}

export function fileSize(bytes: number | null | undefined): string {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
