-- ADR-S094 C0 read-only assessment. Runs on a RESTORED COPY only (never on live).
-- Source: nightly dump POS-CAFE_FULL_2026-11-28_020001.dump (02:00 UTC). Guard live time: V674 installed_on.
\pset footer off
begin;  -- temp views only; everything is rolled back at the end (and this is a scratch copy)

select 'S0 snapshot', max(installed_on)::text as last_migration, (select count(*) from "Organization" where "deletedAt" is null) as orgs from flyway_schema_history;
select 'S0 guard live since (V674 installed_on, deploy of 287bebaa)', installed_on::text from flyway_schema_history where version = '674';
select 'S0 je.status', status, count(*) from "JournalEntry" group by 2 order by 2;

-- ===== A. D-VAT-1 =====
select 'A1 orgs with depositTaxPoint=receipt', count(*) from "SalesSettings" where "depositTaxPoint" = 'receipt';
select 'A2 down-payment links', count(*) from "SalesInvoiceLink" where kind = 'down_payment';
select 'A2 down-payment documents (all / taxed)', count(*), count(*) filter (where "taxAmount" <> 0)
  from "Document" where "documentType" = 'sales_invoice' and data->>'downPaymentFor' is not null;
select 'A3 regular invoices posted after a taxed down payment on the same order', count(distinct l2."documentId")
  from "SalesInvoiceLink" l1
  join "Document" d1 on d1.id = l1."documentId" and d1."taxAmount" <> 0
  join "SalesInvoiceLink" l2 on l2."orderId" = l1."orderId" and l2.kind = 'regular'
  join "Document" d2 on d2.id = l2."documentId" and d2.status not in ('draft', 'cancelled')
  where l1.kind = 'down_payment';
select 'A4 audit entries ever setting depositTaxPoint=receipt', count(*) from "AuditLog"
  where coalesce("newValues"::text, '') ilike '%depositTaxPoint%' and coalesce("newValues"::text, '') ilike '%receipt%';

-- ===== B. Pre-guard invoices, L-1 line mapping, identical lines =====
with g as (select installed_on as t from flyway_schema_history where version = '674')
select 'B1 cash-sale invoices posted before / after guard', count(*) filter (where d."postedAt" < g.t), count(*) filter (where d."postedAt" >= g.t), count(*) filter (where d."postedAt" is null)
  from "Document" d, g where d."sourceType" = 'cash_sale';

create temp view inv as
  select s.id sid, s.number, s."organizationId" org, d.id did, d."postedAt"
  from "SalesSubmission" s join "Document" d on d.id = s."currentInvoiceId"
  where s."processingStatus" = 'invoiced';
create temp view pairs as
  with a as (select inv.sid, l.*, row_number() over (partition by l."submissionId" order by l."sortOrder", l.id) rn
             from "SalesSubmissionLine" l join inv on inv.sid = l."submissionId"),
       b as (select inv.sid, l.*, row_number() over (partition by l."documentId" order by l."lineNumber", l.id) rn
             from "DocumentLine" l join inv on inv.did = l."documentId")
  select coalesce(a.sid, b.sid) sid,
         (a.id is null or b.id is null) unpaired,
         a."productId" is distinct from b."productId" d_product,
         a."variantId" is distinct from b."variantId" d_variant,
         a."taxId" is distinct from b."taxId" d_tax,
         a.quantity is distinct from b.quantity d_qty,
         a."unitPrice" is distinct from b."unitPrice" d_price,
         coalesce(a."discountPercent", 0) is distinct from coalesce(b."discountPercent", 0) d_disc,
         a."uomId" is distinct from b."uomId" d_uom,
         coalesce(a."uomFactor", 1) is distinct from coalesce(b."uomFactor", 1) d_factor,
         a."lineSubtotal" is distinct from b.subtotal d_sub,
         a."lineTax" is distinct from b."taxAmount" d_taxamt,
         a."lineTotal" is distinct from b.total d_total,
         coalesce(a."taxInclusive", false) is distinct from coalesce(b."taxInclusive", false) d_incl
  from a full outer join b on a.sid = b.sid and a.rn = b.rn;
create temp view l1 as
  select sid, bool_or(unpaired) unpaired, bool_or(d_product) product, bool_or(d_variant) variant, bool_or(d_tax) tax,
         bool_or(d_qty) qty, bool_or(d_price) price, bool_or(d_disc) disc, bool_or(d_uom) uom, bool_or(d_factor) factor,
         bool_or(d_sub) sub, bool_or(d_taxamt) taxamt, bool_or(d_total) total, bool_or(d_incl) incl
  from pairs group by sid;
select 'B2 L-1 invoiced submissions checked / failing', count(*),
       count(*) filter (where unpaired or product or variant or tax or qty or price or disc or uom or factor or sub or taxamt or total or incl)
  from l1;
select 'B2 L-1 failure', inv.number, inv."postedAt"::text,
       concat_ws(',', case when unpaired then 'line-count' end, case when product then 'product' end, case when variant then 'variant' end,
                 case when tax then 'tax' end, case when qty then 'qty' end, case when price then 'price' end, case when disc then 'discount' end,
                 case when uom then 'unit' end, case when factor then 'factor' end, case when sub then 'subtotal' end,
                 case when taxamt then 'tax-amount' end, case when total then 'total' end, case when incl then 'tax-inclusive' end)
  from l1 join inv on inv.sid = l1.sid
  where unpaired or product or variant or tax or qty or price or disc or uom or factor or sub or taxamt or total or incl
  order by inv."postedAt";
select 'B3 identical-line groups (groups / lines / submissions / invoiced submissions)', count(*), coalesce(sum(n), 0), count(distinct sid),
       count(distinct sid) filter (where sid in (select sid from inv))
  from (select "submissionId" sid, count(*) n from "SalesSubmissionLine"
        group by "submissionId", "productId", "variantId", "taxId", quantity, "unitPrice", "discountPercent", "uomId", "uomFactor",
                 "lineSubtotal", "lineTax", "lineTotal" having count(*) > 1) g;

-- ===== C. Preflight checks =====
select 'C-1 SalesSubmission.id also in SalesOrder', count(*) from "SalesSubmission" s where exists (select 1 from "SalesOrder" o where o.id = s.id);
select 'C-2 SalesSubmissionLine.id also in SalesOrderLine', count(*) from "SalesSubmissionLine" l where exists (select 1 from "SalesOrderLine" o where o.id = l.id);
select 'C-2 SalesSubmissionPayment.id also in SalesSubmission/Line/SalesOrder', count(*) from "SalesSubmissionPayment" p
  where exists (select 1 from "SalesOrder" o where o.id = p.id) or exists (select 1 from "SalesSubmissionLine" l where l.id = p.id);
select 'C-3 (org, number) overlap CSS vs SalesOrder', count(*) from "SalesSubmission" s
  where exists (select 1 from "SalesOrder" o where o."organizationId" = s."organizationId" and o.number = s.number);
select 'C-3 number patterns (CSS / SO)', (select count(*) from "SalesSubmission" where number !~ '^CSS-'), (select count(*) from "SalesOrder" where number ~ '^CSS-');
select 'C-3 sequences', string_agg(sequencename, ', ' order by sequencename) from pg_sequences
  where sequencename ilike '%css%' or sequencename ilike '%sales_order%' or sequencename ilike '%salesorder%' or sequencename ilike '%so\_%';

select 'RI-1 dangling or cross-org refs on SalesSubmission (partner/branch/profile/location/salesperson)',
  count(*) filter (where s."partnerId" is not null and not exists (select 1 from "Partner" p where p.id = s."partnerId" and p."organizationId" = s."organizationId")),
  count(*) filter (where s."branchId" is not null and not exists (select 1 from "Branch" b where b.id = s."branchId" and b."organizationId" = s."organizationId")),
  count(*) filter (where s."profileId" is not null and not exists (select 1 from "BranchSalesProfile" b where b.id = s."profileId" and b."organizationId" = s."organizationId")),
  count(*) filter (where s."locationId" is not null and not exists (select 1 from "InventoryLocation" l where l.id = s."locationId" and l."organizationId" = s."organizationId")),
  count(*) filter (where s."salespersonId" is not null and not exists (select 1 from "User" u where u.id = s."salespersonId"))
  from "SalesSubmission" s;
select 'RI-1 null partner (walk-in) submissions', count(*) from "SalesSubmission" where "partnerId" is null;
select 'RI-1 dangling or cross-org refs on lines (product/variant/unit/tax)',
  count(*) filter (where not exists (select 1 from "Product" p where p.id = l."productId" and p."organizationId" = l."organizationId")),
  count(*) filter (where l."variantId" is not null and not exists (select 1 from "ProductVariant" v where v.id = l."variantId")),
  count(*) filter (where l."uomId" is not null and not exists (select 1 from "UnitOfMeasure" u where u.id = l."uomId")),
  count(*) filter (where l."taxId" is not null and not exists (select 1 from "Tax" t where t.id = l."taxId" and t."organizationId" = l."organizationId"))
  from "SalesSubmissionLine" l;

select 'RI-2 invoiced submissions / three-way disagreements', count(*),
  count(*) filter (where d.id is null or d."sourceType" is distinct from 'cash_sale' or d."sourceId" is distinct from s.id
                   or not exists (select 1 from "SalesSubmissionInvoice" i where i."submissionId" = s.id and i.status = 'active' and i."invoiceId" = s."currentInvoiceId")
                   or (select count(*) from "SalesSubmissionInvoice" i where i."submissionId" = s.id and i.status = 'active') <> 1)
  from "SalesSubmission" s left join "Document" d on d.id = s."currentInvoiceId" where s."processingStatus" = 'invoiced';
select 'RI-2 cash_sale documents not referenced as a current invoice', count(*) from "Document" d
  where d."sourceType" = 'cash_sale' and not exists (select 1 from "SalesSubmission" s where s."currentInvoiceId" = d.id);
select 'RI-2 non-invoiced submissions with a current invoice', count(*) from "SalesSubmission" where "processingStatus" <> 'invoiced' and "currentInvoiceId" is not null;

select 'RI-3 deposits with unresolved cashSaleId', count(*) from "CustomerDeposit" d where d."cashSaleId" is not null and not exists (select 1 from "SalesSubmission" s where s.id = d."cashSaleId");
select 'RI-3 fiscal documents (total / unresolved submission)', count(*), count(*) filter (where "submissionId" is not null and not exists (select 1 from "SalesSubmission" s where s.id = "submissionId")) from "FiscalDocument";
select 'RI-3 gate passes on cash sales (total / unresolved)', count(*), count(*) filter (where not exists (select 1 from "SalesSubmission" s where s.id = g."sourceId")) from "GatePass" g where g."sourceType" = 'cash_sale';
select 'RI-3 gate-pass lines with unresolved sourceLineId', count(*) from "GatePassLine" gl join "GatePass" g on g.id = gl."gatePassId"
  where g."sourceType" = 'cash_sale' and gl."sourceLineId" is not null and not exists (select 1 from "SalesSubmissionLine" l where l.id = gl."sourceLineId" and l."submissionId" = g."sourceId");
select 'RI-3 cash_sale ledger rows (total / unresolved sale / unresolved line)', count(*),
  count(*) filter (where not exists (select 1 from "SalesSubmission" s where s.id = il."referenceId")),
  count(*) filter (where il."sourceLineId" is null or not exists (select 1 from "SalesSubmissionLine" l where l.id = il."sourceLineId" and l."submissionId" = il."referenceId"))
  from "InventoryLedger" il where il."referenceType" = 'cash_sale';
select 'RI-3 lines with issueLedgerCode not found in cash_sale ledger rows', count(*) from "SalesSubmissionLine" l
  where l."issueLedgerCode" is not null and not exists (select 1 from "InventoryLedger" il where il."ledgerCode" = l."issueLedgerCode" and il."referenceType" = 'cash_sale');
select 'RI-3 DMS links on cash sales', count(*) from "DmsDocumentLink" where "resType" ilike '%cash%' or "resType" ilike '%submission%';

select 'ST-1 states', "executionStatus", "processingStatus", coalesce("deliveryMethod", '-'), coalesce("deliveryStatus", '-'), count(*)
  from "SalesSubmission" group by 2, 3, 4, 5 order by 2, 3, 4, 5;
select 'ST-1 to drain: confirmed, not invoiced (count / open deposit value)', count(*),
  coalesce(sum((select sum(d.amount - d."appliedAmount" - d."refundedAmount") from "CustomerDeposit" d where d."cashSaleId" = s.id and d.status = 'open')), 0)
  from "SalesSubmission" s where s."executionStatus" = 'confirmed' and s."processingStatus" <> 'invoiced';
select 'ST-1 drafts (no ledger effect)', count(*) from "SalesSubmission" where "executionStatus" = 'draft';
select 'ST-1 fiscal pending or unknown', count(*) from "FiscalDocument" where status in ('pending', 'sent', 'unknown');

select 'M-1 header totals vs lines (subtotal / tax / grand mismatches)',
  count(*) filter (where s.subtotal is distinct from x.sub), count(*) filter (where s."taxTotal" is distinct from x.tax), count(*) filter (where s."grandTotal" is distinct from x.tot)
  from "SalesSubmission" s join (select "submissionId", sum("lineSubtotal") sub, sum("lineTax") tax, sum("lineTotal") tot from "SalesSubmissionLine" group by 1) x on x."submissionId" = s.id;
select 'M-1 grandTotal = subtotal + tax (mismatches)', count(*) from "SalesSubmission" where "grandTotal" is distinct from subtotal + "taxTotal";
select 'M-1 confirmed: paidTotal vs verified tenders / vs grandTotal (mismatches)',
  count(*) filter (where s."paidTotal" is distinct from coalesce(p.v, 0)), count(*) filter (where s."paidTotal" is distinct from s."grandTotal")
  from "SalesSubmission" s left join (select "submissionId", sum(amount) filter (where "verificationStatus" = 'verified') v from "SalesSubmissionPayment" group by 1) p on p."submissionId" = s.id
  where s."executionStatus" = 'confirmed';
select 'M-1 invoiced: invoice total vs grandTotal / residual <> 0 / applications vs total (mismatches)',
  count(*) filter (where d."totalAmount" is distinct from s."grandTotal"),
  count(*) filter (where d."amountResidual" <> 0),
  count(*) filter (where coalesce((select sum(a.amount) from "DepositApplication" a where a."documentId" = d.id and a."reversedAt" is null), 0) is distinct from d."totalAmount")
  from "SalesSubmission" s join "Document" d on d.id = s."currentInvoiceId" where s."processingStatus" = 'invoiced';
select 'M-1 invoiced: cash_sale deposits not fully consumed', count(*) from "CustomerDeposit" d join "SalesSubmission" s on s.id = d."cashSaleId"
  where s."processingStatus" = 'invoiced' and (d.status <> 'closed' or d.amount <> d."appliedAmount" + d."refundedAmount");
select 'M-1 voided/cancelled: deposits not refunded or released', count(*) from "CustomerDeposit" d join "SalesSubmission" s on s.id = d."cashSaleId"
  where s."executionStatus" in ('voided', 'cancelled') and d.status <> 'closed';

create temp view m2 as
  select s.id sid, s."executionStatus" es, s."processingStatus" ps, coalesce(sum(jl."baseDebit" - jl."baseCredit"), 0) bal
  from "SalesSubmission" s
  join "AccountMapping" m on m."organizationId" = s."organizationId" and m.key = 'unbilled_receivable' and m."deletedAt" is null
  left join "JournalEntry" je on je.status = 'posted' and ((je."sourceType" in ('cash_sale', 'cash_sale_void') and je."sourceId" = s.id)
                                   or je.id = (select d."journalEntryId" from "Document" d where d.id = s."currentInvoiceId"))
  left join "JournalLine" jl on jl."journalEntryId" = je.id and jl."accountId" = m."accountId"
  group by s.id, s."executionStatus", s."processingStatus";
select 'M-2 unbilled receivable (1215) per sale: state / sales / non-zero / balance', es, ps, count(*), count(*) filter (where bal <> 0), sum(bal)
  from m2 group by es, ps order by es, ps;

select 'D-1 tender providers (raw)', method, coalesce(provider, '-'), count(*), count(*) filter (where coalesce(reference, '') = '') as no_reference
  from "SalesSubmissionPayment" group by 2, 3 order by 2, 3;
select 'D-1 duplicate references across tenders (org, method, normalized provider, reference)', count(*), coalesce(sum(n), 0) from (
  select "organizationId", method,
         case when lower(provider) like 'mtn%' then 'mtn_momo' when lower(provider) like 'airtel%' then 'airtel_money' else lower(coalesce(provider, '')) end np,
         lower(trim(reference)) r, count(*) n
  from "SalesSubmissionPayment" where coalesce(trim(reference), '') <> ''
  group by 1, 2, 3, 4 having count(*) > 1) x;

-- ===== D. Credit notes =====
select 'D1 credit notes', coalesce(d."sourceType", 'NULL'), d.status, (d."reversedDocumentId" is not null) as reverses_invoice,
       coalesce(r."sourceType", '-') as reversed_source, (exists (select 1 from "SalesInvoiceLink" l where l."documentId" = d.id)) as sales_linked, count(*)
  from "Document" d left join "Document" r on r.id = d."reversedDocumentId"
  where d."documentType" = 'credit_note' group by 2, 3, 4, 5, 6;

-- ===== E. Other facts =====
select 'E1 manual sales invoices (NULL source) by org/status', o.code, d.status, count(*)
  from "Document" d join "Organization" o on o.id = d."organizationId"
  where d."documentType" = 'sales_invoice' and d."sourceType" is null group by 2, 3 order by 2, 3;
select 'E2 cash-sale ledger rows with serial / batch', count(*) filter (where "serialId" is not null), count(*) filter (where "batchId" is not null), count(*)
  from "InventoryLedger" where "referenceType" in ('cash_sale', 'cash_sale_void');
select 'E3 V-ID-2 tracked lines: issued base qty vs line qty x factor (lines checked / mismatches / tracked lines without issue on confirmed sales)',
  count(*) filter (where x.q is not null),
  count(*) filter (where x.q is not null and x.q <> l.quantity * coalesce(l."uomFactor", 1)),
  count(*) filter (where x.q is null and p."trackInventory" and s."executionStatus" = 'confirmed')
  from "SalesSubmissionLine" l join "SalesSubmission" s on s.id = l."submissionId" join "Product" p on p.id = l."productId"
  left join (select "sourceLineId", -sum("quantityChange") q from "InventoryLedger" where "referenceType" = 'cash_sale' group by 1) x on x."sourceLineId" = l.id;
select 'E4 POS invoices by org', o.code, count(*) from "Invoice" i join "Organization" o on o.id = i."organizationId" group by 2 order by 2;
select 'E5 cash-sale currencies', "currencyCode", count(*) from "SalesSubmission" group by 2 order by 2;

-- ===== F. Stock-issue linkage (sourceLineId vs issueLedgerCode) =====
select 'F1 tracked lines on confirmed sales: total / no issueLedgerCode / code resolves to rows of the same sale', count(*),
  count(*) filter (where l."issueLedgerCode" is null),
  count(*) filter (where exists (select 1 from "InventoryLedger" il where il."ledgerCode" = l."issueLedgerCode"
                                 and il."referenceType" = 'cash_sale' and il."referenceId" = l."submissionId"))
  from "SalesSubmissionLine" l join "SalesSubmission" s on s.id = l."submissionId" join "Product" p on p.id = l."productId"
  where p."trackInventory" and s."executionStatus" = 'confirmed';
select 'F2 issue codes shared by two lines of one organization', count(*) from (
  select "organizationId", "issueLedgerCode" from "SalesSubmissionLine" where "issueLedgerCode" is not null group by 1, 2 having count(*) > 1) x;
select 'F3 cash_sale rows without sourceLineId: total / matched by exactly one line of the same sale', count(*),
  count(*) filter (where (select count(*) from "SalesSubmissionLine" l where l."issueLedgerCode" = il."ledgerCode" and l."submissionId" = il."referenceId") = 1)
  from "InventoryLedger" il where il."referenceType" = 'cash_sale' and il."sourceLineId" is null;
select 'F4 issued base qty via issueLedgerCode: lines checked / mismatches', count(*), count(*) filter (where q <> qty) from (
  select l.quantity * coalesce(l."uomFactor", 1) qty,
         (select -sum(il."quantityChange") from "InventoryLedger" il where il."ledgerCode" = l."issueLedgerCode"
            and il."referenceType" = 'cash_sale' and il."referenceId" = l."submissionId") q
  from "SalesSubmissionLine" l where l."issueLedgerCode" is not null) x where q is not null;
select 'F6 cash_sale rows: newest without sourceLineId / oldest with', (select max("createdAt")::text from "InventoryLedger" where "referenceType" = 'cash_sale' and "sourceLineId" is null),
  (select min("createdAt")::text from "InventoryLedger" where "referenceType" = 'cash_sale' and "sourceLineId" is not null);
select 'F9 invoiced pickups with deliveryStatus pending: total / stock issued for every tracked line', count(*), count(*) filter (where not exists (
    select 1 from "SalesSubmissionLine" l join "Product" p on p.id = l."productId" where l."submissionId" = s.id and p."trackInventory" and l."issueLedgerCode" is null))
  from "SalesSubmission" s where s."deliveryMethod" = 'pickup' and s."processingStatus" = 'invoiced' and s."deliveryStatus" = 'pending';

-- ===== E6. Customer documents without sourceType: module evidence (for the C4 sourceKind backfill) =====
select 'E6 sales invoices without sourceType', d.status,
  case when exists (select 1 from "FleetDeliveryOrder" f where f."goodsInvoiceId" = d.id) then 'fleet_delivery'
       when exists (select 1 from "FleetDeliveryOrder" f where f."chargesInvoiceId" = d.id)
         or exists (select 1 from "FleetDeliveryCharge" c where c."invoiceId" = d.id) then 'fleet_charges'
       when exists (select 1 from "DunningEntry" e where e."feeInvoiceId" = d.id) then 'collections_fee'
       when exists (select 1 from "RecurringDocumentRun" r where r."documentId" = d.id) then 'recurring'
       when exists (select 1 from "DocumentRelation" r where r."toDocumentId" = d.id or r."fromDocumentId" = d.id) then 'related'
       else 'manual (no module evidence)' end as kind, count(*)
  from "Document" d where d."documentType" = 'sales_invoice' and d."sourceType" is null group by 2, 3 order by 3, 2;

rollback;
