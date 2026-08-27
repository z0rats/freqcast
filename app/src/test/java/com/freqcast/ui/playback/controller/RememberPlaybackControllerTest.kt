package com.freqcast.ui.playback.controller

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freqcast.ui.RadioPlaybackService
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers [rememberPlaybackController]'s bind/unbind lifecycle - the one piece of logic in this
 * file, everything else is Compose plumbing. Binds to a real [RadioPlaybackService] (built via
 * [Robolectric.buildService], same approach as [com.freqcast.ui.RadioPlaybackServiceAutoTest]) so
 * the [ServiceConnection][android.content.ServiceConnection] callback wiring is exercised for
 * real, not mocked - `onServiceConnected`/`onServiceDisconnected` are exactly the kind of thing a
 * mock would let silently drift from the real `LocalBinder` contract.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RememberPlaybackControllerTest {
    // Same workaround as DragDropStateTest: ComposeTestRule's host ComponentActivity isn't
    // declared in this app's own manifest, so Robolectric can't resolve it unless registered
    // first - must run before composeTestRule's own "before" launches it.
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

    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerComposeHostActivity).around(composeTestRule)

    // MediaSession enforces a unique id per process (see RadioPlaybackServiceAutoTest/
    // RadioPlaybackServiceConnectionErrorTest's identical note) - must be torn down or the next
    // test's session creation fails with "Session ID must be unique".
    private var service: RadioPlaybackService? = null

    @After
    fun tearDown() {
        service?.onDestroy()
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `binds on ON_START, exposes a controller, and clears it again on ON_STOP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val builtService = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        service = builtService
        val binder = builtService.LocalBinder()
        val intent = Intent(context, RadioPlaybackService::class.java)
        val shadowApp = shadowOf(context as Application)
        shadowApp.setComponentNameAndServiceForBindServiceForIntent(
            intent,
            ComponentName(context, RadioPlaybackService::class.java),
            binder,
        )
        shadowApp.setBindServiceCallsOnServiceConnectedDirectly(true)

        val lifecycleOwner = FakeLifecycleOwner()
        lateinit var controllerState: State<PlaybackController?>

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                controllerState = rememberPlaybackController()
            }
        }
        composeTestRule.waitForIdle()
        assertNull(controllerState.value)

        composeTestRule.runOnIdle { lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
        composeTestRule.runOnIdle { lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }

        assertTrue(controllerState.value is ServiceBackedPlaybackController)

        composeTestRule.runOnIdle { lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }

        assertNull(controllerState.value)
    }
}
