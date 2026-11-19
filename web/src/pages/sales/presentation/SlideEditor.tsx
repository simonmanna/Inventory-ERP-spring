import { useState, type ClipboardEvent, type ReactNode } from 'react';
import { ClipboardPaste, Copy, ExternalLink, ImagePlus, Plus, RotateCcw, Sparkles, Trash2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';
import { copyText, readImage } from './media';
import { CHATGPT_URL, extractJson, parseChart, parseTable, sanitizeEdit, slidePrompt } from './chatgpt';
import { CUSTOM_LAYOUTS, relayout, type CustomLayout, type ReportSlide, type SlideEdit, type SlideKind, type SlideTable } from './report';
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
const IMAGE_LAYOUTS: CustomLayout[] = ['textImage', 'imageText', 'image'];
const slotsFor = (s: ReportSlide) => (s.kind === 'custom' ? (IMAGE_LAYOUTS.includes(s.layout ?? 'bullets') ? ['Image'] : undefined) : IMAGE_SLOTS[s.kind]);

function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="space-y-2 rounded-lg border p-3">
      <div className="flex items-center justify-between"><h4 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h4>{action}</div>
      {children}
    </section>
  );
}

/** Paste box for tables / chart data copied from Excel, CSV or a ChatGPT Markdown table. */
function PasteBox({ hint, onImport }: { hint: string; onImport: (text: string) => void }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  if (!open) return <Button variant="outline" size="sm" onClick={() => setOpen(true)}><ClipboardPaste className="mr-1 h-3.5 w-3.5" />{tx('Paste data')}</Button>;
  return (
    <div className="w-full space-y-1 rounded-md border border-dashed p-2">
      <Label className="text-xs">{hint}</Label>
      <Textarea rows={5} className="font-mono text-xs" value={text} onChange={(e) => setText(e.target.value)} placeholder={'Product\tRevenue\nTiles\t1200\nCement\t800'} />
      <div className="flex gap-2">
        <Button size="sm" disabled={!text.trim()} onClick={() => {
          try { onImport(text); setText(''); setOpen(false); } catch (e) { notify.error(tx('Could not read the data'), e instanceof Error ? e.message : undefined); }
        }}>{tx('Import')}</Button>
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
      </div>
    </div>
  );
}

function TableEditor({ t, onChange, columns }: { t: SlideTable; onChange: (t: SlideTable) => void; columns?: boolean }) {
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
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" onClick={() => onChange({ ...t, rows: [...t.rows, Array.from({ length: cols }, () => '')] })}><Plus className="mr-1 h-3.5 w-3.5" />{tx('Add row')}</Button>
        {columns && cols < 8 && <Button variant="outline" size="sm" onClick={() => onChange({ header: [...t.header, ''], rows: t.rows.map((r) => [...r, '']), total: t.total && [...t.total, ''] })}><Plus className="mr-1 h-3.5 w-3.5" />{tx('Add column')}</Button>}
        {columns && cols > 1 && <Button variant="outline" size="sm" onClick={() => onChange({ header: t.header.slice(0, -1), rows: t.rows.map((r) => r.slice(0, -1)), total: t.total?.slice(0, -1) })}><X className="mr-1 h-3.5 w-3.5" />{tx('Remove last column')}</Button>}
        <PasteBox hint={tx('Paste a table from Excel, CSV or ChatGPT (first row = headings)')} onImport={(text) => {
          const p = parseTable(text);
          // fixed-layout report tables keep their column count; custom tables take the pasted shape
          const w = columns ? p.header.length : cols;
          const fit = (r: string[]) => Array.from({ length: w }, (_, i) => r[i] ?? '');
          onChange({ ...t, header: columns ? p.header : fit(p.header), rows: p.rows.map(fit), total: t.total && fit(t.total) });
        }} />
      </div>
    </div>
  );
}

/** Copy a prompt for this slide to ChatGPT, then paste its JSON reply back. */
function ChatGptPanel({ s, org, onChange }: { s: ReportSlide; org: string; onChange: (p: SlideEdit) => void }) {
  const [ask, setAsk] = useState('');
  const [reply, setReply] = useState('');
  const [error, setError] = useState<string | null>(null);
  const copy = async () => {
    try { await copyText(slidePrompt(s, ask, org)); notify.success(tx('Prompt copied'), tx('Paste it into ChatGPT, then paste the answer below.')); }
    catch { notify.error(tx('Could not copy to the clipboard')); }
  };
  const apply = () => {
    try {
      const patch = sanitizeEdit(extractJson(reply), s);
      onChange(patch);
      setReply('');
      setError(null);
      notify.success(tx('ChatGPT changes applied'), Object.keys(patch).join(', '));
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };
  return (
    <Section title={tx('Improve with ChatGPT')}>
      <ol className="list-decimal space-y-0.5 pl-4 text-xs text-muted-foreground">
        <li>{tx('Say what you want (or leave empty to polish the wording).')}</li>
        <li>{tx('Copy the prompt and paste it into ChatGPT.')}</li>
        <li>{tx('Paste ChatGPT\'s answer here and apply. Paste images it makes with Ctrl+V anywhere in this window.')}</li>
      </ol>
      <Textarea rows={2} value={ask} onChange={(e) => setAsk(e.target.value)} placeholder={tx('e.g. Rewrite the observation for the board and add three next actions')} />
      <div className="flex flex-wrap gap-2">
        <Button size="sm" onClick={copy}><Copy className="mr-1 h-3.5 w-3.5" />{tx('Copy prompt')}</Button>
        <Button size="sm" variant="outline" asChild><a href={CHATGPT_URL} target="_blank" rel="noopener noreferrer"><ExternalLink className="mr-1 h-3.5 w-3.5" />{tx('Open ChatGPT')}</a></Button>
      </div>
      <Textarea rows={4} className="font-mono text-xs" value={reply} onChange={(e) => { setReply(e.target.value); setError(null); }} placeholder={tx('Paste ChatGPT\'s reply (JSON) here')} />
      {error && <p className="text-xs text-rose-600">{error}</p>}
      <Button size="sm" disabled={!reply.trim()} onClick={apply}><Sparkles className="mr-1 h-3.5 w-3.5" />{tx('Apply reply')}</Button>
    </Section>
  );
}

interface Props {
  org: string;
  onDelete?: () => void;
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
export function SlideEditor({ slide: s, n, edited, org, onChange, onReset, onDelete, onClose, onPrev, onNext }: Props) {
  if (!s) return null;
  const k = s.kind;
  const custom = k === 'custom';
  const layout = s.layout ?? 'bullets';
  const field = (key: 'title' | 'subtitle' | 'heading' | 'note', label: string) => (
    <div className="space-y-1"><Label className="text-xs">{label}</Label><Input value={s[key]} onChange={(e) => onChange({ [key]: e.target.value })} /></div>
  );
  const meta = META_FIELDS[k];
  const slots = slotsFor(s);
  const setImage = (i: number, v: string) => {
    const n = slots?.length ?? 1;
    const imgs = [...(s.images ?? [])];
    while (imgs.length < n) imgs.push('');
    imgs[i] = v;
    onChange({ images: imgs });
  };
  /** Ctrl+V of an image (e.g. copied from ChatGPT) fills the first empty image slot, else the first. */
  const onPaste = async (e: ClipboardEvent) => {
    if (!slots) return;
    const item = Array.from(e.clipboardData.items).find((x) => x.type.startsWith('image/'));
    const file = item?.getAsFile();
    if (!file) return;
    e.preventDefault();
    const empty = slots.findIndex((_, i) => !s.images?.[i]);
    setImage(empty >= 0 ? empty : 0, await readImage(file));
    notify.success(tx('Image pasted'));
  };

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="flex h-[92vh] max-w-[96vw] flex-col gap-3 p-4 xl:max-w-[1500px]" onPaste={onPaste}>
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
            {edited && !custom && <Button variant="outline" size="sm" onClick={onReset}><RotateCcw className="mr-1 h-3.5 w-3.5" />{tx('Reset slide')}</Button>}
            {onDelete && <Button variant="outline" size="sm" className="text-rose-600" onClick={onDelete}><Trash2 className="mr-1 h-3.5 w-3.5" />{tx('Delete slide')}</Button>}
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
            {custom && (
              <Section title={tx('Layout')}>
                <div className="grid grid-cols-2 gap-1.5">
                  {CUSTOM_LAYOUTS.map((l) => (
                    <button key={l.key} type="button" onClick={() => onChange(relayout(s, l.key))}
                      className={`rounded-md border px-2 py-1.5 text-left text-xs ${layout === l.key ? 'border-primary bg-primary/10 font-medium' : 'hover:bg-muted'}`}>{tx(l.label)}</button>
                  ))}
                </div>
                <div className="space-y-1"><Label className="text-xs">{tx('Caption under the thumbnail')}</Label><Input value={s.label} onChange={(e) => onChange({ label: e.target.value })} /></div>
              </Section>
            )}

            <ChatGptPanel s={s} org={org} onChange={onChange} />

            <Section title={tx('Text')}>
              {field('title', tx('Title'))}
              {custom && field('subtitle', tx('Subtitle'))}
              {custom && layout !== 'table' && layout !== 'chart' && layout !== 'cards' && field('heading', tx('Panel heading'))}
              {custom && layout !== 'table' && layout !== 'chart' && layout !== 'cards' && (
                <div className="space-y-1"><Label className="text-xs">{layout === 'image' ? tx('Caption (used when there is no subtitle)') : tx('Paragraphs')}</Label>
                  <Textarea rows={4} value={s.text} onChange={(e) => onChange({ text: e.target.value })} /></div>
              )}
              {custom && field('note', tx('Footer note'))}
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

            {s.table && (!custom || layout === 'table') && <Section title={tx('Table')}><TableEditor t={s.table} columns={custom} onChange={(table) => onChange({ table })} /></Section>}

            {s.chart && (!custom || layout === 'chart' || layout === 'chartText') && (
              <Section title={tx('Chart data')}>
                {(k === 'catChart' || custom) && (
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
                <PasteBox hint={tx('Paste two columns: label and value (from Excel, CSV or ChatGPT)')} onImport={(text) => onChange({ chart: { ...s.chart!, ...parseChart(text) } })} />
              </Section>
            )}

            {s.cards && (!custom || layout === 'cards') && (
              <Section title={tx('Cards')}>
                {s.cards.map((c, i) => (
                  <div key={i} className="space-y-1 rounded-md bg-muted/40 p-2">
                    <Input className="h-8 font-medium" value={c.title} onChange={(e) => onChange({ cards: s.cards!.map((x, j) => (j === i ? { ...x, title: e.target.value } : x)) })} />
                    <Textarea rows={2} value={c.body} onChange={(e) => onChange({ cards: s.cards!.map((x, j) => (j === i ? { ...x, body: e.target.value } : x)) })} />
                  </div>
                ))}
              </Section>
            )}

            {s.list && (!custom || !['table', 'chart', 'cards', 'image'].includes(layout)) && (
              <Section title={k === 'visits' ? tx('Offices & sites visited') : custom ? tx('Bullet points') : tx('Next actions')}
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
              <Section title={tx('Images')} action={<span className="text-[11px] text-muted-foreground">{tx('Upload, or paste with Ctrl+V')}</span>}>
                <div className="grid grid-cols-2 gap-2">
                  {slots.map((label, i) => {
                    const src = s.images?.[i];
                    const set = (v: string) => setImage(i, v);
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
