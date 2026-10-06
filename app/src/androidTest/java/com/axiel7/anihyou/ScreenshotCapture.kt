package com.axiel7.anihyou

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Captures the real rendered state for screenshot gates on every supported API level.
 *
 * Compose's captureToImage uses PixelCopy.request(Window, Rect, ...), which does not exist before API 26 and made
 * the whole API24 suite fail before any assertion could judge the screen. API 26 and newer keep that exact path.
 * Below API 26 the instrumentation takes a screenshot of the actual display through UiAutomation after Compose is
 * idle. The result is a non-empty full-screen bitmap, never a skipped assertion or a placeholder image.
 *
 * UiAutomation captures the global display, not the Compose root. An idle Compose tree and a non-empty bitmap do not
 * prove that the app is on screen: the launcher onboarding card was once stored as "diagnostics.png" on API 24.
 * [captureVerifiedScreenshot] therefore refuses to store a picture unless the app window is the active, focused,
 * RESUMED window both before and after the capture and the expected screen content is displayed.
 */
internal fun ComposeTestRule.captureRootBitmap(): Bitmap {
    waitForIdle()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return onRoot().captureToImage().asAndroidBitmap()
    }
    val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
        "UiAutomation returned no screenshot on API ${Build.VERSION.SDK_INT}"
    }
    check(screenshot.width > 0 && screenshot.height > 0) { "UiAutomation returned an empty screenshot" }
    return screenshot
}

/** What the OS reports about the screen at one instant. Pure data so the decision is testable without a device. */
internal data class ForegroundSnapshot(
    val expectedPackage: String,
    val activeWindowPackage: String?,
    val activityResumed: Boolean,
    val windowFocused: Boolean,
    val windowPackages: List<String> = emptyList(),
)

/**
 * The fail-closed decision: an empty list means the app screen is the one being displayed.
 *
 * The activity window itself loses input focus while one of the app's own dialogs (for example the rollback
 * confirmation) is in front of it. That is still the app screen: the active window belongs to the app package and the
 * activity stays RESUMED. A window of another package (launcher, system UI, another app) in the foreground is always a
 * violation, whatever focus the activity window reports.
 */
internal object ScreenshotForegroundPolicy {
    fun violations(snapshot: ForegroundSnapshot): List<String> = buildList {
        if (snapshot.activeWindowPackage != snapshot.expectedPackage) {
            add("active window belongs to ${snapshot.activeWindowPackage ?: "no package"}, expected ${snapshot.expectedPackage}")
        }
        if (!snapshot.activityResumed) add("activity is not RESUMED")
    }

    /** True when an own window (dialog) sits in front of the activity window. Reported, never a violation. */
    fun appDialogInFront(snapshot: ForegroundSnapshot): Boolean =
        snapshot.activeWindowPackage == snapshot.expectedPackage && snapshot.activityResumed && !snapshot.windowFocused
}

internal class ScreenshotNotOfAppException(message: String) : AssertionError(message)

internal class VerifiedScreenshot(val bitmap: Bitmap, val report: String)

private const val CAPTURE_LOG_TAG = "EP07CAPTURE"

/** Reads the real foreground state through UiAutomation and the activity. Never throws for a missing window. */
internal fun readForegroundSnapshot(activity: ComponentActivity): ForegroundSnapshot {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    var resumed = false
    var focused = false
    instrumentation.runOnMainSync {
        resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !activity.isFinishing
        focused = activity.window.decorView.hasWindowFocus()
    }
    val automation = instrumentation.uiAutomation
    @Suppress("DEPRECATION")
    val activePackage = runCatching {
        automation.rootInActiveWindow?.let { root -> root.packageName?.toString().also { root.recycle() } }
    }.getOrNull()
    val windows = runCatching {
        automation.windows.mapNotNull { window ->
            window.root?.let { root -> root.packageName?.toString().also { root.recycle() } }
        }
    }.getOrDefault(emptyList())
    return ForegroundSnapshot(activity.packageName, activePackage, resumed, focused, windows)
}

/**
 * Waits a bounded time for the app screen to be the foreground, verifies it again after the accessibility stream has
 * settled, runs [assertTarget] (the semantic content of the screen that is meant to be on the picture), takes the
 * picture, and verifies the foreground and the content once more. A launcher, another app or an overlay at any of the
 * three checks throws [ScreenshotNotOfAppException]; nothing is stored and no placeholder is produced.
 */
internal fun ComposeTestRule.captureVerifiedScreenshot(
    activity: ComponentActivity,
    name: String,
    timeoutMs: Long = 10_000,
    assertTarget: () -> Unit,
): VerifiedScreenshot {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val started = SystemClock.elapsedRealtime()
    var attempts = 0
    var firstViolations: List<String> = emptyList()
    var before = readForegroundSnapshot(activity)
    while (true) {
        waitForIdle()
        attempts++
        before = readForegroundSnapshot(activity)
        var problems = ScreenshotForegroundPolicy.violations(before)
        if (attempts == 1) firstViolations = problems
        if (problems.isEmpty()) {
            // Window transitions and system overlays announce themselves on the accessibility stream.
            runCatching { automation.waitForIdle(250, 2_000) }
            before = readForegroundSnapshot(activity)
            problems = ScreenshotForegroundPolicy.violations(before)
            if (problems.isEmpty()) break
        }
        if (SystemClock.elapsedRealtime() - started > timeoutMs) {
            throw ScreenshotNotOfAppException(
                "$name: the app screen never became the foreground within ${timeoutMs} ms: $problems; " +
                    "windows=${before.windowPackages}",
            )
        }
        Thread.sleep(100)
    }
    assertTarget()
    val bitmap = captureRootBitmap()
    val after = readForegroundSnapshot(activity)
    val afterProblems = ScreenshotForegroundPolicy.violations(after)
    if (afterProblems.isNotEmpty()) {
        bitmap.recycle()
        throw ScreenshotNotOfAppException("$name: the foreground changed during the capture: $afterProblems; windows=${after.windowPackages}")
    }
    assertTarget()
    check(distinctSampledColors(bitmap) >= 3) { "$name: the captured frame is blank" }
    val waitedMs = SystemClock.elapsedRealtime() - started
    val report = buildString {
        appendLine("name=$name")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("expectedPackage=${before.expectedPackage}")
        appendLine("activeWindowPackageBefore=${before.activeWindowPackage}")
        appendLine("activeWindowPackageAfter=${after.activeWindowPackage}")
        appendLine("activityResumed=${before.activityResumed}/${after.activityResumed}")
        appendLine("activityWindowFocused=${before.windowFocused}/${after.windowFocused}")
        appendLine("appDialogInFront=${ScreenshotForegroundPolicy.appDialogInFront(before)}/${ScreenshotForegroundPolicy.appDialogInFront(after)}")
        appendLine("windowPackages=${before.windowPackages}")
        appendLine("attempts=$attempts")
        appendLine("firstAttemptViolations=$firstViolations")
        appendLine("waitedMs=$waitedMs")
        appendLine("bitmap=${bitmap.width}x${bitmap.height}")
    }
    Log.i(CAPTURE_LOG_TAG, report.replace('\n', ' '))
    return VerifiedScreenshot(bitmap, report)
}

/** Writes `<name>.png` and `<name>.capture.txt` only after the verified capture succeeded. */
internal fun ComposeTestRule.storeVerifiedScreenshot(
    activity: ComponentActivity,
    directory: File,
    name: String,
    assertTarget: () -> Unit,
) {
    check(directory.mkdirs() || directory.isDirectory) { "Could not create screenshot directory $directory" }
    val shot = captureVerifiedScreenshot(activity, name, assertTarget = assertTarget)
    File(directory, "$name.png").outputStream().buffered().use { output ->
        check(shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode screenshot $name" }
    }
    File(directory, "$name.capture.txt").writeText(shot.report)
    shot.bitmap.recycle()
}

private fun distinctSampledColors(bitmap: Bitmap): Int {
    val colors = HashSet<Int>()
    val stepX = maxOf(1, bitmap.width / 24)
    val stepY = maxOf(1, bitmap.height / 24)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            colors += bitmap.getPixel(x, y)
            if (colors.size >= 3) return colors.size
            x += stepX
        }
        y += stepY
    }
    return colors.size
}

/** Sends the device to the home screen through the accessibility stream. Used only by the negative regression. */
internal fun pressHomeThroughUiAutomation(): Boolean =
    InstrumentationRegistry.getInstrumentation().uiAutomation
        .performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
