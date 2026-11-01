import { useEffect, useRef, useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  DOCUMENT_KINDS,
  previewTemplate,
  useDeletePrintTemplate,
  usePrintTemplate,
  usePrintTemplates,
  useRestoreTemplateVersion,
  useSavePrintTemplate,
  useTemplateFields,
  useTemplateVersions,
} from '@/features/customization/api';
import { api } from '@/lib/api';
import { apiMessage } from '@/lib/notify';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';

const STARTER = `<h1>{{document.kindLabel}} {{document.number}}</h1>
<table width="100%">
  <tr>
    <td><strong>{{company.name}}</strong><br>{{company.address}}</td>
    <td align="right">{{partner.name}}<br>{{partner.address}}<br>{{document.issueDate}}</td>
  </tr>
</table>
<table width="100%" border="1" cellpadding="4">
  <tr><th>#</th><th>Description</th><th>Qty</th><th>Price</th><th>Total</th></tr>
  {{#lines}}
  <tr><td>{{no}}</td><td>{{description}}</td><td align="right">{{quantity}}</td>
    <td align="right">{{unitPrice}}</td><td align="right">{{total}}</td></tr>
  {{/lines}}
</table>
<p align="right">Subtotal {{document.subtotal}}<br>Tax {{document.tax}}<br>
  <strong>Total {{document.total}} {{document.currency}}</strong></p>
{{#document.notes}}<p>{{document.notes}}</p>{{/document.notes}}`;

const KIND_LABEL: Record<string, string> = {
  sales_invoice: 'Sales invoice', credit_note: 'Credit note', vendor_bill: 'Vendor bill',
  purchase_order: 'Purchase order', sales_order: 'Sales order', sales_quotation: 'Quotation',
};

/** Print-template designer: code editor with a field palette and live preview (ADR-S029 §3). */
export default function PrintTemplatesPage() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.printTemplate.manage);
  const [kind, setKind] = useState<string>('sales_invoice');
  const { data: templates } = usePrintTemplates(kind);
  const [selected, setSelected] = useState<string | 'new' | undefined>();

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Print templates')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Design how invoices, orders and quotations print.')}{' '}
          <code className="text-xs">{'{{document.number}}'}</code>{' · '}<code className="text-xs">{'{{#lines}}…{{/lines}}'}</code>
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <select className={SELECT} value={kind} onChange={(e) => { setKind(e.target.value); setSelected(undefined); }}>
          {DOCUMENT_KINDS.map((k) => <option key={k} value={k}>{tx(KIND_LABEL[k])}</option>)}
        </select>
        {(templates ?? []).map((t) => (
          <Button key={t.id} size="sm" variant={selected === t.id ? 'default' : 'outline'} onClick={() => setSelected(t.id)}>
            {t.name} {t.isDefault && <Badge variant="secondary" className="ml-1">{tx('Default')}</Badge>}
          </Button>
        ))}
        {canManage && <Button size="sm" variant="ghost" onClick={() => setSelected('new')}>+ {tx('New template')}</Button>}
      </div>
      {selected && <Designer key={`${kind}-${selected}`} kind={kind} id={selected === 'new' ? undefined : selected}
        canManage={canManage} onSaved={(id) => setSelected(id)} onDeleted={() => setSelected(undefined)} />}
    </div>
  );
}

function Designer({ kind, id, canManage, onSaved, onDeleted }: {
  kind: string; id?: string; canManage: boolean; onSaved: (id: string) => void; onDeleted: () => void;
}) {
  const { data: template } = usePrintTemplate(id);
  const { data: fields } = useTemplateFields(kind);
  const { data: versions } = useTemplateVersions(id);
  const save = useSavePrintTemplate();
  const restore = useRestoreTemplateVersion();
  const remove = useDeletePrintTemplate();
  const [name, setName] = useState('');
  const [content, setContent] = useState(STARTER);
  const [css, setCss] = useState('');
  const [paperSize, setPaperSize] = useState<'A4' | 'A5' | 'Letter'>('A4');
  const [orientation, setOrientation] = useState<'portrait' | 'landscape'>('portrait');
  const [isDefault, setIsDefault] = useState(false);
  const [recordId, setRecordId] = useState('');
  const [html, setHtml] = useState('');
  const [error, setError] = useState<string | null>(null);
  const editor = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (template) {
      setName(template.name);
      setContent(template.content ?? '');
      setCss(template.css ?? '');
      setPaperSize(template.paperSize);
      setOrientation(template.orientation);
      setIsDefault(template.isDefault);
    }
  }, [template]);

  const debounced = useDebouncedValue(JSON.stringify({ content, css, recordId, paperSize, orientation }), 500);
  useEffect(() => {
    const input = JSON.parse(debounced) as { content: string; css: string; recordId: string; paperSize: string; orientation: string };
    if (!input.content.trim()) return;
    let live = true;
    previewTemplate({ documentKind: kind, content: input.content, css: input.css, recordId: input.recordId || undefined,
      paperSize: input.paperSize, orientation: input.orientation })
      .then((r) => { if (live) { setHtml(r.html); setError(null); } })
      .catch((e) => { if (live) setError(apiMessage(e, tx('Preview failed'))); });
    return () => { live = false; };
  }, [debounced, kind]);

  const insert = (text: string) => {
    const el = editor.current;
    if (!el) { setContent((c) => c + text); return; }
    const start = el.selectionStart ?? content.length;
    const end = el.selectionEnd ?? content.length;
    setContent(content.slice(0, start) + text + content.slice(end));
    requestAnimationFrame(() => { el.focus(); el.selectionStart = el.selectionEnd = start + text.length; });
  };

  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{id ? tx('Edit template') : tx('New template')}
            {template && <span className="ml-2 text-xs font-normal text-muted-foreground">v{template.version}</span>}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-2 md:grid-cols-4">
            <div className="space-y-1 md:col-span-2">
              <Label>{tx('Name')}</Label>
              <Input value={name} onChange={(e) => setName(e.target.value)} disabled={!canManage} />
            </div>
            <div className="space-y-1">
              <Label>{tx('Paper')}</Label>
              <select className={`${SELECT} w-full`} value={paperSize} disabled={!canManage}
                onChange={(e) => setPaperSize(e.target.value as 'A4')}>
                <option>A4</option><option>A5</option><option>Letter</option>
              </select>
            </div>
            <div className="space-y-1">
              <Label>{tx('Orientation')}</Label>
              <select className={`${SELECT} w-full`} value={orientation} disabled={!canManage}
                onChange={(e) => setOrientation(e.target.value as 'portrait')}>
                <option value="portrait">{tx('Portrait')}</option><option value="landscape">{tx('Landscape')}</option>
              </select>
            </div>
          </div>
          <div className="space-y-1">
            <Label>{tx('Template (HTML)')}</Label>
            <textarea ref={editor} value={content} onChange={(e) => setContent(e.target.value)} disabled={!canManage}
              spellCheck={false} className="h-80 w-full rounded-md border border-input bg-background p-2 font-mono text-xs" />
          </div>
          <div className="space-y-1">
            <Label>{tx('Styles (CSS)')}</Label>
            <textarea value={css} onChange={(e) => setCss(e.target.value)} disabled={!canManage} spellCheck={false}
              className="h-24 w-full rounded-md border border-input bg-background p-2 font-mono text-xs" />
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={isDefault} disabled={!canManage} onChange={(e) => setIsDefault(e.target.checked)} />
            {tx('Default template for this document kind')}
          </label>
          {canManage && (
            <div className="flex flex-wrap gap-2">
              <Button disabled={save.isPending || !name.trim()} onClick={() => save.mutate(
                { id, documentKind: id ? undefined : kind, name, content, css, paperSize, orientation, isDefault },
                { onSuccess: (t) => onSaved((t as { id: string }).id) })}>{tx('Save')}</Button>
              {id && <Button variant="outline" onClick={() => remove.mutate(id, { onSuccess: onDeleted })}>{tx('Delete')}</Button>}
            </div>
          )}
          <div>
            <Label>{tx('Fields')}</Label>
            <div className="mt-1 flex max-h-48 flex-wrap gap-1 overflow-y-auto">
              {(fields ?? []).map((f) => (
                <button key={f.path} type="button" title={f.sample ?? ''} disabled={!canManage}
                  className="rounded border px-1.5 py-0.5 font-mono text-[11px] hover:bg-muted"
                  onClick={() => insert(f.section ? `{{#${f.path}}}\n{{/${f.path}}}` : `{{${f.path.replace(/^lines\[\]\./, '')}}}`)}>
                  {f.section ? `#${f.path}` : f.path}
                </button>
              ))}
            </div>
          </div>
          {versions && versions.length > 1 && canManage && id && (
            <div>
              <Label>{tx('Versions')}</Label>
              <div className="mt-1 flex flex-wrap gap-1">
                {versions.map((v) => (
                  <Button key={v.id} size="sm" variant="ghost" onClick={() => restore.mutate({ id, version: v.version })}>
                    v{v.version} · {String(v.createdAt).slice(0, 10)}
                  </Button>
                ))}
              </div>
            </div>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{tx('Preview')}</CardTitle>
          <div className="flex items-center gap-2">
            <Input value={recordId} onChange={(e) => setRecordId(e.target.value.trim())} placeholder={tx('Record id (blank = sample)')}
              className="h-8 w-64 text-xs" />
            {id && recordId && (
              <Button size="sm" variant="outline" onClick={async () => {
                try {
                  const res = await api.get(`/print-templates/render/${kind}/${recordId}`,
                    { params: { templateId: id, format: 'pdf' }, responseType: 'blob' });
                  window.open(URL.createObjectURL(res.data as Blob), '_blank');
                } catch (e) {
                  setError(apiMessage(e, tx('Preview failed')));
                }
              }}>PDF</Button>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {error && <p className="mb-2 text-sm text-destructive">{error}</p>}
          <iframe title={tx('Preview')} sandbox="" srcDoc={html} className="h-[720px] w-full rounded border bg-white" />
        </CardContent>
      </Card>
    </div>
  );
}
