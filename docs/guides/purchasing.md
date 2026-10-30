# Purchasing guide

How to buy with Inventory-ERP, from "we need cement" to "the supplier is paid". Everything here is under **Purchases**
in the menu. What you see depends on your permissions.

## The everyday flow

1. **Ask for it: Purchase Requests → New.** Add the items, quantities, the date you need them and where they should be
   delivered. Optionally add a preferred supplier, cost centre or project. Submit it, and a manager approves it.
2. **Decide how to buy: Sourcing Workbench.** Approved request lines wait here. You have two choices:
   - **Buy now.** Tick the lines, pick the supplier and store, then **Create purchase order**.
   - **Compare first.** Tick the lines, choose **Request quotations**, invite suppliers and send the RFQ.
3. **Compare quotes: RFQs → open the RFQ.**
   - Enter each supplier's prices, or send them a **Link** so they type the quote in themselves.
   - The comparison shows the total delivered cost (price + freight, converted to your currency) and a weighted score.
   - Award each line, splitting it between suppliers if you want. If you do not pick the cheapest offer, write why.
   - **Create purchase orders** makes one order per supplier.
4. **Order: Purchases → the order.**
   - Approval follows your approval workflows.
   - **Print** the order, **Mark as sent**, and record the supplier's confirmation. The supplier can also confirm
     through a **Supplier link**.
   - Need to change it? Use **Request change**. Reductions apply at once; increases may need a second approver, and
     the supplier is asked to confirm again.
5. **Receive: Goods Receipts.**
   - Receive what arrived.
   - If your organisation requires it, bulk material must come over the **weighbridge** first.
   - Goods that need a quality check go to **Incoming Inspection**.
6. **Inspect: Incoming Inspection.** Record the rejected quantity and the defects (e.g. torn bags, wet cement), then
   **Complete**. Rejected goods automatically become a **supplier claim**. The supplier's bill waits until the
   inspection is done.
7. **Bill and pay.**
   - Enter the supplier bill against the order.
   - The three-way match compares the bill with what was ordered and received, within the tolerances your
     organisation set.
   - Possible duplicate bills are flagged or blocked.
8. **Claim back: Supplier Claims.** Use a claim for short deliveries, damage, quality rejects, price differences or late
   delivery. Send it, record the supplier's answer, then **Settle**. This creates a debit note that reduces what you
   owe.

## Buying regularly

- **Purchase Agreements.**
  - Negotiate once: a quantity over a period (blanket) or fixed prices (rate contract).
  - A second person activates the agreement.
  - **Release now** creates an order against it. The remaining quantity is shown, and the agreement closes when it is
    used up or expires.
- **Replenishment.**
  - Set a minimum and maximum per item and store, or let the system use your actual daily use and lead time.
  - The page lists what is low. **Raise request** creates a draft purchase request.
  - Rules marked *automatic* do this every night.

## Control and oversight

- **Purchasing Policies** change how strict purchasing is, without code. Each change is approved by a second person
  and starts on its date. Set a policy for the whole organisation, a branch or a product category:
  - only approved suppliers; required certificates (e.g. explosives licence)
  - requisition needed above an amount
  - over-receipt tolerance; weighbridge required; inspection required
  - budget control (warn / block / block with override)
  - match tolerances and two-way matching for services
  - duplicate bill check
  - when order changes need re-approval
- **Budget Commitments.** Money promised on approved orders counts against budgets before the bill arrives. The budget
  screen shows *committed* and *available* next to actuals.
- **Order Changes** lists changes waiting for a second approver.
- **Emergency Purchases.** When work cannot wait (a crusher breakdown), flag the order as an emergency with a reason.
  Someone other than the buyer reviews it afterwards.
- **Supplier Register** holds each supplier's status, bank accounts (verified by a second person), certificates with
  expiry reminders, products, and price history.

## Insight

**Purchasing Dashboard** shows:
- spend by supplier and month
- supplier scorecards: on time, in full, rejects, claims, lead time and an A–D grade
- alerts for late deliveries, high rejects, single-source items and expiring certificates

While ordering, the price check warns when a price is unusually high or low compared with the last 12 months.

## Supplier links

A supplier link opens exactly one RFQ or order for one supplier. There is nothing else behind it.
- It expires after 14 days unless you choose otherwise, and you can revoke it.
- The supplier needs no account: they open the link and quote, decline, or confirm the order.
- Every use is recorded.
