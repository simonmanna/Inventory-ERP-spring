package com.poscafe.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.account.AccountRepository
import com.poscafe.pos.data.repo.AuthRepository
import com.poscafe.pos.sync.SyncWorker
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.poscafe.pos.ui.screens.APPS_ROUTE
import com.poscafe.pos.ui.screens.AppTarget
import com.poscafe.pos.ui.screens.BarcodeAppScreen
import com.poscafe.pos.ui.screens.DashboardScreen
import com.poscafe.pos.ui.screens.HomeScreen
import com.poscafe.pos.ui.screens.HomeTab
import com.poscafe.pos.ui.screens.LoginScreen
import com.poscafe.pos.ui.screens.PinLoginScreen
import com.poscafe.pos.ui.screens.SetupScreen
import com.poscafe.pos.ui.screens.SyncScreen
import com.poscafe.pos.ui.theme.PosTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var config: DeviceConfig
    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var account: AccountRepository

    /**
     * Mobile app shell (2026-11-23):
     *   setup → login (account; PIN when offline or standalone) → dashboard (app grid)
     *   dashboard → pos-pin/{tab} (every cashier unlocks the till with a PIN) → home/{tab} (the POS)
     * The account stays signed in across restarts until Sign out; the till locks on Lock, on idle and whenever the
     * dashboard is shown.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = when {
            !config.isReady -> "setup"
            account.restore() -> "dashboard"
            else -> "login"
        }
        setContent {
            PosTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                ) {
                    val nav = rememberNavController()
                    // Revoked mid-shift (detected by a sync run) or re-enroll chosen: sign out and go to setup.
                    val revoked by config.revoked.collectAsState()
                    val setupRequested by config.setupRequested.collectAsState()
                    // ADR-S083 C4: a cashier idle past the org's timeout locks the till; the next one signs in with a PIN.
                    LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(30_000)
                            if (auth.idleExpired()) {
                                auth.lockPos()
                                nav.navigate("pos-pin/${HomeTab.Sell.name}") { popUpTo(DASHBOARD) }
                            }
                        }
                    }
                    LaunchedEffect(revoked, setupRequested) {
                        if ((revoked || setupRequested) && nav.currentDestination?.route != "setup") {
                            account.signOut()
                            nav.navigate("setup") { popUpTo(0) { inclusive = true } }
                        }
                    }
                    val toDashboard: () -> Unit = { nav.navigate(DASHBOARD) { popUpTo(0) { inclusive = true } } }
                    // Stayed signed in: once online, check the account still stands (deactivated → back to sign-in).
                    LaunchedEffect(Unit) {
                        if (start == DASHBOARD && !account.revalidate()) {
                            nav.navigate("login") { popUpTo(0) { inclusive = true } }
                        }
                    }
                    NavHost(
                        navController = nav,
                        startDestination = start,
                        enterTransition = { fadeIn(tween(220)) },
                        exitTransition = { fadeOut(tween(180)) },
                        popEnterTransition = { fadeIn(tween(220)) },
                        popExitTransition = { fadeOut(tween(180)) },
                    ) {
                        composable("setup") {
                            SetupScreen(onDone = { signedIn ->
                                if (signedIn) toDashboard() else nav.navigate("login") { popUpTo(0) { inclusive = true } }
                            })
                        }
                        composable("login") { LoginScreen(onSignedIn = toDashboard) }
                        composable(DASHBOARD) {
                            // Outside the till: whoever unlocked it is signed out of it (the POS always asks for a PIN).
                            LaunchedEffect(Unit) { auth.lockPos() }
                            DashboardScreen(
                                onOpenApp = { target ->
                                    when (target) {
                                        is AppTarget.Pos -> nav.navigate("pos-pin/${target.tab.name}")
                                        is AppTarget.Route -> nav.navigate(target.route)
                                    }
                                },
                                onOpenSync = { nav.navigate("sync") },
                                onSignedOut = { nav.navigate("login") { popUpTo(0) { inclusive = true } } },
                            )
                        }
                        composable(
                            "pos-pin/{tab}",
                            arguments = listOf(navArgument("tab") { type = NavType.StringType }),
                        ) { entry ->
                            val tab = entry.arguments?.getString("tab") ?: HomeTab.Sell.name
                            PinLoginScreen(
                                onLoggedIn = { nav.navigate("home/$tab") { popUpTo("pos-pin/{tab}") { inclusive = true } } },
                                title = "Point of Sale",
                                subtitle = "Enter your PIN to open the till.",
                                onBack = { if (!nav.popBackStack(DASHBOARD, false)) toDashboard() },
                            )
                        }
                        composable(
                            "home/{tab}",
                            arguments = listOf(navArgument("tab") { type = NavType.StringType }),
                        ) { entry ->
                            val tab = entry.arguments?.getString("tab")
                                ?.let { name -> HomeTab.entries.firstOrNull { it.name == name } } ?: HomeTab.Sell
                            HomeScreen(
                                initialTab = tab,
                                onOpenSync = { nav.navigate("sync") },
                                onLock = {
                                    auth.lockPos()
                                    nav.navigate("pos-pin/${HomeTab.Sell.name}") { popUpTo(DASHBOARD) }
                                },
                                onNavigate = { route ->
                                    if (route == APPS_ROUTE) {
                                        if (!nav.popBackStack(DASHBOARD, false)) toDashboard()
                                    } else {
                                        nav.navigate(route)
                                    }
                                },
                            )
                        }
                        composable("barcode") { BarcodeAppScreen(onBack = { nav.popBackStack() }) }
                        composable("sync") { SyncScreen(onBack = { nav.popBackStack() }) }
                        composable("messaging") { com.poscafe.pos.ui.screens.messaging.MessagingScreen(onBack = { nav.popBackStack() }) }
                        composable("menu-manager") { com.poscafe.pos.ui.screens.manage.MenuManagerScreen(onBack = { nav.popBackStack() }) }
                        composable("products") { com.poscafe.pos.ui.screens.manage.ProductsScreen(onBack = { nav.popBackStack() }) }
                        composable("taxes") { com.poscafe.pos.ui.screens.manage.TaxesScreen(onBack = { nav.popBackStack() }) }
                        composable("modifier-groups") { com.poscafe.pos.ui.screens.manage.ModifierGroupsScreen(onBack = { nav.popBackStack() }) }
                        composable("accompaniment-groups") { com.poscafe.pos.ui.screens.manage.AccompanimentGroupsScreen(onBack = { nav.popBackStack() }) }
                        composable("tables-manager") { com.poscafe.pos.ui.screens.manage.TablesManagerScreen(onBack = { nav.popBackStack() }) }
                        composable("registers") { com.poscafe.pos.ui.screens.manage.RegistersScreen(onBack = { nav.popBackStack() }) }
                        composable("shift-history") { com.poscafe.pos.ui.screens.manage.ShiftHistoryScreen(onBack = { nav.popBackStack() }) }
                        composable("crm") { com.poscafe.pos.ui.screens.crm.CrmScreen(onBack = { nav.popBackStack() }) }
                        composable("customers") { com.poscafe.pos.ui.screens.manage.CustomersScreen(onBack = { nav.popBackStack() }) }
                        composable("suppliers") { com.poscafe.pos.ui.screens.manage.SuppliersScreen(onBack = { nav.popBackStack() }) }
                        composable("stock") { com.poscafe.pos.ui.screens.manage.StockScreen(onBack = { nav.popBackStack() }) }
                        composable("inventory-count") { com.poscafe.pos.ui.screens.manage.InventoryCountScreen(onBack = { nav.popBackStack() }) }
                        composable("purchases") { com.poscafe.pos.ui.screens.manage.PurchasesScreen(onBack = { nav.popBackStack() }) }
                        composable("expenses") { com.poscafe.pos.ui.screens.manage.ExpensesScreen(onBack = { nav.popBackStack() }) }
                        composable("staff") { com.poscafe.pos.ui.screens.manage.StaffScreen(onBack = { nav.popBackStack() }) }
                        composable("reports") { com.poscafe.pos.ui.screens.ReportsScreen(onBack = { nav.popBackStack() }) }
                        composable("backup") { com.poscafe.pos.ui.screens.manage.BackupScreen(onBack = { nav.popBackStack() }) }
                        composable("pos-report") { com.poscafe.pos.ui.screens.report.PosReportScreen(onBack = { nav.popBackStack() }) }
                        composable("business") { com.poscafe.pos.ui.screens.manage.BusinessSettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }

    private companion object {
        const val DASHBOARD = "dashboard"
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        auth.touch()
        return super.dispatchTouchEvent(ev)
    }

    /** App foreground: push whatever was sold while backgrounded (no-op standalone). */
    override fun onStart() {
        super.onStart()
        if (config.isEnrolled) SyncWorker.syncNow(this, replace = false)
    }
}
