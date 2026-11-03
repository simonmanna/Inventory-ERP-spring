import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { ImagePlus } from 'lucide-react';
import { C, CSS_FONT, G, type Box } from './look';
import { money2, type ReportSlide, type SlideTable } from './report';

/** HTML replica of one report slide on a 1280×720 canvas (same geometry the PPTX exporter uses). */

const hex = (c: string) => `#${c}`;
const at = (b: Box, extra: CSSProperties = {}): CSSProperties => ({ position: 'absolute', left: b.x, top: b.y, width: b.w, height: b.h, ...extra });
const shadow = '0 3px 5px rgba(0,0,0,.28)';

function Rect({ b, color, style }: { b: Box; color: string; style?: CSSProperties }) {
  return <div style={at(b, { background: hex(color), ...style })} />;
}

function Text({ b, children, style }: { b: Box; children: ReactNode; style?: CSSProperties }) {
  return <div style={at(b, { display: 'flex', alignItems: 'center', whiteSpace: 'pre-line', overflow: 'hidden', ...style })}>{children}</div>;
}

function Band({ s, h = G.band.h, color = C.navyBand }: { s: ReportSlide; h?: number; color?: string }) {
  return (
    <>
      <Rect b={{ ...G.band, h }} color={color} style={{ boxShadow: shadow }} />
      <Text b={G.bandTitle} style={{ color: '#fff', fontSize: 32, fontWeight: 700 }}>{s.title}</Text>
      {s.subtitle && <Text b={G.bandSub} style={{ color: hex(C.muted), fontSize: 17 }}>{s.subtitle}</Text>}
    </>
  );
}

const TopBar = () => <Rect b={G.topBar} color={C.navy} style={{ boxShadow: shadow }} />;

function Pill({ b, children, size = 28, color = C.navy, bg = C.pill, align = 'flex-start' }: { b: Box; children: ReactNode; size?: number; color?: string; bg?: string; align?: string }) {
  return (
    <Text b={b} style={{ background: hex(bg), borderRadius: 10, boxShadow: shadow, padding: '0 14px', fontSize: size, fontWeight: 700, color: hex(color), justifyContent: align }}>
      {children}
    </Text>
  );
}

function Card({ b, children, bg = C.panel, line = C.panelLine, radius = 40, style }: { b: Box; children: ReactNode; bg?: string; line?: string; radius?: number; style?: CSSProperties }) {
  return <div style={at(b, { background: hex(bg), border: `1px solid ${hex(line)}`, borderRadius: radius, boxShadow: '0 4px 6px rgba(0,0,0,.25)', ...style })}>{children}</div>;
}

interface TableLook {
  head: string; headInk?: string; headSize: number; headH: number; size: number; firstSize?: number; rowH: number;
  zebra?: string; firstBold?: boolean; bodyBold?: boolean; align?: Array<'left' | 'center'>; totalBg?: string; totalSize?: number;
}

function Table({ t, x, y, cols, look }: { t: SlideTable; x: number; y: number; cols: readonly number[]; look: TableLook }) {
  const cell = (i: number): CSSProperties => ({
    width: cols[i], padding: '4px 10px', textAlign: look.align?.[i] ?? 'left', verticalAlign: 'middle', whiteSpace: 'pre-line',
    borderRight: i < cols.length - 1 ? '1px solid #fff' : undefined,
  });
  return (
    <table style={{ position: 'absolute', left: x, top: y, width: cols.reduce((a, b) => a + b, 0), borderCollapse: 'collapse', tableLayout: 'fixed' }}>
      <thead>
        <tr style={{ height: look.headH, background: hex(look.head), color: hex(look.headInk ?? C.white), fontSize: look.headSize, fontWeight: 700 }}>
          {t.header.map((h, i) => <th key={i} style={cell(i)}>{h}</th>)}
        </tr>
      </thead>
      <tbody>
        {t.rows.map((r, ri) => (
          <tr key={ri} style={{ height: look.rowH, background: ri % 2 === 1 ? hex(look.zebra ?? C.zebra) : '#fff', color: hex(C.ink), fontSize: look.size, fontWeight: look.bodyBold ? 700 : 400 }}>
            {r.map((v, i) => (
              <td key={i} style={{ ...cell(i), borderRight: i < cols.length - 1 ? `1px solid ${ri % 2 ? '#fff' : hex(C.zebra)}` : undefined, fontWeight: i === 0 && look.firstBold !== false ? 700 : undefined, fontSize: i === 0 && look.firstSize ? look.firstSize : undefined }}>{v}</td>
            ))}
          </tr>
        ))}
        {t.total && (
          <tr style={{ height: look.rowH, background: hex(look.totalBg ?? C.total), color: '#fff', fontSize: look.totalSize ?? 16, fontWeight: 700 }}>
            {t.total.map((v, i) => <td key={i} style={cell(i)}>{v}</td>)}
          </tr>
        )}
      </tbody>
    </table>
  );
}

function ImageSlot({ b, src, dark }: { b: Box; src?: string; dark?: boolean }) {
  if (src) return <img src={src} alt="" style={at(b, { objectFit: 'cover' })} />;
  return (
    <div style={at(b, { border: `3px dashed ${dark ? '#64748b' : '#94a3b8'}`, background: dark ? '#0f172a' : '#f8fafc', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#94a3b8', fontSize: 18, gap: 8 })}>
      <ImagePlus size={28} /> Photo
    </div>
  );
}

// ---------------------------------------------------------------- charts

const niceMax = (v: number) => {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  const n = v / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p;
};

/** Flat column chart on a grey radial-gradient chart area (the reference deck's Excel style). */
function BarChart({ b, s }: { b: Box; s: ReportSlide }) {
  const ch = s.chart!;
  const max = niceMax(Math.max(...ch.values, 0));
  const plot = { x: 50, y: 62, w: b.w - 70, h: b.h - 165 };
  const showValues = ch.values.length <= 4;
  return (
    <div style={at(b, { background: 'radial-gradient(circle at 50% 30%, #ffffff 0%, #e6e6e6 55%, #bfbfbf 100%)', border: '1px solid #bfbfbf' })}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 12, textAlign: 'center', fontSize: 22, fontWeight: 700, color: '#404040' }}>{ch.title}</div>
      <div style={{ position: 'absolute', left: 8, top: plot.y, height: plot.h, width: 20, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ transform: 'rotate(-90deg)', whiteSpace: 'nowrap', fontSize: 10, fontWeight: 700, color: '#404040' }}>{ch.yTitle}</span>
      </div>
      <div style={{ position: 'absolute', left: plot.x, top: plot.y, width: plot.w, height: plot.h, borderBottom: '2px solid #404040' }}>
        {[1, 2, 3, 4, 5, 6, 7].map((i) => <div key={i} style={{ position: 'absolute', left: 0, right: 0, top: `${(i / 7) * 100 - 100 / 7}%`, borderTop: '1px solid #a6a6a6' }} />)}
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'flex-end' }}>
          {ch.values.map((v, i) => (
            <div key={i} style={{ flex: 1, height: '100%', display: 'flex', alignItems: 'flex-end', justifyContent: 'center' }}>
              <div style={{ width: '60%', height: `${(v / max) * 100}%`, background: hex(C.chartBar), opacity: 0.85, position: 'relative' }}>
                {showValues && <span style={{ position: 'absolute', top: 6, left: 0, right: 0, textAlign: 'center', color: '#fff', fontSize: 11, fontWeight: 700 }}>{money2(v)}</span>}
              </div>
            </div>
          ))}
        </div>
      </div>
      <div style={{ position: 'absolute', left: plot.x, top: plot.y + plot.h + 6, width: plot.w, display: 'flex' }}>
        {ch.labels.map((l, i) => <div key={i} style={{ flex: 1, textAlign: 'center', fontSize: 11, color: '#404040', padding: '0 2px', lineHeight: 1.3 }}>{l}</div>)}
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 48, textAlign: 'center', fontSize: 11, fontWeight: 700, color: '#404040' }}>{ch.xTitle}</div>
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 10, display: 'flex', justifyContent: 'center' }}>
        <span style={{ background: '#ebebeb', padding: '3px 12px', fontSize: 11, color: '#404040' }}><span style={{ display: 'inline-block', width: 7, height: 7, background: hex(C.chartBar), marginRight: 5 }} />{ch.series}</span>
      </div>
    </div>
  );
}

/** Three 3-D columns (blue / red / green), one per period. */
function Bar3D({ b, s }: { b: Box; s: ReportSlide }) {
  const ch = s.chart!;
  const max = niceMax(Math.max(...ch.values, 0));
  const plot = { x: 130, y: 20, w: b.w - 150, h: b.h - 140 };
  const ticks = [0, 1, 2, 3, 4, 5];
  return (
    <div style={at(b)}>
      {ticks.map((t) => (
        <div key={t} style={{ position: 'absolute', left: 0, top: plot.y + plot.h - (t / 5) * plot.h - 9, width: plot.x - 12, textAlign: 'right', fontSize: 14, color: '#1F3864' }}>
          {money2((max / 5) * t).replace(/\.00$/, max >= 1e5 ? '.00' : '')}
        </div>
      ))}
      <div style={{ position: 'absolute', left: plot.x, top: plot.y, width: plot.w, height: plot.h }}>
        {ticks.map((t) => <div key={t} style={{ position: 'absolute', left: 0, right: 0, bottom: `${(t / 5) * 100}%`, borderTop: '1px solid #dbe5f1' }} />)}
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'flex-end', gap: 0, paddingRight: 70 }}>
          {ch.values.map((v, i) => {
            const c = C.bars3d[i % 3];
            return (
              <div key={i} style={{ flex: 1, height: '100%', display: 'flex', alignItems: 'flex-end', justifyContent: 'center' }}>
                <div style={{ position: 'relative', width: 30, height: `${Math.max(0.5, (v / max) * 100)}%` }}>
                  <div style={{ position: 'absolute', inset: 0, background: `linear-gradient(90deg, ${hex(c)}cc, ${hex(c)})` }} />
                  <div style={{ position: 'absolute', top: 0, bottom: 0, left: '100%', width: 9, background: hex(c), filter: 'brightness(.65)', transform: 'skewY(-40deg)', transformOrigin: 'left top' }} />
                  <div style={{ position: 'absolute', left: 0, bottom: '100%', width: 30, height: 8, background: hex(c), filter: 'brightness(.8)', transform: 'skewX(-50deg)', transformOrigin: 'left bottom' }} />
                </div>
              </div>
            );
          })}
        </div>
      </div>
      <div style={{ position: 'absolute', left: plot.x, top: plot.y + plot.h + 10, width: plot.w - 70, display: 'flex' }}>
        {ch.labels.map((l, i) => <div key={i} style={{ flex: 1, textAlign: 'center', fontSize: 14, color: '#1F3864', lineHeight: 1.35, padding: '0 4px' }}>{l}</div>)}
      </div>
      <div style={{ position: 'absolute', left: plot.x, width: plot.w - 70, top: b.h - 40, display: 'flex', justifyContent: 'center', gap: 16, fontSize: 14, color: '#1F3864' }}>
        <span><span style={{ display: 'inline-block', width: 9, height: 9, background: hex(C.bars3d[0]), marginRight: 5 }} />{ch.series}</span>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- slides

function body(s: ReportSlide, n: number | null): ReactNode {
  switch (s.kind) {
    case 'cover': {
      const g = G.cover;
      const [logo, photo] = s.images ?? [];
      return (
        <>
          {photo && <img src={photo} alt="" style={at(g.photo, { objectFit: 'cover' })} />}
          <div style={at(g.logo, { background: '#000', display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column' })}>
            {logo ? <img src={logo} alt="" style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} /> : (
              <>
                <span style={{ fontFamily: 'Georgia, serif', fontSize: 50, fontWeight: 700, letterSpacing: 8, color: hex(C.gold), textAlign: 'center', lineHeight: 1.05 }}>{s.meta?.logoText}</span>
                {s.meta?.logoSub && <span style={{ fontFamily: 'Georgia, serif', fontSize: 30, fontWeight: 700, letterSpacing: 18, color: hex(C.gold) }}>{s.meta.logoSub}</span>}
              </>
            )}
          </div>
          <Text b={g.title} style={{ justifyContent: 'center', textAlign: 'center', fontFamily: 'Arial, sans-serif', fontSize: 30, fontWeight: 700, color: '#000', whiteSpace: 'pre' }}>{s.title}</Text>
          <Rect b={{ x: 0, y: 645, w: 1280, h: 75 }} color={C.white} />
        </>
      );
    }
    case 'summary': {
      const g = G.summary;
      const kv: Array<[string, string]> = [['Submitted by:', s.meta?.submittedBy ?? ''], ['Role:', s.meta?.role ?? ''], ['Report date:', s.meta?.reportDate ?? '']];
      return (
        <>
          <Rect b={g.band} color={C.navySummary} />
          <Text b={g.title} style={{ color: '#fff', fontSize: 25, fontWeight: 700, fontFamily: 'Arial, sans-serif' }}>{s.title}</Text>
          <Rect b={g.strip} color={C.cyan} style={{ borderTop: `2px solid ${hex(C.orange)}`, borderBottom: `2px solid ${hex(C.orange)}` }} />
          <div style={{ position: 'absolute', left: g.kv.x, top: g.kv.y, width: g.kv.w, boxShadow: shadow, fontFamily: 'Arial, sans-serif' }}>
            {kv.map(([k, v]) => (
              <div key={k} style={{ display: 'flex', height: g.kvRow, borderBottom: '1px solid #d6d6d6' }}>
                <div style={{ width: g.kvCols[0], background: hex(C.grey), color: hex(C.keyLabel), fontSize: 23, fontWeight: 700, padding: '0 10px', display: 'flex', alignItems: 'center', borderRight: '1px solid #d6d6d6' }}>{k}</div>
                <div style={{ flex: 1, background: '#fff', fontSize: 18, padding: '0 10px', display: 'flex', alignItems: 'center', color: '#232323' }}>{v}</div>
              </div>
            ))}
          </div>
          <Text b={g.heading} style={{ background: hex(C.navySummary), color: '#fff', fontSize: 24, fontWeight: 700, padding: '0 24px', fontFamily: 'Arial, sans-serif', boxShadow: shadow }}>{s.heading}</Text>
          {s.table && (
            <div style={{ fontFamily: 'Arial, sans-serif' }}>
              <Table t={s.table} x={g.table.x} y={g.table.y} cols={g.cols} look={{ head: C.tan, headSize: 13, headH: 25, size: 13, rowH: 32, zebra: C.grey, align: ['left', 'center', 'center', 'left'] }} />
            </div>
          )}
        </>
      );
    }
    case 'catTable': {
      const g = G.catTable;
      return (
        <>
          <TopBar />
          <Text b={g.title} style={{ fontSize: 32, fontWeight: 700, color: hex(C.navyTable) }}>{s.title}</Text>
          <Text b={g.sub} style={{ fontSize: 14, color: hex(C.muted) }}>{s.subtitle}</Text>
          {s.table && <Table t={s.table} x={g.table.x} y={g.table.y} cols={g.cols} look={{ head: C.navyTable, headSize: 13, headH: 44, size: 14, rowH: Math.min(46, Math.floor(380 / Math.max(1, s.table.rows.length))), align: ['left', 'center', 'center', 'center'] }} />}
          <div style={{ position: 'absolute', left: 88, top: g.table.y + 44 + (s.table ? (s.table.rows.length + 1) * Math.min(46, Math.floor(380 / Math.max(1, s.table.rows.length))) : 0) + 10, fontSize: 14 }}>{s.note}</div>
        </>
      );
    }
    case 'catChart': {
      const g = G.catChart;
      const withText = !!s.text.trim();
      return (
        <>
          <Rect b={g.band} color={C.navy} />
          <Rect b={g.strip} color={C.blue} style={{ boxShadow: shadow }} />
          <Text b={g.title} style={{ color: '#fff', fontSize: 24, fontWeight: 700 }}>{s.title}</Text>
          <Text b={g.sub} style={{ color: '#fff', fontSize: 12, whiteSpace: 'nowrap' }}>{s.subtitle}</Text>
          <BarChart b={withText ? g.chartWithText : g.chartWide} s={s} />
          {withText && (
            <>
              <Rect b={g.divider} color={C.bar} style={{ boxShadow: shadow }} />
              <Card b={g.card}>
                <div style={{ padding: '30px 34px', textAlign: 'center' }}>
                  <div style={{ fontSize: 17, fontWeight: 700, color: hex(C.link), textDecoration: 'underline', marginBottom: 8 }}>{s.heading}</div>
                  <div style={{ fontSize: 17, fontWeight: 700, fontStyle: 'italic', whiteSpace: 'pre-line', fontFamily: 'Arial, sans-serif' }}>{s.text}</div>
                </div>
              </Card>
            </>
          )}
          <Rect b={g.bottom} color={C.bar} style={{ boxShadow: shadow }} />
        </>
      );
    }
    case 'catCompare': {
      const g = G.catCompare;
      return (
        <>
          <TopBar />
          <Pill b={g.titlePill} size={28}>{s.title}</Pill>
          <Pill b={g.headPill} size={28} align="center">{s.heading}</Pill>
          <Bar3D b={g.chart} s={s} />
          <Rect b={g.divider} color={C.bar} style={{ boxShadow: shadow }} />
          <Card b={g.card}>
            <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '20px 40px', textAlign: 'center', fontSize: 17, fontWeight: 700, fontStyle: 'italic', whiteSpace: 'pre-line' }}>{s.text}</div>
          </Card>
          <Rect b={g.bottom} color={C.bar} style={{ boxShadow: shadow }} />
          <Text b={g.note} style={{ fontSize: 12, color: hex(C.muted) }}>{s.note}</Text>
        </>
      );
    }
    case 'lost': {
      const g = G.lost;
      return (
        <>
          <Rect b={{ ...G.band, h: 110 }} color={C.navyBand} style={{ boxShadow: shadow }} />
          <Text b={{ x: 50, y: 25, w: 1100, h: 62 }} style={{ color: '#fff', fontSize: 32, fontWeight: 700 }}>{s.title}</Text>
          <Text b={g.sub} style={{ fontSize: 18, color: hex(C.muted) }}>{s.subtitle}</Text>
          {s.table && <Table t={s.table} x={g.table.x} y={g.table.y} cols={g.cols} look={{ head: C.brown, headSize: 19, headH: 75, size: 18, firstSize: 18, rowH: 76 }} />}
        </>
      );
    }
    case 'visits': {
      const g = G.visits;
      return (
        <>
          <TopBar />
          <Pill b={g.titlePill} size={27}>{n != null ? s.title : s.title}</Pill>
          <Pill b={g.headPill} size={18} color={C.black}>{s.heading}</Pill>
          {(s.list ?? []).slice(0, 5).map((v, i) => (
            <Text key={i} b={{ ...g.item, y: g.item.y + i * g.itemStep }} style={{ background: hex(C.panel), border: `1px solid ${hex(C.panelLine)}`, borderRadius: 8, boxShadow: shadow, padding: '0 28px', fontSize: 16, fontWeight: 700 }}>{v}</Text>
          ))}
          {g.images.map((b, i) => <ImageSlot key={i} b={b} src={s.images?.[i]} />)}
        </>
      );
    }
    case 'market':
    case 'progress':
    case 'plan': {
      const g = s.kind === 'market' ? G.market : s.kind === 'progress' ? G.progress : G.plan;
      const look: TableLook = s.kind === 'plan'
        ? { head: C.brown, headSize: 15, headH: 69, size: 13, firstSize: 17, rowH: 72, bodyBold: true }
        : { head: C.brown, headSize: 19, headH: s.kind === 'market' ? 88 : 84, size: 13, firstSize: 14, rowH: s.kind === 'market' ? 90 : 70, bodyBold: true };
      return (
        <>
          <Band s={s} />
          {s.table && <Table t={s.table} x={g.table.x} y={g.table.y} cols={g.cols} look={look} />}
          {s.kind === 'progress' && (
            <Card b={G.progress.key} bg={C.greenBg} line={C.greenLine} radius={18}>
              <div style={{ padding: '26px 38px', fontSize: 19, fontWeight: 700, color: hex(C.green) }}>{s.heading}&nbsp;&nbsp;|&nbsp;&nbsp;{s.text}</div>
            </Card>
          )}
          {s.note && (
            <Text b={s.kind === 'progress' ? G.progress.note : s.kind === 'plan' ? G.plan.note : { x: 69, y: 655, w: 1100, h: 26 }}
              style={{ fontSize: s.kind === 'plan' ? 17 : 13, fontStyle: 'italic', color: hex(C.muted) }}>{s.note}</Text>
          )}
        </>
      );
    }
    case 'digital':
    case 'objectives': {
      const g = s.kind === 'digital' ? G.digital : G.objectives;
      return (
        <>
          <Band s={s} />
          {(s.cards ?? []).slice(0, 4).map((c, i) => (
            <Card key={i} b={g.cards[i]} bg={C.white} line={C.cardLine} radius={28}>
              <div style={{ padding: '18px 30px' }}>
                <div style={{ fontSize: 19, fontWeight: 700, color: hex(C.navyText), marginBottom: 8 }}>{c.title}</div>
                <div style={{ fontSize: 15, color: hex(C.ink), lineHeight: 1.4 }}>{c.body}</div>
              </div>
            </Card>
          ))}
          {s.kind === 'digital' && <ImageSlot b={G.digital.image} src={s.images?.[0]} dark />}
          <Text b={g.note} style={s.kind === 'digital' ? { fontSize: 17, fontStyle: 'italic', color: hex(C.muted) } : { fontSize: 17, fontWeight: 700, color: hex(C.green) }}>{s.note}</Text>
        </>
      );
    }
    case 'results': {
      const g = G.results;
      return (
        <>
          <Band s={s} />
          <Card b={g.card} bg={C.greenBg} line={C.greenLine} radius={50}><span /></Card>
          <Text b={g.heading} style={{ fontSize: 21, fontWeight: 700, color: hex(C.green) }}>{s.heading}</Text>
          <div style={at(g.text, { fontSize: 18, color: hex(C.ink), lineHeight: 1.45 })}>
            {s.text.split('\n').filter(Boolean).map((p, i) => <p key={i} style={{ margin: '0 0 12px' }}>{p}</p>)}
          </div>
          <Text b={g.actHead} style={{ fontSize: 21, fontWeight: 700, color: hex(C.navyText) }}>NEXT ACTIONS</Text>
          {(s.list ?? []).map((v, i) => (
            <Text key={i} b={{ ...g.act, y: g.act.y + i * g.act.h }} style={{ fontSize: 17, color: hex(C.ink) }}>•&nbsp;{v}</Text>
          ))}
          <Text b={g.note} style={{ fontSize: 13, fontStyle: 'italic', color: hex(C.muted) }}>{s.note}</Text>
        </>
      );
    }
  }
}

export function SlideCanvas({ slide, n = null }: { slide: ReportSlide; n?: number | null }) {
  return (
    <div style={{ position: 'relative', width: 1280, height: 720, background: slide.kind === 'catTable' ? '#F5F7FA' : '#fff', overflow: 'hidden', fontFamily: CSS_FONT, color: '#000' }}>
      {body(slide, n)}
    </div>
  );
}

/** Scales the 1280×720 canvas to the container width. */
export function ScaledSlide({ slide, n }: { slide: ReportSlide; n?: number | null }) {
  const ref = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(0.2);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setScale(e.contentRect.width / 1280));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return (
    <div ref={ref} className="relative aspect-video w-full overflow-hidden">
      <div style={{ transform: `scale(${scale})`, transformOrigin: 'top left', position: 'absolute', left: 0, top: 0 }}>
        <SlideCanvas slide={slide} n={n} />
      </div>
    </div>
  );
}
