import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Package, Plus, Sparkles } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListCard, ListPageHeader } from '@/components/list/list-page';
import { FilterSelect, ListToolbar, SearchInput } from '@/components/list/list-toolbar';
import { useAuthStore } from '@/stores/auth.store';
import { num, usePlmItems, useRegisterActive } from '@/features/manufacturing/api-plm';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { Avatar, EmptyState, LifecyclePill, PlmSubNav, RevisionBadge } from './plm-ui';
import { RegisterItemDialog } from './item-dialogs';
import { LIFECYCLE, LIFECYCLE_ORDER } from './vocab';

/** Products under lifecycle management. */
export function PlmProductsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const state = params.get('state') ?? '';
  const setState = (v: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set('state', v); else next.delete('state');
    setParams(next, { replace: true });
  };
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 250);
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.manage);
  const { data = [], isLoading } = usePlmItems({ state, q });
  const { data: all = [] } = usePlmItems({});
  const registered = useMemo(() => new Set(all.map((i) => i.productId)), [all]);
  const [registering, setRegistering] = useState(false);
  const registerActive = useRegisterActive();

  const bulk = () => {
    if (!confirm(tx('Register every product with an active BOM that is not under PLM yet, as released revision A?'))) return;
    registerActive.mutate(undefined, {
      onSuccess: (r) => {
        const n = num(r.registered);
        if (n) notify.success(tx('{{n}} products registered', { n }));
        else notify.info(tx('Every product with an active BOM is already registered.'));
      },
    });
  };

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('PLM products')}
        description={tx('Lifecycle state, revision, owner and open engineering work per product.')}
        icon={Package}
        actions={canManage && (
          <>
            <Button variant="outline" onClick={bulk} disabled={registerActive.isPending}>
              <Sparkles className="mr-1 h-4 w-4" />{tx('Register all with an active BOM')}
            </Button>
            <Button onClick={() => setRegistering(true)}><Plus className="mr-1 h-4 w-4" />{tx('Register product')}</Button>
          </>
        )}
      />
      <PlmSubNav />
      <ListToolbar>
        <SearchInput value={search} onChange={setSearch} placeholder={tx('Search code or name…')} />
        <FilterSelect
          value={state}
          onChange={setState}
          allLabel={tx('Any state')}
          options={LIFECYCLE_ORDER.map((s) => ({ value: s, label: LIFECYCLE[s].label }))}
        />
        <span className="ml-auto text-xs text-muted-foreground">{tx('{{n}} products', { n: data.length })}</span>
      </ListToolbar>

      {isLoading ? <Skeleton className="h-72 w-full rounded-xl" /> : data.length === 0 ? (
        <EmptyState
          icon={Package}
          title={state || q ? tx('No products match') : tx('No products under PLM yet')}
          hint={tx('Register a product to track its lifecycle, revisions, changes and documents.')}
          action={canManage && !state && !q && (
            <div className="flex gap-2">
              <Button variant="outline" onClick={bulk}><Sparkles className="mr-1 h-4 w-4" />{tx('Register all with an active BOM')}</Button>
              <Button onClick={() => setRegistering(true)}><Plus className="mr-1 h-4 w-4" />{tx('Register product')}</Button>
            </div>
          )}
        />
      ) : (
        <ListCard>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Product')}</TableHead>
                <TableHead>{tx('Revision')}</TableHead>
                <TableHead>{tx('State')}</TableHead>
                <TableHead>{tx('Owner')}</TableHead>
                <TableHead>{tx('Active BOM')}</TableHead>
                <TableHead className="text-right">{tx('Open changes')}</TableHead>
                <TableHead className="text-right">{tx('Released docs')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((i) => (
                <TableRow key={i.id} className="cursor-pointer" onClick={() => navigate(`/manufacturing/plm/products/${i.id}`)}>
                  <TableCell>
                    <div className="font-mono text-xs text-muted-foreground">{i.productCode}</div>
                    <div className="font-medium">{i.productName}</div>
                  </TableCell>
                  <TableCell><RevisionBadge revision={i.revision} /></TableCell>
                  <TableCell><LifecyclePill state={i.lifecycleState} /></TableCell>
                  <TableCell>
                    {i.ownerName ? <span className="flex items-center gap-1.5 text-sm"><Avatar name={i.ownerName} />{i.ownerName}</span>
                      : <span className="text-xs text-muted-foreground">—</span>}
                  </TableCell>
                  <TableCell className="font-mono text-xs">{i.activeBom ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell className="text-right">
                    {num(i.openChanges) > 0
                      ? <span className="rounded-full bg-sky-100 px-2 py-0.5 text-xs font-semibold text-sky-700 dark:bg-sky-500/20 dark:text-sky-300">{num(i.openChanges)}</span>
                      : <span className="text-muted-foreground">0</span>}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{num(i.releasedDocuments)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ListCard>
      )}

      <RegisterItemDialog
        open={registering}
        onClose={() => setRegistering(false)}
        registered={registered}
        onDone={(item) => navigate(`/manufacturing/plm/products/${item.id}`)}
      />
    </div>
  );
}
