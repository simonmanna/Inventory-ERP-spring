# Processing: sell at any stage

Use this when you extract or buy a product, process it, sell some, and keep processing the rest. It works for stone, aggregates, bought goods you modify, and any other product.

Find it under **Manufacturing → Processing**.

## Set up once

1. **Products.** Make one product per stage, such as Raw Granite, Granite Blocks, Polished Granite. Turn on **Batch Tracking**, set **When stock runs out** to **Block the issue** so every sale can name its lot, and set **Production stage**.
2. **Steps.** Create one step per operation: what it takes, what it makes, and its expected yield. Activate it. To change an active step, create a new version; past jobs keep the one they ran on.
3. **Routes** (optional). List the usual order of steps. A route only suggests; it never blocks anything.

**Expected yield matters.** Loss up to expected raises the unit cost of good output. Loss above it goes to expense, and needs a reason and a user who can approve losses.

## Run a job

From **Stock by Stage**, click **Process further** on a lot. Or use **New job**.

- **Post job:** for a step already done. Enter input, costs, output and loss together.
- **Save draft and reserve:** holds the input so nothing else can take it. Then **Start job**, add costs, and record output as it happens.

A job can record output in parts; the rest stays in process at its cost. To complete it, every unit of input must be accounted for as output, recoverable residual or loss.

## Different units

Enter output in its own unit, such as m². For yield, enter the **measured weight** if you have it. Otherwise give dimensions with a density, or the step's factor is used and marked as estimated. The system never converts tonnes to m² on its own.

The job shows **mass yield** (10 t in, 9 t recovered: 90%) and **commercial yield** (8.5 m² per tonne) separately.

If an output's share of cost depends on different units, set the step to share cost by weight or market value.

## Sell

Sell any lot's product on a normal quotation, order, invoice or delivery. Nothing about its stage stops the sale; only **Can be sold** on the product does. The sale's lot is picked automatically, and its actual cost becomes cost of sales. An invoice ships from a warehouse that holds enough of the product. A sale larger than the stock is refused.

## Trace and report

- **Trace** on a lot shows where it came from and where it went, down to the customer.
- **Processing Dashboard** shows stock, work in progress, yield and gross profit by stage, on actual cost.

## Late costs

If a transport or customs bill arrives after processing, post it as a landed cost voucher as usual, then run **Costing → Cost adjustment run**. The part on stock still unprocessed raises that lot's cost. The rest follows the goods through each job: stock still on hand gets its share, and the share of stock already sold goes to current-period cost of sales. Closed-period journals are never rewritten.

**Limitations:**

- Sending the sold share to current-period cost of sales is a proposed policy. The client's accountant has not approved it yet.
- This applies to processing jobs and other staged orders. Ordinary single-step production orders can still send late cost to cost of sales; see [issue #8](https://github.com/simonmanna/Inventory-ERP-spring/issues/8).

## Reversing

A job can be reversed only while its output lots are still fully where they were received. Otherwise the message names what to undo first. Undo the latest document first.

## Limits

- One job takes input from one location. Transfer stock first if it is elsewhere.
- Reservations are per product and location. If another movement takes a named lot first, Start refuses with a clear message.
- A job's inputs share one cost pool. If two lots must keep separate costs, run separate jobs.
- Bulk stockpiles physically mix, so products not tracked by lot trace only to the job.
- Standard-costed and serial-tracked products cannot be used in steps.
