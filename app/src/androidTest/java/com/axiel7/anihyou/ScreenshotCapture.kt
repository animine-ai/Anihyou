package com.axiel7.anihyou

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Captures the real rendered state for screenshot gates on every supported API level.
 *
 * Compose's captureToImage uses PixelCopy.request(Window, Rect, ...), which does not exist before API 26 and made
 * the whole API24 suite fail before any assertion could judge the screen. API 26 and newer keep that exact path.
 * Below API 26 the instrumentation takes a screenshot of the actual display through UiAutomation after Compose is
 * idle. The result is a non-empty full-screen bitmap, never a skipped assertion or a placeholder image.
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
