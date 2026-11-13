// src/pages/LoginPage.tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import {
  BarChart3,
  Building2,
  Eye,
  EyeOff,
  Headphones,
  KeyRound,
  Loader2,
  Lock,
  LogIn,
  Mail,
  Settings,
  Store,
  ShieldCheck,
  User,
  Users,
  Zap,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { useTranslation } from 'react-i18next';
import { LanguageSwitcher } from '@/components/language-switcher';
import { tx } from '@/lib/i18n/tx';

// Corporate blue theme. The sign-in card is always light so it reads the same in
// light and dark mode; the backdrop is a deep navy with soft light sweeps.
const styles = `
  .login-root {
    background:
      radial-gradient(900px 600px at 78% 10%, hsl(214 95% 45% / 0.55), transparent 60%),
      radial-gradient(800px 700px at 10% 90%, hsl(222 90% 30% / 0.6), transparent 60%),
      linear-gradient(135deg, #061a44 0%, #0a2a6b 45%, #0b3d99 100%);
    color: #ffffff;
  }
  .login-sweep { animation: login-drift 18s ease-in-out infinite alternate; transform-origin: 70% 30%; }
  .login-card {
    background: linear-gradient(180deg, #ffffff 0%, #f6f9ff 100%);
    box-shadow: 0 40px 90px -30px rgba(2, 12, 40, 0.75), 0 0 0 1px rgba(255,255,255,0.6);
  }
  .login-input:focus-visible { box-shadow: 0 0 0 4px hsl(214 95% 55% / 0.15); }
  .login-btn { background: linear-gradient(90deg, #1d8bff 0%, #1463f3 55%, #0d47d9 100%); }
  .login-btn:hover:not(:disabled) { filter: brightness(1.07); }
  .login-in { animation: login-in 0.45s cubic-bezier(.2,.8,.2,1) both; }
  .login-skyline { mask-image: linear-gradient(to top, black 10%, transparent 95%);
    -webkit-mask-image: linear-gradient(to top, black 10%, transparent 95%); }
  @keyframes login-drift { from { transform: rotate(-2deg) scale(1); } to { transform: rotate(2deg) scale(1.05); } }
  @keyframes login-in { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
  @media (prefers-reduced-motion: reduce) { .login-sweep, .login-in { animation: none; } }
`;

interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  user: { id: string; email: string; firstName: string; lastName: string | null; roles: string[] };
  permissions: string[];
  organization?: { id: string; code: string; name: string; currencyCode: string; timezone: string };
}

type View = 'login' | 'mfa' | 'forgot' | 'forgot-sent';

const ORG_STORAGE_KEY = 'login.organizationCode';
const EMAIL_STORAGE_KEY = 'login.email';
const REMEMBER_STORAGE_KEY = 'login.remember';

const readStored = (key: string): string => {
  try {
    return localStorage.getItem(key) ?? '';
  } catch {
    return '';
  }
};
const writeStored = (key: string, value: string | null) => {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* storage unavailable */
  }
};

/**
 * Public demo build (VITE_DEMO_MODE=true): the sign-in card shows the demo
 * credentials and comes prefilled with them, and a bare username is accepted in
 * place of an email. Never set this on a deployment that holds real data.
 */
const DEMO_MODE = import.meta.env.VITE_DEMO_MODE === 'true';
const DEMO_LOGIN = { username: 'admin', password: 'admin' };
const DEMO_EMAIL_DOMAIN = 'demo.test';
/** The API signs in by email; in demo mode `admin` means `admin@demo.test`. */
const toLoginEmail = (value: string): string => {
  const v = value.trim();
  return DEMO_MODE && v && !v.includes('@') ? `${v}@${DEMO_EMAIL_DOMAIN}` : v;
};

const inputCls =
  'login-input h-12 rounded-xl border-slate-200 bg-white pl-11 text-[15px] text-slate-900 shadow-sm ' +
  'placeholder:text-slate-400 focus-visible:border-blue-500 focus-visible:ring-0 focus-visible:ring-offset-0';

export function LoginPage() {
  const { t } = useTranslation();
  const [view, setView] = useState<View>('login');
  // "Remember me" keeps the organization code and email on this device. It is on
  // by default, which matches the old behaviour of always remembering the org.
  const [remember, setRemember] = useState<boolean>(() => readStored(REMEMBER_STORAGE_KEY) !== 'false');
  // The organization code is a visible field. VITE_ORG_CODE (build time) only
  // prefills it; otherwise it starts empty, or with the last code used here.
  const [orgCode, setOrgCode] = useState<string>(
    () => (import.meta.env.VITE_ORG_CODE as string | undefined) ?? readStored(ORG_STORAGE_KEY),
  );
  const [email, setEmail] = useState(() => (DEMO_MODE ? DEMO_LOGIN.username : readStored(EMAIL_STORAGE_KEY)));
  const [password, setPassword] = useState(DEMO_MODE ? DEMO_LOGIN.password : '');
  const [mfaToken, setMfaToken] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const navigate = useNavigate();
  const setSession = useAuthStore((s) => s.setSession);

  const rememberDevice = () => {
    writeStored(REMEMBER_STORAGE_KEY, remember ? 'true' : 'false');
    writeStored(ORG_STORAGE_KEY, remember ? orgCode.trim() : null);
    writeStored(EMAIL_STORAGE_KEY, remember && !DEMO_MODE ? email.trim() : null);
  };

  const login = useMutation({
    mutationFn: async () =>
      (await api.post<LoginResponse>('/auth/login', { organizationCode: orgCode.trim(), email: toLoginEmail(email), password })).data,
    onSuccess: (data) => {
      rememberDevice();
      if ((data as any).requiresMfa) {
        setMfaToken((data as any).mfaToken);
        setView('mfa');
        return;
      }
      setSession(data);
      notify.success(tx('Welcome back, {{firstName}}', { firstName: data.user.firstName }));
      navigate('/');
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Sign in failed')),
  });

  const mfa = useMutation({
    mutationFn: async () =>
      (await api.post<LoginResponse>('/auth/mfa-login', { mfaToken, code: mfaCode })).data,
    onSuccess: (data) => {
      setSession(data);
      notify.success(tx('Welcome back, {{firstName}}', { firstName: data.user.firstName }));
      navigate('/');
    },
    onError: (e: any) => notify.error(e?.response?.data?.message ?? tx('Invalid MFA code')),
  });

  const forgot = useMutation({
    mutationFn: async () => (await api.post('/auth/forgot-password', { organizationCode: orgCode, email })).data,
    onSuccess: () => setView('forgot-sent'),
    onError: () => setView('forgot-sent'),
  });

  const year = new Date().getFullYear();

  return (
    <>
      <style>{styles}</style>
      <div className="login-root relative flex min-h-screen flex-col overflow-hidden">
        <Backdrop />

        {/* ── Top bar ── */}
        <header className="relative z-20 flex items-center justify-between px-5 pt-5 sm:px-10 sm:pt-8 lg:px-16">
          <Brand />
          <LanguageSwitcher className="gap-1.5 rounded-full px-3 text-white hover:bg-white/15 hover:text-white [&_span]:text-sm" />
        </header>

        {/* ── Main ── */}
        <main className="relative z-10 mx-auto grid w-full max-w-7xl flex-1 items-center gap-10 px-5 py-8 sm:px-10 lg:grid-cols-[1.15fr_1fr] lg:gap-16 lg:px-16">
          {/* Marketing column */}
          <section className="hidden flex-col lg:flex">
            <h1 className="text-5xl font-extrabold leading-[1.08] tracking-tight xl:text-6xl">
              {tx('Run your business')}
              <br />
              <span className="text-sky-400">{tx('smarter')}</span>
              {tx(', not harder.')}
            </h1>
            <p className="mt-6 max-w-xl text-lg leading-relaxed text-white/85">
              {tx('A powerful, secure and easy-to-use ERP system to manage your operations, boost productivity and drive growth.')}
            </p>

            <div className="mt-10 grid max-w-xl grid-cols-4 divide-x divide-white/15">
              <Feature icon={<BarChart3 className="h-6 w-6" />} label={tx('Real-Time Insights')} />
              <Feature icon={<Settings className="h-6 w-6" />} label={tx('Integrated Modules')} />
              <Feature icon={<ShieldCheck className="h-6 w-6" />} label={tx('Enterprise Security')} />
              <Feature icon={<Store className="h-6 w-6" />} label={tx('Offline-First POS')} />
            </div>

            <DashboardPreview />

            <div className="mt-8 inline-flex w-fit items-center gap-5 rounded-2xl border border-white/15 bg-white/10 px-5 py-3 text-sm font-medium backdrop-blur-md">
              <span className="inline-flex items-center gap-2">
                <ShieldCheck className="h-5 w-5 text-emerald-400" /> {tx('Trusted by businesses worldwide')}
              </span>
              <span className="h-5 w-px bg-white/25" />
              <span className="inline-flex items-center gap-2">
                <Users className="h-5 w-5 text-sky-300" /> {tx('Growing together')}
              </span>
            </div>
          </section>

          {/* Sign-in card */}
          <section className="login-card login-in relative mx-auto w-full max-w-[540px] overflow-hidden rounded-[28px] px-6 py-8 text-slate-900 sm:px-12 sm:py-11">
            <ShieldBadge />

            <div className="relative mb-8 pr-24">
              <h2 className="text-3xl font-extrabold tracking-tight text-slate-900 sm:text-[2.1rem]">
                {view === 'login' && tx('Welcome Back!')}
                {view === 'mfa' && tx('Two-factor authentication')}
                {view === 'forgot' && t('auth.resetPassword')}
                {view === 'forgot-sent' && tx('Check your inbox')}
              </h2>
              <p className="mt-2 text-base text-slate-500">
                {view === 'login' && tx('Sign in to access your ERP system')}
                {view === 'mfa' && tx('Enter the 6-digit code from your authenticator.')}
                {view === 'forgot' && tx("We'll email you a secure reset link.")}
                {view === 'forgot-sent' && tx('If the account exists, a reset link is on its way.')}
              </p>
            </div>

            {view === 'login' && (
              <form className="relative space-y-5" onSubmit={(e) => { e.preventDefault(); login.mutate(); }}>
                {DEMO_MODE && (
                  <div className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900">
                    <div className="font-bold">{tx('Demo system')}</div>
                    <div className="mt-1">
                      {tx('Username')} <code className="font-bold">{DEMO_LOGIN.username}</code>
                      {' · '}
                      {tx('Password')} <code className="font-bold">{DEMO_LOGIN.password}</code>
                    </div>
                  </div>
                )}
                <Field label={tx('Organization')} labelIcon={<Building2 className="h-5 w-5" />} htmlFor="login-org">
                  <IconInput icon={<Building2 className="h-5 w-5" />}>
                    <Input
                      id="login-org"
                      value={orgCode}
                      onChange={(e) => setOrgCode(e.target.value)}
                      autoComplete="organization"
                      required
                      className={inputCls}
                      placeholder={tx('Enter your organization code')}
                    />
                  </IconInput>
                </Field>
                <Field
                  label={DEMO_MODE ? tx('Username') : tx('Email Address')}
                  labelIcon={<User className="h-5 w-5" />}
                  htmlFor="login-email"
                >
                  <IconInput icon={<Mail className="h-5 w-5" />}>
                    <Input
                      id="login-email"
                      type={DEMO_MODE ? 'text' : 'email'}
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      autoComplete={DEMO_MODE ? 'username' : 'email'}
                      required
                      className={inputCls}
                      placeholder={DEMO_MODE ? DEMO_LOGIN.username : tx('Enter your email address')}
                    />
                  </IconInput>
                </Field>
                <Field label={t('auth.password')} labelIcon={<Lock className="h-5 w-5" />} htmlFor="login-password">
                  <IconInput icon={<Lock className="h-5 w-5" />}>
                    <Input
                      id="login-password"
                      type={showPwd ? 'text' : 'password'}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      autoComplete="current-password"
                      required
                      className={`${inputCls} pr-12`}
                      placeholder={tx('Enter your password')}
                    />
                    <button
                      type="button"
                      onClick={() => setShowPwd(!showPwd)}
                      className="absolute right-3.5 top-1/2 -translate-y-1/2 rounded-md p-1 text-slate-400 transition-colors hover:text-slate-700"
                      aria-label={showPwd ? tx('Hide password') : tx('Show password')}
                    >
                      {showPwd ? <EyeOff className="h-5 w-5" /> : <Eye className="h-5 w-5" />}
                    </button>
                  </IconInput>
                </Field>

                <div className="flex items-center justify-between gap-4 text-sm">
                  <label
                    className="inline-flex cursor-pointer select-none items-center gap-2.5 font-medium text-slate-700"
                    title={tx('Remember the organization and email on this device')}
                  >
                    <input
                      type="checkbox"
                      checked={remember}
                      onChange={(e) => setRemember(e.target.checked)}
                      className="h-[18px] w-[18px] cursor-pointer rounded accent-blue-600"
                    />
                    {tx('Remember me')}
                  </label>
                  <button
                    type="button"
                    onClick={() => setView('forgot')}
                    className="font-semibold text-blue-600 transition-colors hover:text-blue-800"
                  >
                    {t('auth.forgotPassword')}
                  </button>
                </div>

                <PrimaryButton pending={login.isPending} icon={<LogIn className="h-5 w-5" />}>
                  {t('auth.login')}
                </PrimaryButton>

                <Divider />

                <p className="text-center text-sm text-slate-500">
                  {tx("Don't have an account?")}{' '}
                  <span className="font-semibold text-blue-600">{tx('Contact your administrator')}</span>
                </p>
              </form>
            )}

            {view === 'mfa' && (
              <form className="relative space-y-5" onSubmit={(e) => { e.preventDefault(); mfa.mutate(); }}>
                <Field label={t('auth.mfaCode')} labelIcon={<KeyRound className="h-5 w-5" />} htmlFor="login-mfa">
                  <Input
                    id="login-mfa"
                    value={mfaCode}
                    onChange={(e) => setMfaCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                    placeholder="123456"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="\d{6}"
                    autoFocus
                    required
                    className="login-input h-14 rounded-xl border-slate-200 bg-white text-center text-2xl font-extrabold tracking-[0.4em] text-slate-900 placeholder:text-slate-300 focus-visible:border-blue-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                  />
                </Field>
                <PrimaryButton
                  pending={mfa.isPending}
                  disabled={mfaCode.length !== 6}
                  icon={<KeyRound className="h-5 w-5" />}
                >
                  {tx('Verify')}
                </PrimaryButton>
                <BackLink onClick={() => setView('login')}>{t('common.back')}</BackLink>
              </form>
            )}

            {view === 'forgot' && (
              <form className="relative space-y-5" onSubmit={(e) => { e.preventDefault(); forgot.mutate(); }}>
                <Field label={tx('Organization')} labelIcon={<Building2 className="h-5 w-5" />} htmlFor="forgot-org">
                  <IconInput icon={<Building2 className="h-5 w-5" />}>
                    <Input
                      id="forgot-org"
                      value={orgCode}
                      onChange={(e) => setOrgCode(e.target.value)}
                      autoComplete="organization"
                      required
                      className={inputCls}
                      placeholder={tx('Enter your organization code')}
                    />
                  </IconInput>
                </Field>
                <Field label={tx('Email Address')} labelIcon={<User className="h-5 w-5" />} htmlFor="forgot-email">
                  <IconInput icon={<Mail className="h-5 w-5" />}>
                    <Input
                      id="forgot-email"
                      type="email"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      autoComplete="email"
                      required
                      className={inputCls}
                      placeholder={tx('Enter your email address')}
                    />
                  </IconInput>
                </Field>
                <PrimaryButton pending={forgot.isPending} icon={<Mail className="h-5 w-5" />}>
                  {t('auth.sendResetLink')}
                </PrimaryButton>
                <BackLink onClick={() => setView('login')}>{tx('Back to sign in')}</BackLink>
              </form>
            )}

            {view === 'forgot-sent' && (
              <div className="relative space-y-5 text-center">
                <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl bg-blue-50 text-blue-600">
                  <Mail className="h-8 w-8" />
                </div>
                <p className="text-sm text-slate-600">
                  {tx('If an account exists for')} <strong className="text-slate-900">{email}</strong>
                  {tx(', a reset link has been sent.')}
                </p>
                <Button
                  variant="outline"
                  onClick={() => setView('login')}
                  className="h-12 w-full rounded-xl border-slate-200 bg-white font-semibold text-slate-800 hover:bg-slate-50"
                >
                  {tx('Back to sign in')}
                </Button>
              </div>
            )}

            <div className="relative mt-8 flex items-center justify-center gap-5 text-xs text-slate-400">
              <span className="inline-flex items-center gap-1.5"><ShieldCheck className="h-4 w-4" /> {tx('Encrypted')}</span>
              <span className="inline-flex items-center gap-1.5"><Zap className="h-4 w-4" /> {tx('Offline-first')}</span>
            </div>
          </section>
        </main>

        {/* ── Footer ── */}
        <footer className="relative z-10 border-t border-white/10 bg-[#071a45]/80 backdrop-blur-md">
          <div className="mx-auto flex max-w-7xl flex-col items-center justify-between gap-2 px-5 py-4 text-sm text-white/80 sm:flex-row sm:px-10 lg:px-16">
            <span>© {year} {tx('ERP')}. {tx('All rights reserved.')}</span>
            <span className="inline-flex items-center gap-2">
              <Headphones className="h-4 w-4" /> {tx('Need help? Contact your system administrator.')}
            </span>
          </div>
        </footer>
      </div>
    </>
  );
}

/* ───────────────────────── pieces ───────────────────────── */

const Brand = () => (
  <div className="flex items-center gap-3 sm:gap-4">
    <svg viewBox="0 0 64 64" className="h-11 w-11 drop-shadow-[0_6px_18px_rgba(56,189,248,0.45)] sm:h-14 sm:w-14" aria-hidden>
      <defs>
        <linearGradient id="login-hex" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#38bdf8" />
          <stop offset="1" stopColor="#1d4ed8" />
        </linearGradient>
      </defs>
      <path d="M32 3 57 17.5v29L32 61 7 46.5v-29Z" fill="url(#login-hex)" />
      <path d="M32 9.5 51.5 20.75v22.5L32 54.5 12.5 43.25v-22.5Z" fill="#0b2a6b" opacity=".55" />
      <rect x="19" y="36" width="5" height="9" rx="1.2" fill="#fff" />
      <rect x="27" y="31" width="5" height="14" rx="1.2" fill="#fff" />
      <rect x="35" y="26" width="5" height="19" rx="1.2" fill="#fff" />
      <path d="m18 31 9-7 6 4 11-9" fill="none" stroke="#7dd3fc" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M40 19h4.5v4.5" fill="none" stroke="#7dd3fc" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
    <div>
      <div className="text-2xl font-extrabold leading-none tracking-tight sm:text-3xl">{tx('ERP')}</div>
      <div className="mt-1.5 text-xs text-white/80 sm:text-sm">
        {tx('Plan')} <span className="mx-1 text-white/50">•</span> {tx('Manage')}{' '}
        <span className="mx-1 text-white/50">•</span> {tx('Grow')}
      </div>
    </div>
  </div>
);

/** Deep-blue backdrop: soft light sweeps plus a faint city skyline. */
const Backdrop = () => (
  <div className="pointer-events-none absolute inset-0" aria-hidden>
    <svg className="login-sweep absolute inset-0 h-full w-full" viewBox="0 0 1600 900" preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id="login-sweep-a" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#60a5fa" stopOpacity=".55" />
          <stop offset="1" stopColor="#1d4ed8" stopOpacity="0" />
        </linearGradient>
        <linearGradient id="login-sweep-b" x1="1" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#93c5fd" stopOpacity=".35" />
          <stop offset="1" stopColor="#2563eb" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d="M820 -40 C 960 220, 1180 380, 1640 360 L1640 -40 Z" fill="url(#login-sweep-a)" />
      <path d="M1040 -40 C 1080 300, 1300 520, 1640 560 L1640 -40 Z" fill="url(#login-sweep-b)" />
      <path d="M760 -40 C 900 260, 1160 420, 1640 400" fill="none" stroke="#bfdbfe" strokeOpacity=".35" strokeWidth="2" />
      <path d="M-40 640 C 300 520, 620 600, 900 900" fill="none" stroke="#60a5fa" strokeOpacity=".18" strokeWidth="2" />
    </svg>
    <svg className="login-skyline absolute inset-x-0 bottom-0 h-[42%] w-full opacity-30" viewBox="0 0 1600 380" preserveAspectRatio="none">
      <g fill="#1e40af">
        {SKYLINE.map(([x, w, h]) => (
          <rect key={x} x={x} y={380 - h} width={w} height={h} />
        ))}
      </g>
      <g fill="#93c5fd" opacity=".35">
        {SKYLINE.filter((_, i) => i % 2 === 0).map(([x, w, h]) =>
          Array.from({ length: Math.floor(h / 26) }, (_, r) => (
            <rect key={`${x}-${r}`} x={x + 6} y={380 - h + 12 + r * 26} width={w - 12} height={3} />
          )),
        )}
      </g>
    </svg>
  </div>
);

// [x, width, height] of skyline blocks.
const SKYLINE: [number, number, number][] = [
  [0, 70, 150], [80, 50, 230], [140, 90, 180], [240, 60, 290], [310, 80, 200], [400, 45, 140],
  [455, 75, 250], [540, 60, 170], [610, 95, 310], [715, 55, 190], [780, 80, 260], [870, 50, 150],
  [930, 90, 330], [1030, 60, 220], [1100, 85, 280], [1195, 55, 170], [1260, 95, 300], [1365, 60, 210],
  [1435, 80, 260], [1525, 75, 180],
];

const Feature: React.FC<{ icon: React.ReactNode; label: string }> = ({ icon, label }) => (
  <div className="flex flex-col items-center gap-3 px-3 text-center">
    <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-b from-blue-500 to-blue-700 shadow-lg shadow-blue-950/40 ring-1 ring-white/20">
      {icon}
    </div>
    <span className="text-sm font-semibold leading-snug">{label}</span>
  </div>
);

/** A stylised dashboard window: KPI tiles, a bar chart and a donut. Pure decoration. */
const DashboardPreview = () => (
  <div className="mt-10 hidden max-w-xl xl:block" aria-hidden>
    <div className="rounded-2xl border border-white/20 bg-white/10 p-2 shadow-2xl shadow-blue-950/50 backdrop-blur-md">
      <div className="flex items-center gap-1.5 px-2 pb-2">
        <span className="h-2.5 w-2.5 rounded-full bg-rose-400/80" />
        <span className="h-2.5 w-2.5 rounded-full bg-amber-300/80" />
        <span className="h-2.5 w-2.5 rounded-full bg-emerald-400/80" />
      </div>
      <div className="flex gap-2 rounded-xl bg-white/95 p-3">
        <div className="flex w-10 flex-col items-center gap-2 rounded-lg bg-blue-900 py-3">
          {[0, 1, 2, 3, 4].map((i) => (
            <span key={i} className={`h-2 w-5 rounded-full ${i === 1 ? 'bg-sky-300' : 'bg-white/30'}`} />
          ))}
        </div>
        <div className="flex-1 space-y-2">
          <div className="grid grid-cols-3 gap-2">
            {['bg-blue-500', 'bg-emerald-500', 'bg-amber-500'].map((c) => (
              <div key={c} className="rounded-lg bg-slate-50 p-2 ring-1 ring-slate-100">
                <span className={`block h-1.5 w-8 rounded-full ${c}`} />
                <span className="mt-1.5 block h-2.5 w-14 rounded bg-slate-300" />
              </div>
            ))}
          </div>
          <div className="flex gap-2">
            <div className="flex h-24 flex-1 items-end gap-1.5 rounded-lg bg-slate-50 p-2 ring-1 ring-slate-100">
              {[35, 50, 42, 64, 55, 72, 60, 84, 70, 92].map((h, i) => (
                <span
                  key={i}
                  className="flex-1 rounded-t bg-gradient-to-t from-blue-700 to-sky-400"
                  style={{ height: `${h}%` }}
                />
              ))}
            </div>
            <div className="flex w-24 items-center justify-center rounded-lg bg-slate-50 ring-1 ring-slate-100">
              <svg viewBox="0 0 36 36" className="h-16 w-16 -rotate-90">
                <circle cx="18" cy="18" r="14" fill="none" stroke="#e2e8f0" strokeWidth="6" />
                <circle cx="18" cy="18" r="14" fill="none" stroke="#2563eb" strokeWidth="6" strokeDasharray="48 88" />
                <circle cx="18" cy="18" r="14" fill="none" stroke="#f59e0b" strokeWidth="6" strokeDasharray="22 88" strokeDashoffset="-48" />
                <circle cx="18" cy="18" r="14" fill="none" stroke="#10b981" strokeWidth="6" strokeDasharray="14 88" strokeDashoffset="-70" />
              </svg>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
);

/** Shield + padlock emblem in the card's top-right corner. */
const ShieldBadge = () => (
  <div className="pointer-events-none absolute right-6 top-6 sm:right-10 sm:top-8" aria-hidden>
    <div className="absolute -inset-8 rounded-full bg-blue-200/40 blur-2xl" />
    <svg viewBox="0 0 80 92" className="relative h-20 w-[70px] sm:h-24 sm:w-[84px]">
      <defs>
        <linearGradient id="login-shield" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#dbeafe" />
          <stop offset="1" stopColor="#bfdbfe" />
        </linearGradient>
        <linearGradient id="login-lock" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#3b82f6" />
          <stop offset="1" stopColor="#1d4ed8" />
        </linearGradient>
      </defs>
      <path d="M40 3 75 15v28c0 22-15 38-35 46C20 81 5 65 5 43V15Z" fill="url(#login-shield)" stroke="#93c5fd" strokeWidth="2" />
      <path d="M31 42v-7a9 9 0 0 1 18 0v7" fill="none" stroke="#1d4ed8" strokeWidth="4.5" strokeLinecap="round" />
      <rect x="25" y="41" width="30" height="24" rx="5" fill="url(#login-lock)" />
      <circle cx="40" cy="51" r="3.2" fill="#fff" />
      <rect x="38.6" y="52" width="2.8" height="6.5" rx="1.2" fill="#fff" />
    </svg>
  </div>
);

const Field: React.FC<{ label: string; labelIcon: React.ReactNode; htmlFor: string; children: React.ReactNode }> = ({
  label,
  labelIcon,
  htmlFor,
  children,
}) => (
  <div className="login-in">
    <label htmlFor={htmlFor} className="mb-2 flex items-center gap-2 text-[15px] font-semibold text-slate-800">
      <span className="text-slate-700">{labelIcon}</span>
      {label}
    </label>
    {children}
  </div>
);

const IconInput: React.FC<{ icon: React.ReactNode; children: React.ReactNode }> = ({ icon, children }) => (
  <div className="relative">
    <span className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-500">{icon}</span>
    {children}
  </div>
);

const PrimaryButton: React.FC<{
  pending: boolean;
  disabled?: boolean;
  icon: React.ReactNode;
  children: React.ReactNode;
}> = ({ pending, disabled, icon, children }) => (
  <Button
    type="submit"
    disabled={pending || disabled}
    className="login-btn h-12 w-full gap-2.5 rounded-xl border-0 text-base font-bold text-white shadow-lg shadow-blue-600/30 transition"
  >
    {pending ? <Loader2 className="h-5 w-5 animate-spin" /> : icon}
    {children}
  </Button>
);

const BackLink: React.FC<{ onClick: () => void; children: React.ReactNode }> = ({ onClick, children }) => (
  <button
    type="button"
    onClick={onClick}
    className="block w-full text-center text-sm font-medium text-slate-500 transition-colors hover:text-slate-900"
  >
    {children}
  </button>
);

const Divider = () => (
  <div className="flex items-center gap-4 text-xs font-semibold uppercase tracking-wider text-slate-400">
    <span className="h-px flex-1 bg-slate-200" />
    <ShieldCheck className="h-4 w-4" />
    <span className="h-px flex-1 bg-slate-200" />
  </div>
);
