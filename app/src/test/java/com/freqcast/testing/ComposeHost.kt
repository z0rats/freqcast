package com.freqcast.testing

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * `createComposeRule()` launches a plain [ComponentActivity] to host content, but Robolectric only
 * resolves activities declared in the merged manifest - which this app's own manifest doesn't
 * declare. This registers it as an outer [TestRule] so it runs before the compose rule's own
 * "before" launches the activity (`@Before` is too late, `@BeforeClass` too early - see
 * `docs/architecture/testing-ci.md`).
 *
 * Usage:
 * ```
 * val composeTestRule = createComposeRule()
 * @get:Rule val ruleChain = composeHostRuleChain(composeTestRule)
 * ```
 */
fun composeHostRuleChain(composeTestRule: ComposeContentTestRule = createComposeRule()): RuleChain =
    RuleChain.outerRule(registerComposeHostActivity).around(composeTestRule)

private val registerComposeHostActivity =
    TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val appContext = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(appContext.packageManager)
                    .addActivityIfNotPresent(ComponentName(appContext, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }
