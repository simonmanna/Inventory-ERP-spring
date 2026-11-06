import { PartnersList } from '@/components/partners/PartnersPage';
import { PartnerDetailPage } from '@/pages/partners/PartnerDetailPage';
import { PartnerCreatePage } from '@/pages/partners/PartnerCreatePage';
import { CustomerImportExport } from '@/pages/partners/CustomerImportExport';

export function CustomersPage() {
  return <div className="space-y-3"><CustomerImportExport /><PartnersList partnerType="customer" /></div>;
}

export function CustomerDetailPage() {
  return <PartnerDetailPage partnerType="customer" />;
}

export function CustomerCreatePage() {
  return <PartnerCreatePage partnerType="customer" />;
}
