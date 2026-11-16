import { useState } from 'react';
import { Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { tx } from '@/lib/i18n/tx';
import type { Workspace } from '@/features/projects/api';
import { useBonds } from '@/features/projects/construction-api';
import { Section } from './shared';
import { BondDetailDialog, BondFormDialog, BondTable, useBondPerms } from '../../bonds/BondsUi';

/** Bonds and guarantees of this project (ADR-S074); the full register is under Projects → Bonds & guarantees. */
export function BondsSection({ ws }: { ws: Workspace }) {
  const perms = useBondPerms();
  const { data: bonds = [] } = useBonds({ projectId: ws.id }, perms.read);
  const [creating, setCreating] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  if (!perms.read) return <Section title={tx('Bonds & guarantees')}><p className="text-sm text-muted-foreground">{tx('You need the bond read permission.')}</p></Section>;
  return (
    <Section title={tx('Bonds & guarantees')} hint={tx('Performance, advance-payment and retention bonds issued for this project or received from its subcontractors.')}
      action={perms.manage && <Button size="sm" onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" />{tx('New bond')}</Button>}>
      <BondTable bonds={bonds} showProject={false} onOpen={(b) => setOpen(b.id)} />
      {creating && <BondFormDialog open projectId={ws.id} onClose={() => setCreating(false)} />}
      <BondDetailDialog bondId={open} onClose={() => setOpen(null)} />
    </Section>
  );
}
