import { PartnersList } from '@/components/partners/PartnersPage';
import { PartnerDetailPage } from '@/pages/partners/PartnerDetailPage';
import { PartnerCreatePage } from '@/pages/partners/PartnerCreatePage';

export function SuppliersPage() {
  return <PartnersList partnerType="supplier" />;
}

export function SupplierDetailPage() {
  return <PartnerDetailPage partnerType="supplier" />;
}

export function SupplierCreatePage() {
  return <PartnerCreatePage partnerType="supplier" />;
}
