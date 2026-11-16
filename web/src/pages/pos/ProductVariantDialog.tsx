import React from 'react';
import { Package, X } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { useAuthStore } from '@/stores/auth.store';
import { useProductVariantOptions, type VariantOption } from '@/features/products/variants-api';
import { tx } from '@/lib/i18n/tx';

const fmt = (n: number | string) =>
  `${useAuthStore.getState().organization?.currencyCode ?? 'IDR'} ${Number(n || 0).toLocaleString()}`;

interface Props {
  /** The product being sold; `null` closes the dialog. */
  product: { id: string; name: string } | null;
  onClose: () => void;
  onPick: (variant: VariantOption) => void;
}

/**
 * Retail POS picker for a product with variants (ADR-S072). Lists the product's active ProductVariants with their
 * effective sales price (fixed variant price, else product price + attribute extras — the price the server charges);
 * one tap adds that variant to the cart.
 */
export const ProductVariantDialog: React.FC<Props> = ({ product, onClose, onPick }) => {
  const { data: variants = [], isLoading } = useProductVariantOptions(product?.id, !!product);

  return (
    <Dialog open={!!product} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Package className="h-4 w-4" />
            {product?.name}
          </DialogTitle>
          <DialogDescription>{tx('Choose variant…')}</DialogDescription>
        </DialogHeader>
        <div className="max-h-[60vh] space-y-2 overflow-y-auto py-2">
          {isLoading ? (
            <p className="text-sm text-muted-foreground">{tx('Loading products…')}</p>
          ) : variants.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('No variants')}</p>
          ) : (
            variants.map((v) => (
              <button
                key={v.id}
                type="button"
                onClick={() => onPick(v)}
                className="flex w-full items-center justify-between gap-3 rounded-lg border-2 border-slate-200 bg-white px-4 py-3 text-left text-slate-700 transition-all duration-150 hover:border-amber-500 hover:bg-amber-50 hover:shadow-sm"
              >
                <span className="min-w-0">
                  <span className="block truncate font-semibold">{v.name}</span>
                  {v.sku || v.barcode ? (
                    <span className="block truncate text-xs text-slate-500">{[v.sku, v.barcode].filter(Boolean).join(' · ')}</span>
                  ) : null}
                </span>
                <span className="shrink-0 font-mono text-sm font-bold">{fmt(v.salesPrice)}</span>
              </button>
            ))
          )}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}><X className="mr-1 h-4 w-4" /> {tx('Cancel')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};
