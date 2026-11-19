import { PERMISSIONS } from '@erp/shared';
import { tx } from '@/lib/i18n/tx';
import type { TourDef } from '../types';

const P = PERMISSIONS.sales;

/**
 * Sales tours (ADR-S079). Built on call, not at import, so the text follows the language the user picked.
 * Every anchor named here must exist as a `data-tour` attribute (guarded by catalog.test.ts).
 */
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
      steps: [
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
          body: tx('Open the Learn panel again and choose "Create a quotation" to make your first offer with the guide at your side.'),
          advanceOn: 'next',
        },
      ],
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
        {
          route: '/sales/quotations/:id',
          anchor: 'sales.quote.actions',
          title: tx('Next steps'),
          body: tx('"Submit for approval" checks discounts against company policy and approves automatically when within limits. Then "Mark as sent" when the customer has it, "Customer accepted" when they agree, and "Convert" to turn it into a sales order. The Actions menu has PDF, print and email.'),
          advanceOn: 'next',
        },
        {
          title: tx('Well done!'),
          body: tx('You have created a quotation. When the customer accepts it, it becomes a sales order.'),
          advanceOn: 'next',
        },
      ],
    },
  ];
}
