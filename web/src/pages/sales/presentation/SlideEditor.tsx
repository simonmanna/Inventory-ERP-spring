import type { ReactNode } from 'react';
import { ImagePlus, Plus, RotateCcw, Trash2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { tx } from '@/lib/i18n/tx';
import type { ReportSlide, SlideEdit, SlideKind, SlideTable } from './report';
import { ScaledSlide } from './SlideView';

const has = (k: SlideKind, ...kinds: SlideKind[]) => kinds.includes(k);

const META_FIELDS: Partial<Record<SlideKind, Array<[string, string]>>> = {
  cover: [['logoText', 'Logo text (used when no logo image)'], ['logoSub', 'Logo second line']],
  summary: [['submittedBy', 'Submitted by'], ['role', 'Role'], ['reportDate', 'Report date']],
};
const IMAGE_SLOTS: Partial<Record<SlideKind, string[]>> = {
  cover: ['Logo', 'Background photo'],
  visits: ['Photo 1 (large)', 'Photo 2', 'Photo 3', 'Photo 4'],
  digital: ['Screenshot'],
};

/** Shrinks an uploaded image to ≤1600px JPEG so it stays light in the deck and in local storage. */
function readImage(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onerror = () => reject(r.error);
    r.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error('Unsupported image'));
      img.onload = () => {
        const scale = Math.min(1, 1600 / Math.max(img.width, img.height));
        const c = document.createElement('canvas');
        c.width = Math.round(img.width * scale);
        c.height = Math.round(img.height * scale);
        c.getContext('2d')!.drawImage(img, 0, 0, c.width, c.height);
        resolve(file.type === 'image/png' ? c.toDataURL('image/png') : c.toDataURL('image/jpeg', 0.85));
      };
      img.src = String(r.result);
    };
    r.readAsDataURL(file);
  });
}

function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="space-y-2 rounded-lg border p-3">
      <div className="flex items-center justify-between"><h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h4>{action}</div>
      {children}
    </section>
  );
}

function TableEditor({ t, onChange }: { t: SlideTable; onChange: (t: SlideTable) => void }) {
  const cols = t.header.length;
  const setCell = (r: number, c: number, v: string) => onChange({ ...t, rows: t.rows.map((row, i) => (i === r ? row.map((x, j) => (j === c ? v : x)) : row)) });
  return (
    <div className="space-y-2">
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <thead>
            <tr>{t.header.map((h, c) => (
              <th key={c} className="p-0.5"><Input className="h-7 text-xs font-semibold" value={h} onChange={(e) => onChange({ ...t, header: t.header.map((x, j) => (j === c ? e.target.value : x)) })} /></th>
            ))}<th className="w-7" /></tr>
          </thead>
          <tbody>
            {t.rows.map((row, r) => (
              <tr key={r}>
                {row.map((v, c) => (
                  <td key={c} className="p-0.5 align-top">
                    <Textarea rows={1} className="min-h-7 resize-y px-2 py-1 text-xs" value={v} onChange={(e) => setCell(r, c, e.target.value)} />
                  </td>
                ))}
                <td className="p-0.5 align-top">
                  <Button variant="ghost" size="icon" className="h-7 w-7" aria-label={tx('Remove row')} onClick={() => onChange({ ...t, rows: t.rows.filter((_, i) => i !== r) })}><Trash2 className="h-3.5 w-3.5" /></Button>
                </td>
              </tr>
            ))}
            {t.total && (
              <tr className="bg-muted/50">{t.total.map((v, c) => (
                <td key={c} className="p-0.5"><Input className="h-7 text-xs font-semibold" value={v} onChange={(e) => onChange({ ...t, total: t.total!.map((x, j) => (j === c ? e.target.value : x)) })} /></td>
              ))}<td /></tr>
            )}
          </tbody>
        </table>
      </div>
      <Button variant="outline" size="sm" onClick={() => onChange({ ...t, rows: [...t.rows, Array.from({ length: cols }, () => '')] })}><Plus className="mr-1 h-3.5 w-3.5" />{tx('Add row')}</Button>
    </div>
  );
}

interface Props {
  slide: ReportSlide | null;
  n: number | null;
  edited: boolean;
  onChange: (patch: SlideEdit) => void;
  onReset: () => void;
  onClose: () => void;
  onPrev?: () => void;
  onNext?: () => void;
}

/** Full-size slide in a dialog with a form for every editable part; changes are live. */
export function SlideEditor({ slide: s, n, edited, onChange, onReset, onClose, onPrev, onNext }: Props) {
  if (!s) return null;
  const k = s.kind;
  const field = (key: 'title' | 'subtitle' | 'heading' | 'note', label: string) => (
    <div className="space-y-1"><Label className="text-xs">{label}</Label><Input value={s[key]} onChange={(e) => onChange({ [key]: e.target.value })} /></div>
  );
  const meta = META_FIELDS[k];
  const slots = IMAGE_SLOTS[k];

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="flex h-[92vh] max-w-[96vw] flex-col gap-3 p-4 xl:max-w-[1500px]">
        <DialogHeader className="flex-row items-center justify-between space-y-0 pr-8">
          <div>
            <DialogTitle className="text-base">{n != null ? `${tx('Slide')} ${n} · ` : ''}{s.label}</DialogTitle>
            <DialogDescription className="text-xs">{tx('Edit the content on the right — the preview updates as you type.')}</DialogDescription>
          </div>
          <div className="flex items-center gap-2">
            <label className="flex cursor-pointer items-center gap-2 rounded-md border px-3 py-1.5 text-sm">
              <input type="checkbox" className="h-4 w-4 accent-primary" checked={s.on} onChange={(e) => onChange({ on: e.target.checked })} />
              {tx('Include in presentation')}
            </label>
            {edited && <Button variant="outline" size="sm" onClick={onReset}><RotateCcw className="mr-1 h-3.5 w-3.5" />{tx('Reset slide')}</Button>}
          </div>
        </DialogHeader>

        <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-[minmax(0,1fr)_420px]">
          <div className="flex min-h-0 flex-col justify-center gap-3">
            <div className={`overflow-hidden rounded-md border shadow-lg ${s.on ? '' : 'opacity-50 grayscale'}`}><ScaledSlide slide={s} n={n} /></div>
            <div className="flex justify-between">
              <Button variant="outline" size="sm" onClick={onPrev} disabled={!onPrev}>← {tx('Previous')}</Button>
              <Button variant="outline" size="sm" onClick={onNext} disabled={!onNext}>{tx('Next')} →</Button>
            </div>
          </div>

          <div className="min-h-0 space-y-3 overflow-y-auto pr-1">
            <Section title={tx('Text')}>
              {field('title', tx('Title'))}
              {has(k, 'catTable', 'catChart', 'lost', 'market', 'progress', 'digital', 'results', 'objectives', 'plan') && field('subtitle', tx('Subtitle'))}
              {has(k, 'summary', 'catChart', 'catCompare', 'visits', 'progress', 'results') && field('heading', tx('Panel heading'))}
              {has(k, 'catChart', 'catCompare', 'progress', 'results') && (
                <div className="space-y-1">
                  <Label className="text-xs">{k === 'catChart' ? tx('Key observation (leave empty for a full-width chart)') : tx('Commentary')}</Label>
                  <Textarea rows={5} value={s.text} onChange={(e) => onChange({ text: e.target.value })} />
                </div>
              )}
              {has(k, 'catTable', 'catCompare', 'market', 'progress', 'digital', 'results', 'objectives', 'plan') && field('note', tx('Footer note'))}
              {meta?.map(([key, label]) => (
                <div key={key} className="space-y-1"><Label className="text-xs">{tx(label)}</Label>
                  <Input value={s.meta?.[key] ?? ''} onChange={(e) => onChange({ meta: { ...s.meta, [key]: e.target.value } })} /></div>
              ))}
            </Section>

            {s.table && <Section title={tx('Table')}><TableEditor t={s.table} onChange={(table) => onChange({ table })} /></Section>}

            {s.chart && (
              <Section title={tx('Chart data')}>
                {k === 'catChart' && (
                  <div className="space-y-1"><Label className="text-xs">{tx('Chart title')}</Label><Input value={s.chart.title} onChange={(e) => onChange({ chart: { ...s.chart!, title: e.target.value } })} /></div>
                )}
                {s.chart.labels.map((l, i) => (
                  <div key={i} className="grid grid-cols-[1fr_140px_28px] gap-1">
                    <Input className="h-8 text-xs" value={l} onChange={(e) => onChange({ chart: { ...s.chart!, labels: s.chart!.labels.map((x, j) => (j === i ? e.target.value : x)) } })} />
                    <Input className="h-8 text-right text-xs" type="number" value={s.chart?.values[i] ?? 0} onChange={(e) => onChange({ chart: { ...s.chart!, values: s.chart!.values.map((x, j) => (j === i ? Number(e.target.value) || 0 : x)) } })} />
                    <Button variant="ghost" size="icon" className="h-8 w-7" aria-label={tx('Remove')} onClick={() => onChange({ chart: { ...s.chart!, labels: s.chart!.labels.filter((_, j) => j !== i), values: s.chart!.values.filter((_, j) => j !== i) } })}><X className="h-3.5 w-3.5" /></Button>
                  </div>
                ))}
                <Button variant="outline" size="sm" onClick={() => onChange({ chart: { ...s.chart!, labels: [...s.chart!.labels, ''], values: [...s.chart!.values, 0] } })}><Plus className="mr-1 h-3.5 w-3.5" />{tx('Add bar')}</Button>
              </Section>
            )}

            {s.cards && (
              <Section title={tx('Cards')}>
                {s.cards.map((c, i) => (
                  <div key={i} className="space-y-1 rounded-md bg-muted/40 p-2">
                    <Input className="h-8 font-medium" value={c.title} onChange={(e) => onChange({ cards: s.cards!.map((x, j) => (j === i ? { ...x, title: e.target.value } : x)) })} />
                    <Textarea rows={2} value={c.body} onChange={(e) => onChange({ cards: s.cards!.map((x, j) => (j === i ? { ...x, body: e.target.value } : x)) })} />
                  </div>
                ))}
              </Section>
            )}

            {s.list && (
              <Section title={k === 'visits' ? tx('Offices & sites visited') : tx('Next actions')}
                action={<Button variant="ghost" size="sm" onClick={() => onChange({ list: [...s.list!, ''] })}><Plus className="h-3.5 w-3.5" /></Button>}>
                {s.list.map((v, i) => (
                  <div key={i} className="flex gap-1">
                    <Input className="h-8" value={v} onChange={(e) => onChange({ list: s.list!.map((x, j) => (j === i ? e.target.value : x)) })} />
                    <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('Remove')} onClick={() => onChange({ list: s.list!.filter((_, j) => j !== i) })}><X className="h-3.5 w-3.5" /></Button>
                  </div>
                ))}
              </Section>
            )}

            {slots && (
              <Section title={tx('Images')}>
                <div className="grid grid-cols-2 gap-2">
                  {slots.map((label, i) => {
                    const src = s.images?.[i];
                    const set = (v: string) => { const imgs = [...(s.images ?? [])]; while (imgs.length < slots.length) imgs.push(''); imgs[i] = v; onChange({ images: imgs }); };
                    return (
                      <div key={label} className="space-y-1">
                        <span className="text-xs text-muted-foreground">{tx(label)}</span>
                        <label className="flex aspect-video cursor-pointer items-center justify-center overflow-hidden rounded-md border border-dashed bg-muted/30 hover:bg-muted">
                          {src ? <img src={src} alt="" className="h-full w-full object-cover" /> : <ImagePlus className="h-5 w-5 text-muted-foreground" />}
                          <input type="file" accept="image/*" className="hidden" onChange={async (e) => { const f = e.target.files?.[0]; if (f) set(await readImage(f)); e.target.value = ''; }} />
                        </label>
                        {src && <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => set('')}>{tx('Remove')}</Button>}
                      </div>
                    );
                  })}
                </div>
              </Section>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
