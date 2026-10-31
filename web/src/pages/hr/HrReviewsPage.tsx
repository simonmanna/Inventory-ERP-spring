import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import {
  useCreateHrReview,
  useDeleteHrReview,
  useHrEmployees,
  useHrReviews,
  useUpdateHrReview,
  type HrPerformanceReview,
} from '@/features/hr/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { notify } from '@/lib/notify';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border bg-background px-3 text-sm';
const PAGE = 25;
const STATUSES = ['DRAFT', 'SUBMITTED', 'APPROVED'];
const TONE: Record<string, string> = {
  DRAFT: 'bg-muted text-muted-foreground',
  SUBMITTED: 'bg-sky-100 text-sky-800',
  APPROVED: 'bg-emerald-100 text-emerald-800',
};
const day = (d: string | null) => (d ? d.slice(0, 10) : '—');
const EMPTY = { employeeId: '', reviewDate: '', periodStart: '', periodEnd: '', kpiScore: '', rating: '', notes: '', status: 'DRAFT' };

/** Performance reviews: scores, ratings and the review of each employee. */
export function HrReviewsPage() {
  const [employeeId, setEmployeeId] = useState('');
  const [skip, setSkip] = useState(0);
  const [editing, setEditing] = useState<HrPerformanceReview | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const { data, isLoading, isError } = useHrReviews({ employeeId: employeeId || undefined, take: PAGE, skip });
  const { data: employees } = useHrEmployees({ pageSize: 200 });
  const update = useUpdateHrReview();
  const remove = useDeleteHrReview();
  const rows: HrPerformanceReview[] = data?.rows ?? [];
  const total: number = data?.total ?? 0;

  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{tx('Performance reviews')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Scores, ratings and notes for each review period.')}</p>
        </div>
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="mr-1.5 h-4 w-4" />
          {tx('New review')}
        </Button>
      </div>
      <Card>
        <CardContent className="space-y-3 pt-6">
          <select
            className={SELECT}
            value={employeeId}
            onChange={(e) => {
              setEmployeeId(e.target.value);
              setSkip(0);
            }}
          >
            <option value="">{tx('All employees')}</option>
            {(employees?.rows ?? []).map((e: any) => (
              <option key={e.id} value={e.id}>
                {e.firstName} {e.lastName ?? ''} ({e.employeeCode})
              </option>
            ))}
          </select>
          {isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : isError ? (
            <p className="py-8 text-center text-sm text-destructive">{tx('Could not load the reviews')}</p>
          ) : rows.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                    <th className="py-2">{tx('Employee')}</th>
                    <th className="py-2">{tx('Date')}</th>
                    <th className="py-2">{tx('Period')}</th>
                    <th className="py-2 text-right">{tx('KPI score')}</th>
                    <th className="py-2 text-right">{tx('Rating')}</th>
                    <th className="py-2">{tx('Status')}</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} className="border-b last:border-0">
                      <td className="py-2">
                        <Link className="hover:underline" to={`/hr/employees/${r.employeeId}`}>
                          {r.employee?.firstName} {r.employee?.lastName ?? ''}
                        </Link>
                      </td>
                      <td className="py-2">{day(r.reviewDate)}</td>
                      <td className="py-2">
                        {day(r.periodStart)} – {day(r.periodEnd)}
                      </td>
                      <td className="py-2 text-right">{r.kpiScore ?? '—'}</td>
                      <td className="py-2 text-right">{r.rating ?? '—'}</td>
                      <td className="py-2">
                        <Badge className={TONE[r.status] ?? 'bg-muted text-muted-foreground'}>{r.status}</Badge>
                      </td>
                      <td className="py-2 text-right">
                        <div className="flex justify-end gap-1">
                          {r.status === 'DRAFT' ? (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                try {
                                  await update.mutateAsync({ id: r.id, dto: { status: 'SUBMITTED' } });
                                  notify.success(tx('Review updated'));
                                } catch (err) {
                                  notify.error(apiErrorMessage(err, tx('Could not save the review')));
                                }
                              }}
                            >
                              {tx('Submit')}
                            </Button>
                          ) : null}
                          {r.status === 'SUBMITTED' ? (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                try {
                                  await update.mutateAsync({ id: r.id, dto: { status: 'APPROVED' } });
                                  notify.success(tx('Review updated'));
                                } catch (err) {
                                  notify.error(apiErrorMessage(err, tx('Could not save the review')));
                                }
                              }}
                            >
                              {tx('Approve')}
                            </Button>
                          ) : null}
                          <Button size="sm" variant="ghost" onClick={() => setEditing(r)}>
                            {tx('Edit')}
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            aria-label={tx('Delete')}
                            onClick={async () => {
                              try {
                                await remove.mutateAsync(r.id);
                                notify.success(tx('Review deleted'));
                              } catch (err) {
                                notify.error(apiErrorMessage(err, tx('Could not delete the review')));
                              }
                            }}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="py-8 text-center text-sm text-muted-foreground">{tx('No reviews yet.')}</p>
          )}
          {total > PAGE ? (
            <div className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
              <span>
                {skip + 1}–{Math.min(skip + PAGE, total)} / {total}
              </span>
              <Button size="sm" variant="outline" disabled={skip === 0} onClick={() => setSkip(Math.max(0, skip - PAGE))}>
                {tx('Previous')}
              </Button>
              <Button size="sm" variant="outline" disabled={skip + PAGE >= total} onClick={() => setSkip(skip + PAGE)}>
                {tx('Next')}
              </Button>
            </div>
          ) : null}
        </CardContent>
      </Card>
      {createOpen || editing ? (
        <ReviewDialog
          review={editing}
          employees={employees?.rows ?? []}
          onClose={() => {
            setCreateOpen(false);
            setEditing(null);
          }}
        />
      ) : null}
    </div>
  );
}

function ReviewDialog({
  review,
  employees,
  onClose,
}: {
  review: HrPerformanceReview | null;
  employees: any[];
  onClose: () => void;
}) {
  const create = useCreateHrReview();
  const update = useUpdateHrReview();
  const [form, setForm] = useState(
    review
      ? {
          employeeId: review.employeeId,
          reviewDate: review.reviewDate?.slice(0, 10) ?? '',
          periodStart: review.periodStart?.slice(0, 10) ?? '',
          periodEnd: review.periodEnd?.slice(0, 10) ?? '',
          kpiScore: review.kpiScore == null ? '' : String(review.kpiScore),
          rating: review.rating == null ? '' : String(review.rating),
          notes: review.notes ?? '',
          status: review.status,
        }
      : EMPTY,
  );
  const set = (k: string, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const badPeriod = !!form.periodStart && !!form.periodEnd && form.periodEnd < form.periodStart;
  const badRating = form.rating !== '' && (Number(form.rating) < 1 || Number(form.rating) > 5);
  const pending = create.isPending || update.isPending;

  const save = async () => {
    const dto = {
      reviewDate: form.reviewDate || undefined,
      periodStart: form.periodStart || null,
      periodEnd: form.periodEnd || null,
      kpiScore: form.kpiScore === '' ? null : Number(form.kpiScore),
      rating: form.rating === '' ? null : Number(form.rating),
      notes: form.notes || null,
      status: form.status,
    };
    try {
      if (review) await update.mutateAsync({ id: review.id, dto });
      else await create.mutateAsync({ ...dto, employeeId: form.employeeId });
      notify.success(tx('Review saved'));
      onClose();
    } catch (err) {
      notify.error(apiErrorMessage(err, tx('Could not save the review')));
    }
  };

  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{review ? tx('Edit review') : tx('New review')}</DialogTitle>
          <DialogDescription>{tx('A rating runs from 1 (lowest) to 5 (highest).')}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label>{tx('Employee')}</Label>
            <select
              className={SELECT}
              disabled={!!review}
              value={form.employeeId}
              onChange={(e) => set('employeeId', e.target.value)}
            >
              <option value="">{tx('Select…')}</option>
              {employees.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.firstName} {e.lastName ?? ''} ({e.employeeCode})
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('Date')}</Label>
              <Input type="date" value={form.reviewDate} onChange={(e) => set('reviewDate', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Period start')}</Label>
              <Input type="date" value={form.periodStart} onChange={(e) => set('periodStart', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Period end')}</Label>
              <Input type="date" value={form.periodEnd} onChange={(e) => set('periodEnd', e.target.value)} />
            </div>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div className="grid gap-1.5">
              <Label>{tx('KPI score')}</Label>
              <Input type="number" step="0.01" value={form.kpiScore} onChange={(e) => set('kpiScore', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Rating')}</Label>
              <Input type="number" min={1} max={5} value={form.rating} onChange={(e) => set('rating', e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label>{tx('Status')}</Label>
              <select className={SELECT} value={form.status} onChange={(e) => set('status', e.target.value)}>
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label>{tx('Notes')}</Label>
            <Textarea rows={3} value={form.notes} onChange={(e) => set('notes', e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {tx('Cancel')}
          </Button>
          <Button disabled={pending || !form.employeeId || badPeriod || badRating} onClick={save}>
            {tx('Save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
