package com.poscafe.pos.data.repo

import com.poscafe.pos.data.local.dao.ProductCategoryDao
import com.poscafe.pos.data.local.dao.ProductDao
import com.poscafe.pos.data.local.entity.ProductCategoryEntity
import com.poscafe.pos.data.local.entity.ProductEntity
import com.poscafe.pos.data.local.entity.TaxEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoring write-layer for retail products + their categories. Local-first
 * like [CatalogRepository]: writes Room, then enqueues product.upsert /
 * productCategory.upsert on connected devices (no-op standalone).
 */
@Singleton
class ProductRepository @Inject constructor(
    private val productDao: ProductDao,
    private val categoryDao: ProductCategoryDao,
    private val sync: MasterDataSync,
) {
    suspend fun saveProduct(row: ProductEntity) {
        productDao.upsertAll(listOf(row))
        sync.enqueue("product.upsert", buildJsonObject {
            put("id", row.id)
            row.code?.let { put("code", it) }
            put("name", row.name)
            row.sku?.let { put("sku", it) }
            row.barcode?.let { put("barcode", it) }
            row.description?.let { put("description", it) }
            row.categoryId?.let { put("categoryId", it) }
            row.taxId?.let { put("taxId", it) }
            put("salesPrice", row.salesPrice)
            put("costPrice", row.costPrice)
            row.image?.let { put("image", it) }
            put("isActive", row.isActive)
        })
    }

    /**
     * Quick add (POS terminal, Barcode app): a new active product with what the cashier typed, saved locally and
     * queued as `product.upsert` like any other product edit — sellable at once, offline. A barcode or SKU another
     * product already carries (active or not) is refused here rather than by the server later.
     */
    suspend fun createProduct(
        name: String,
        salesPrice: Double,
        costPrice: Double = 0.0,
        sku: String? = null,
        barcode: String? = null,
        category: ProductCategoryEntity? = null,
        tax: TaxEntity? = null,
    ): ProductEntity {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Give the product a name" }
        require(salesPrice >= 0.0) { "The price cannot be negative" }
        val cleanBarcode = barcode?.trim()?.takeIf { it.isNotEmpty() }
        val cleanSku = sku?.trim()?.takeIf { it.isNotEmpty() }
        for (code in listOfNotNull(cleanBarcode, cleanSku)) {
            productDao.anyByCode(code)?.let { throw IllegalArgumentException("$code is already used by ${it.name}") }
        }
        val row = ProductEntity(
            id = UUID.randomUUID().toString(),
            code = null,
            sku = cleanSku,
            barcode = cleanBarcode,
            name = cleanName,
            description = null,
            image = null,
            salesPrice = salesPrice,
            costPrice = costPrice,
            categoryId = category?.id,
            categoryName = category?.name,
            uomName = null,
            taxId = tax?.id,
            taxRate = tax?.rate ?: 0.0,
            // The pull derives inclusivity from the tax row; match it so the cart prices like the server.
            taxInclusive = tax?.isInclusive ?: false,
            isActive = true,
            isService = false,
            updatedAt = System.currentTimeMillis(),
        )
        saveProduct(row)
        return row
    }

    suspend fun deleteProduct(id: String) {
        productDao.delete(id)
        sync.enqueue("product.delete", buildJsonObject { put("id", id) })
    }

    suspend fun saveCategory(row: ProductCategoryEntity) {
        categoryDao.upsertAll(listOf(row))
        sync.enqueue("productCategory.upsert", buildJsonObject {
            put("id", row.id)
            put("name", row.name)
            row.parentId?.let { put("parentId", it) }
        })
    }

    suspend fun deleteCategory(id: String) {
        categoryDao.delete(id)
        sync.enqueue("productCategory.delete", buildJsonObject { put("id", id) })
    }
}
