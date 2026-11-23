package com.poscafe.pos.data.repo

import com.poscafe.pos.data.local.dao.ProductDao
import com.poscafe.pos.data.local.dao.ProductPackagingDao
import com.poscafe.pos.data.local.dao.ProductVariantDao
import com.poscafe.pos.data.local.entity.ProductEntity
import com.poscafe.pos.data.local.entity.ProductPackagingEntity
import com.poscafe.pos.data.local.entity.ProductVariantEntity
import javax.inject.Inject
import javax.inject.Singleton

/** What a scanned (or typed) code is on this device. */
sealed interface ScanMatch {
    val code: String

    /** A product's own barcode or SKU. */
    data class Product(override val code: String, val product: ProductEntity) : ScanMatch

    /** A variant's own barcode or SKU: exactly that variant. */
    data class Variant(override val code: String, val product: ProductEntity, val variant: ProductVariantEntity) : ScanMatch

    /** A multipack barcode (scan the case → `pack.quantity` base units). */
    data class Pack(override val code: String, val product: ProductEntity, val pack: ProductPackagingEntity) : ScanMatch

    /** Nothing on this device carries the code. */
    data class NotFound(override val code: String) : ScanMatch
}

/**
 * Resolves a barcode against the synced catalog, offline, in the till's order: a single-unit product barcode/SKU
 * wins, then a variant's own code, then a multipack barcode. Shared by the POS terminal, the Barcode app and the
 * quick-add form so a code means the same thing everywhere.
 */
@Singleton
class BarcodeLookup @Inject constructor(
    private val productDao: ProductDao,
    private val variantDao: ProductVariantDao,
    private val packagingDao: ProductPackagingDao,
) {
    suspend fun find(raw: String): ScanMatch {
        val code = raw.trim()
        if (code.isEmpty()) return ScanMatch.NotFound(code)
        productDao.byCode(code)?.let { return ScanMatch.Product(code, it) }
        variantDao.byCode(code)?.let { v ->
            productDao.byId(v.productId)?.let { return ScanMatch.Variant(code, it, v) }
        }
        packagingDao.byBarcode(code)?.let { p ->
            productDao.byId(p.productId)?.let { return ScanMatch.Pack(code, it, p) }
        }
        return ScanMatch.NotFound(code)
    }
}
