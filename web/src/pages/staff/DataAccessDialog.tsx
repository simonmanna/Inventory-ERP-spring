import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useBranchOptions } from '@/features/finance/api';
import { api } from '@/lib/api';
import { locationsApi } from '@/lib/api/locations';
import { tx } from '@/lib/i18n/tx';

interface UserDataScope {
  userId: string;
  restricted: boolean;
  branchIds: string[];
  locationIds: string[];
}

function toggle(list: string[], id: string): string[] {
  return list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
}

/** Which branches and locations a user is limited to (BR-1). Nothing ticked means no restriction. */
export function DataAccessDialog({ userId, name, onClose }: { userId: string; name: string; onClose: () => void }) {
  const qc = useQueryClient();
  const { data: scope, isLoading } = useQuery({
    queryKey: ['user-data-scope', userId],
    queryFn: async () => (await api.get<UserDataScope>(`/user-data-scopes/${userId}`)).data,
  });
  const { data: branches = [] } = useBranchOptions();
  const { data: locations } = useQuery({
    queryKey: ['user-data-scope', 'locations'],
    queryFn: () => locationsApi.list({ pageSize: 200 }),
  });
  const [branchIds, setBranchIds] = useState<string[]>([]);
  const [locationIds, setLocationIds] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (scope) {
      setBranchIds(scope.branchIds);
      setLocationIds(scope.locationIds);
    }
  }, [scope]);

  const save = useMutation({
    mutationFn: async () => (await api.put(`/user-data-scopes/${userId}`, { branchIds, locationIds })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['user-data-scope', userId] });
      onClose();
    },
    onError: (e: unknown) => {
      const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(message ?? tx('Could not save'));
    },
  });

  const unrestricted = branchIds.length === 0 && locationIds.length === 0;

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{tx('Data access')}: {name}</DialogTitle>
        </DialogHeader>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>
        ) : (
          <div className="space-y-4 text-sm">
            <p className="text-muted-foreground">
              {unrestricted
                ? tx('No restriction: this user sees every branch and location.')
                : tx('This user only sees the ticked branches and locations.')}
            </p>
            <div className="grid gap-4 sm:grid-cols-2">
              <fieldset className="space-y-1">
                <legend className="font-medium">{tx('Branches')}</legend>
                {branches.map((b) => (
                  <label key={b.id} className="flex items-center gap-2">
                    <input type="checkbox" checked={branchIds.includes(b.id)} onChange={() => setBranchIds((l) => toggle(l, b.id))} />
                    {b.name}
                  </label>
                ))}
              </fieldset>
              <fieldset className="space-y-1">
                <legend className="font-medium">{tx('Locations')}</legend>
                {(locations?.data ?? []).map((l) => (
                  <label key={l.id} className="flex items-center gap-2">
                    <input type="checkbox" checked={locationIds.includes(l.id)} onChange={() => setLocationIds((x) => toggle(x, l.id))} />
                    {l.name}
                  </label>
                ))}
              </fieldset>
            </div>
            {error && <p className="text-destructive">{error}</p>}
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button disabled={save.isPending} onClick={() => save.mutate()}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
