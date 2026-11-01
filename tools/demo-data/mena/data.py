"""Master data for the MENA group demo: three sister companies sharing one database (one tenant each).

Prices come from the owner's October 2026 sheets in /sample (cash-sale form, daily sales report, order form,
Canan Developers quotation, 04.10.2026 slab count). Sale prices are marked up 10-30 % (a few lowered) so the demo
does not show the company's real tariff. Costs are illustrative.
"""

PASSWORD = "MenaDemo@2026"   # demo-only admin password, local database

ORGS = {
    "MENA": {"name": "MENA Granite Ltd", "email": "admin@menagranite.test",
             "role": "Head office: granite processing, block plant, distribution"},
    "MENA-QRY": {"name": "MENA Quarries Ltd", "email": "admin@menaquarries.test",
                 "role": "Nakifuma quarry: granite extraction and crushing"},
    "MENA-SHOW": {"name": "MENA Granite Showroom Ltd", "email": "admin@menashowroom.test",
                  "role": "Kabalagala showroom: retail granite, blocks"},
}

BRANCHES = {
    "MENA": [("NMS", "Namasuba HQ & Factory"), ("NTD", "Ntinda Showroom")],
    "MENA-QRY": [("NKF", "Nakifuma Quarry")],
    "MENA-SHOW": [("KBL", "Kabalagala Showroom")],
}

# code, name, branch code. First listed = the org's default sale warehouse (direct invoices issue from it).
LOCATIONS = {
    "MENA": [("HQ-MAIN", "Namasuba Main Store", "NMS"), ("HQ-FACT", "Namasuba Block & Cutting Floor", "NMS"),
             ("HQ-NTD", "Ntinda Showroom Floor", "NTD")],
    "MENA-QRY": [("QRY-YARD", "Nakifuma Stockpile Yard", "NKF"), ("QRY-STORE", "Nakifuma Fuel & Spares Store", "NKF")],
    "MENA-SHOW": [("SHW-KBL", "Kabalagala Showroom Floor", "KBL")],
}

COST_CENTERS = {
    "MENA": ["Administration", "Finance", "Granite Processing", "Block Manufacturing", "Transport & Logistics",
             "Sales - Namasuba", "Sales - Ntinda Showroom"],
    "MENA-QRY": ["Quarry Operations", "Crushing & Screening", "Transport & Logistics", "Quarry Administration"],
    "MENA-SHOW": ["Showroom Sales", "Showroom Administration"],
}

# Second user per company: maker-checker needs someone other than the maker to post journals / approve.
CHECKERS = {"MENA": "accounts@menagranite.test", "MENA-QRY": "accounts@menaquarries.test",
            "MENA-SHOW": "accounts@menashowroom.test"}

# Extra accounts per org on top of the bootstrap chart: code, name, category key, parent code.
ACCOUNTS_COMMON = [
    ("1210", "Stanbic Bank - Current", "bank", "1000"),
    ("1220", "Centenary Bank - Collections", "bank", "1000"),
    ("1610", "Plant & Machinery", "non_current_asset", "1000"),
    ("1620", "Motor Vehicles & Trucks", "non_current_asset", "1000"),
    ("1630", "Buildings & Showroom Fit-out", "non_current_asset", "1000"),
    ("1700", "Due from Sister Companies", "current_asset", "1000"),
    ("2600", "Due to Sister Companies", "current_liability", "2000"),
    ("2700", "Bank Loan - Equipment", "non_current_liability", "2000"),
    ("3010", "Share Capital", "equity", "3000"),
    ("4110", "Aggregate Sales", "revenue", "4000"),
    ("4120", "Concrete Block Sales", "revenue", "4000"),
    ("4130", "Granite Slab & Finishing Sales", "revenue", "4000"),
    ("4140", "Transport & Delivery Income", "revenue", "4000"),
    ("4150", "Intercompany Sales", "revenue", "4000"),
    ("4300", "Management Fee Income", "other_income", "4000"),
    ("6100", "Fuel & Lubricants", "operating_expense", "5000"),
    ("6110", "Electricity & Water", "operating_expense", "5000"),
    ("6120", "Rent", "operating_expense", "5000"),
    ("6130", "Repairs & Maintenance", "operating_expense", "5000"),
    ("6140", "Transport Hire", "operating_expense", "5000"),
    ("6150", "Security", "operating_expense", "5000"),
    ("6160", "Blasting & Explosives", "operating_expense", "5000"),
    ("6170", "Management Fees (Group)", "operating_expense", "5000"),
    ("6180", "Office & Admin", "operating_expense", "5000"),
    ("6190", "Marketing & Advertising", "operating_expense", "5000"),
    ("6200", "Loan Interest", "other_expense", "5000"),
]

# account code -> expense category name (the category's ledger account is what an expense debits)
EXPENSE_CATEGORIES = [("6100", "Fuel & Lubricants"), ("6110", "Electricity & Water"), ("6120", "Rent"),
                      ("6130", "Repairs & Maintenance"), ("6140", "Transport Hire"), ("6150", "Security"),
                      ("6160", "Blasting & Explosives"), ("6180", "Office & Admin"),
                      ("6190", "Marketing & Advertising")]

PAYMENT_TERMS = [("CASH", "Cash before delivery", 0), ("NET7", "7 days", 7), ("NET15", "15 days", 15),
                 ("NET30", "30 days", 30)]

# Product categories per org: name -> income account code.
CATEGORIES = {
    "MENA": {"Raw Materials": None, "Concrete Blocks": "4120", "Granite Slabs": "4130", "Granite Finishing": "4130",
             "Aggregates (resale)": "4110", "Consumables & Spares": None, "Services": "4140"},
    "MENA-QRY": {"Aggregates": "4110", "Quarry Raw Material": "4150", "Consumables & Spares": None,
                 "Services": "4140"},
    "MENA-SHOW": {"Granite Slabs": "4130", "Granite Finishing": "4130", "Concrete Blocks": "4120",
                  "Services": "4140"},
}

EXTRA_UOMS = [("SQM", "Square metre", "m²", "M"), ("RM", "Running metre", "rm", "M"), ("TRIP", "Truck trip", "trip", "UNIT")]

# code, name, category, uom, sale price, cost price, stocked
PRODUCTS = {
    "MENA-QRY": [
        ("QRY-ROM", "Blasted rock (run-of-mine)", "Quarry Raw Material", "TON", 0, 9000, True),
        ("QRY-GRB-RR", "Raw granite block - Royal Red", "Quarry Raw Material", "TON", 210000, 95000, True),
        ("QRY-GRB-SG", "Raw granite block - Spotted Green", "Quarry Raw Material", "TON", 240000, 105000, True),
        ("AGG-DUST06", "Stone dust 0-6mm", "Aggregates", "TON", 38000, 0, True),
        ("AGG-DUST08", "Stone dust 0-8mm", "Aggregates", "TON", 36000, 0, True),
        ("AGG-0610", "Aggregate 6-10mm", "Aggregates", "TON", 40000, 0, True),
        ("AGG-1014", "Aggregate 10-14mm (1/2 inch)", "Aggregates", "TON", 42000, 0, True),
        ("AGG-1420", "Aggregate 14-20mm", "Aggregates", "TON", 41000, 0, True),
        ("AGG-1425", "Aggregate 14-25mm (1 inch)", "Aggregates", "TON", 38000, 0, True),
        ("AGG-CRR", "Stone base CRR", "Aggregates", "TON", 40000, 0, True),
        ("AGG-HARD", "Hardcore", "Aggregates", "TON", 36000, 0, True),
        ("SP-DIESEL", "Diesel", "Consumables & Spares", "L", 0, 5400, True),
        ("SP-EXPL", "Emulsion explosive", "Consumables & Spares", "KG", 0, 14500, True),
        ("SP-DETON", "Detonators", "Consumables & Spares", "UNIT", 0, 9000, True),
        ("SP-JAW", "Crusher jaw plate", "Consumables & Spares", "UNIT", 0, 4800000, True),
        ("SP-BELT", "Conveyor belt section", "Consumables & Spares", "UNIT", 0, 2600000, True),
        ("SVC-TRANS", "Delivery transport (per trip)", "Services", "TRIP", 850000, 0, False),
    ],
    "MENA": [
        ("RM-CEM", "Cement 50kg (Hima)", "Raw Materials", "BAG", 0, 36500, True),
        ("RM-SAND", "Lake sand", "Raw Materials", "TON", 0, 42000, True),
        ("RM-DUST", "Stone dust 0-6mm (from MENA Quarries)", "Raw Materials", "TON", 0, 34000, True),
        ("RM-AGG1014", "Aggregate 10-14mm (from MENA Quarries)", "Aggregates (resale)", "TON", 52000, 37000, True),
        ("RM-AGG1425", "Aggregate 14-25mm (from MENA Quarries)", "Aggregates (resale)", "TON", 48000, 34000, True),
        ("RM-WATER", "Process water", "Raw Materials", "L", 0, 6, True),
        ("RM-GRB-RR", "Raw granite block - Royal Red", "Raw Materials", "TON", 0, 210000, True),
        ("RM-GRB-SG", "Raw granite block - Spotted Green", "Raw Materials", "TON", 0, 240000, True),
        ("RM-ABRAS", "Polishing abrasives set", "Consumables & Spares", "UNIT", 0, 85000, True),
        ("RM-RESIN", "Epoxy resin", "Consumables & Spares", "KG", 0, 38000, True),
        ("SP-DIESEL", "Diesel", "Consumables & Spares", "L", 0, 5400, True),
        ("BLK-4", "Hollow block 4 inch", "Concrete Blocks", "UNIT", 1800, 0, True),
        ("BLK-6", "Hollow block 6 inch", "Concrete Blocks", "UNIT", 2300, 0, True),
        ("BLK-8", "Hollow block 8 inch", "Concrete Blocks", "UNIT", 2800, 0, True),
        ("GRN-RR", "Granite slab - Royal Red (polished)", "Granite Slabs", "SQM", 110000, 0, True),
        ("GRN-SG", "Granite slab - Spotted Green (polished)", "Granite Slabs", "SQM", 150000, 0, True),
        ("GRN-GSB", "Granite slab - Green spotted slash black", "Granite Slabs", "SQM", 165000, 98000, True),
        ("GRN-SBB", "Granite slab - Spotted black with brown", "Granite Slabs", "SQM", 125000, 72000, True),
        ("GRN-DF", "Granite slab - Dragon Fire", "Granite Slabs", "SQM", 120000, 70000, True),
        ("GRN-VW", "Granite slab - Viscount White (imported)", "Granite Slabs", "SQM", 265000, 0, True),
        ("GRN-WG", "Granite slab - Wave Gray (imported)", "Granite Slabs", "SQM", 175000, 0, True),
        ("FIN-MOLD", "Edge molding (per running metre)", "Granite Finishing", "RM", 12000, 4000, False),
        ("FIN-SKIRT", "Granite skirting", "Granite Finishing", "RM", 32000, 0, True),
        ("SVC-TRANS", "Delivery transport (per trip)", "Services", "TRIP", 90000, 0, False),
        ("SVC-OFFLOAD", "Offloading", "Services", "UNIT", 40000, 0, False),
    ],
    "MENA-SHOW": [
        ("GRN-RR", "Granite slab - Royal Red (polished)", "Granite Slabs", "SQM", 125000, 0, True),
        ("GRN-SG", "Granite slab - Spotted Green (polished)", "Granite Slabs", "SQM", 170000, 0, True),
        ("GRN-GSB", "Granite slab - Green spotted slash black", "Granite Slabs", "SQM", 185000, 0, True),
        ("GRN-DF", "Granite slab - Dragon Fire", "Granite Slabs", "SQM", 140000, 0, True),
        ("GRN-VW", "Granite slab - Viscount White (imported)", "Granite Slabs", "SQM", 295000, 0, True),
        ("FIN-SKIRT", "Granite skirting", "Granite Finishing", "RM", 36000, 0, True),
        ("FIN-MOLD", "Edge molding (per running metre)", "Granite Finishing", "RM", 13000, 0, False),
        ("BLK-6", "Hollow block 6 inch", "Concrete Blocks", "UNIT", 2500, 0, True),
        ("BLK-8", "Hollow block 8 inch", "Concrete Blocks", "UNIT", 3000, 0, True),
        ("SVC-TRANS", "Delivery transport (per trip)", "Services", "TRIP", 100000, 0, False),
    ],
}

# code, name, customer?, supplier?, terms, phone. Names come from the sales sheets (individuals by first name); real
# phone numbers are left out of the repository.
PARTNERS = {
    "MENA-QRY": [
        ("C-ACME", "ACME Construction (order account)", True, False, "NET30", None),
        ("C-SPEAR", "Spear On Road Ltd", True, False, "NET15", None),
        ("C-TEXA", "Texa Solution Ltd", True, False, "NET15", None),
        ("C-METAZ", "Metazone Construction", True, False, "NET7", None),
        ("C-KASULE", "Eng. Kasule", True, False, "NET7", None),
        ("C-MALIK", "Malik Ssenabulya", True, False, "CASH", None),
        ("C-SARAJ", "Sarajan Construction Company", True, False, "NET15", None),
        ("C-WALKIN", "Walk-in quarry customers", True, False, "CASH", None),
        ("S-PETRO", "Petro Industrial Supplies Ltd", False, True, "NET15", None),
        ("S-EXPL", "Uganda Explosives & Blasting Ltd", False, True, "NET30", None),
        ("S-HMP", "Heavy Machinery Parts Ltd", False, True, "NET30", None),
        ("S-UMEME", "Umeme Ltd", False, True, "NET7", None),
        ("S-KTL", "Kampala Transporters Ltd", False, True, "NET15", None),
        ("IC-HQ", "MENA Granite Ltd", True, True, "NET30", None),
    ],
    "MENA": [
        ("C-ZERI", "Zerigaber General Ltd", True, False, "NET15", None),
        ("C-FIDEL", "Fidel Establishment", True, False, "NET15", None),
        ("C-CANAN", "Canan Developers", True, False, "NET30", None),
        ("C-SHAY", "Shay Builders", True, False, "NET7", None),
        ("C-OPHIR", "Ophir Contractors", True, False, "NET7", None),
        ("C-PAUL", "Mr Paul (Kololo villa project)", True, False, "NET15", None),
        ("C-MAWA", "Mawa Interiors", True, False, "NET15", None),
        ("C-WALKIN", "Walk-in customers", True, False, "CASH", None),
        ("S-HIMA", "Hima Cement Distributors", False, True, "NET30", None),
        ("S-SAND", "Lake Victoria Sand Dealers", False, True, "NET7", None),
        ("S-IMPORT", "Xiamen Stone Export Co. (imports)", False, True, "NET30", None),
        ("S-CLEAR", "Kampala Clearing & Forwarding Ltd", False, True, "NET7", None),
        ("S-PETRO", "Petro Industrial Supplies Ltd", False, True, "NET15", None),
        ("S-ABRAS", "Industrial Abrasives & Chemicals Ltd", False, True, "NET30", None),
        ("S-UMEME", "Umeme Ltd", False, True, "NET7", None),
        ("S-LAND", "Namasuba Property Holdings (landlord)", False, True, "NET7", None),
        ("S-TIPPER", "Namasuba Tipper Hire", False, True, "NET7", None),
        ("IC-QRY", "MENA Quarries Ltd", True, True, "NET30", None),
        ("IC-SHOW", "MENA Granite Showroom Ltd", True, True, "NET30", None),
    ],
    "MENA-SHOW": [
        ("C-KARIM", "Karim", True, False, "CASH", None),
        ("C-LOYAL", "Loyality Rolex", True, False, "CASH", None),
        ("C-MICH", "Micheal Andom", True, False, "CASH", None),
        ("C-SIIMBA", "Siimba Company Ltd", True, False, "NET15", None),
        ("C-MAPUK", "Mapuk Developers", True, False, "NET15", None),
        ("C-WARA", "Wara Construction", True, False, "NET7", None),
        ("C-LUZIGE", "Luzige Interiors", True, False, "NET7", None),
        ("C-WALKIN", "Walk-in showroom customers", True, False, "CASH", None),
        ("S-LAND", "Kabalagala Plaza (landlord)", False, True, "NET7", None),
        ("S-UMEME", "Umeme Ltd", False, True, "NET7", None),
        ("S-TIPPER", "Kabalagala Boda & Pickup Delivery", False, True, "NET7", None),
        ("IC-HQ", "MENA Granite Ltd", True, True, "NET30", None),
    ],
}
