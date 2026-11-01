"""Pull the reports the demo shows and save them (reports/*.json) plus a compact console summary."""
import json
import os

import seed as S

OUT = os.path.join(S.HERE, "reports")
RANGE = {"from": "2026-10-01", "to": "2026-11-30"}

CALLS = {
    "trial_balance": ("/reports/accounting/trial-balance", {"from": "2026-01-01", "to": "2026-12-31"}),
    "profit_and_loss": ("/reports/accounting/profit-and-loss", RANGE),
    "balance_sheet": ("/reports/accounting/balance-sheet", {"asOf": "2026-11-30", "to": "2026-11-30"}),
    "cash_flow": ("/reports/accounting/cash-flow", RANGE),
    "inventory_valuation": ("/reports/inventory/valuation", {}),
    "inventory_gl_tieout": ("/reports/inventory/gl-tieout", {}),
    "negative_stock": ("/inventory/reports/negative-stock", {}),
    "ar_aging": ("/reports/ar/aging", {"asOf": "2026-11-30"}),
    "ar_customer_balances": ("/reports/ar/customer-balances", {}),
    "ap_aging": ("/reports/ap/aging", {"asOf": "2026-11-30"}),
    "sales_analysis": ("/reports/ar/sales-analysis", RANGE),
    "revenue_by_customer": ("/reports/ar/revenue-by-customer", RANGE),
    "sales_profitability": ("/sales/analytics/profitability", RANGE),
    "dashboard_kpi": ("/reports/dashboard-kpi", {}),
    "branch_comparison": ("/reports/branches/comparison", RANGE),
    "cost_center_pl": ("/cost-centers/reports/profit-and-loss", RANGE),
    "landed_cost_by_item": ("/procurement/landed-cost-reports/by-item", RANGE),
    "grni": ("/procurement/reports/grni", {}),
    "tieout": ("/reports/accounting/tieout", RANGE),
}
GROUP = {"intercompany_reconciliation": ("/intercompany/reconciliation", RANGE),
         "consolidation_pl": ("/intercompany/consolidation", dict(RANGE, statement="pl")),
         "consolidation_tb": ("/intercompany/consolidation", dict(RANGE, statement="tb"))}


def pull():
    os.makedirs(OUT, exist_ok=True)
    status = {}
    for org in S.D.ORGS:
        a = S.api(org)
        calls = dict(CALLS, **(GROUP if org == "MENA" else {}))
        for name, (path, params) in calls.items():
            try:
                res = a.get(path, **params)
                status[(org, name)] = "ok"
            except S.ApiError as e:
                res = {"error": str(e)}
                status[(org, name)] = "ERR %s" % e.status
            json.dump(res, open(os.path.join(OUT, "%s.%s.json" % (org, name)), "w"), indent=1, default=str)
    for (org, name), s in status.items():
        if s != "ok":
            print("%-10s %-28s %s" % (org, name, s))
    print("pulled", len(status), "reports;", sum(1 for s in status.values() if s == "ok"), "ok")


if __name__ == "__main__":
    pull()
