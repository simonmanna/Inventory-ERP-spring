import { useCallback, useRef, useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { tx } from '@/lib/i18n/tx';

export interface AskOptions {
  title: string;
  description?: ReactNode;
  /** Label of the text field; omit `input` (or pass 'none') for a plain confirmation. */
  label?: string;
  input?: 'text' | 'textarea' | 'number' | 'select' | 'none';
  /** Choices for input 'select': [value, label]. */
  options?: Array<[string, string]>;
  defaultValue?: string;
  placeholder?: string;
  confirmLabel?: string;
  destructive?: boolean;
  /** A text answer must not be blank (default true when there is an input). */
  required?: boolean;
}

interface Pending extends AskOptions {
  resolve: (v: string | null) => void;
}

/**
 * Sales readiness X-8: an in-app replacement for window.prompt / window.confirm (which viewers and many browsers
 * block). `ask(...)` resolves with the typed answer (an empty string for a plain confirmation) or null when dismissed;
 * render the returned `dialog` once in the component.
 */
export function useAsk(): { ask: (o: AskOptions) => Promise<string | null>; dialog: ReactNode } {
  const [pending, setPending] = useState<Pending | null>(null);
  const [value, setValue] = useState('');
  const ref = useRef<Pending | null>(null);

  const ask = useCallback((o: AskOptions) => new Promise<string | null>((resolve) => {
    ref.current?.resolve(null);
    const p = { ...o, resolve };
    ref.current = p;
    setValue(o.defaultValue ?? (o.input === 'select' ? o.options?.[0]?.[0] ?? '' : ''));
    setPending(p);
  }), []);

  const finish = (v: string | null) => {
    ref.current?.resolve(v);
    ref.current = null;
    setPending(null);
  };

  const kind = pending?.input ?? (pending?.label ? 'textarea' : 'none');
  const required = pending?.required ?? kind !== 'none';
  const blocked = required && !value.trim();

  const dialog = (
    <Dialog open={!!pending} onOpenChange={(o) => { if (!o) finish(null); }}>
      <DialogContent className="w-[calc(100vw-2rem)] max-w-md">
        <DialogHeader>
          <DialogTitle>{pending?.title}</DialogTitle>
          {pending?.description && <DialogDescription>{pending.description}</DialogDescription>}
        </DialogHeader>
        {kind !== 'none' && (
          <div className="space-y-1">
            {pending?.label && <label htmlFor="ask-input" className="text-sm font-medium">{pending.label}</label>}
            {kind === 'textarea' && <Textarea id="ask-input" rows={3} autoFocus value={value} placeholder={pending?.placeholder}
              onChange={(e) => setValue(e.target.value)} />}
            {(kind === 'text' || kind === 'number') && <Input id="ask-input" autoFocus type={kind === 'number' ? 'number' : 'text'}
              step="any" value={value} placeholder={pending?.placeholder} onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !blocked) finish(value.trim()); }} />}
            {kind === 'select' && (
              <select id="ask-input" className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={value}
                onChange={(e) => setValue(e.target.value)}>
                {pending?.options?.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            )}
          </div>
        )}
        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={() => finish(null)}>{tx('Back')}</Button>
          <Button variant={pending?.destructive ? 'destructive' : 'default'} disabled={blocked}
            onClick={() => finish(kind === 'none' ? '' : value.trim())}>
            {pending?.confirmLabel ?? tx('OK')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
  return { ask, dialog };
}
