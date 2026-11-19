import { useState } from 'react';
import { Copy, ExternalLink, Palette as PaletteIcon, Plus, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { tx } from '@/lib/i18n/tx';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { CHATGPT_URL, deckPrompt, parseDeckReply, type DeckReply } from './chatgpt';
import { cleanHex, FONTS, STYLES, THEMES, type DeckLook } from './look';
import { CUSTOM_LAYOUTS, type CustomLayout, type ReportSlide } from './report';
import { copyText } from './media';

/** Theme swatches, layout style and font for the whole deck. */
export function LookBar({ look, onChange }: { look: DeckLook; onChange: (l: DeckLook) => void }) {
  const set = (p: Partial<DeckLook>) => onChange({ ...look, ...p });
  return (
    <div className="flex flex-wrap items-end gap-4 rounded-xl border bg-card p-4 shadow-sm">
      <div className="space-y-1.5">
        <span className="flex items-center gap-1 text-xs font-medium text-muted-foreground"><PaletteIcon className="h-3.5 w-3.5" />{tx('Theme')}</span>
        <div className="flex flex-wrap gap-1.5">
          {THEMES.map((t) => {
            const colors = t.key === 'brand' ? look.brand.map((c, i) => cleanHex(c, t.colors[i])) : t.colors;
            return (
              <button key={t.key} type="button" onClick={() => set({ theme: t.key })} title={tx(t.label)}
                className={cn('flex items-center gap-1.5 rounded-full border px-2 py-1 text-xs', look.theme === t.key ? 'border-primary ring-2 ring-primary/30' : 'hover:bg-muted')}>
                <span className="flex overflow-hidden rounded-full border">{colors.map((c, i) => <span key={i} className="h-3.5 w-3" style={{ background: `#${c}` }} />)}</span>
                {tx(t.label)}
              </button>
            );
          })}
        </div>
      </div>
      {look.theme === 'brand' && (
        <div className="flex items-end gap-2">
          {([tx('Primary'), tx('Secondary'), tx('Accent')] as const).map((label, i) => (
            <label key={i} className="space-y-1 text-xs">
              <span className="block text-muted-foreground">{label}</span>
              <input type="color" className="h-8 w-12 cursor-pointer rounded border bg-transparent" value={`#${cleanHex(look.brand[i], THEMES[THEMES.length - 1].colors[i])}`}
                onChange={(e) => { const b = [...look.brand] as DeckLook['brand']; b[i] = e.target.value.slice(1).toUpperCase(); set({ brand: b }); }} />
            </label>
          ))}
        </div>
      )}
      <div className="space-y-1.5">
        <span className="text-xs font-medium text-muted-foreground">{tx('Layout style')}</span>
        <div className="flex gap-1.5">
          {STYLES.map((s) => (
            <button key={s.key} type="button" title={tx(s.hint)} onClick={() => set({ style: s.key })}
              className={cn('rounded-full border px-3 py-1 text-xs', look.style === s.key ? 'border-primary bg-primary text-primary-foreground' : 'hover:bg-muted')}>{tx(s.label)}</button>
          ))}
        </div>
      </div>
      <label className="space-y-1.5 text-xs">
        <span className="block font-medium text-muted-foreground">{tx('Font')}</span>
        <select className="h-8 rounded-md border bg-background px-2 text-sm" value={look.font} onChange={(e) => set({ font: e.target.value })}>
          {FONTS.map((f) => <option key={f} value={f} style={{ fontFamily: f }}>{f}</option>)}
        </select>
      </label>
    </div>
  );
}

/** Choose a layout for a new slide and where it goes. */
export function AddSlideDialog({ open, slides, defaultAfter, onClose, onAdd }: {
  open: boolean; slides: ReportSlide[]; defaultAfter: string; onClose: () => void; onAdd: (layout: CustomLayout, after: string) => void;
}) {
  const [after, setAfter] = useState(defaultAfter);
  if (!open) return null;
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Add a slide')}</DialogTitle>
          <DialogDescription>{tx('Pick a layout. You can fill it by typing, pasting data or images, or with ChatGPT.')}</DialogDescription>
        </DialogHeader>
        <label className="space-y-1 text-sm">
          <span className="text-xs text-muted-foreground">{tx('Insert after')}</span>
          <select className="h-9 w-full rounded-md border bg-background px-2" value={after} onChange={(e) => setAfter(e.target.value)}>
            {slides.map((s, i) => <option key={s.id} value={s.id}>{i + 1}. {s.label}</option>)}
            <option value="">{tx('At the end')}</option>
          </select>
        </label>
        <div className="grid grid-cols-2 gap-2">
          {CUSTOM_LAYOUTS.map((l) => (
            <Button key={l.key} variant="outline" className="h-auto justify-start whitespace-normal py-2 text-left text-xs" onClick={() => onAdd(l.key, after)}>
              <Plus className="mr-1 h-3.5 w-3.5 shrink-0" />{tx(l.label)}
            </Button>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Whole-deck round trip with ChatGPT: copy every included slide, paste back edits and new slides. */
export function DeckChatGptDialog({ open, slides, org, onClose, onApply }: {
  open: boolean; slides: ReportSlide[]; org: string; onClose: () => void; onApply: (r: DeckReply) => void;
}) {
  const [ask, setAsk] = useState('');
  const [reply, setReply] = useState('');
  const [error, setError] = useState<string | null>(null);
  if (!open) return null;
  const included = slides.filter((s) => s.on);
  const copy = async () => {
    try { await copyText(deckPrompt(included, ask, org)); notify.success(tx('Prompt copied'), tx('Paste it into ChatGPT, then paste the answer below.')); }
    catch { notify.error(tx('Could not copy to the clipboard')); }
  };
  const apply = () => {
    try {
      const r = parseDeckReply(reply, slides);
      onApply(r);
      notify.success(tx('ChatGPT changes applied'),
        `${Object.keys(r.edits).length} ${tx('slides changed')}, ${r.added.length} ${tx('slides added')}${r.skipped.length ? `, ${r.skipped.length} ${tx('ignored')}` : ''}`);
      setReply('');
      setError(null);
      onClose();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  };
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Sparkles className="h-4 w-4" />{tx('Edit the whole deck with ChatGPT')}</DialogTitle>
          <DialogDescription>
            {tx('The prompt contains the text, tables and chart numbers of the included slides (no images). Nothing is sent automatically — you paste it into ChatGPT yourself.')}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-1">
          <Label className="text-xs">{tx('What should ChatGPT do?')}</Label>
          <Textarea rows={3} value={ask} onChange={(e) => setAsk(e.target.value)}
            placeholder={tx('e.g. Make the commentary suitable for the board, and add a slide with three marketing ideas for next month')} />
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={copy} disabled={!included.length}><Copy className="mr-1 h-4 w-4" />{tx('Copy prompt')} ({included.length} {tx('slides')})</Button>
          <Button variant="outline" asChild><a href={CHATGPT_URL} target="_blank" rel="noopener noreferrer"><ExternalLink className="mr-1 h-4 w-4" />{tx('Open ChatGPT')}</a></Button>
        </div>
        <div className="space-y-1">
          <Label className="text-xs">{tx('ChatGPT\'s reply')}</Label>
          <Textarea rows={8} className="font-mono text-xs" value={reply} onChange={(e) => { setReply(e.target.value); setError(null); }} placeholder='{"slides": [...], "newSlides": [...]}' />
          {error && <p className="text-xs text-rose-600">{error}</p>}
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={apply} disabled={!reply.trim()}><Sparkles className="mr-1 h-4 w-4" />{tx('Apply reply')}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
