import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { tx } from '@/lib/i18n/tx';
import { PartnerCreateForm, type CreatedPartner } from './PartnerCreatePage';

/**
 * The new customer / supplier page in a dialog, for documents that need a partner on the spot (e.g. a branch cash
 * sale): same fields, checks and API calls as {@code /customers/new}; {@code onCreated} hands the new partner back so
 * the document can use it straight away.
 */
export function PartnerCreateDialog({ open, onOpenChange, partnerType = 'customer', onCreated }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  partnerType?: 'customer' | 'supplier';
  onCreated: (created: CreatedPartner) => void;
}) {
  const isCustomer = partnerType === 'customer';
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-5xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{isCustomer ? tx('New customer') : tx('New supplier')}</DialogTitle>
          <DialogDescription>
            {isCustomer
              ? tx('Saved customers are selected on this document straight away.')
              : tx('Saved suppliers are selected on this document straight away.')}
          </DialogDescription>
        </DialogHeader>
        {open && (
          <PartnerCreateForm
            partnerType={partnerType}
            onCreated={(created) => { onCreated(created); onOpenChange(false); }}
            onCancel={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}
