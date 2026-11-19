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

/** Opened sales order → confirmed → delivery planned. */
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
    title: tx('Plan the deliveries'),
    body: tx('Choose the delivery method and address, then add a delivery for what goes out now. An order can be delivered in several parts; the strip at the top shows what is still to schedule.'),
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
    anchor: 'sales.order.tab.billing',
    title: tx('Billing'),
    body: tx('Open the Billing tab. It shows what has been delivered and what can be invoiced.'),
    advanceOn: { click: 'sales.order.tab.billing' },
  },
  {
    route: '/sales/orders/:id',
    anchor: 'sales.order.fulfilment',
    title: tx('Fulfilment'),
    body: tx('"Create delivery order" sends the order to the warehouse. Stock is issued when the delivery is dispatched, and the order is delivered when the customer confirms receipt.'),
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

/** A walk-in sale paid on the spot. */
const cashSaleSteps = (): TourStep[] => [
  {
    route: '/sales/cash-sales',
    anchor: 'sales.cash.new',
    title: tx('Start a cash sale'),
    body: tx('A cash sale is for a customer who pays and takes the goods now, without a quotation or order. Click "New cash sale".'),
    advanceOn: { click: 'sales.cash.new' },
  },
  {
    route: '/sales/cash-sales/new',
    anchor: 'sales.cash.customer',
    title: tx('Customer'),
    body: tx('Leave the walk-in customer, or pick a named customer when they need an invoice in their name.'),
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
    body: tx('Click Save. Payments can be added once the sale is saved.'),
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
    body: tx('Click "Add payment" and enter the payment mode, the amount and any reference such as the mobile money code. Split payments are added one by one.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.actions',
    title: tx('Finish the sale'),
    body: tx('Depending on your branch, either "Confirm sale & handover" posts the stock, payments and revenue at once, or "Submit to head office" sends the sale for review first.'),
    advanceOn: 'next',
  },
  {
    route: '/sales/cash-sales/:id',
    anchor: 'sales.cash.ribbon',
    title: tx('Track it here'),
    body: tx('The ribbon shows where the sale stands, from draft to invoiced.'),
    advanceOn: 'next',
  },
];

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
      version: 1,
      module: 'sales',
      title: tx('Confirm and deliver an order'),
      summary: tx('Confirm a sales order, check stock and plan the delivery.'),
      keywords: ['confirm', 'deliver', 'delivery', 'dispatch', 'stock', 'availability', 'reserve', 'fulfilment', 'ship'],
      permissions: [P.orderConfirm],
      startPath: '/sales/orders',
      steps: [
        openFromList('/sales/orders', '/sales/orders/:id', tx('Click a sales order to open it.')),
        ...confirmAndDeliverSteps(),
        done(tx('The order is confirmed and on its way. Next: invoice it.')),
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
      version: 1,
      module: 'sales',
      title: tx('Record a cash sale'),
      summary: tx('A walk-in customer pays and takes the goods: record the sale and the payment.'),
      keywords: ['cash', 'walk', 'counter', 'pay', 'payment', 'mobile', 'money', 'receipt', 'shop'],
      permissions: [P.cashSaleCreate],
      startPath: '/sales/cash-sales',
      steps: [...cashSaleSteps(), done(tx('You have recorded a cash sale.'))],
    },
    {
      key: 'sales.order-to-cash',
      version: 1,
      module: 'sales',
      title: tx('Quote to cash: the full sale'),
      summary: tx('One sale from start to finish: quotation, sales order, delivery and invoice, in four chapters.'),
      keywords: ['full', 'whole', 'complete', 'process', 'wizard', 'end', 'cycle', 'order', 'cash', 'quote'],
      permissions: [P.quotationWrite],
      startPath: '/sales/quotations',
      steps: [
        ...chapter(tx('Chapter 1 · Quotation'), createQuotationSteps()),
        ...chapter(tx('Chapter 2 · Sales order'), quoteToOrderSteps()),
        ...chapter(tx('Chapter 3 · Delivery'), confirmAndDeliverSteps()),
        ...chapter(tx('Chapter 4 · Invoice'), invoiceSteps()),
        done(tx('You have taken a sale from quotation to invoice. That is the whole Sales cycle.')),
      ],
    },
  ];
}
