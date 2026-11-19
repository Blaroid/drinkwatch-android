package com.drinkwatch.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cheapest possible end-to-end check: the app launches, the shell renders and
 * the tabs exist. Runs on an emulator in CI, so a Compose or manifest regression
 * fails the build instead of shipping.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomTabsRender() {
        compose.onNodeWithText("Prices").assertIsDisplayed()
        compose.onNodeWithText("Basket").assertIsDisplayed()
        compose.onNodeWithText("Budget").assertIsDisplayed()
        compose.onNodeWithText("You").assertIsDisplayed()
    }
}
