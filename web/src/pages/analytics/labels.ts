import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import type { Format } from './api';

/** Every label of the dashboards, by the key the server sends. Unknown keys show as sent. */
const labels = (): Record<string, string> => ({
  // quarry
  extractionVolume: tx('Extraction volume'), extractionCostPerTonne: tx('Extraction cost per tonne'),
  targetAchievement: tx('Target achievement'), targetTonnes: tx('Target tonnes'), extractionCost: tx('Extraction cost'),
  extractionLoss: tx('Extraction loss'), extractionTrend: tx('Extraction by month'),
  extractionBySite: tx('Extraction by site'), extractionTargets: tx('Plans and targets'),
  netTonnes: tx('Net tonnes'), grossTonnes: tx('Gross tonnes'), logs: tx('Logs'), lossPct: tx('Loss %'),
  totalCost: tx('Total cost'), costPerTonne: tx('Cost per tonne'), siteName: tx('Site'), code: tx('Code'),
  periodStart: tx('Start'), periodEnd: tx('End'), actualTonnes: tx('Actual tonnes'), achievedPct: tx('Achieved %'),
  // aggregates
  inputOutputYield: tx('Input-to-output yield'), productionVolume: tx('Production volume'), feedVolume: tx('Feed volume'),
  materialLoss: tx('Material loss'), materialLossPct: tx('Material loss %'), aggregateCostPerTonne: tx('Cost per tonne'),
  aggregateVolumeTrend: tx('Output and loss by month'), aggregateYieldTrend: tx('Yield by month'),
  aggregatesByPlant: tx('Yield by plant'), aggregatesByProduct: tx('Output by product'), yieldPct: tx('Yield %'),
  outputTonnes: tx('Output tonnes'), lossTonnes: tx('Loss tonnes'), feedTonnes: tx('Feed tonnes'), plantName: tx('Plant'),
  runs: tx('Runs'), expectedYieldPct: tx('Expected yield %'), actualYieldPct: tx('Actual yield %'),
  productName: tx('Product'), role: tx('Role'), expectedTonnes: tx('Expected tonnes'), varianceTonnes: tx('Variance'),
  actualPct: tx('Actual %'),
  // blocks
  blocksProduced: tx('Blocks produced'), blocksPressed: tx('Blocks pressed'), rejectionRate: tx('Rejection rate'),
  secondGrade: tx('Second grade'), costPerAcceptableBlock: tx('Cost per acceptable block'),
  finishedGoodsValue: tx('Finished goods value'), blocksTrend: tx('Good and rejected blocks by month'),
  blocksByProduct: tx('Blocks by product'), blocksBreakage: tx('Breakage by reason'), goodBlocks: tx('Good blocks'),
  rejectedBlocks: tx('Rejected blocks'), orders: tx('Orders'), rejectionRatePct: tx('Rejection rate'),
  stage: tx('Stage'), reasonName: tx('Reason'), quantity: tx('Quantity'), postings: tx('Postings'),
  pressing: tx('Pressing'), cubing: tx('Cubing'),
  // inventory
  stockValue: tx('Stock value'), stockAvailability: tx('Stock availability'), belowMinimum: tx('Below minimum'),
  agedOver90: tx('Aged over 90 days'), slowStock: tx('Slow stock'), deadStock: tx('Dead stock'),
  stockDiscrepancies: tx('Stock discrepancies'), discrepancyValue: tx('Discrepancy value'),
  stockAgingValue: tx('Stock value by age'), stockByLocation: tx('Stock by location'),
  stockDiscrepancySources: tx('Discrepancies by source'), locationName: tx('Location'), branchName: tx('Branch'),
  products: tx('Products'), value: tx('Value'), source: tx('Source'), entries: tx('Entries'),
  ledgerDrift: tx('Ledger drift'), countVariance: tx('Count variance'), surveyVariance: tx('Stockpile survey variance'),
  d0_30: tx('0-30 days'), d31_60: tx('31-60 days'), d61_90: tx('61-90 days'), d91_180: tx('91-180 days'),
  d180_plus: tx('Over 180 days'), d1_30: tx('1-30 days'), d90_plus: tx('Over 90 days'), notDue: tx('Not due'),
  // procurement
  procurementSpend: tx('Procurement spend'), purchaseOrders: tx('Purchase orders'), activeSuppliers: tx('Active suppliers'),
  supplierScore: tx('Supplier score'), supplierOnTime: tx('Supplier on-time'),
  procurementSpendTrend: tx('Spend by month'), purchasePriceTrend: tx('Purchase price by month'),
  supplierPerformance: tx('Supplier performance'), purchasePriceChange: tx('Purchase price change'),
  spend: tx('Spend'), partnerName: tx('Partner'), onTimeRate: tx('On-time %'), fillRate: tx('Fill rate %'),
  qualityRate: tx('Quality %'), priceVarianceRate: tx('Price variance %'), score: tx('Score'),
  firstPeriod: tx('First month'), firstPrice: tx('First price'), lastPeriod: tx('Last month'), lastPrice: tx('Last price'),
  changePct: tx('Change %'),
  // sales
  revenue: tx('Revenue'), salesVolume: tx('Sales volume'), grossMargin: tx('Gross margin'),
  grossMarginPct: tx('Gross margin %'), customerDebt: tx('Customer debt'), overdueDebt: tx('Overdue debt'),
  revenueTrend: tx('Revenue by month'), customerDebtAging: tx('Customer debt by age'),
  salesByCustomer: tx('Sales by customer'), marginByProduct: tx('Gross margin by product'),
  customerDebtByCustomer: tx('Customer debt by customer'), amount: tx('Amount'), label: tx('Name'),
  sharePercent: tx('Share %'), cost: tx('Cost'), margin: tx('Margin'), marginPct: tx('Margin %'),
  costBasis: tx('Cost basis'), total: tx('Total'), production: tx('Production cost'),
  stock_average: tx('Average stock cost'), cost_price: tx('Cost price'),
  // fleet
  deliveriesCompleted: tx('Deliveries completed'), onTimeDeliveries: tx('On-time deliveries'),
  tonnesDelivered: tx('Tonnes delivered'), fuelCost: tx('Fuel cost'), fuelLitres: tx('Fuel litres'),
  transportCost: tx('Transport cost'), transportCostPerTonne: tx('Transport cost per tonne'),
  deliveriesTrend: tx('Deliveries by month'), fuelCostTrend: tx('Fuel cost by month'),
  deliveriesByCustomer: tx('Deliveries by customer'), deliveriesByCarrier: tx('Deliveries by carrier'),
  trips: tx('Trips'), onTimePct: tx('On-time %'), tonnes: tx('Tonnes'), km: tx('Km'),
  // maintenance
  equipmentDowntime: tx('Equipment downtime'), plantDowntime: tx('Plant downtime'),
  fleetAvailability: tx('Fleet availability'), maintenanceExpenditure: tx('Maintenance expenditure'),
  breakdownFrequency: tx('Breakdown frequency'), breakdownsPerUnit: tx('Breakdowns per unit'), mtbf: tx('MTBF'),
  mttr: tx('MTTR'), oee: tx('OEE'), maintenanceSpendTrend: tx('Maintenance spend by month'),
  downtimeByUnit: tx('Downtime by unit'), downtimeByPlant: tx('Downtime by plant'),
  oeeByWorkCenter: tx('OEE by work centre'), partsCost: tx('Parts'), labourCost: tx('Labour'),
  externalCost: tx('External'), unit: tx('Unit'), category: tx('Category'), downtimeHours: tx('Downtime hours'),
  failures: tx('Breakdowns'), availabilityPct: tx('Availability %'), mtbfHours: tx('MTBF'), mttrHours: tx('MTTR'),
  operatingHours: tx('Operating hours'), throughputTph: tx('Tonnes per hour'), workCenterName: tx('Work centre'),
  shifts: tx('Shifts'), downtimeMinutes: tx('Downtime minutes'), availability: tx('Availability %'),
  performance: tx('Performance %'), quality: tx('Quality %'),
  // finance
  netCashFlow: tx('Net cash flow'), closingCash: tx('Closing cash'), operatingExpenses: tx('Operating expenses'),
  financeReceivables: tx('Receivables'), financePayables: tx('Payables'), grossProfit: tx('Gross profit'),
  netProfit: tx('Net profit'), profitMargin: tx('Profit margin'), profitTrend: tx('Income and expenses by month'),
  receivablesAging: tx('Receivables by age'), payablesAging: tx('Payables by age'),
  receivablesByCustomer: tx('Receivables by customer'), payablesBySupplier: tx('Payables by supplier'),
  income: tx('Income'), expense: tx('Expenses'),
  // branch
  branchRevenue: tx('Revenue'), branchExpenses: tx('Expenses'), branchProfit: tx('Net profit'), branchMargin: tx('Margin %'),
  stockPosition: tx('Stock position'), openReceivables: tx('Open receivables'), branchComparison: tx('Branches compared'),
  branchPerformance: tx('Branch performance'), name: tx('Branch'), marginPercent: tx('Margin %'),
  deliveries: tx('Deliveries'), deliveredTonnes: tx('Tonnes delivered'), producedQty: tx('Produced'),
  // drill-downs
  'extraction-logs': tx('Extraction logs'), 'crushing-runs': tx('Crushing runs'), 'precast-orders': tx('Block orders'),
  'below-minimum': tx('Products below minimum'), 'count-variances': tx('Stock counts with variances'),
  'stockpile-surveys': tx('Stockpile surveys with variances'), 'purchase-orders': tx('Purchase orders'),
  invoices: tx('Sales invoices'), receivables: tx('Open sales invoices'), payables: tx('Open vendor bills'),
  'fuel-logs': tx('Fuel logs'), 'work-orders': tx('Work orders'), downtime: tx('Downtime records'),
  // navigation and filters
  analytics: tx('Analytics'),
  branch: tx('Branch'), site: tx('Site'), deliverySite: tx('Delivery site'), product: tx('Product'), partner: tx('Customer'),
});

const notes = (): Record<string, string> => ({
  targetsWholePlan: tx('A plan that overlaps the period counts in full, with its actual tonnes for the whole plan.'),
  blocksByOrderDate: tx('Orders count in the period they were created.'),
  blocksTruncated: tx('Only the first 500 orders of the period are included. Narrow the period.'),
  stockAsOfNow: tx('Stock figures are as of now, whatever the period.'),
  stockAgingPartial: tx('Aging and drift cover part of the stock. Narrow the filters for a full figure.'),
  scorecardGrossSpend: tx('The supplier table shows spend with tax; the tile is net of tax.'),
  scorecardAllProducts: tx('The supplier table covers every product.'),
  debtAsOfNow: tx('Debt is as of now, whatever the period.'),
  marginProductLines: tx('Gross margin covers lines with a product, so its revenue can be lower.'),
  fuelNotByCustomer: tx('Fuel belongs to vehicles, so the customer, site and product filters do not apply to it.'),
  oeeUnavailable: tx('OEE needs all branches and a period of 93 days or less.'),
  cashOrgWide: tx('Cash figures cover the whole organization, so they are hidden in a branch view.'),
  balancesAsOfNow: tx('Open balances are as of now, whatever the period.'),
});

export const kpiLabel = (key: string): string => labels()[key] ?? key;
export const noteLabel = (key: string): string => notes()[key] ?? key;

function number(v: unknown, digits = 2): string {
  const n = Number(v);
  return Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: digits }) : String(v);
}

/** A value as the server's `format` asks; a dash when there is none. */
export function formatValue(v: unknown, format: Format): string {
  if (v === null || v === undefined || v === '') return '—';
  switch (format) {
    case 'money': return money(v as string | number);
    case 'rate': return number(v, 4);
    case 'tonnes': return `${number(v)} t`;
    case 'hours': return `${number(v)} h`;
    case 'percent': return `${number(v)}%`;
    case 'count': return number(v, 0);
    case 'qty': return number(v);
    case 'date': return String(v).slice(0, 10);
    default: return kpiLabel(String(v));
  }
}
