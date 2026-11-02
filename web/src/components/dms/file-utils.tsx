import {
  File as FileIcon, FileArchive, FileAudio, FileImage, FileSpreadsheet, FileText, FileVideo, Presentation,
} from 'lucide-react';
import { resolveAssetUrl } from '@/lib/api';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { signedUrl } from '@/features/dms/api';

/** Icon for a content type / file name. */
export function fileIcon(contentType?: string | null, name?: string | null) {
  const t = (contentType ?? '').toLowerCase();
  const n = (name ?? '').toLowerCase();
  if (t.startsWith('image/')) return FileImage;
  if (t.startsWith('video/')) return FileVideo;
  if (t.startsWith('audio/')) return FileAudio;
  if (t.includes('spreadsheet') || t.includes('excel') || /\.(xlsx?|csv|ods)$/.test(n)) return FileSpreadsheet;
  if (t.includes('presentation') || /\.(pptx?|odp)$/.test(n)) return Presentation;
  if (t.includes('zip') || t.includes('compressed') || /\.(zip|rar|7z|tar|gz)$/.test(n)) return FileArchive;
  if (t === 'application/pdf' || t.startsWith('text/') || /\.(pdf|docx?|txt|odt|rtf|md)$/.test(n)) return FileText;
  return FileIcon;
}

export const isImage = (contentType?: string | null) => (contentType ?? '').toLowerCase().startsWith('image/');

/** Download the current (or a given) version through a fresh signed URL. */
export async function downloadDocument(id: string, version?: number) {
  try {
    const s = await signedUrl(id, version);
    const a = document.createElement('a');
    a.href = resolveAssetUrl(s.url) ?? s.url;
    a.download = s.filename;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } catch (err) {
    notify.error(apiMessage(err, tx('Could not download the file')));
  }
}

/** Files of a drag event or file input, as an array. */
export const filesOf = (list: FileList | null | undefined): File[] => (list ? Array.from(list) : []);
