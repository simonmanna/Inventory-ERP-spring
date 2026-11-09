import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AlertTriangle, ArrowRightLeft, ClipboardCheck, Inbox, MoreHorizontal, Pencil, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { ListCard, ListPageHeader } from '@/components/list/list-page';
import { FilterSelect, ListToolbar, SearchInput } from '@/components/list/list-toolbar';
import { useAuthStore } from '@/stores/auth.store';
import { usePlmRequests, type ChangeRequest, type RequestStatus } from '@/features/manufacturing/api-plm';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { Avatar, ChangeStatusPill, EmptyState, PlmSubNav, PriorityPill, ProductLabel, RequestStatusPill } from './plm-ui';
import { RequestDialog, ReviewDialog } from './request-dialogs';
import { ConvertDialog } from './change-dialogs';
import { REASONS, REQUEST_STATUS, day } from './vocab';

/** Engineering change requests (ECR): raise, review, convert into change orders. */
export function PlmRequestsPage() {
  const [params] = useSearchParams();
  const has = useAuthStore((s) => s.hasPermission);
  const me = useAuthStore((s) => s.user?.id);
  const canRequest = has(PERMISSIONS.plm.request);
  const canApprove = has(PERMISSIONS.plm.approve);
  const canChange = has(PERMISSIONS.plm.change);
  const [status, setStatus] = useState(params.get('q') ? '' : 'open');
  const [search, setSearch] = useState(params.get('q') ?? '');
  const q = useDebouncedValue(search, 250);
  const { data = [], isLoading } = usePlmRequests({ status, q });
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<ChangeRequest | null>(null);
  const [reviewing, setReviewing] = useState<ChangeRequest | null>(null);
  const [converting, setConverting] = useState<ChangeRequest | null>(null);

  const canEdit = (r: ChangeRequest) => r.status === 'new' && canRequest && (r.requestedById === me || canApprove);
  const canReview = (r: ChangeRequest) => r.status === 'new' && canApprove;
  const canConvert = (r: ChangeRequest) => (r.status === 'new' || r.status === 'accepted') && canChange;

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Change requests')}
        description={tx('Anyone can report a problem or propose an improvement; engineering reviews it and turns it into a change order.')}
        icon={Inbox}
        actions={canRequest && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New request')}</Button>}
      />
      <PlmSubNav />
      <ListToolbar>
        <SearchInput value={search} onChange={setSearch} placeholder={tx('Search number or title…')} />
        <FilterSelect
          value={status}
          onChange={setStatus}
          allLabel={tx('Any status')}
          options={[{ value: 'open', label: tx('Open (new + accepted)') },
            ...(Object.keys(REQUEST_STATUS) as RequestStatus[]).map((s) => ({ value: s, label: REQUEST_STATUS[s].label }))]}
        />
        <span className="ml-auto text-xs text-muted-foreground">{tx('{{n}} requests', { n: data.length })}</span>
      </ListToolbar>

      {isLoading ? <Skeleton className="h-72 w-full rounded-xl" /> : data.length === 0 ? (
        <EmptyState
          icon={Inbox}
          title={tx('No change requests')}
          hint={tx('Change requests capture problems and ideas before engineering commits to a change.')}
          action={canRequest && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New request')}</Button>}
        />
      ) : (
        <ListCard>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('ECR')}</TableHead>
                <TableHead>{tx('Title')}</TableHead>
                <TableHead>{tx('Product')}</TableHead>
                <TableHead>{tx('Reason')}</TableHead>
                <TableHead>{tx('Priority')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead>{tx('Needed by')}</TableHead>
                <TableHead>{tx('Requested by')}</TableHead>
                <TableHead>{tx('Change order')}</TableHead>
                <TableHead className="w-10" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((r) => {
                const actions = [
                  canReview(r) && { label: tx('Review'), icon: ClipboardCheck, run: () => setReviewing(r) },
                  canConvert(r) && { label: tx('Convert to change order'), icon: ArrowRightLeft, run: () => setConverting(r) },
                  canEdit(r) && { label: tx('Edit'), icon: Pencil, run: () => setEditing(r) },
                ].filter(Boolean) as Array<{ label: string; icon: typeof Pencil; run: () => void }>;
                return (
                  <TableRow key={r.id}>
                    <TableCell className="whitespace-nowrap font-mono text-xs">{r.number}</TableCell>
                    <TableCell className="max-w-[280px]">
                      <div className="font-medium">{r.title}</div>
                      {r.description && <div className="line-clamp-1 text-xs text-muted-foreground">{r.description}</div>}
                      {r.decisionNote && r.status === 'rejected' && <div className="text-xs text-rose-600">{r.decisionNote}</div>}
                    </TableCell>
                    <TableCell className="max-w-[200px] text-sm"><ProductLabel product={r.product} /></TableCell>
                    <TableCell className="text-sm text-muted-foreground">{REASONS[r.reasonCode] ?? r.reasonCode}</TableCell>
                    <TableCell><PriorityPill priority={r.priority} /></TableCell>
                    <TableCell><RequestStatusPill status={r.status} /></TableCell>
                    <TableCell className={cn('whitespace-nowrap text-sm', r.overdue && 'font-medium text-rose-600')}>
                      {r.overdue && <AlertTriangle className="mr-1 inline h-3.5 w-3.5" />}
                      {r.dueDate ? day(String(r.dueDate)) : '—'}
                    </TableCell>
                    <TableCell className="text-sm">
                      {r.requestedByName ? <span className="flex items-center gap-1.5"><Avatar name={r.requestedByName} />{r.requestedByName}</span> : '—'}
                    </TableCell>
                    <TableCell>
                      {r.changeId ? (
                        <span className="flex items-center gap-1.5">
                          <Link to={`/manufacturing/plm/changes/${r.changeId}`} className="font-mono text-xs text-primary hover:underline">{r.changeNumber ?? tx('Open')}</Link>
                          {r.changeStatus && <ChangeStatusPill status={r.changeStatus} />}
                        </span>
                      ) : <span className="text-xs text-muted-foreground">—</span>}
                    </TableCell>
                    <TableCell>
                      {actions.length > 0 && (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Actions')}><MoreHorizontal className="h-4 w-4" /></Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            {actions.map((a) => (
                              <DropdownMenuItem key={a.label} onClick={a.run}><a.icon className="mr-2 h-4 w-4" />{a.label}</DropdownMenuItem>
                            ))}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </ListCard>
      )}

      <RequestDialog open={creating || !!editing} request={editing} onClose={() => { setCreating(false); setEditing(null); }} />
      <ReviewDialog request={reviewing} onClose={() => setReviewing(null)} />
      <ConvertDialog request={converting} onClose={() => setConverting(null)} />
    </div>
  );
}
