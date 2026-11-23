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
import com.poscafe.pos.data.local.dao.ProductVariantDao
import com.poscafe.pos.data.local.dao.RegisterDao
import com.poscafe.pos.data.local.dao.SettingsDao
import com.poscafe.pos.data.local.dao.StaffDao
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
    private val productVariantDao: ProductVariantDao,
    private val kitchenVoids: KitchenVoidRepository,
    private val holdDao: HoldDao,
    private val customerDao: CustomerDao,
    private val customerRepo: CustomerRepository,
    private val staffDao: StaffDao,
    opQueue: OpQueueDao,
    private val auth: AuthRepository,
    private val sales: SaleRepository,
    private val tabRepo: TabRepository,
    private val sessions: CashSessionRepository,
    private val printer: ReceiptPrinter,
    private val barcodes: BarcodeLookup,
    private val productRepo: ProductRepository,
    private val catalogRepo: CatalogRepository,
    val config: DeviceConfig,
    cashRefs: com.poscafe.pos.data.local.dao.CashInventoryDao,
) : ViewModel() {

    /** ADR-S083 C4: why new sales are refused right now (offline window lapsed, clock wound back), else null. */
    private val offlineBlock: String?
        get() = if (!config.isEnrolled) null
        else com.poscafe.pos.data.OfflineWindow.block(
            config.lastPullAt, config.offlinePolicy.offlineWindowHours, System.currentTimeMillis(),
        )

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

    /** Signed-in cashier's id; the screen sets it, since [AuthRepository.current] is not observable. */
    val currentUserId = MutableStateFlow<String?>(null)

    /**
     * Who owns the open shift when it is not the signed-in cashier, else null. The server
     * rejects such a sale ("This register is open under another cashier...") unless the org
     * runs a shared drawer, a setting devices are never sent. Taking money for a sale the
     * server will refuse is worse than refusing it here, so the till stops it up front.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val foreignShiftOwner: StateFlow<String?> =
        combine(session, currentUserId) { s, me -> s to me }
            .mapLatest { (s, me) ->
                if (s == null || me == null || s.actorUserId == me) null
                else staffDao.byId(s.actorUserId)?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ") }
                    ?: "another cashier"
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
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

    /** Dine-in target; null = no table picked yet (dine-in) or a takeaway sale. */
    val selectedTable = MutableStateFlow<PosTableEntity?>(null)

    /**
     * Cafe/restaurant order type, as on the web terminal: an order starts as
     * dine-in and needs a table before anything can be rung up. Takeaway is an
     * explicit choice, never the silent default. Ignored in retail mode.
     */
    val takeaway = MutableStateFlow(false)

    /** Cafe mode, dine-in, no table yet: the Sell tab shows the floor instead of the menu. */
    val needsTable: StateFlow<Boolean> =
        combine(isRetailMode, takeaway, selectedTable) { retail, ta, table -> !retail && !ta && table == null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // Price the cart to the server's tax scale (organisation currency decimals, from the last pull).
        CartEngine.taxScale = config.taxScale
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

    /**
     * Phase I6: taking a line off the tab after it was sent to the kitchen is a
     * kitchen void — it needs a reason and a manager PIN, and the started food
     * is wasted on the server (`kitchen.void`). [quantity] is how much comes off.
     */
    data class VoidRequest(val line: CartEngine.CartLine, val quantity: Double)

    var voidRequest by mutableStateOf<VoidRequest?>(null); private set
    var voidMessage by mutableStateOf<String?>(null); private set

    fun cancelVoid() { voidRequest = null; voidMessage = null }

    /** Verify the approver (pos:override + pos:void, as the server checks), then queue the void. */
    fun confirmKitchenVoid(reason: String, pin: String) {
        val req = voidRequest ?: return
        val cashier = auth.current ?: return
        viewModelScope.launch {
            voidMessage = null
            if (reason.isBlank()) { voidMessage = "A reason is required to void an item"; return@launch }
            val approver = auth.verifyApproval(pin, "pos:void")
            if (approver.isFailure) {
                voidMessage = approver.exceptionOrNull()?.message ?: "PIN rejected"
                return@launch
            }
            val table = selectedTable.value
            runCatching {
                kitchenVoids.record(
                    actorUserId = cashier.userId,
                    tableLabel = table?.let { tableLabel(it) } ?: "",
                    reason = reason,
                    overrideById = approver.getOrThrow().userId,
                    overridePin = pin,
                    voided = listOf(KitchenVoidRepository.Voided(req.line, req.quantity)),
                )
            }.onSuccess {
                val left = req.line.quantity - req.quantity
                cart = cart.mapNotNull {
                    if (it.lineId != req.line.lineId) it else if (left <= 0.0) null else it.copy(quantity = left)
                }
                if (left <= 0.0) firedLineIds = firedLineIds - req.line.lineId
                table?.let {
                    tabRepo.save(it.id, cart, guestCount = 0, partnerId = selectedCustomer?.id, firedLineIds = firedLineIds, actorUserId = cashier.userId)
                }
                voidRequest = null
            }.onFailure { voidMessage = it.message ?: "Void failed" }
        }
    }

    /** Switch tables: bank the current tab, then load the target table's tab.
     *  Items rung up before any table was picked (takeaway-in-progress) follow
     *  the cashier onto the table they assign, rather than being wiped. */
    fun selectTable(table: PosTableEntity?) {
        if (isRetailMode.value) { selectedTable.value = null; return }
        // Picking a table makes it dine-in; "no table" from the picker is takeaway.
        takeaway.value = table == null
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
        if (selectedTable.value != null) backToFloor()
    }

    /**
     * Back to the floor for the next order. A table's order is banked on its tab first; a
     * takeaway cart has nowhere to go, so the floor is only reachable once it is empty.
     */
    fun backToFloor() {
        if (isRetailMode.value) return
        val table = selectedTable.value
        if (table == null && cart.isNotEmpty()) return
        viewModelScope.launch {
            table?.let {
                tabRepo.save(it.id, cart, guestCount = 0, partnerId = selectedCustomer?.id, firedLineIds = firedLineIds, actorUserId = auth.current?.userId)
            }
            // Deselect before emptying the cart so the tab autosave can't save the empty cart over it.
            selectedTable.value = null
            takeaway.value = false
            cart = emptyList()
            firedLineIds = emptySet()
            selectedCustomer = null
            orderDiscountPercent = 0.0
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
            selectedCustomer = null
            orderDiscountPercent = 0.0
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
            if (lines.isEmpty()) return@launch
            // Recalled lines join whatever is already rung up (never overwrite a live cart),
            // and the hold is consumed so it can't be recalled — and charged — twice.
            cart = cart + lines
            if (selectedCustomer == null) hold.partnerId?.let { pid -> selectedCustomer = customers.value.find { it.id == pid } }
            holdDao.softDelete(hold.id)
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
        if (needsTable.value) { error = SELECT_TABLE; return }
        viewModelScope.launch {
            val variants = menuDao.variants(item.id)
            val modGroups = menuDao.modifierGroupsFor(item.id).map { ModGroup(it, menuDao.modifiers(it.id)) }
            val accGroups = menuDao.accompanimentGroupsFor(item.id).map { AccGroup(it, menuDao.accompanimentOptions(it.id)) }
            if (variants.isEmpty() && modGroups.isEmpty() && accGroups.isEmpty()) {
                addSimple(item, taxOf(item))
            } else {
                itemConfig = ItemConfig(item, variants, modGroups, accGroups)
            }
        }
    }

    /** Phase I6: a product with variants asks which one before it goes on the cart. */
    data class VariantPick(val product: ProductEntity, val variants: List<ProductVariantEntity>)

    var variantPick by mutableStateOf<VariantPick?>(null); private set

    fun dismissVariantPick() { variantPick = null }

    fun onProductTap(product: ProductEntity) {
        viewModelScope.launch {
            val variants = if (product.hasVariants) productVariantDao.forProduct(product.id) else emptyList()
            if (variants.isNotEmpty()) {
                variantPick = VariantPick(product, variants)
            } else {
                addProduct(product, null)
            }
        }
    }

    fun onVariantPicked(product: ProductEntity, variant: ProductVariantEntity) {
        variantPick = null
        addProduct(product, variant)
    }

    /** Add one unit of [product] (or of its [variant], priced at the variant's effective price). */
    private fun addProduct(product: ProductEntity, variant: ProductVariantEntity?) {
        // Never merge into a line already sent to the kitchen: that quantity was fired.
        val existing = cart.find {
            it.productId == product.id && it.variantId == variant?.id && it.note == null && it.lineId !in firedLineIds
        }
        cart = if (existing != null) {
            cart.map { if (it.lineId == existing.lineId) it.copy(quantity = it.quantity + 1) else it }
        } else {
            cart + CartEngine.CartLine(
                lineId = UUID.randomUUID().toString(),
                productId = product.id,
                name = variant?.let { "${product.name} · ${it.name}" } ?: product.name,
                quantity = 1.0,
                baseUnitPrice = variant?.price ?: product.salesPrice,
                variantId = variant?.id,
                productVariantName = variant?.name,
                taxRatePercent = product.taxRate,
                taxInclusive = product.taxInclusive,
                taxId = product.taxId,
                uomId = product.salesUomId,
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        productSearchQuery.value = query
        if (query.isBlank()) {
            selectedProductCategory.value = null
        }
    }

    /** Typed search (keyboard or a USB/Bluetooth wedge scanner): an exact code goes on the cart, else the list filters. */
    fun searchProducts(query: String) {
        viewModelScope.launch { applyCode(query, fromCamera = false) }
    }

    // ---- Camera scanning + quick add (mobile app shell) ----

    /** Result of the last camera scan, shown in the scanner while it stays open (continuous mode). */
    var scanFeedback by mutableStateOf<ScanFeedback?>(null); private set

    /** A scanned code nothing on this device carries: offer to create it. */
    var scanMiss by mutableStateOf<String?>(null); private set

    fun dismissScanMiss() { scanMiss = null }

    fun onScanned(code: String) {
        viewModelScope.launch { applyCode(code, fromCamera = true) }
    }

    /**
     * Resolve [raw] with [BarcodeLookup] (product → variant → multipack) and put it on the cart. A product with
     * variants opens the variant picker. A camera miss is remembered as [scanMiss] so the screen can offer quick add.
     */
    private suspend fun applyCode(raw: String, fromCamera: Boolean) {
        when (val m = barcodes.find(raw)) {
            is ScanMatch.Product -> {
                onProductTap(m.product)
                scanFeedback = ScanFeedback(if (m.product.hasVariants) "Pick a variant of ${m.product.name}" else "Added ${m.product.name}", ok = true)
                productSearchQuery.value = ""
            }
            is ScanMatch.Variant -> {
                addProduct(m.product, m.variant)
                scanFeedback = ScanFeedback("Added ${m.product.name} · ${m.variant.name}", ok = true)
                productSearchQuery.value = ""
            }
            is ScanMatch.Pack -> {
                addProductPack(m.product, m.pack.quantity, m.pack.name)
                scanFeedback = ScanFeedback("Added ${m.product.name} · ${m.pack.name}", ok = true)
                productSearchQuery.value = ""
            }
            is ScanMatch.NotFound -> if (fromCamera && m.code.isNotEmpty()) {
                scanFeedback = ScanFeedback("No product for ${m.code}", ok = false)
                scanMiss = m.code
            }
        }
    }

    /** May the signed-in cashier create catalog items from the till (same rights as the Products / Menu screens)? */
    val canQuickAdd: Boolean
        get() = allowed(auth.current?.permissions.orEmpty(), setOf("product:create", "product:update"))

    val taxes: StateFlow<List<TaxEntity>> =
        menuDao.taxes().map { list -> list.filter { it.isActive } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Open quick-add form; [barcode] pre-filled when it came from a scan miss. */
    data class QuickAdd(val barcode: String?)

    var quickAdd by mutableStateOf<QuickAdd?>(null); private set
    var quickAddError by mutableStateOf<String?>(null); private set
    var quickAddBusy by mutableStateOf(false); private set

    fun openQuickAdd(barcode: String? = null) {
        scanMiss = null
        quickAddError = null
        quickAdd = QuickAdd(barcode)
    }

    fun dismissQuickAdd() {
        if (!quickAddBusy) quickAdd = null
    }

    /**
     * Save what was typed: retail → a product (`product.upsert`), cafe → a menu item (`menuItem.upsert`). Both are
     * local-first, so the new item sells at once, offline, and reaches the server ahead of the sale that uses it.
     */
    fun saveQuickAdd(d: QuickAddDraft) {
        if (quickAddBusy) return
        viewModelScope.launch {
            quickAddBusy = true
            quickAddError = null
            try {
                if (!canQuickAdd) throw IllegalStateException("You do not have permission to add products")
                if (isRetailMode.value) {
                    val product = productRepo.createProduct(
                        name = d.name,
                        salesPrice = d.price,
                        costPrice = d.cost ?: 0.0,
                        sku = d.sku,
                        barcode = d.barcode,
                        category = productCategories.value.find { it.id == d.categoryId },
                        tax = taxes.value.find { it.id == d.taxId },
                    )
                    if (d.addToCart) addProduct(product, null)
                } else {
                    require(d.name.isNotBlank()) { "Give the item a name" }
                    val item = MenuItemEntity(
                        id = UUID.randomUUID().toString(),
                        code = null,
                        name = d.name.trim(),
                        description = null,
                        categoryId = d.categoryId,
                        basePriceMajor = d.price,
                        taxId = d.taxId,
                        image = null,
                        isAvailable = true,
                        displayOrder = (items.value.maxOfOrNull { it.displayOrder } ?: 0) + 1,
                    )
                    catalogRepo.saveMenuItem(item, emptyList(), emptyList(), emptyList(), d.cost, null)
                    if (d.addToCart && !needsTable.value) addSimple(item, taxOf(item))
                }
                quickAdd = null
            } catch (e: Exception) {
                quickAddError = e.message ?: "Could not save"
            } finally {
                quickAddBusy = false
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
            taxId = product.taxId,
            uomId = product.salesUomId,
        )
    }

    fun dismissConfig() { itemConfig = null }

    /** The menu item's tax row (server prices a menu line with `MenuItem.taxId`'s rate and inclusivity). */
    private suspend fun taxOf(item: MenuItemEntity): TaxEntity? =
        item.taxId?.let { menuDao.tax(it) }?.takeIf { it.isActive }

    private fun addSimple(item: MenuItemEntity, tax: TaxEntity?) {
        val price = item.basePriceMajor ?: 0.0
        val existing = cart.find {
            it.menuItemId == item.id && it.variantId == null && it.modifiers.isEmpty() && it.accompaniments.isEmpty() &&
                it.note == null && it.lineId !in firedLineIds
        }
        cart = if (existing != null) {
            cart.map { if (it.lineId == existing.lineId) it.copy(quantity = it.quantity + 1) else it }
        } else {
            cart + CartEngine.CartLine(
                lineId = UUID.randomUUID().toString(),
                menuItemId = item.id,
                name = item.name,
                quantity = 1.0,
                baseUnitPrice = price,
                taxRatePercent = tax?.rate ?: 0.0,
                taxInclusive = tax?.isInclusive ?: false,
                taxId = tax?.id,
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
        itemConfig = null
        viewModelScope.launch {
            val tax = taxOf(item)
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
                taxRatePercent = tax?.rate ?: 0.0,
                taxInclusive = tax?.isInclusive ?: false,
                taxId = tax?.id,
            )
        }
    }

    fun changeQty(lineId: String, delta: Double) {
        val line = cart.find { it.lineId == lineId } ?: return
        if (delta < 0 && lineId in firedLineIds) {
            // Already in the kitchen: coming off is a kitchen void (reason + manager PIN).
            voidMessage = null
            voidRequest = VoidRequest(line, minOf(-delta, line.quantity))
            return
        }
        cart = cart.mapNotNull {
            if (it.lineId != lineId) it
            else (it.quantity + delta).let { q -> if (q <= 0) null else it.copy(quantity = q) }
        }
    }

    fun removeLine(lineId: String) {
        val line = cart.find { it.lineId == lineId } ?: return
        if (lineId in firedLineIds) {
            voidMessage = null
            voidRequest = VoidRequest(line, line.quantity)
            return
        }
        cart = cart.filterNot { it.lineId == lineId }
    }

    /** Per-line discount %. Folds into the line via CartEngine before tax. */
    fun setLineDiscount(lineId: String, percent: Double) {
        val p = percent.coerceIn(0.0, 100.0)
        cart = cart.map { if (it.lineId == lineId) it.copy(discountPercent = p) else it }
    }

    fun setOrderDiscount(percent: Double) { orderDiscountPercent = percent.coerceIn(0.0, 100.0) }

    fun clear() {
        if (cart.any { it.lineId in firedLineIds }) {
            error = "Items already sent to the kitchen must be voided one by one (manager PIN)."
            return
        }
        cart = emptyList()
        orderDiscountPercent = 0.0
    }

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
        // ADR-S083 C4: past the offline window (or with the clock wound back) the till stops new sales until it
        // syncs; the queue still pushes. Read synchronously from the last pull time the sync status keeps.
        offlineBlock?.let { error = it; return }
        val billLines = cart.filter { it.lineId in lineIds }
        if (billLines.isEmpty()) return
        val table = if (isRetailMode.value) null else selectedTable.value
        if (needsTable.value) { error = SELECT_TABLE; return }
        session.value?.let { s ->
            if (s.actorUserId != user.userId) {
                error = "This shift was opened by ${foreignShiftOwner.value ?: "another cashier"}. " +
                    "Close it (More → Close session) and open your own before selling."
                return
            }
        }
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
                    selectedCustomer = null
                    takeaway.value = false
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

    /** A stale message (e.g. "Select a table first") must not greet the next checkout. */
    fun clearError() { error = null }

    /** Totals of a split-bill subset, with the whole-order discount the sale will carry. */
    fun totalsOf(lineIds: Set<String>): CartEngine.CartTotals =
        CartEngine.totals(cart.filter { it.lineId in lineIds }, orderDiscountPercent)

    companion object {
        const val SELECT_TABLE = "Select a table first, or choose Takeaway."
    }

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
