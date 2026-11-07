"""Tenant, branches, warehouses, units, products, partners, employees, fleet."""
from core import A, ADMIN, CHECKER, ORG, PASSWORD, bootstrap, ensure, ids, once, rows, save

PRODUCTS = [  # code, name, category, list price per tonne
    ("RAW-GRANITE", "Raw Granite (ROM)", "Raw Material", 0),
    ("AGG-DUST", "Stone Dust / Quarry Fines (0-5mm)", "Aggregates", 38000),
    ("AGG-0510", "Aggregate (5-10mm)", "Aggregates", 72000),
    ("AGG-1020", "Aggregate (10-20mm)", "Aggregates", 68000),
    ("AGG-2040", "Aggregate (20-40mm)", "Aggregates", 65000),
    ("AGG-CRUN", "Crusher Run", "Aggregates", 42000),
    ("AGG-MSAND", "Manufactured Sand", "Sand & Fines", 58000),
    ("AGG-REJ", "Reject Material", "Waste", 6000),
]
CUSTOMERS = [  # code, name, phone, site, zone, km
    ("C-KRW", "Kampala Roadworks Ltd (DEMO)", "+256700100101", "Northern Bypass Upgrade", "0-10 KM", 8),
    ("C-MUK", "Mukono Builders Co-op (DEMO)", "+256700100102", "Mukono Town Housing", "11-25 KM", 22),
    ("C-JIN", "Jinja Highway Contractors (DEMO)", "+256700100103", "Jinja Road Section 4", "26-50 KM", 48),
    ("C-MAS", "Masaka Precast Ltd (DEMO)", "+256700100104", "Masaka Precast Yard", "51-100 KM", 92),
    ("C-GUL", "Gulu Dam Engineering (DEMO)", "+256700100105", "Karuma Access Road", "Above 100 KM", 210),
    ("C-WAK", "Wakiso Ready-Mix Ltd (DEMO)", "+256700100106", "Wakiso Batching Plant", "11-25 KM", 18),
]
SUPPLIERS = [("S-FUEL", "Central Fuel Distributors (DEMO)"), ("S-POWER", "Regional Power Utility (DEMO)"),
             ("S-MINING", "Mining Authority Levy Account (DEMO)")]
EMPLOYEES = [  # code, first, last, job title
    ("GR-001", "Grace", "Namaganda", "General Manager"), ("GR-002", "Patrick", "Ssemakula", "Quarry Manager"),
    ("GR-003", "Rose", "Akello", "Production Manager"), ("GR-004", "Isaac", "Mugisha", "Quality Control Officer"),
    ("GR-005", "Sarah", "Nakato", "Weighbridge Operator"), ("GR-006", "Moses", "Okello", "Fleet Manager"),
    ("GR-007", "Joan", "Atim", "Storekeeper"), ("GR-008", "David", "Kato", "Sales Manager"),
    ("GR-009", "Esther", "Nabirye", "Accountant"), ("GR-010", "Robert", "Lubega", "Excavator Operator"),
    ("GR-011", "Henry", "Opio", "Excavator Operator"), ("GR-012", "Peter", "Wasswa", "Wheel Loader Operator"),
    ("GR-013", "Tom", "Ssebunya", "Truck Driver"), ("GR-014", "Alex", "Etyang", "Truck Driver"),
    ("GR-015", "Brian", "Mutebi", "Truck Driver"), ("GR-016", "Ivan", "Tumusiime", "Truck Driver"),
    ("GR-017", "Simon", "Odongo", "Truck Driver"), ("GR-018", "Paul", "Kiggundu", "Truck Driver"),
]
CATEGORIES = [("TIPPER-HD", "Heavy-duty Tipper Truck", "road_vehicle", "odometer"),
              ("TRUCK-MD", "Medium-duty Truck", "road_vehicle", "odometer"),
              ("EXCAVATOR", "Quarry Excavator", "mobile_plant", "hours"),
              ("LOADER", "Wheel Loader", "mobile_plant", "hours"),
              ("DOZER", "Bulldozer", "mobile_plant", "hours"),
              ("CRUSHER", "Crushing Equipment", "fixed_plant", "hours")]
VEHICLES = [  # code, name, plate, class, payload, make, model, meter, opening reading
    ("T-01", "Tipper 01", "UBL 482K", "TIPPER-HD", 30, "Sinotruk", "Howo 371", "odometer", 84210),
    ("T-02", "Tipper 02", "UBK 351S", "TIPPER-HD", 30, "Sinotruk", "Howo 371", "odometer", 112450),
    ("T-03", "Tipper 03", "UAZ 455M", "TIPPER-HD", 30, "Shacman", "F3000", "odometer", 96320),
    ("T-04", "Tipper 04", "UBA 812T", "TIPPER-HD", 30, "Shacman", "F3000", "odometer", 71880),
    ("M-01", "Medium Truck 01", "UBH 207C", "TRUCK-MD", 12, "Isuzu", "FVZ", "odometer", 143200),
    ("M-02", "Medium Truck 02", "UBJ 664F", "TRUCK-MD", 12, "Isuzu", "FVZ", "odometer", 128750),
    ("EX-01", "Excavator 01", None, "EXCAVATOR", None, "Komatsu", "PC360", "hours", 8420),
    ("EX-02", "Excavator 02", None, "EXCAVATOR", None, "CAT", "336D", "hours", 11230),
    ("EX-03", "Excavator 03", None, "EXCAVATOR", None, "Hitachi", "ZX350", "hours", 6410),
    ("WL-01", "Wheel Loader 01", None, "LOADER", None, "CAT", "966H", "hours", 9310),
    ("WL-02", "Wheel Loader 02", None, "LOADER", None, "Liugong", "856H", "hours", 5120),
    ("DZ-01", "Bulldozer 01", None, "DOZER", None, "Komatsu", "D85", "hours", 7050),
    ("CR-01", "Mobile Jaw Crusher", None, "CRUSHER", None, "Metso", "LT106", "hours", 12400),
]
DRIVERS = {"T-01": "GR-013", "T-02": "GR-014", "T-03": "GR-015", "T-04": "GR-016", "M-01": "GR-017", "M-02": "GR-018"}


def tenant():
    def go():
        bootstrap({"organizationCode": ORG, "organizationName": "GreenRock Construction Materials Ltd (DEMO)",
                   "timezone": "Africa/Kampala", "currencyCode": "UGX", "adminEmail": ADMIN,
                   "adminFirstName": "GreenRock", "adminLastName": "Admin", "adminPassword": PASSWORD})
        return True
    once("tenant", go)
    A.login()
    ids("self")["userId"] = A.user.get("id")
    ids("self")["orgId"] = A.user.get("organizationId")
    save()


def org_setup():
    br = ids("branch")
    for x in rows(A.get("/branches", pageSize=100)):
        br[x["code"]] = x["id"]
    for code, name in (("GR-QRY", "Main Quarry & Production Plant"), ("GR-CEN", "Central Distribution Depot"),
                       ("GR-NTH", "Northern Distribution Depot")):
        if code not in br:
            br[code] = A.post("/branches", {"code": code, "name": name, "timezone": "Africa/Kampala"})["id"]
    save()
    for code, name, b in (
            ("WH-ROM-A", "Raw Stone / ROM Warehouse - Granite Ridge", "GR-QRY"),
            ("WH-ROM-B", "Raw Stone / ROM Warehouse - Eastern Rock Face", "GR-QRY"),
            ("WH-ROM-C", "Raw Stone / ROM Warehouse - Southern Extension", "GR-QRY"),
            ("WH-AGG", "Crushed Aggregates Warehouse", "GR-QRY"),
            ("WH-SAND", "Sand & Fines Warehouse", "GR-QRY"),
            ("WH-FIN", "Finished Products Warehouse", "GR-QRY"),
            ("WH-REJ", "Rejects and Waste Holding Area", "GR-QRY"),
            ("WH-CEN", "Central Depot Yard", "GR-CEN"), ("WH-NTH", "Northern Depot Yard", "GR-NTH")):
        ensure("location", code, "/inventory/locations", "/inventory/locations",
               {"code": code, "name": name, "type": "warehouse", "branchId": br[b]}, params={"pageSize": 200})

    # Second user: postings that need maker-checker are approved by the accountant.
    def checker():
        if any(u.get("email") == CHECKER for u in rows(A.get("/users", limit=200))):
            return True
        role = next(r for r in rows(A.get("/roles")) if r["name"] == "Administrator")
        return A.post("/users", {"email": CHECKER, "password": PASSWORD, "firstName": "Esther", "lastName": "Nabirye",
                                 "roleIds": [role["id"]]})["id"]
    once("checker", checker)
    print("  branches", len(br), "locations", len(ids("location")))


def catalogue():
    uoms = {u["code"]: u for u in rows(A.get("/uoms", pageSize=200))}
    print("  uoms:", sorted(uoms))
    u = ids("uom")
    for code, x in uoms.items():
        u[code] = x["id"]
    save()
    cats = {c["name"]: c["id"] for c in rows(A.get("/product-categories", pageSize=200))}
    pc = ids("pcat")
    for name in ("Raw Material", "Aggregates", "Sand & Fines", "Waste"):
        pc[name] = cats.get(name) or A.post("/product-categories", {"name": name})["id"]
    save()
    ton = next(c for c in ("TON", "T", "MT", "TONNE", "t") if c in u)
    ids("self")["ton"] = ton
    for code, name, cat, sale in PRODUCTS:
        ensure("product", code, "/products", "/products", {
            "code": code, "name": name + " (DEMO)", "sku": code, "categoryId": pc[cat], "uomId": u[ton],
            "salesUomId": u[ton], "purchaseUomId": u[ton], "productType": "stockable", "trackInventory": True,
            "costingMethod": "AVCO", "salesPrice": sale, "costPrice": 0, "allowFractionalSale": True},
            params={"pageSize": 200})
    terms = rows(A.get("/payment-terms"))
    term = next((t["id"] for t in terms if t.get("netDays") == 30), None) or A.post(
        "/payment-terms", {"code": "NET30", "name": "Net 30", "netDays": 30, "method": "net_days"})["id"]
    for code, name, phone, *_ in CUSTOMERS:
        ensure("partner", code, "/partners", "/partners", {"code": code, "name": name, "isCompany": True,
               "isCustomer": True, "isSupplier": False, "phone": phone, "paymentTermId": term},
               params={"pageSize": 200})
    for code, name in SUPPLIERS:
        ensure("partner", code, "/partners", "/partners", {"code": code, "name": name, "isCompany": True,
               "isCustomer": False, "isSupplier": True, "paymentTermId": term}, params={"pageSize": 200})
    print("  products", len(ids("product")), "partners", len(ids("partner")))


def people():
    e = ids("employee")
    have = {x.get("employeeCode"): x["id"] for x in rows(A.get("/hr/employees", limit=200))}
    for code, first, last, job in EMPLOYEES:
        if code not in e:
            e[code] = have.get(code) or A.post("/hr/employees", {
                "employeeCode": code, "firstName": first, "lastName": last, "jobTitle": job,
                "employmentType": "FULL_TIME", "phone": "+25670" + code[-3:] + "0000"})["id"]
    save()
    print("  employees", len(e))


def fleet():
    br, cat = ids("branch"), ids("fleetcat")
    for code, name, kind, meter in CATEGORIES:
        ensure("fleetcat", code, "/fleet/categories", "/fleet/categories",
               {"code": code, "name": name, "kind": kind, "defaultMeterType": meter})
    for code, name, plate, c, payload, make, model, meter, opening in VEHICLES:
        ensure("vehicle", code, "/fleet/vehicles", "/fleet/vehicles", {
            "code": code, "name": name + " (DEMO)", "registrationPlate": plate, "categoryId": cat[c],
            "ownership": "owned", "make": make, "model": model, "year": 2021, "payloadCapacityT": payload,
            "fuelType": "diesel", "meterType": meter, "openingOdometer": opening if meter == "odometer" else None,
            "openingHours": opening if meter == "hours" else None, "branchId": br["GR-QRY"],
            "notes": "DEMO fleet record", "acquiredOn": "2021-03-01"}, params={"pageSize": 200})
    qual = [cat["TIPPER-HD"], cat["TRUCK-MD"]]
    for v, emp in DRIVERS.items():
        ensure("driver", v, "/fleet/drivers", "/fleet/drivers", {
            "code": "DRV-" + v, "employeeId": ids("employee")[emp], "driverType": "driver",
            "licenceNumber": "DEMO-LIC-" + emp, "licenceClasses": ["CH"], "licenceIssuedOn": "2022-01-01",
            "licenceExpiresOn": "2029-01-01", "medicalExpiresOn": "2028-01-01", "qualifiedCategoryIds": qual,
            "notes": "DEMO driver"}, field="__state_only__", params={"pageSize": 1})
    print("  vehicles", len(ids("vehicle")), "drivers", len(ids("driver")))


def run():
    tenant()
    org_setup()
    catalogue()
    people()
    fleet()
