import { PERMISSIONS } from '@erp/shared';
import { tx } from '@/lib/i18n/tx';
import type { TourDef, TourStep } from '../types';

const P = PERMISSIONS.sales;

/*
 * Sales tours (ADR-S079). Built on call, not at import, so the text follows the language the user picked.
 * Every anchor named here must exist as a `data-tour` attribute (guarded by catalog.test.ts).
 *
 * The steps of each task are kept apart from the tour, so the order-to-cash tour can chain them.
 */

const overviewSteps = (): TourStep[] => [
  {
    route: '/sales',
    anchor: 'sales.flow',
    title: tx('From quote to cash'),
    body: tx('Every sale follows the same four stages. This row shows how much money sits at each stage right now.'),
    advanceOn: 'next',
  },
  {
    route: '/sales',
    anchor: 'sales.flow.1',
    title: tx('1. Quotations'),
    body: tx('A quotation is your offer to the customer: products, quantities and prices. Nothing leaves the warehouse and nothing is owed yet.'),
    advanceOn: 'next',
  },
  {
    route: '/sales',
    anchor: 'sales.flow.2',
    title: tx('2. Sales orders'),
    body: tx('When the customer accepts, the quotation is converted into a sales order. The order is the promise to deliver.'),
    advanceOn: 'next',
  },
  {
    route: '/sales',
    anchor: 'sales.flow.3',
    title: tx('3. Deliveries'),
    body: tx('Confirmed orders wait here until the goods are delivered. Stock leaves the warehouse when a delivery is validated.'),
    advanceOn: 'next',
  },
  {
    route: '/sales',
    anchor: 'sales.flow.4',
    title: tx('4. Invoices and payment'),
    body: tx('The order is invoiced and the customer owes the money until the payment is recorded. Click any stage to see the documents behind it.'),
    advanceOn: 'next',
  },
  {
    title: tx('Ready for the first step?'),
    body: tx('Open the Learn panel again and choose "Create a quotation", or "Quote to cash" to follow one sale from start to finish.'),
    advanceOn: 'next',
  },
];

/** From the quotation list to a saved draft quotation. */
const createQuotationSteps = (): TourStep[] => [
  {
    route: '/sales/quotations',
    anchor: 'sales.quotes.new',
    title: tx('Start a new quotation'),
    body: tx('All quotations are listed here. Click "New quotation" to start one.'),
    advanceOn: { click: 'sales.quotes.new' },
  },
  {
    route: '/sales/quotations/new',
    anchor: 'sales.quote.customer',
    title: tx('Choose the customer'),
    body: tx('Type part of the customer\'s name and pick them from the list. If they are new, use the + button to add them.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/quotations/new',
    anchor: 'sales.quote.dates',
    title: tx('Dates'),
    body: tx('The expiration is the last day the offer is valid. The delivery date is what you promise the customer; it carries over to the order.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/quotations/new',
    anchor: 'sales.quote.add-line',
    title: tx('Add a product'),
    body: tx('Click "Add a line" to add a product. You can also open the Catalog to add several products at once.'),
    advanceOn: { click: 'sales.quote.add-line' },
  },
  {
    route: '/sales/quotations/new',
    anchor: 'sales.quote.lines',
    title: tx('Fill in the line'),
    body: tx('Pick the product, then enter the quantity. The price comes from the price list; change it only if you agreed a different price. Add more lines the same way.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/quotations/new',
    anchor: 'sales.quote.totals',
    title: tx('Check the totals'),
    body: tx('Taxes and the total are worked out for you. Use "Tax Incl." / "Tax Excl." above the lines to choose how prices are entered.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/quotations/new',
    anchor: 'sales.quote.save',
    title: tx('Save'),
    body: tx('Click Save. The quotation gets its number and is kept as a draft.'),
    advanceOn: { route: '/sales/quotations/:id' },
  },
  {
    route: '/sales/quotations/:id',
    anchor: 'sales.quote.ribbon',
    title: tx('Where the quotation stands'),
    body: tx('This ribbon shows the stage: Draft, Approved, Sent, Accepted, Converted. It moves on as you use the buttons.'),
    advanceOn: 'next',
  },
];

/** Opened quotation → approved and accepted → sales order. */
const quoteToOrderSteps = (): TourStep[] => [
  {
    route: '/sales/quotations/:id',
    anchor: 'sales.quote.actions',
    title: tx('Get it approved and accepted'),
    body: tx('"Submit for approval" checks discounts against company policy and approves automatically when within limits. Then "Mark as sent" when the customer has it, and "Customer accepted" when they agree. The Convert button appears once the quotation is approved.'),
    advanceOn: { visible: 'sales.quote.convert' },
  },
  {
    route: '/sales/quotations/:id',
    anchor: 'sales.quote.convert',
    title: tx('Convert'),
    body: tx('The customer said yes. Click Convert to turn the quotation into an order.'),
    advanceOn: { click: 'sales.quote.convert' },
  },
  {
    route: '/sales/quotations/:id',
    anchor: 'sales.quote.convert-target',
    title: tx('Choose "Sales order"'),
    body: tx('A sales order keeps the agreed prices and tracks delivery, invoicing and changes. Choose a proforma instead when the customer pays in advance.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/quotations/:id',
    anchor: 'sales.quote.convert-confirm',
    title: tx('Create the order'),
    body: tx('Click Convert. If some products are short in stock you will be told which, and can still create the order as a back-order.'),
    advanceOn: { click: 'sales.quote.convert-confirm' },
  },
  {
    route: '/sales/quotations/:id',
    anchor: 'sales.quote.links',
    title: tx('Open the new order'),
    body: tx('The quotation is now Converted. Click the Order button to open the sales order it created.'),
    advanceOn: { route: '/sales/orders/:id' },
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.ribbon',
    title: tx('Your sales order'),
    body: tx('The order carries the customer, lines and prices of the quotation. The ribbon follows it through delivery and invoicing.'),
    advanceOn: 'next',
  },
];

/** Opened sales order → confirmed → deliveries, delivery notes and proof of delivery. */
const confirmAndDeliverSteps = (): TourStep[] => [
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.actions',
    title: tx('Confirm the order'),
    body: tx('A draft order is only a plan. "Confirm order" commits to it: the customer\'s credit limit is checked, and the order may go on hold or to approval if it is over a limit.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.confirm',
    title: tx('Click "Confirm order"'),
    body: tx('Click it now. If the order is already confirmed this button is not shown: press Skip.'),
    advanceOn: { click: 'sales.order.confirm' },
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.tab.delivery',
    title: tx('Delivery & fulfilment'),
    body: tx('Open this tab to plan how the goods reach the customer.'),
    advanceOn: { click: 'sales.order.tab.delivery' },
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.deliveries',
    title: tx('Delivery plan'),
    body: tx('The strip at the top shows the requested date and how far the order is: scheduled, dispatched, delivered. Below it, choose the delivery method, address and dispatch warehouse.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.add-delivery',
    title: tx('Add a delivery'),
    body: tx('Enter what goes out on this trip, the date, vehicle and driver, then click "Add delivery". An order can go out in several deliveries; each one gets its own delivery note number. This box is shown while something is left to schedule.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.delivery-list',
    title: tx('Dispatch and confirm each delivery'),
    body: tx('Every row is one delivery with its delivery note (DN-…). Open the ⋮ menu on a row: "Dispatch (issue stock)" when the goods leave the warehouse, "Mark in transit" on the road, and "Confirm delivery" with what the customer actually received.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.delivery-list',
    title: tx('The delivery note'),
    body: tx('In the same ⋮ menu, "Delivery note (PDF)" downloads the delivery note and "Print delivery note" prints it for the driver. The customer signs it on receipt; the PDF and print buttons in the documents list below do the same.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.line-fulfilment',
    title: tx('Fulfilment by order line'),
    body: tx('For each product: how much is scheduled, dispatched, delivered, invoiced and still outstanding. Returns and damaged goods show here too.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.availability',
    title: tx('Is the stock there?'),
    body: tx('Availability shows, line by line, what is in stock in the dispatch warehouse. Reserve stock here so another order cannot take it.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.links',
    title: tx('Follow-up documents'),
    body: tx('These buttons count the documents made from this order: its quotation, deliveries and invoices. Click one to open them.'),
    advanceOn: 'next',
  },
];

/** Opened sales order → draft invoice → posted. */
const invoiceSteps = (): TourStep[] => [
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.tab.billing',
    title: tx('Open Billing'),
    body: tx('Invoices are made from the order. Open the Billing tab.'),
    advanceOn: { click: 'sales.order.tab.billing' },
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.billing',
    title: tx('What can be invoiced'),
    body: tx('This shows the lines and amount ready to invoice: usually what has been delivered. Ask for an advance with "Down payment invoice", or record money already received with "Record deposit".'),
    advanceOn: 'next',
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.invoice',
    title: tx('Create the invoice'),
    body: tx('Click "Create invoice". A draft invoice is made for the amount shown and opens straight away.'),
    advanceOn: { route: '/invoices/:id' },
  },
  {
    route: '/invoices/:id',
    anchor: 'invoice.ribbon',
    title: tx('Draft, Posted, Paid'),
    body: tx('A draft invoice can still be checked and changed. It counts in the books only once it is posted.'),
    advanceOn: 'next',
  },
  {
    route: '/invoices/:id',
    anchor: 'invoice.actions',
    title: tx('Post and collect'),
    body: tx('"Confirm" posts the invoice: it gets its final number, the customer owes the amount, and it can no longer be edited. When the customer pays, use "Register Payment". PDF prints or sends the invoice.'),
    advanceOn: 'next',
  },
];

/** Showroom: a new cash sale with its lines and payment. */
const showroomSteps = (): TourStep[] => [
  {
    route: '/sales/cash-sales',
    anchor: 'sales.cash.new',
    title: tx('Start a cash sale'),
    body: tx('A branch cash sale is for a customer who pays at the showroom, without a quotation or order. Click "New cash sale".'),
    advanceOn: { click: 'sales.cash.new' },
  },
  {
    route: '/sales/cash-sales/new',
    anchor: 'sales.cash.customer',
    title: tx('Customer'),
    body: tx('Leave the walk-in customer, or pick a named customer when they need an invoice in their name. Head office checks these details later.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/new',
    anchor: 'sales.cash.add-line',
    title: tx('Add what was sold'),
    body: tx('Click "Add a line", pick the product and enter the quantity. Add one line per product.'),
    advanceOn: { click: 'sales.cash.add-line' },
  },
  {
    route: '/sales/cash-sales/new',
    anchor: 'sales.cash.totals',
    title: tx('Totals'),
    body: tx('The totals are an estimate until you save; saving works out prices and taxes exactly.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/new',
    anchor: 'sales.cash.save',
    title: tx('Save the draft'),
    body: tx('Click Save. The sale gets its number; payments can be added once it is saved.'),
    advanceOn: { route: '/sales/cash-sales/:id' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.tab.payments',
    title: tx('Payments'),
    body: tx('Open "Billing & payments details" to record how the customer paid.'),
    advanceOn: { click: 'sales.cash.tab.payments' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.add-payment',
    title: tx('Add the payment'),
    body: tx('Click "Add payment" and enter the payment mode, the amount and the reference, such as the mobile money code. Split payments are added one by one. A supervisor marks each payment Verified once the money is seen.'),
    advanceOn: 'next',
  },
];

/** Confirm the cash sale, hand the goods over and print the delivery note. */
const cashHandoverSteps = (): TourStep[] => [
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.confirm',
    title: tx('Confirm sale & handover'),
    body: tx('Click "Confirm sale & handover". Stock, payments and revenue are posted once, here. After that the amounts are frozen. If your branch does not confirm sales itself, the button is not shown: press Skip.'),
    advanceOn: { click: 'sales.cash.confirm' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.tab.delivery',
    title: tx('Delivery and fulfilment'),
    body: tx('Open this tab to record how the goods reach the customer.'),
    advanceOn: { click: 'sales.cash.tab.delivery' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.delivery',
    title: tx('Collect or deliver'),
    body: tx('Choose "Customer collect" when the customer takes the goods, or delivery to site with the address, contact, vehicle and driver.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'handover.actions',
    title: tx('Record the handover'),
    body: tx('Use Schedule and "Mark dispatched" for a delivery, then "Mark delivered" (or "Mark collected") with the name of the person who received the goods.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'handover.note',
    title: tx('Delivery note'),
    body: tx('The delivery note lists what was handed over. Print it or download the PDF for the customer to sign; it also lists any returns.'),
    advanceOn: 'next',
  },
];

/** Branch sends the confirmed sale to head office. */
const cashSubmitSteps = (): TourStep[] => [
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.submit',
    title: tx('Submit to head office'),
    body: tx('Click "Submit to head office". Head office reviews the sale and issues the official invoice. If the button is not shown, the sale is not confirmed yet or was already submitted: press Skip.'),
    advanceOn: { click: 'sales.cash.submit' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.ribbon',
    title: tx('Track it here'),
    body: tx('The ribbon shows where the sale stands. If head office returns it for correction, the reason is in the Timeline tab: correct it and resubmit.'),
    advanceOn: 'next',
  },
];

/** Head office: from the processing queue to the official invoice. */
const headOfficeSteps = (): TourStep[] => [
  {
    route: '/sales/cash-sales/processing',
    anchor: 'sales.cash.queues',
    title: tx('Branch Sales Processing'),
    body: tx('Head office sees every branch\'s submitted sales here, by queue: Pending review, Awaiting invoice, Returned, Rejected and Invoiced.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/processing',
    anchor: 'sales.cash.list',
    title: tx('Open a sale'),
    body: tx('Click a sale in the list to review it.'),
    advanceOn: { route: '/sales/cash-sales/:id' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.start-review',
    title: tx('Start the review'),
    body: tx('Click "Start review" so the branch sees that head office is checking it. Already under review? Press Skip.'),
    advanceOn: { click: 'sales.cash.start-review' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.review',
    title: tx('Verification checklist'),
    body: tx('Tick each check: customer, products and prices, tax, payment evidence, stock issue and delivery. Below it you see what was posted when the branch confirmed the sale.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.actions',
    title: tx('Approve, return or reject'),
    body: tx('"Approve & invoice" when everything is right: one click approves the sale, posts its invoice, applies the branch payments (method, reference, payer) and copies the delivery onto the invoice. "Approve only" leaves invoicing for later. "Return for correction" sends it back to the branch with a reason; "Reject" refuses it (the posting stays until the sale is reopened or returned).'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.generate-invoice',
    title: tx('Generate the invoice'),
    body: tx('Approved with "Approve only"? Click "Generate invoice". It moves the sale to receivables, applies the payments and copies the delivery; nothing is posted twice. Already invoiced? Press Skip.'),
    advanceOn: { click: 'sales.cash.generate-invoice' },
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.links',
    title: tx('The official invoice'),
    body: tx('The Invoice button opens the invoice made for this sale. Payments, Delivery and Timeline are one click away too.'),
    advanceOn: 'next',
  },
];

/** Sales reports: from the hub to one report, exported. */
const reportSteps = (slug: string, pick: string): TourStep[] => [
  {
    route: '/sales/reports',
    anchor: 'sales.reports.filters',
    title: tx('Sales reports'),
    body: tx('Choose the period and branch here; the overview and every report use them.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/reports',
    anchor: 'sales.reports.catalog',
    title: tx('All sales reports'),
    body: tx('Reports are grouped by subject. Search by name if you know what you are looking for.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/reports',
    anchor: `sales.reports.page.${slug}`,
    title: tx('Open the report group'),
    body: pick,
    advanceOn: { route: '/sales/reports/:page' },
  },
  {
    route: '/sales/reports/:page',
    anchor: 'sales.report.rail',
    title: tx('Pick a report'),
    body: tx('Every report of the group is listed on the left. Click one to show it.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/reports/:page',
    anchor: 'sales.report.filters',
    title: tx('Filters'),
    body: tx('Narrow the report by period, branch, customer, product or salesperson. The figures update straight away.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/reports/:page',
    anchor: 'sales.report.body',
    title: tx('Read the report'),
    body: tx('Totals at the top, then the chart and the table. Click a column heading to sort, and a row to drill down to the documents behind it.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/reports/:page',
    anchor: 'sales.report.export',
    title: tx('Export and print'),
    body: tx('Switch between chart and table, export to Excel, CSV or PDF, or print the report.'),
    advanceOn: 'next',
  },
];

/** Bridge to a sidebar page from anywhere. */
const goTo = (path: string, title: string, body: string): TourStep => ({
  anchor: `nav:${path}`,
  title,
  body,
  advanceOn: { route: path },
});

/** Opening step for tours that start on a record picked from a list. */
const openFromList = (route: string, target: string, body: string): TourStep => ({
  route,
  title: tx('Open a document'),
  body,
  advanceOn: { route: target },
});

const done = (body: string): TourStep => ({ title: tx('Well done!'), body, advanceOn: 'next' });

/** Mark the first step of a part with its chapter heading. */
const chapter = (name: string, steps: TourStep[]): TourStep[] =>
  steps.map((s, i) => (i === 0 ? { ...s, chapter: name } : s));

export function salesTours(): TourDef[] {
  return [
    {
      key: 'sales.overview',
      version: 1,
      module: 'sales',
      title: tx('Sales at a glance'),
      summary: tx('How a sale moves from quotation to cash, and where to find each step.'),
      keywords: ['dashboard', 'overview', 'start', 'introduction', 'flow', 'basics'],
      permissions: [P.reportRead],
      startPath: '/sales',
      steps: overviewSteps(),
    },
    {
      key: 'sales.create-quotation',
      version: 1,
      module: 'sales',
      title: tx('Create a quotation'),
      summary: tx('Make an offer for a customer: choose the customer, add products and prices, save and send it.'),
      keywords: ['quote', 'quotation', 'offer', 'estimate', 'new', 'customer', 'price'],
      permissions: [P.quotationWrite],
      startPath: '/sales/quotations',
      steps: [
        ...createQuotationSteps(),
        {
          route: '/sales/quotations/:id',
          anchor: 'sales.quote.actions',
          title: tx('Next steps'),
          body: tx('"Submit for approval" checks discounts against company policy. Then "Mark as sent", "Customer accepted" and finally "Convert" to make it a sales order. The Actions menu has PDF, print and email.'),
          advanceOn: 'next',
        },
        done(tx('You have created a quotation. When the customer accepts it, take the "Quotation to sales order" tour.')),
      ],
    },
    {
      key: 'sales.quote-to-order',
      version: 1,
      module: 'sales',
      title: tx('Quotation to sales order'),
      summary: tx('The customer accepted: get the quotation approved and convert it into a sales order.'),
      keywords: ['convert', 'accepted', 'order', 'approve', 'won', 'quote'],
      permissions: [P.quotationConvert, PERMISSIONS.crm.quotationConvert],
      startPath: '/sales/quotations',
      steps: [
        openFromList('/sales/quotations', '/sales/quotations/:id', tx('Click the quotation the customer accepted to open it.')),
        ...quoteToOrderSteps(),
        done(tx('The quotation is now a sales order. Next: confirm it and deliver.')),
      ],
    },
    {
      key: 'sales.confirm-and-deliver',
      version: 2,
      module: 'sales',
      title: tx('Confirm and deliver an order'),
      summary: tx('Confirm a sales order, plan deliveries, dispatch them, confirm receipt and print the delivery note.'),
      keywords: ['confirm', 'deliver', 'delivery', 'note', 'dispatch', 'stock', 'availability', 'reserve', 'fulfilment', 'ship', 'driver'],
      permissions: [P.orderConfirm, P.deliveryWrite],
      startPath: '/sales/orders',
      steps: [
        openFromList('/sales/orders', '/sales/orders/:id', tx('Click a sales order to open it.')),
        ...confirmAndDeliverSteps(),
        done(tx('The order is confirmed and its deliveries planned. Next: invoice it.')),
      ],
    },
    {
      key: 'sales.invoice-order',
      version: 1,
      module: 'sales',
      title: tx('Invoice an order'),
      summary: tx('Create the invoice from a sales order, post it and register the payment.'),
      keywords: ['invoice', 'bill', 'billing', 'payment', 'deposit', 'down', 'post', 'collect'],
      permissions: [P.invoiceCreate],
      startPath: '/sales/orders',
      steps: [
        openFromList('/sales/orders', '/sales/orders/:id', tx('Click the sales order you want to invoice.')),
        ...invoiceSteps(),
        done(tx('The order is invoiced. Unpaid invoices show under "To collect" on the Sales dashboard.')),
      ],
    },
    {
      key: 'sales.cash-sale',
      version: 2,
      module: 'sales',
      title: tx('Record a branch cash sale'),
      summary: tx('At the showroom: record the sale and payment, hand the goods over with a delivery note and submit to head office.'),
      keywords: ['cash', 'branch', 'showroom', 'walk', 'counter', 'pay', 'payment', 'mobile', 'money', 'receipt', 'shop', 'delivery', 'note', 'submit'],
      permissions: [P.cashSaleCreate],
      startPath: '/sales/cash-sales',
      steps: [...showroomSteps(), ...cashHandoverSteps(), ...cashSubmitSteps(),
        done(tx('The sale is recorded, handed over and with head office.'))],
    },
    {
      key: 'sales.branch-cash-review',
      version: 1,
      module: 'sales',
      title: tx('Process branch sales (head office)'),
      summary: tx('Review the sales submitted by branches, approve, return or reject them, and issue the official invoice.'),
      keywords: ['head', 'office', 'review', 'approve', 'branch', 'cash', 'processing', 'verify', 'invoice', 'return', 'reject'],
      permissions: [P.cashSaleReview, P.cashSaleApprove, P.cashSaleInvoice],
      startPath: '/sales/cash-sales/processing',
      steps: [...headOfficeSteps(), done(tx('The branch sale is approved and invoiced.'))],
    },
    {
      key: 'sales.branch-cash-full',
      version: 1,
      module: 'sales',
      title: tx('Branch cash sales: showroom to report'),
      summary: tx('The whole branch cash sale in five chapters: the sale, handover and delivery note, submission, head-office review and invoice, and the Branch Cash Sales report.'),
      keywords: ['branch', 'cash', 'full', 'whole', 'complete', 'process', 'wizard', 'showroom', 'head', 'office', 'report', 'delivery', 'note'],
      permissions: [P.cashSaleCreate, P.cashSaleReadAll],
      startPath: '/sales/cash-sales',
      steps: [
        ...chapter(tx('Chapter 1 · The sale'), showroomSteps()),
        ...chapter(tx('Chapter 2 · Handover and delivery note'), cashHandoverSteps()),
        ...chapter(tx('Chapter 3 · Submit to head office'), cashSubmitSteps()),
        ...chapter(tx('Chapter 4 · Head-office review and invoice'), [
          goTo('/sales/cash-sales/processing', tx('Over to head office'),
            tx('Head office works from "Branch Sales Processing" in the Sales menu. Click it (it needs head-office rights; otherwise ask a colleague to show you).')),
          ...headOfficeSteps(),
        ]),
        ...chapter(tx('Chapter 5 · Branch Cash Sales report'), [
          goTo('/sales/reports', tx('Reports'), tx('Click "Sales Reports" in the Sales menu, under Reporting.')),
          ...reportSteps('branch-cash', tx('Click "Branch Cash Sales": takings per branch and day, the cash / mobile money / bank split, the sale register and head-office processing.')),
        ]),
        done(tx('You have followed a branch cash sale from the showroom to the report.')),
      ],
    },
    {
      key: 'sales.reports',
      version: 1,
      module: 'sales',
      title: tx('Use the sales reports'),
      summary: tx('Find a report, filter it by period and branch, drill down, and export it to Excel or PDF.'),
      keywords: ['report', 'reports', 'analysis', 'export', 'excel', 'pdf', 'print', 'filter', 'period', 'branch'],
      permissions: [P.reportRead],
      startPath: '/sales/reports',
      steps: [
        ...reportSteps('performance', tx('Click "Sales Performance" to open the summary, period sales and comparisons.')),
        done(tx('You can now find, filter and export any sales report.')),
      ],
    },
    {
      key: 'sales.order-to-cash',
      version: 2,
      module: 'sales',
      title: tx('Quote to cash: quotation to delivery note and reports'),
      summary: tx('One sale from start to finish in five chapters: quotation, sales order, delivery and delivery note, invoice, and the reports.'),
      keywords: ['full', 'whole', 'complete', 'process', 'wizard', 'end', 'cycle', 'order', 'cash', 'quote', 'delivery', 'note', 'report'],
      permissions: [P.quotationWrite],
      startPath: '/sales/quotations',
      steps: [
        ...chapter(tx('Chapter 1 · Quotation'), createQuotationSteps()),
        ...chapter(tx('Chapter 2 · Sales order'), quoteToOrderSteps()),
        ...chapter(tx('Chapter 3 · Delivery and delivery note'), confirmAndDeliverSteps()),
        ...chapter(tx('Chapter 4 · Invoice'), invoiceSteps()),
        ...chapter(tx('Chapter 5 · Reports'), [
          goTo('/sales/reports', tx('See it in the reports'), tx('Click "Sales Reports" in the Sales menu, under Reporting.')),
          ...reportSteps('orders', tx('Click "Orders & Fulfilment": order registers, backorders, deliveries, trips and drivers. "Billing & Collections" next to it covers invoices and payments.')),
        ]),
        done(tx('You have taken a sale from quotation through delivery note and invoice to the reports. That is the whole Sales cycle.')),
      ],
    },
  ];
}
