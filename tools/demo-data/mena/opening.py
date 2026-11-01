"""Opening position at 31 Oct 2026 (go-live 1 Nov 2026), derived from the October sheets.

Stock: code -> (location, qty, unit cost). GL: account code -> debit(+)/credit(-). AR/AP: partner code -> amount.
Intercompany balances agree across the three companies (they eliminate on consolidation).
"""

OPENING_DATE = "2026-10-31"

STOCK = {
    "MENA-QRY": [
        ("QRY-ROM", "QRY-YARD", 6000, 9000), ("QRY-GRB-RR", "QRY-YARD", 120, 95000),
        ("QRY-GRB-SG", "QRY-YARD", 80, 105000), ("AGG-DUST06", "QRY-YARD", 1800, 19500),
        ("AGG-DUST08", "QRY-YARD", 600, 19000), ("AGG-0610", "QRY-YARD", 400, 22000),
        ("AGG-1014", "QRY-YARD", 900, 23000), ("AGG-1420", "QRY-YARD", 500, 23000),
        ("AGG-1425", "QRY-YARD", 1400, 21000), ("AGG-CRR", "QRY-YARD", 700, 20000),
        ("AGG-HARD", "QRY-YARD", 1000, 16000), ("SP-DIESEL", "QRY-STORE", 12000, 5400),
        ("SP-EXPL", "QRY-STORE", 2000, 14500), ("SP-DETON", "QRY-STORE", 400, 9000),
        ("SP-JAW", "QRY-STORE", 2, 4800000), ("SP-BELT", "QRY-STORE", 3, 2600000),
    ],
    "MENA": [
        ("RM-CEM", "HQ-MAIN", 600, 36500), ("RM-SAND", "HQ-MAIN", 120, 42000), ("RM-DUST", "HQ-MAIN", 150, 34000),
        ("RM-AGG1014", "HQ-MAIN", 80, 37000), ("RM-AGG1425", "HQ-MAIN", 60, 34000),
        ("RM-WATER", "HQ-MAIN", 50000, 6), ("RM-GRB-RR", "HQ-MAIN", 40, 210000), ("RM-GRB-SG", "HQ-MAIN", 25, 240000),
        ("RM-ABRAS", "HQ-MAIN", 30, 85000), ("RM-RESIN", "HQ-MAIN", 120, 38000), ("SP-DIESEL", "HQ-MAIN", 3000, 5400),
        ("BLK-4", "HQ-MAIN", 6000, 1050), ("BLK-6", "HQ-MAIN", 9000, 1250), ("BLK-8", "HQ-MAIN", 7500, 1500),
        # 04.10.2026 slab count (pieces x ~1.9 m2), less what the showroom company holds
        ("GRN-RR", "HQ-MAIN", 190, 62000), ("GRN-SG", "HQ-MAIN", 40, 85000), ("GRN-GSB", "HQ-MAIN", 240, 98000),
        ("GRN-SBB", "HQ-MAIN", 210, 72000), ("GRN-DF", "HQ-MAIN", 190, 70000), ("GRN-VW", "HQ-MAIN", 20, 165000),
        ("GRN-WG", "HQ-MAIN", 6, 110000), ("FIN-SKIRT", "HQ-MAIN", 300, 12000),
    ],
    "MENA-SHOW": [
        ("GRN-RR", "SHW-KBL", 60, 99000), ("GRN-SG", "SHW-KBL", 25, 135000), ("GRN-GSB", "SHW-KBL", 30, 148000),
        ("GRN-DF", "SHW-KBL", 30, 108000), ("GRN-VW", "SHW-KBL", 15, 238000), ("FIN-SKIRT", "SHW-KBL", 120, 28800),
        ("BLK-6", "SHW-KBL", 1500, 2070), ("BLK-8", "SHW-KBL", 1200, 2520),
    ],
}

GL = {
    "MENA-QRY": {"1210": 186500000, "1220": 42300000, "1100": 6800000, "MOMO-MTN": 3200000,
                 "1610": 2850000000, "1620": 960000000, "1490": -420000000,
                 "2700": -1200000000, "3010": -1500000000},
    "MENA": {"1210": 248000000, "1220": 61500000, "1100": 9400000, "MOMO-MTN": 4100000,
             "1610": 1250000000, "1620": 420000000, "1630": 380000000, "1490": -260000000,
             "2700": -800000000, "3010": -1000000000},
    "MENA-SHOW": {"1220": 38600000, "1100": 4200000, "MOMO-MTN": 2900000, "1630": 145000000, "1490": -18000000,
                  "3010": -100000000},
}

AR = {
    "MENA-QRY": {"C-ACME": 78400000, "C-SPEAR": 22900000, "C-TEXA": 6100000, "C-KASULE": 1944510,
                 "IC-HQ": 46500000},
    "MENA": {"C-ZERI": 9600000, "C-FIDEL": 4500000, "C-CANAN": 18000000, "C-PAUL": 14514455, "IC-SHOW": 28700000},
    "MENA-SHOW": {"C-SIIMBA": 3510000, "C-MAPUK": 7257500},
}

AP = {
    "MENA-QRY": {"S-PETRO": 18600000, "S-EXPL": 12400000, "S-HMP": 9800000},
    "MENA": {"S-HIMA": 21900000, "S-SAND": 3200000, "IC-QRY": 46500000},
    "MENA-SHOW": {"IC-HQ": 28700000},
}
