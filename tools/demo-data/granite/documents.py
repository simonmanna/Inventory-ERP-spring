"""Sample sales documents: quotations, a sales order, a draft invoice and a draft branch cash sale."""
from api import STATE, call, day, log, products, save, variant


def line(p, code, qty=None, size=None, **want):
    """A document line for the variant of `code` matching `want`; `size` = pieces / length / width / height."""
    v = variant(p[code], **want)
    out = {"productId": p[code], "variantId": v["id"], "description": v.get("description") or code,
           "unitPrice": float(v["salesPrice"])}
    out.update(size or {"quantity": qty})
    return out


def section(name):
    return {"displayType": "line_section", "description": name}


def once(key, fn):
    if key in STATE.get("documents", {}):
        log("  skip", key, STATE["documents"][key])
        return
    doc = fn()
    number = doc.get("number") or doc.get("documentNumber") or doc["id"]
    STATE.setdefault("documents", {})[key] = number
    save()
    log("  created", key, number)


def run(ctx):
    p = products("GR-") | products("AGG-") | products("SRV-")
    acme = ctx.customer

    def products_quote():
        return call("POST", "/sales/quotations", {
            "partnerId": acme, "notes": "Demo quotation: granite products with images, sizes and variants.", "lines": [
                section("Kitchen"),
                line(p, "GR-CTP-BBR", size={"pieces": 2, "length": 3.0, "width": 0.6}, Thickness="30 mm", Edge="Bullnose"),
                section("Living room and stairs"),
                line(p, "GR-TIL-RSP", size={"pieces": 120, "length": 0.6, "width": 0.6}, Tile_Size="60 x 60 cm",
                     Finish="Polished"),
                line(p, "GR-STP-TRD", size={"pieces": 14, "length": 1.2}, Colour="Tan Brown", Step_Length="1.2 m"),
                line(p, "GR-SKT", size={"pieces": 12, "length": 2.4}, Colour="Multicolour Red", Skirting_Height="10 cm"),
                section("Site works"),
                line(p, "AGG-CRS", size={"pieces": 2, "length": 4.0, "width": 2.2, "height": 1.0},
                     Grade="Crushed stone 20-25 mm")] + truck()})

    def truck():  # one truck load, priced by the product's truck unit
        if "TRUCK" not in ctx.uom:
            return []
        v = variant(p["AGG-CRS"], Grade="Hardcore 40 mm+")
        return [{"productId": p["AGG-CRS"], "variantId": v["id"], "quantity": 1, "uomId": ctx.uom["TRUCK"],
                 "description": "Crushed Stone Aggregates - Hardcore 40 mm+ (1 truck)"}]

    top = {"pieces": 2, "length": 3.0, "width": 0.6}

    def services_quote():
        return call("POST", "/sales/quotations", {
            "partnerId": acme, "notes": "Kitchen worktop supply, fabrication and installation-ready finishing.", "lines": [
                section("Stone"),
                line(p, "GR-CTP-BBR", size=top, Thickness="30 mm", Edge="Straight"),
                section("Fabrication & processing services"),
                line(p, "SRV-FAB", size=top, Material="Granite", Slab_Thickness="30 mm"),
                line(p, "SRV-EDGE", size={"pieces": 1, "length": 7.2}, Edge_Profile="Full bullnose", Material="Granite"),
                line(p, "SRV-CUTOUT", 1, **{"Cut-out_Type": "Under-mount sink (polished)", "Material": "Granite"}),
                line(p, "SRV-CUTOUT", 1, **{"Cut-out_Type": "Hob / cooktop", "Material": "Granite"}),
                line(p, "SRV-DRILL", 2, Hole_Size="35 mm (tap)", Material="Granite"),
                line(p, "SRV-SEAL", size=top, Sealer="Stain-proof impregnator")]})

    def order():
        return call("POST", "/sales/orders", {
            "partnerId": acme, "notes": "Office reception: floor re-polish and memorial plaque.", "lines": [
                line(p, "SRV-REPOLISH", size={"pieces": 1, "length": 8.0, "width": 6.5}, Polish_Finish="High gloss"),
                line(p, "SRV-REPAIR", 3, Damage="Moderate (cracks, stains)"),
                line(p, "SRV-SEAL", size={"pieces": 1, "length": 8.0, "width": 6.5}, Sealer="Standard sealer"),
                line(p, "SRV-ENGRAVE", 32, Lettering="Engraved + gold leaf", Material="Granite"),
                line(p, "SRV-CNC", 2, Complexity="Medium")]})

    def invoice():
        lines = [
            line(p, "SRV-CUT-PC", 12, Material="Granite", Slab_Thickness="20 mm"),
            line(p, "SRV-CUT-SLAB", 2, Material="Marble", Slab_Thickness="20 mm"),
            line(p, "SRV-WATERJET", size={"pieces": 1, "length": 4.5}, Material="Marble", Slab_Thickness="20 mm"),
            line(p, "SRV-CHAMFER", size={"pieces": 4, "length": 2.4}, Material="Granite"),
            line(p, "SRV-POLISH", size={"pieces": 6, "length": 1.2, "width": 0.6}, Polish_Finish="Leathered",
                 Material="Granite")]
        return call("POST", "/invoices", {"partnerId": acme, "issueDate": day(0), "paymentMode": "credit",
                                          "notes": "Cutting and finishing of customer-supplied stone.",
                                          "lines": [dict(l, lineType="product") for l in lines]}, key=True)

    def cash_sale():
        return call("POST", "/sales/cash-sales", {"branchId": ctx.branch, "partnerId": acme, "lines": [
            line(p, "GR-SKT", size={"pieces": 5, "length": 2.4}, Colour="Absolute Black", Skirting_Height="10 cm"),
            line(p, "SRV-CUT-M2", size={"pieces": 3, "length": 1.2, "width": 0.6}, Material="Granite",
                 Slab_Thickness="20 mm"),
            line(p, "SRV-DRILL", 6, Hole_Size="6-12 mm (fixing)", Material="Granite"),
            line(p, "SRV-CHAMFER", size={"pieces": 5, "length": 2.4}, Material="Granite")]})

    once("products-quotation", products_quote)
    once("services-quotation", services_quote)
    once("services-order", order)
    once("services-invoice-draft", invoice)
    if ctx.branch:
        once("branch-cash-sale-draft", cash_sale)
