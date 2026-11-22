import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckCircle2, Loader2, Send } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { getApiBaseUrl } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

/** Hosted website lead form (CRM enhancements T3a): a page a company can link to instead of embedding the form. */
export function PublicLeadFormPage() {
  const { formKey = '' } = useParams();
  const base = `${getApiBaseUrl()}/public/crm/forms/${encodeURIComponent(formKey)}`;
  const [info, setInfo] = useState<{ name: string; company: string; thankYouMessage: string } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [values, setValues] = useState({ name: '', email: '', phone: '', company: '', message: '', _hp: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    fetch(base, { credentials: 'omit' })
      .then(async (r) => { const b = await r.json().catch(() => ({})); if (!r.ok) throw new Error(b.message || tx('This form is not available.')); setInfo(b); })
      .catch((e) => setLoadError(e instanceof Error ? e.message : String(e)));
  }, [base]);

  const set = (k: keyof typeof values) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setValues((v) => ({ ...v, [k]: e.target.value }));
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== ''));
      const r = await fetch(`${base}/submit`, { method: 'POST', credentials: 'omit', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...body, pageUrl: window.location.href }) });
      const out = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(r.status === 429 ? tx('Too many attempts. Please wait a minute and try again.') : out.message || tx('Something went wrong. Please try again.'));
      setDone(out.message);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="min-h-screen bg-muted/30 px-4 py-10">
      <div className="mx-auto max-w-lg rounded-xl border bg-card p-6 shadow-sm">
        {loadError && <p className="text-center text-sm">{loadError}</p>}
        {!info && !loadError && <div className="flex justify-center p-10"><Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label={tx('Loading')} /></div>}
        {info && done && (
          <div className="py-8 text-center">
            <CheckCircle2 className="mx-auto mb-3 h-10 w-10 text-emerald-600" aria-hidden />
            <p className="font-medium">{done}</p>
          </div>
        )}
        {info && !done && (
          <form className="space-y-3" onSubmit={submit}>
            <div>
              <p className="text-xs uppercase tracking-wide text-muted-foreground">{info.company}</p>
              <h1 className="text-xl font-semibold">{info.name}</h1>
            </div>
            <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Your name')} *</span><Input required maxLength={120} autoComplete="name" value={values.name} onChange={set('name')} /></label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Email')}</span><Input type="email" maxLength={254} autoComplete="email" value={values.email} onChange={set('email')} /></label>
              <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Phone')}</span><Input type="tel" maxLength={40} autoComplete="tel" value={values.phone} onChange={set('phone')} /></label>
            </div>
            <label className="block text-sm"><span className="mb-1 block font-medium">{tx('Company')}</span><Input maxLength={200} autoComplete="organization" value={values.company} onChange={set('company')} /></label>
            <label className="block text-sm"><span className="mb-1 block font-medium">{tx('How can we help?')}</span><Textarea rows={4} maxLength={5000} value={values.message} onChange={set('message')} /></label>
            <input name="_hp" tabIndex={-1} autoComplete="off" aria-hidden="true" className="absolute -left-[9999px] h-px w-px opacity-0" value={values._hp} onChange={set('_hp')} />
            <p className="text-xs text-muted-foreground">{tx('Please give an email address or a phone number so we can reply.')}</p>
            {error && <p role="alert" className="text-sm text-rose-600">{error}</p>}
            <Button type="submit" className="w-full" disabled={busy || !values.name.trim() || (!values.email.trim() && !values.phone.trim())}>
              {busy ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <Send className="mr-1.5 h-4 w-4" />}{tx('Send')}
            </Button>
          </form>
        )}
      </div>
    </div>
  );
}
