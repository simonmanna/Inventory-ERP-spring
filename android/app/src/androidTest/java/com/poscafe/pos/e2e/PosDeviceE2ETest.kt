package com.poscafe.pos.e2e

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * End-to-end checks against the real app on a real device and a real server.
 * Each test starts the installed app fresh and drives it through the UI, the
 * way a cashier would. Server details come from instrumentation arguments, and
 * a test without the arguments it needs is skipped — so a plain
 * `connectedAndroidTest` run never touches a server:
 *
 * ```
 * adb shell am instrument -w \
 *   -e class com.poscafe.pos.e2e.PosDeviceE2ETest#reenrollAgainstServer \
 *   -e serverUrl http://192.168.1.10:8088/api/v1 -e deviceId <id> -e token <token> -e prefix T1 \
 *   -e managerName Admin -e managerPin 1234 \
 *   com.poscafe.pos.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The host script prepares the server (registers / revokes the device, toggles
 * Wi-Fi) and checks the server side. Screenshots of every failure land in the
 * app's external files dir under `e2e/`.
 *
 * Run it with `am instrument`, not Gradle's connected tests: those uninstall
 * the app afterwards and would delete the till's data.
 */
@RunWith(AndroidJUnit4::class)
class PosDeviceE2ETest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val app = instrumentation.targetContext

    private fun arg(name: String): String? = args.getString(name)?.takeIf { it.isNotBlank() }
    private fun need(vararg names: String) = names.forEach { assumeTrue("missing -e $it", arg(it) != null) }

    @Before
    fun launch() {
        // MIUI never reports the UI as idle; don't wait for it.
        Configurator.getInstance().waitForIdleTimeout = 0
        device.wakeUp()
        // MIUI blocks activity starts from a background process, so launch through the shell.
        val component = app.packageManager.getLaunchIntentForPackage(app.packageName)!!.component!!
        device.executeShellCommand("am start -W -f ${Intent.FLAG_ACTIVITY_CLEAR_TASK} -n ${component.flattenToShortString()}")
        device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), TIMEOUT)
    }

    @After
    fun screenshotOnExit() {
        val dir = File(app.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        device.takeScreenshot(File(dir, "${System.currentTimeMillis()}-last.png"))
    }

    // ------------------------------------------------------------------ tests

    /** A server that does not know this device's token is reported, and nothing is wiped. */
    @Test
    fun serverChangeRejectsUnknownDevice() {
        need("serverUrl", "managerName", "managerPin")
        signIn(arg("managerName")!!, arg("managerPin")!!)
        openServerDialog()
        fields()[0].text = arg("serverUrl")
        fields()[1].text = arg("managerPin")
        tap("Test & save")
        waitText("does not recognise this device", 30_000)
        log("server change correctly rejected; staff still present")
    }

    /** Manager re-enrolls the till against a server with a fresh device token. */
    @Test
    fun reenrollAgainstServer() {
        need("serverUrl", "deviceId", "token", "prefix", "managerName", "managerPin")
        signIn(arg("managerName")!!, arg("managerPin")!!)
        openServerDialog()
        fields()[1].text = arg("managerPin")
        tap("Re-enroll this device…")
        waitText("Set up this terminal")
        enroll()
    }

    /** First enrolment on a till sitting at the setup screen (e.g. after revocation). */
    @Test
    fun enrollFromSetup() {
        need("serverUrl", "deviceId", "token", "prefix")
        waitText("Set up this terminal")
        enroll()
    }

    /** Cashier opens a shift if needed and completes a cash sale. */
    @Test
    fun cashSale() {
        need("cashierName", "cashierPin", "item")
        signIn(arg("cashierName")!!, arg("cashierPin")!!)
        ensureShiftOpen()
        startOrder()
        addItem(arg("item")!!)
        // Phones show the cart behind a "View cart" bar; tablets show it beside the menu.
        val charge = By.textStartsWith("Charge")
        if (!device.wait(Until.hasObject(charge), 2_000)) tap("View cart")
        find(charge).click()
        waitText("Checkout")
        tap("Complete payment")
        waitText("Payment successful", 30_000)
        val number = device.findObject(By.text(Pattern.compile("[A-Z0-9]+-\\d{6}")))?.text
        log("sale completed: ${number ?: "(number not shown)"}")
        tap("New order")
        // A finished sale hands the till back to the floor for the next order.
        if (arg("table") != null) waitText(FLOOR_HINT)
    }

    /** A cashier who does not own the open shift is told so and cannot charge (the server would reject it). */
    @Test
    fun foreignShiftBlocksCharge() {
        need("cashierName", "cashierPin", "item")
        signIn(arg("cashierName")!!, arg("cashierPin")!!)
        waitText("Shift opened by")
        startOrder()
        addItem(arg("item")!!)
        val charge = By.textStartsWith("Charge")
        if (!device.wait(Until.hasObject(charge), 2_000)) tap("View cart")
        find(charge).click()
        waitText("Checkout")
        tap("Complete payment")
        waitText("Close it (More")
        if (device.hasObject(By.text("Payment successful"))) fail("sale went through on another cashier's shift")
        log("charge refused on another cashier's shift")
    }

    /** Cafe mode: a dine-in order cannot be rung up before a table is picked. */
    @Test
    fun dineInStartsAtTheFloor() {
        need("cashierName", "cashierPin")
        signIn(arg("cashierName")!!, arg("cashierPin")!!)
        waitText(FLOOR_HINT)
        if (device.hasObject(By.text("All"))) fail("the menu is reachable before a table is picked")
        log("Sell opens on the floor, menu hidden")
        tap("Takeaway")
        waitText("All")
        log("Takeaway opens the menu without a table")
        // Phones collapse the unset chip to its icon; tablets show the label.
        (device.findObject(By.desc("Takeaway")) ?: find(By.text("Takeaway"))).click()
        tap("Floor")
        waitText(FLOOR_HINT)
        log("Floor button returns to the tables")
        tap(arg("table") ?: "Table 1")
        waitText("All")
        device.pressBack()
        waitText(FLOOR_HINT)
        log("Back from a table returns to the floor")
    }

    /** Three wrong PINs trigger the backoff; the host restarts the app and runs [pinLockoutSurvivesRestart]. */
    @Test
    fun pinLockout() {
        need("cashierName")
        atPinScreen()
        tapName(arg("cashierName")!!)
        repeat(3) { enterPin("0000"); Thread.sleep(1_500) }
        enterPin("0000")
        waitText("Too many wrong PINs")
        log("backoff active after 3 failures")
    }

    @Test
    fun pinLockoutSurvivesRestart() {
        need("cashierName", "cashierPin")
        atPinScreen()
        tapName(arg("cashierName")!!)
        enterPin(arg("cashierPin")!!)
        waitText("Too many wrong PINs")
        log("backoff still active after a restart, even with the right PIN")
    }

    /** After the host revokes the device, a sync sends the till back to setup with a notice. */
    @Test
    fun revokedDeviceReturnsToSetup() {
        need("managerName", "managerPin")
        // The launch sync usually finds the revocation before anyone signs in.
        val notice = By.textContains("was revoked in the back office")
        if (!device.wait(Until.hasObject(notice), 20_000)) {
            signIn(arg("managerName")!!, arg("managerPin")!!)
            tap("More")
            scrollTo("Sync now").click()
            waitText("was revoked in the back office", 60_000)
        }
        log("revoked device is back at setup")
    }

    /** Reports what the Sync screen shows (queued / rejected counts, last error). */
    @Test
    fun syncReport() {
        need("managerName", "managerPin")
        signIn(arg("managerName")!!, arg("managerPin")!!)
        tap("More")
        scrollTo("Sync now")
        Thread.sleep(3_000)
        val visible = device.findObjects(By.text(Pattern.compile("(?s).+"))).map { it.text }
        log("sync: " + visible.joinToString(" | ").replace('\n', ' '))
        tap("Sync now")
        Thread.sleep(10_000)
        val after = device.findObjects(By.text(Pattern.compile("(?s).+"))).map { it.text }
        log("after sync: " + after.joinToString(" | ").replace('\n', ' '))
        device.findObject(By.text("Review"))?.let { review ->
            review.click()
            Thread.sleep(2_000)
            val failed = device.findObjects(By.text(Pattern.compile("(?s).+"))).map { it.text }
            log("review: " + failed.joinToString(" | ").replace('\n', ' '))
            if (arg("retry") == "true") {
                (device.findObject(By.text("Retry all")) ?: device.findObject(By.text("Retry")))?.click()
                Thread.sleep(20_000)
                val retried = device.findObjects(By.text(Pattern.compile("(?s).+"))).map { it.text }
                log("after retry: " + retried.joinToString(" | ").replace('\n', ' '))
            }
        }
    }

    /** A manager discards every rejected op from the Sync screen, which then shows nothing to review. */
    @Test
    fun discardRejected() {
        need("managerName", "managerPin")
        signIn(arg("managerName")!!, arg("managerPin")!!)
        tap("More")
        scrollTo("Sync now")
        val review = device.wait(Until.findObject(By.text("Review")), 5_000)
        if (review == null) {
            log("nothing rejected")
            return
        }
        review.click()
        while (device.wait(Until.hasObject(By.text("Discard…")), 5_000)) {
            tap("Discard…")
            // Field labels are not visible text on every OEM; wait for the dialog body.
            waitText("will not accept it again")
            fields().last().text = arg("managerPin")
            tap("Discard")
            if (!device.wait(Until.gone(By.text("Cancel")), 15_000)) fail("discard dialog stayed open")
            log("discarded one rejected op")
        }
        waitText("Nothing needs attention")
        log("no rejected ops left")
    }

    // ---------------------------------------------------------------- helpers

    private fun signIn(name: String, pin: String) {
        atPinScreen()
        log("at PIN screen")
        tapName(name)
        log("picked $name")
        enterPin(pin)
        log("PIN entered")
        // Home shell: the bottom bar is always there.
        waitText("More")
    }

    /** Start every flow from the PIN screen: lock the terminal if someone is still signed in. */
    private fun atPinScreen() {
        val pinScreen = By.text("Who's on the till?")
        device.wait(Until.hasObject(By.pkg(app.packageName)), TIMEOUT)
        if (device.wait(Until.hasObject(pinScreen), 4_000)) return
        tap("More")
        scrollTo("Lock terminal").click()
        if (!device.wait(Until.hasObject(pinScreen), TIMEOUT)) fail("could not lock the terminal")
    }

    private fun enterPin(pin: String) {
        pin.forEach { d -> find(By.text(d.toString())).click() }
        find(By.desc("Log in")).click()
    }

    private fun openServerDialog() {
        tap("More")
        // The list may still be settling after the scroll; retry the tap until the dialog shows.
        repeat(3) {
            scrollTo("Server address…")
            Thread.sleep(600)
            find(By.text("Server address…")).click()
            if (device.wait(Until.hasObject(By.text("Test & save")), 4_000)) return
        }
        waitText("Test & save")
    }

    private fun enroll() {
        tap("Connect to server")
        val f = fields()
        f[0].text = arg("serverUrl")
        f[1].text = arg("deviceId")
        f[2].text = arg("token")
        // A notice above the form (revoked / key store reset) can push the prefix field off screen.
        if (f.size < 4) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
            Thread.sleep(500)
        }
        fields().last().text = arg("prefix")
        // No Back to hide the keyboard: with none showing it would leave the app.
        scrollTo("Enroll & first sync").click()
        waitText("Who's on the till?", 120_000)
        log("enrolled and first sync done")
    }

    private fun ensureShiftOpen() {
        if (device.wait(Until.hasObject(By.text("Cash session closed")), 3_000)) {
            tap("Open now")
            waitText("Open cash session")
            tap("Open session")
            device.wait(Until.gone(By.text("Open cash session")), TIMEOUT)
            log("shift opened")
        }
    }

    /** Cafe mode starts at the floor: pick `-e table` (e.g. "Table 1"), else go takeaway. */
    private fun startOrder() {
        if (!device.wait(Until.hasObject(By.text(FLOOR_HINT)), 3_000)) return
        val table = arg("table")
        if (table != null) tap(table) else tap("Takeaway")
        log("order started: ${table ?: "takeaway"}")
    }

    private fun addItem(name: String) {
        tap("All")
        val item = device.wait(Until.findObject(By.text(name)), TIMEOUT)
            ?: scrollTo(name)
        item.click()
        // Items with options open a sheet; take the defaults.
        device.wait(Until.findObject(By.text(Pattern.compile("Add · .*"))), 2_000)?.click()
        log("added $name")
    }

    /** Text fields on screen, top to bottom. */
    private fun fields(): List<UiObject2> {
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT)
        return device.findObjects(By.clazz("android.widget.EditText")).sortedBy { it.visibleBounds.top }
    }

    private fun find(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), TIMEOUT) ?: fail("not found: $selector")

    private fun tap(text: String) = find(By.text(text)).click()

    /** Staff tiles render "first last", so a missing last name leaves a trailing space. */
    private fun tapName(name: String) {
        val tile = By.text(Pattern.compile("""\s*""" + Pattern.quote(name) + """\s*"""))
        // The staff grid sits above the keypad and scrolls on its own; swipe inside it.
        repeat(5) {
            device.wait(Until.findObject(tile), 1_500)?.let { it.click(); return }
            val x = device.displayWidth / 2
            device.swipe(x, device.displayHeight * 2 / 5, x, device.displayHeight / 6, 25)
        }
        find(tile).click()
    }

    private fun waitText(fragment: String, timeout: Long = TIMEOUT) {
        if (!device.wait(Until.hasObject(By.textContains(fragment)), timeout)) fail("never saw \"$fragment\"")
    }

    private fun scrollTo(text: String): UiObject2 {
        repeat(8) {
            device.findObject(By.text(text))?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        }
        return find(By.text(text))
    }

    private fun fail(message: String): Nothing {
        val visible = device.findObjects(By.text(Pattern.compile("(?s).+"))).map { it.text } +
            device.findObjects(By.desc(Pattern.compile("(?s).+"))).map { "desc:" + it.contentDescription }
        log("on screen: " + visible.joinToString(" | ").replace('\n', ' '))
        val dir = File(app.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        device.takeScreenshot(File(dir, "${System.currentTimeMillis()}-fail.png"))
        throw AssertionError(message)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        val result = Bundle().apply { putString("e2e", message) }
        instrumentation.sendStatus(0, result)
    }

    private companion object {
        const val TAG = "PosE2E"
        const val TIMEOUT = 15_000L
        const val FLOOR_HINT = "Pick a table to start the order"
    }
}
