package com.poscafe.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.local.dao.InventoryDao
import com.poscafe.pos.data.local.dao.MenuDao
import com.poscafe.pos.data.local.dao.ProductCategoryDao
import com.poscafe.pos.data.local.entity.ProductCategoryEntity
import com.poscafe.pos.data.local.entity.TaxEntity
import com.poscafe.pos.data.repo.AuthRepository
import com.poscafe.pos.data.repo.BarcodeLookup
import com.poscafe.pos.data.repo.ProductRepository
import com.poscafe.pos.data.repo.ScanMatch
import com.poscafe.pos.ui.components.KVRow
import com.poscafe.pos.ui.components.Money
import com.poscafe.pos.ui.components.PosCard
import com.poscafe.pos.ui.components.PrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Barcode app (mobile app shell): point the camera at any label and see what it is on this device — name, price,
 * category and on-hand — offline. A code nothing carries can be created as a product on the spot (with the
 * permission to manage products), so receiving a new line of stock is scan → name → price → done.
 */
@HiltViewModel
class BarcodeAppViewModel @Inject constructor(
    private val lookup: BarcodeLookup,
    private val products: ProductRepository,
    private val auth: AuthRepository,
    inventoryDao: InventoryDao,
    categoryDao: ProductCategoryDao,
    menuDao: MenuDao,
) : ViewModel() {
    val categories: StateFlow<List<ProductCategoryEntity>> = categoryDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val taxes: StateFlow<List<TaxEntity>> =
        menuDao.taxes().map { l -> l.filter { it.isActive } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val onHand: StateFlow<Map<Pair<String, String?>, Double>> = inventoryDao.productStockLevels()
        .map { l -> l.associate { (it.productId to it.variantId) to it.onHand } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Newest first; kept while the screen is open. */
    val history = mutableStateListOf<ScanMatch>()
    var feedback by mutableStateOf<ScanFeedback?>(null); private set
    var creating by mutableStateOf<String?>(null); private set
    var createError by mutableStateOf<String?>(null); private set
    var createBusy by mutableStateOf(false); private set

    val canCreate: Boolean get() = allowed(auth.current?.permissions.orEmpty(), setOf("product:create", "product:update"))

    fun onScanned(code: String) {
        viewModelScope.launch {
            val m = lookup.find(code)
            history.removeAll { it.code == m.code }
            history.add(0, m)
            feedback = when (m) {
                is ScanMatch.NotFound -> ScanFeedback("No product for ${m.code}", ok = false)
                is ScanMatch.Product -> ScanFeedback(m.product.name, ok = true)
                is ScanMatch.Variant -> ScanFeedback("${m.product.name} · ${m.variant.name}", ok = true)
                is ScanMatch.Pack -> ScanFeedback("${m.product.name} · ${m.pack.name}", ok = true)
            }
        }
    }

    fun onHandOf(productId: String, variantId: String? = null): Double? = onHand.value[productId to variantId]

    fun startCreate(code: String) {
        createError = null
        creating = code
    }

    fun cancelCreate() {
        if (!createBusy) creating = null
    }

    fun create(d: QuickAddDraft) {
        if (createBusy) return
        viewModelScope.launch {
            createBusy = true
            createError = null
            try {
                val p = products.createProduct(
                    name = d.name,
                    salesPrice = d.price,
                    costPrice = d.cost ?: 0.0,
                    sku = d.sku,
                    barcode = d.barcode,
                    category = categories.value.find { it.id == d.categoryId },
                    tax = taxes.value.find { it.id == d.taxId },
                )
                val code = d.barcode ?: d.sku ?: creating.orEmpty()
                history.removeAll { it.code == code || it.code == creating }
                history.add(0, ScanMatch.Product(code, p))
                creating = null
            } catch (e: Exception) {
                createError = e.message ?: "Could not save"
            } finally {
                createBusy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeAppScreen(onBack: () -> Unit, vm: BarcodeAppViewModel = hiltViewModel()) {
    var scanning by remember { mutableStateOf(false) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    val taxes by vm.taxes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Barcode") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    onClick = { scanning = true },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.QrCodeScanner, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Tap to scan", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Look up price and stock, or add a new product",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            if (vm.history.isEmpty()) {
                item {
                    Text(
                        "Scanned items appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(vm.history, key = { it.code + it.javaClass.simpleName }) { m -> ScanResultCard(m, vm) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (scanning) {
        BarcodeScannerDialog(
            onDismiss = { scanning = false },
            onBarcodeDetected = vm::onScanned,
            feedback = vm.feedback,
            allowContinuous = true,
            title = "Scan a product",
        )
    }

    vm.creating?.let { code ->
        QuickAddItemDialog(
            forMenu = false,
            initialBarcode = code,
            categories = categories.map { PickOption(it.id, it.name) },
            taxes = taxes.map { PickOption(it.id, "${it.name} (${if (it.rate % 1.0 == 0.0) it.rate.toInt() else it.rate}%)") },
            initialCategoryId = null,
            error = vm.createError,
            busy = vm.createBusy,
            showAddToCart = false,
            onSave = vm::create,
            onDismiss = vm::cancelCreate,
        )
    }
}

@Composable
private fun ScanResultCard(m: ScanMatch, vm: BarcodeAppViewModel) {
    PosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val found = m !is ScanMatch.NotFound
                Box(
                    Modifier.size(36.dp).background(
                        if (found) Color(0xFF0E9F6E).copy(alpha = 0.14f) else Color(0xFFF59E0B).copy(alpha = 0.16f),
                        RoundedCornerShape(10.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (found) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, null,
                        tint = if (found) Color(0xFF0E9F6E) else Color(0xFFB45309),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (m) {
                            is ScanMatch.Product -> m.product.name
                            is ScanMatch.Variant -> "${m.product.name} · ${m.variant.name}"
                            is ScanMatch.Pack -> "${m.product.name} · ${m.pack.name}"
                            is ScanMatch.NotFound -> "Not in the catalog"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(m.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (m) {
                is ScanMatch.Product -> {
                    KVRow("Price", Money.format(m.product.salesPrice), emphasize = true)
                    m.product.categoryName?.let { KVRow("Category", it) }
                    m.product.sku?.let { KVRow("SKU", it) }
                    if (m.product.hasVariants) KVRow("Variants", "Pick one at the till")
                    vm.onHandOf(m.product.id)?.let { KVRow("On hand", qty(it)) }
                }
                is ScanMatch.Variant -> {
                    KVRow("Price", Money.format(m.variant.price), emphasize = true)
                    m.variant.sku?.let { KVRow("SKU", it) }
                    vm.onHandOf(m.product.id, m.variant.id)?.let { KVRow("On hand", qty(it)) }
                }
                is ScanMatch.Pack -> {
                    KVRow("Pack", "${qty(m.pack.quantity)} × ${m.product.name}")
                    KVRow("Pack price", Money.format(m.product.salesPrice * m.pack.quantity), emphasize = true)
                    vm.onHandOf(m.product.id)?.let { KVRow("On hand (units)", qty(it)) }
                }
                is ScanMatch.NotFound -> if (vm.canCreate) {
                    PrimaryButton("Create product", { vm.startCreate(m.code) }, Modifier.fillMaxWidth(), height = 48.dp)
                } else {
                    Text(
                        "Nothing on this device carries this code. A manager can add it in Products.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun qty(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)
