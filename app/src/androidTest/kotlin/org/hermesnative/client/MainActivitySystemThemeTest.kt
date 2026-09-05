package org.hermesnative.client

import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class MainActivitySystemLightThemeTest {
    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule =
        RuleChain
            .outerRule(SystemNightModeRule(UiModeManager.MODE_NIGHT_NO))
            .around(composeTestRule)

    @Test
    fun api24_system_light_theme_launches_and_renders_entry_state() {
        assertEquals(24, Build.VERSION.SDK_INT)
        assertLaunchTheme(
            expectedNightMode = Configuration.UI_MODE_NIGHT_NO,
            expectedSurfaceColor = Color(0xFFF9F9FF),
        )
    }

    private fun assertLaunchTheme(
        expectedNightMode: Int,
        expectedSurfaceColor: Color,
    ) {
        composeTestRule.runOnIdle {
            assertEquals(
                expectedNightMode,
                composeTestRule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            )
        }
        composeTestRule
            .onNodeWithText("Hermes Native Client")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Connect to a Hermes Gateway")
            .assertIsDisplayed()
        assertSurfaceColor(composeTestRule.activity, expectedSurfaceColor)
    }
}

@RunWith(AndroidJUnit4::class)
class MainActivitySystemDarkThemeTest {
    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule =
        RuleChain
            .outerRule(SystemNightModeRule(UiModeManager.MODE_NIGHT_YES))
            .around(composeTestRule)

    @Test
    fun api24_system_dark_theme_launches_and_renders_entry_state() {
        assertEquals(24, Build.VERSION.SDK_INT)
        composeTestRule.runOnIdle {
            assertEquals(
                Configuration.UI_MODE_NIGHT_YES,
                composeTestRule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            )
        }
        composeTestRule
            .onNodeWithText("Hermes Native Client")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Connect to a Hermes Gateway")
            .assertIsDisplayed()
        assertSurfaceColor(composeTestRule.activity, Color(0xFF121318))
    }
}

private fun assertSurfaceColor(
    activity: MainActivity,
    expectedColor: Color,
) {
    var actualColor = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        actualColor = bitmap.getPixel(1, bitmap.height / 2)
        bitmap.recycle()
    }
    assertEquals(expectedColor.toArgb(), actualColor)
}

private class SystemNightModeRule(
    private val nightMode: Int,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val uiModeManager =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .targetContext
                        .getSystemService(UiModeManager::class.java)
                val originalNightMode = uiModeManager.nightMode
                try {
                    uiModeManager.setNightMode(nightMode)
                    base.evaluate()
                } finally {
                    uiModeManager.setNightMode(originalNightMode)
                }
            }
        }
}
