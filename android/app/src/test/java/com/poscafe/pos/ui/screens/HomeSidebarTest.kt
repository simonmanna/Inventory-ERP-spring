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

    private fun show(currentTab: HomeTab = HomeTab.Sell, isRetail: Boolean = false, standalone: Boolean = false) {
        compose.setContent {
            PosTheme {
                Box(Modifier.fillMaxSize()) {
                    HomeSidebar(
                        businessName = "Demo Cafe",
                        cashierName = "Sarah Cashier",
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
    fun `sections follow the web sidebar and the active section starts open`() {
        show()
        listOf("Dashboard", "POS", "Inventory", "Customers", "Suppliers", "Menus", "Tables", "Accounting",
            "Expenses", "Purchases", "Communication", "Settings").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        // POS holds the active tab (Sell), so it is expanded; Inventory is not.
        compose.onNodeWithText("POS Terminal").assertIsDisplayed()
        compose.onNodeWithText("Stock Levels").assertDoesNotExist()
        snapshot("sidebar-default")
    }

    @Test
    fun `expanding a section and tapping an item navigates`() {
        show()
        compose.onNodeWithText("Inventory").performClick()
        compose.onNodeWithText("Stock Levels").performClick()
        assertEquals("stock", route)
        snapshot("sidebar-inventory-open")
    }

    @Test
    fun `tab items switch the home tab`() {
        show()
        compose.onNodeWithText("POS Receipts").performClick()
        assertEquals(HomeTab.Orders, tab)
    }

    @Test
    fun `retail hides tables and standalone hides sync`() {
        show(isRetail = true, standalone = true)
        compose.onNodeWithText("Tables").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Company Settings").assertIsDisplayed()
        compose.onNodeWithText("Sync").assertDoesNotExist()
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
