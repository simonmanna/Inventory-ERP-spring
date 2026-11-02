"""Entry point: python run.py [setup] [opening] [production] [quarry] [intercompany] [hq] [showroom]  (default: all)"""
import sys

import scenarios as SC
import crm as CRM
import fixups as FX
import seed as S

PHASES = {"setup": S.setup, "opening": S.opening, "production": SC.masters_production, "quarry": SC.quarry,
          "intercompany": SC.intercompany, "hq": SC.hq, "showroom": SC.showroom, "quotations": SC.quotations, "fixups": FX.fixups,
          "fixups2": FX.fixups2, "crm": CRM.crm}

if __name__ == "__main__":
    for ph in sys.argv[1:] or list(PHASES):
        print("#", ph)
        try:
            PHASES[ph]()
        except S.ApiError as e:
            print("FAILED in", ph, ":", e)
            raise SystemExit(1)
