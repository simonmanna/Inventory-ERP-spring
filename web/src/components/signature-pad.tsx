import { forwardRef, useEffect, useImperativeHandle, useRef, type PointerEvent } from 'react';
import { Eraser } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { tx } from '@/lib/i18n/tx';

export interface SignaturePadHandle { dataUrl: () => string | null }

/** Finger / mouse signature on a canvas (pointer events); `dataUrl()` = PNG data URL, or null when empty. */
export const SignaturePad = forwardRef<SignaturePadHandle>(function SignaturePad(_props, ref) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);
  const drawn = useRef(false);

  useEffect(() => {
    const c = canvas.current;
    if (!c) return;
    const ratio = window.devicePixelRatio || 1;
    c.width = c.offsetWidth * ratio;
    c.height = c.offsetHeight * ratio;
    const ctx = c.getContext('2d');
    if (ctx) {
      ctx.scale(ratio, ratio);
      ctx.lineWidth = 2.2;
      ctx.lineCap = 'round';
      ctx.strokeStyle = '#111827';
    }
  }, []);

  const point = (e: PointerEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };

  useImperativeHandle(ref, () => ({
    dataUrl: () => (drawn.current && canvas.current ? canvas.current.toDataURL('image/png') : null),
  }), []);

  const clear = () => {
    const c = canvas.current;
    const ctx = c?.getContext('2d');
    if (c && ctx) ctx.clearRect(0, 0, c.width, c.height);
    drawn.current = false;
  };

  return (
    <div className="space-y-1">
      <canvas
        ref={canvas}
        className="h-40 w-full touch-none rounded-lg border bg-white"
        onPointerDown={(e) => {
          drawing.current = true;
          e.currentTarget.setPointerCapture(e.pointerId);
          const ctx = e.currentTarget.getContext('2d');
          const p = point(e);
          ctx?.beginPath();
          ctx?.moveTo(p.x, p.y);
        }}
        onPointerMove={(e) => {
          if (!drawing.current) return;
          const ctx = e.currentTarget.getContext('2d');
          const p = point(e);
          ctx?.lineTo(p.x, p.y);
          ctx?.stroke();
          drawn.current = true;
        }}
        onPointerUp={() => { drawing.current = false; }}
        onPointerLeave={() => { drawing.current = false; }}
      />
      <Button type="button" variant="ghost" size="sm" onClick={clear}><Eraser className="mr-1 h-4 w-4" /> {tx('Clear signature')}</Button>
    </div>
  );
});
