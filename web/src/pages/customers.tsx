import { PartnersList } from '@/components/partners/PartnersPage';
import { PartnerDetailPage } from '@/pages/partners/PartnerDetailPage';
import { PartnerCreatePage } from '@/pages/partners/PartnerCreatePage';

export function CustomersPage() {
  return <PartnersList partnerType="customer" />;
}

export function CustomerDetailPage() {
  return <PartnerDetailPage partnerType="customer" />;
}

export function CustomerCreatePage() {
  return <PartnerCreatePage partnerType="customer" />;
}
