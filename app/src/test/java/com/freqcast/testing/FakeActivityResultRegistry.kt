package com.freqcast.testing

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat

/**
 * Stands in for the system picker/dialog behind every `rememberLauncherForActivityResult` in
 * [content]: records each launch's contract + input, and answers it synchronously with whatever
 * [resultFor] returns for that contract (null = the user cancelled).
 */
class FakeActivityResultRegistry(
    var resultFor: (ActivityResultContract<*, *>) -> Any? = { null },
) : ActivityResultRegistry() {
    val launches = mutableListOf<Pair<ActivityResultContract<*, *>, Any?>>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launches += contract to input
        dispatchResult(requestCode, resultFor(contract))
    }

    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = this@FakeActivityResultRegistry
            }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, content = content)
    }
}
