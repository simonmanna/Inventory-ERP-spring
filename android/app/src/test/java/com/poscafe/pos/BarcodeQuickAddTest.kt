package com.poscafe.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.ProductEntity
import com.poscafe.pos.data.local.entity.ProductPackagingEntity
import com.poscafe.pos.data.local.entity.ProductVariantEntity
import com.poscafe.pos.data.local.entity.TaxEntity
import com.poscafe.pos.data.repo.AuthRepository
import com.poscafe.pos.data.repo.BarcodeLookup
import com.poscafe.pos.data.repo.MasterDataSync
import com.poscafe.pos.data.repo.ProductRepository
import com.poscafe.pos.data.repo.ScanMatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Camera scanning resolves codes like the till always has, and quick add makes a sellable product at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BarcodeQuickAddTest {

    private lateinit var db: PosDatabase
    private lateinit var lookup: BarcodeLookup
    private lateinit var products: ProductRepository

    private fun product(id: String, barcode: String?, active: Boolean = true, sku: String? = null) = ProductEntity(
        id = id, code = null, sku = sku, barcode = barcode, name = "P-$id", description = null, image = null,
        salesPrice = 1000.0, costPrice = 500.0, categoryId = null, categoryName = null, uomName = null, taxId = null,
        taxRate = 0.0, taxInclusive = false, isActive = active, isService = false, updatedAt = 0L,
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        val config = DeviceConfig(context, context.getSharedPreferences("barcode-test", Context.MODE_PRIVATE))
        config.standalone = true // no op queue needed for these checks
        val auth = AuthRepository(db.staffDao(), db.opQueueDao(), config)
        lookup = BarcodeLookup(db.productDao(), db.productVariantDao(), db.productPackagingDao())
        products = ProductRepository(db.productDao(), db.productCategoryDao(), MasterDataSync(db.opQueueDao(), config, auth))
        db.productDao().upsertAll(listOf(product("p1", "111"), product("p2", "222"), product("old", "999", active = false)))
        db.productVariantDao().upsertAll(
            listOf(ProductVariantEntity(id = "v1", productId = "p2", name = "Large", sku = "P2-L", barcode = "333", price = 1500.0, isActive = true)),
        )
        db.productPackagingDao().upsertAll(listOf(ProductPackagingEntity("k1", "p1", "Carton", 24.0, "444", true)))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `codes resolve product, then variant, then pack`() = runBlocking {
        assertEquals("p1", (lookup.find(" 111 ") as ScanMatch.Product).product.id)
        val v = lookup.find("333") as ScanMatch.Variant
        assertEquals("p2" to "v1", v.product.id to v.variant.id)
        val pack = lookup.find("444") as ScanMatch.Pack
        assertEquals(24.0, pack.pack.quantity, 0.0)
        assertEquals(ScanMatch.NotFound("555"), lookup.find("555"))
        assertTrue("an inactive product is not sold", lookup.find("999") is ScanMatch.NotFound)
    }

    @Test
    fun `quick add creates a product the next scan finds`() = runBlocking {
        val tax = TaxEntity(id = "t1", name = "VAT", rate = 18.0, isActive = true, isInclusive = true)
        val p = products.createProduct(" Mango juice ", 3500.0, barcode = "555", tax = tax)
        assertEquals("Mango juice", p.name)
        assertTrue("inclusivity follows the tax row, as the pull does", p.taxInclusive)
        assertEquals(p.id, (lookup.find("555") as ScanMatch.Product).product.id)
    }

    @Test
    fun `quick add refuses a code another product carries, even an inactive one`() = runBlocking {
        val taken = runCatching { products.createProduct("Dup", 10.0, barcode = "999") }.exceptionOrNull()
        assertEquals("999 is already used by P-old", taken?.message)
        assertTrue(runCatching { products.createProduct(" ", 10.0) }.isFailure)
    }
}
