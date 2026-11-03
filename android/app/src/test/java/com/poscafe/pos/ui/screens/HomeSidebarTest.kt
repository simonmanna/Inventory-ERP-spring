package com.poscafe.pos.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poscafe.pos.ui.theme.PosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class HomeSidebarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var tab: HomeTab? = null
    private var route: String? = null

    private fun show(
        currentTab: HomeTab = HomeTab.Sell,
        isRetail: Boolean = false,
        standalone: Boolean = false,
        permissions: Set<String> = setOf("admin"),
    ) {
        compose.setContent {
            PosTheme {
                Box(Modifier.fillMaxSize()) {
                    HomeSidebar(
                        businessName = "Demo Cafe",
                        cashierName = "Sarah Cashier",
                        permissions = permissions,
                        standalone = standalone,
                        isRetail = isRetail,
                        currentTab = currentTab,
                        onTab = { tab = it },
                        onNavigate = { route = it },
                    )
                }
            }
        }
    }

    @Test
    fun `sections follow a shift and the active section starts open`() {
        show()
        listOf("Dashboard", "Sell", "Receipts", "Cash & Shifts", "Reports", "Catalog", "Stock", "Customers",
            "Setup", "Device").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        // Sell holds the active tab, so it is expanded; Stock is not.
        compose.onNodeWithText("POS Terminal").assertIsDisplayed()
        compose.onNodeWithText("Stock Levels").assertDoesNotExist()
        snapshot("sidebar-default")
    }

    @Test
    fun `expanding a section and tapping an item navigates`() {
        show()
        compose.onNodeWithText("Stock").performClick()
        compose.onNodeWithText("Stock Levels").performClick()
        assertEquals("stock", route)
        snapshot("sidebar-inventory-open")
    }

    @Test
    fun `tab items switch the home tab`() {
        show()
        compose.onNodeWithText("Receipts").performClick()
        compose.onNodeWithText("POS Receipts").performClick()
        assertEquals(HomeTab.Orders, tab)
    }

    @Test
    fun `retail hides tables and standalone hides sync`() {
        show(isRetail = true, standalone = true)
        compose.onNodeWithText("Floor Plan").assertDoesNotExist()
        compose.onNodeWithText("Setup").performClick()
        compose.onNodeWithText("Company Settings").assertIsDisplayed()
        compose.onNodeWithText("Tables").assertDoesNotExist()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Backup & Restore").assertIsDisplayed()
        compose.onNodeWithText("Sync").assertDoesNotExist()
    }

    @Test
    fun `a cashier only sees front-of-house sections`() {
        show(permissions = setOf("pos:checkout", "pos:read", "cash_session:open", "cash_session:read"))
        listOf("Sell", "Receipts", "Cash & Shifts", "Device").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        listOf("Dashboard", "Reports", "Catalog", "Stock", "Setup").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Backup & Restore").assertDoesNotExist()
        compose.onNodeWithText("Sync").assertIsDisplayed()
    }

    /** Writes the rendered drawer to build/sidebar-snapshots for a visual check. */
    private fun snapshot(name: String) {
        val dir = File("build/sidebar-snapshots").apply { mkdirs() }
        compose.waitForIdle()
        // captureToImage() times out under Robolectric on this Compose version; draw the view directly.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
