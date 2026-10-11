package com.murkio.identity.android

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.ui.MurkioIdentityCanvas
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the default canvas to a PNG with Robolectric's native graphics, to
 * look at what Compose really paints. Runs only with -Dmurkio.screenshots=dir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h800dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theDefaultCanvas() {
        val directory = System.getProperty("murkio.screenshots").orEmpty()
        assumeTrue("set -Dmurkio.screenshots=<dir> to render", directory.isNotEmpty())

        compose.setContent {
            Box(Modifier.requiredWidth(420.dp).background(Color(0xFFF3F4F6))) {
                MurkioIdentityCanvas(preview = Json.write(readSpec("canvas/default.json")), locale = "en")
            }
        }
        compose.waitForIdle()

        // Drawn into a software canvas: no window compositor is needed for that.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val file = File(directory, "kotlin-canvas.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 1000)
    }
}
