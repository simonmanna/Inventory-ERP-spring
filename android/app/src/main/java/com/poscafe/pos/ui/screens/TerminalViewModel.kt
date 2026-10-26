package com.poscafe.pos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.CustomerDao
import com.poscafe.pos.data.local.dao.HoldDao
import com.poscafe.pos.data.local.dao.MenuDao
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.dao.ProductCategoryDao
import com.poscafe.pos.data.local.dao.ProductDao
import com.poscafe.pos.data.local.dao.ProductPackagingDao
import com.poscafe.pos.data.local.dao.RegisterDao
import com.poscafe.pos.data.local.dao.SettingsDao
import com.poscafe.pos.data.local.dao.TableDao
import com.poscafe.pos.data.local.entity.*
import com.poscafe.pos.data.repo.*
import com.poscafe.pos.printing.ReceiptPrinter
import com.poscafe.pos.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ceil

/**
 * The offline terminal: menu grid → cart → tender → local receipt print.
 * Everything works with zero connectivity; the op queue + SyncWorker handle
 * the rest when the network returns.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val menuDao: MenuDao,
    tableDao: TableDao,
    private val registerDao: RegisterDao,
    private val settingsDao: SettingsDao,
    private val productDao: ProductDao,
    private val productCategoryDao: ProductCategoryDao,
    private val productPackagingDao: ProductPackagingDao,
    private val holdDao: HoldDao,
    private val customerDao: CustomerDao,
    private val customerRepo: CustomerRepository,
    opQueue: OpQueueDao,
    private val auth: AuthRepository,
    private val sales: SaleRepository,
    private val tabRepo: TabRepository,
    private val sessions: CashSessionRepository,
    private val printer: ReceiptPrinter,
    val config: DeviceConfig,
    cashRefs: com.poscafe.pos.data.local.dao.CashInventoryDao,
) : ViewModel() {

    /** The org's configured payment tiles (same as the web terminal); empty until synced. */
    val paymentMethods: StateFlow<List<com.poscafe.pos.data.local.entity.PaymentMethodEntity>> =
        cashRefs.paymentMethodsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedCategory = MutableStateFlow<String?>(null)
    val categories: StateFlow<List<MenuCategoryEntity>> =
        menuDao.categories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<MenuItemEntity>> =
        selectedCategory.flatMapLatest { menuDao.items(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val session = sessions.openSession().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Null until Room has answered, then the open session or null — one flow, so the screen never
     * sees "loaded" before the session itself (`session == null` can't tell closed from not loaded).
     */
    val sessionLoad: StateFlow<SessionLoad?> =
        sessions.openSession().map { SessionLoad(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class SessionLoad(val open: LocalCashSessionEntity?)
    val tables: StateFlow<List<PosTableEntity>> =
        tableDao.tables().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val queuedOps: StateFlow<Int> =
        opQueue.queuedCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val isRetailMode: StateFlow<Boolean> =
        settingsDao.byKeyFlow("pos.mode")
            .map { setting ->
                if (setting == null) false
                else runCatching {
                    val el = Json.parseToJsonElement(setting.valueJson)
                    (el as? JsonPrimitive)?.content == "retail"
                }.getOrDefault(false)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Retail-specific state ----
    val selectedProductCategory = MutableStateFlow<String?>(null)
    val productCategories: StateFlow<List<ProductCategoryEntity>> =
        productCategoryDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val productSearchQuery = MutableStateFlow("")

    /** Grid = category browse, or name/sku/barcode contains-search while typing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val products: StateFlow<List<ProductEntity>> =
        combine(selectedProductCategory, productSearchQuery) { cat, query -> cat to query }
            .flatMapLatest { (cat, query) ->
                if (query.isBlank()) productDao.byCategory(cat) else productDao.search(query.trim())
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Dine-in target; null = takeaway/counter sale. */
    val selectedTable = MutableStateFlow<PosTableEntity?>(null)

    init {
        // Retail has no tables — a table picked in cafe mode must never leak
        // into a retail sale as dine_in.
        viewModelScope.launch {
            runCatching {
                isRetailMode.collect { retail -> if (retail) selectedTable.value = null }
            }.onFailure { error = "init(retail-watch): ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    // ---- Dine-in tabs (one open tab per table) ----

    /** Table ids that currently carry an open (unsettled) tab — badges the floor. */
    val openTabTableIds: StateFlow<List<String>> =
        tabRepo.openTableIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Line ids on the current tab already sent to the kitchen. */
    var firedLineIds by mutableStateOf<Set<String>>(emptySet()); private set

    val hasUnfiredLines: Boolean get() = cart.any { it.lineId !in firedLineIds }

    /** Switch tables: bank the current tab, then load the target table's tab.
     *  Items rung up before any table was picked (takeaway-in-progress) follow
     *  the cashier onto the table they assign, rather than being wiped. */
    fun selectTable(table: PosTableEntity?) {
        if (isRetailMode.value) { selectedTable.value = null; return }
        val previous = selectedTable.value
        if (previous?.id == table?.id) return
        viewModelScope.launch {
            // Unassigned items only carry over when there is no source table to
            // bank them against; when switching tables the cart belongs to the
            // previous table and must not leak onto the next one.
            val carryover = if (previous == null) cart else emptyList()
            if (previous != null) {
                tabRepo.save(previous.id, cart, guestCount = 0, partnerId = selectedCustomer?.id, firedLineIds = firedLineIds, actorUserId = auth.current?.userId)
            }
            selectedTable.value = table
            if (table != null) {
                val tab = tabRepo.load(table.id)
                cart = (tab?.lines ?: emptyList()) + carryover
                firedLineIds = tab?.firedLineIds ?: emptySet()
                tab?.partnerId?.let { pid -> selectedCustomer = customers.value.find { it.id == pid } }
            } else {
                cart = carryover
                firedLineIds = emptySet()
            }
        }
    }

    /** Bank the tab and return to the floor without settling. */
    fun saveTab() {
        val table = selectedTable.value ?: return
        viewModelScope.launch {
            tabRepo.save(table.id, cart, guestCount = 0, partnerId = selectedCustomer?.id, firedLineIds = firedLineIds, actorUserId = auth.current?.userId)
            cart = emptyList()
            firedLineIds = emptySet()
            selectedTable.value = null
        }
    }

    /** Fire the not-yet-sent lines to the kitchen printer as a KOT round. */
    fun fireKitchen() {
        val table = selectedTable.value ?: return
        val round = cart.filter { it.lineId !in firedLineIds }
        if (round.isEmpty()) return
        val host = config.printerHost
        viewModelScope.launch {
            if (host != null) {
                runCatching { printer.printKot(host = host, title = tableLabel(table), lines = round) }
                    .onFailure { error = "KOT print failed: ${it.message}" }
            }
            firedLineIds = firedLineIds + round.map { it.lineId }
            tabRepo.save(table.id, cart, guestCount = 0, partnerId = selectedCustomer?.id, firedLineIds = firedLineIds, actorUserId = auth.current?.userId)
        }
    }

    private fun tableLabel(table: PosTableEntity): String =
        table.name?.takeIf { it.isNotBlank() } ?: "Table ${table.number}"

    val customers: StateFlow<List<CustomerEntity>> =
        customerDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var selectedCustomer by mutableStateOf<CustomerEntity?>(null); private set

    val holds: StateFlow<List<LocalHoldEntity>> =
        holdDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectCustomer(customer: CustomerEntity?) { selectedCustomer = customer }

    fun createCustomer(name: String, phone: String?) {
        viewModelScope.launch {
            val entity = CustomerEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                phone = phone?.takeIf { it.isNotBlank() },
                email = null,
                note = null,
                loyaltyPoints = 0,
                createdAt = System.currentTimeMillis(),
            )
            // Local upsert + customer.upsert op — the op queue is FIFO, so the
            // Partner reaches the server before any sale that references it.
            selectedCustomer = customerRepo.save(entity, auth.current?.userId)
        }
    }

    // ---- Hold / park ----

    private val holdJson = Json { encodeDefaults = false }

    fun saveHold(name: String) {
        if (cart.isEmpty()) return
        viewModelScope.launch {
            val linesJson = holdJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(CartEngine.CartLine.serializer()),
                cart,
            )
            holdDao.insert(
                LocalHoldEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    linesJson = linesJson,
                    totalAmount = totals.total,
                    partnerId = selectedCustomer?.id,
                    actorUserId = auth.current?.userId,
                    createdAt = System.currentTimeMillis(),
                    syncStatus = "local",
                ),
            )
            cart = emptyList()
        }
    }

    fun retrieveHold(hold: LocalHoldEntity) {
        viewModelScope.launch {
            val lines = runCatching {
                holdJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(CartEngine.CartLine.serializer()),
                    hold.linesJson,
                )
            }.getOrDefault(emptyList())
            if (lines.isNotEmpty()) cart = lines
        }
    }

    fun deleteHold(hold: LocalHoldEntity) {
        viewModelScope.launch { holdDao.softDelete(hold.id) }
    }

    var cart by mutableStateOf<List<CartEngine.CartLine>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var charging by mutableStateOf(false); private set
    var successSale by mutableStateOf<SaleRepository.CompletedSale?>(null); private set
    /** Whole-order discount %, applied on top of any per-line discounts. */
    var orderDiscountPercent by mutableStateOf(0.0); private set

    // NOTE: init block split from the one above (line 157) so that
    // `cart` and the other `by mutableStateOf` delegates are initialized
    // BEFORE `snapshotFlow` eagerly reads their current value. Kotlin
    // initializes class body top-to-bottom — referencing a property
    // declared later in the class from an init block crashes with NPE
    // because the delegate backing field is still null.
    init {
        viewModelScope.launch {
            runCatching {
                snapshotFlow { cart }.collect { lines ->
                    val table = selectedTable.value ?: return@collect
                    if (isRetailMode.value) return@collect
                    tabRepo.save(table.id, lines, guestCount = 0, partnerId = selectedCustomer?.id, firedLineIds = firedLineIds, actorUserId = auth.current?.userId)
                }
            }.onFailure { error = "init(tab-autosave): ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    val totals: CartEngine.CartTotals get() = CartEngine.totals(cart, orderDiscountPercent)
    val cashier get() = auth.current

    // ---- item configuration (variants / add-ons / accompaniments) ----

    data class ModGroup(val group: ModifierGroupEntity, val modifiers: List<ModifierEntity>)
    data class AccGroup(val group: AccompanimentGroupEntity, val options: List<AccompanimentOptionEntity>)
    data class ItemConfig(
        val item: MenuItemEntity,
        val variants: List<MenuItemVariantEntity>,
        val modGroups: List<ModGroup>,
        val accGroups: List<AccGroup>,
    )

    var itemConfig by mutableStateOf<ItemConfig?>(null); private set

    /** Tap = instant add for plain items; items with options open the sheet. */
    fun onItemTap(item: MenuItemEntity) {
        viewModelScope.launch {
            val variants = menuDao.variants(item.id)
            val modGroups = menuDao.modifierGroupsFor(item.id).map { ModGroup(it, menuDao.modifiers(it.id)) }
            val accGroups = menuDao.accompanimentGroupsFor(item.id).map { AccGroup(it, menuDao.accompanimentOptions(it.id)) }
            if (variants.isEmpty() && modGroups.isEmpty() && accGroups.isEmpty()) {
                addSimple(item)
            } else {
                itemConfig = ItemConfig(item, variants, modGroups, accGroups)
            }
        }
    }

    fun onProductTap(product: ProductEntity) {
        val price = product.salesPrice
        val existing = cart.find { it.productId == product.id && it.note == null }
        cart = if (existing != null) {
            cart.map { if (it.lineId == existing.lineId) it.copy(quantity = it.quantity + 1) else it }
        } else {
            cart + CartEngine.CartLine(
                lineId = UUID.randomUUID().toString(),
                productId = product.id,
                name = product.name,
                quantity = 1.0,
                baseUnitPrice = price,
                taxRatePercent = product.taxRate,
                taxInclusive = product.taxInclusive,
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        productSearchQuery.value = query
        if (query.isBlank()) {
            selectedProductCategory.value = null
        }
    }

    fun searchProducts(query: String) {
        viewModelScope.launch {
            // A single-unit barcode/sku wins first; otherwise try a multipack
            // barcode (scan the case → add `quantity` base units).
            val product = productDao.byCode(query)
            if (product != null) {
                onProductTap(product)
                productSearchQuery.value = ""
                return@launch
            }
            val pack = productPackagingDao.byBarcode(query.trim())
            if (pack != null) {
                val base = productDao.byId(pack.productId)
                if (base != null) {
                    addProductPack(base, pack.quantity, pack.name)
                    productSearchQuery.value = ""
                }
            }
        }
    }

    /** Add a whole pack = `quantity` base units of the product as one line. */
    private fun addProductPack(product: ProductEntity, quantity: Double, packName: String) {
        cart = cart + CartEngine.CartLine(
            lineId = UUID.randomUUID().toString(),
            productId = product.id,
            name = "${product.name} · $packName",
            quantity = quantity,
            baseUnitPrice = product.salesPrice,
            taxRatePercent = product.taxRate,
            taxInclusive = product.taxInclusive,
        )
    }

    fun dismissConfig() { itemConfig = null }

    private fun addSimple(item: MenuItemEntity) {
        val price = item.basePriceMajor ?: 0.0
        val existing = cart.find { it.menuItemId == item.id && it.variantId == null && it.modifiers.isEmpty() && it.accompaniments.isEmpty() && it.note == null }
        cart = if (existing != null) {
            cart.map { if (it.lineId == existing.lineId) it.copy(quantity = it.quantity + 1) else it }
        } else {
            cart + CartEngine.CartLine(
                lineId = UUID.randomUUID().toString(),
                menuItemId = item.id,
                name = item.name,
                quantity = 1.0,
                baseUnitPrice = price,
            )
        }
    }

    fun addConfigured(
        item: MenuItemEntity,
        variant: MenuItemVariantEntity?,
        modifiers: List<CartEngine.ModifierSel>,
        accompaniments: List<CartEngine.AccompanimentSel>,
        note: String?,
        quantity: Int,
    ) {
        cart = cart + CartEngine.CartLine(
            lineId = UUID.randomUUID().toString(),
            menuItemId = item.id,
            name = item.name,
            quantity = quantity.toDouble(),
            baseUnitPrice = variant?.price ?: (item.basePriceMajor ?: 0.0),
            variantId = variant?.id,
            variantName = variant?.name,
            modifiers = modifiers,
            accompaniments = accompaniments,
            note = note?.takeIf { it.isNotBlank() },
        )
        itemConfig = null
    }

    fun changeQty(lineId: String, delta: Double) {
        cart = cart.mapNotNull {
            if (it.lineId != lineId) it
            else (it.quantity + delta).let { q -> if (q <= 0) null else it.copy(quantity = q) }
        }
    }

    fun removeLine(lineId: String) { cart = cart.filterNot { it.lineId == lineId } }

    /** Per-line discount %. Folds into the line via CartEngine before tax. */
    fun setLineDiscount(lineId: String, percent: Double) {
        val p = percent.coerceIn(0.0, 100.0)
        cart = cart.map { if (it.lineId == lineId) it.copy(discountPercent = p) else it }
    }

    fun setOrderDiscount(percent: Double) { orderDiscountPercent = percent.coerceIn(0.0, 100.0) }

    fun clear() { cart = emptyList(); orderDiscountPercent = 0.0 }

    /** Split tender: settle the whole cart with one or more payment legs. */
    fun charge(tenders: List<SaleRepository.Tender>, onDone: () -> Unit) {
        settleLines(cart.map { it.lineId }.toSet(), tenders, onDone)
    }

    /**
     * Settle a subset of the cart as its own sale (split bill). Passing every
     * line id is an ordinary full checkout. Each call emits one `sale.checkout`
     * op; the remaining lines stay on the tab until the last bill settles.
     */
    fun settleLines(lineIds: Set<String>, tenders: List<SaleRepository.Tender>, onDone: () -> Unit) {
        val user = auth.current ?: return
        if (charging || tenders.isEmpty()) return
        // A spent licence stops new sales, and says so where the cashier is already looking. Note
        // what is *not* blocked: the queue still pushes, so a day sold before the licence lapsed
        // still reaches the server. Takings are never held hostage to billing.
        val licence = config.license.value
        if (!licence.writable) {
            error = licence.message
                ?: "This system is read-only: the licence has expired. Ask the manager."
            return
        }
        val billLines = cart.filter { it.lineId in lineIds }
        if (billLines.isEmpty()) return
        val table = if (isRetailMode.value) null else selectedTable.value
        viewModelScope.launch {
            charging = true
            try {
                error = null
                val sale = sales.checkout(
                    actorUserId = user.userId,
                    lines = billLines,
                    tenders = tenders,
                    cashSessionLocalId = session.value?.id,
                    tableId = table?.id,
                    orderType = if (table != null) "dine_in" else "takeaway",
                    partnerId = selectedCustomer?.id,
                    transactionDiscountPercent = orderDiscountPercent,
                )
                printSale(sale, billLines, tenders, user.displayName)
                val remaining = cart.filterNot { it.lineId in lineIds }
                firedLineIds = firedLineIds - lineIds
                if (remaining.isEmpty()) {
                    table?.let { tabRepo.clear(it.id) }
                    cart = emptyList()
                    orderDiscountPercent = 0.0
                    selectedTable.value = null
                    successSale = sale
                } else {
                    // Split remainder stays open on the tab (auto-persisted).
                    cart = remaining
                }
                onDone()
            } catch (e: Exception) {
                error = e.message
            } finally {
                charging = false
            }
        }
    }

    private suspend fun printSale(
        sale: SaleRepository.CompletedSale,
        lines: List<CartEngine.CartLine>,
        tenders: List<SaleRepository.Tender>,
        cashierName: String,
    ) {
        val host = config.printerHost ?: return
        runCatching {
            printer.printReceipt(
                host = host,
                header = config.receiptHeader(),
                sale = sale,
                lines = lines,
                tenders = tenders,
                cashierName = cashierName,
                offline = !config.standalone,
                footer = config.receiptFooter,
            )
        }
    }

    fun dismissSuccess() { successSale = null }

    // ---- cash session ----

    var opening by mutableStateOf<CashSessionRepository.OpeningOptions?>(null); private set
    var openError by mutableStateOf<String?>(null); private set

    fun loadRegisters() {
        openError = null
        viewModelScope.launch { opening = sessions.openingOptions() }
    }

    fun openSession(registerId: String, float: Double, sourceId: String?, notes: String?, onDone: () -> Unit) {
        val user = auth.current ?: return
        viewModelScope.launch {
            runCatching { sessions.open(user.userId, registerId, float, sourceId, notes) }
                .onFailure { openError = it.message }
                .onSuccess { openError = null; onDone() }
        }
    }
}
