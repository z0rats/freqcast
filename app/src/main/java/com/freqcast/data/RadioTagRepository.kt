package com.freqcast.data

import android.content.Context
import java.util.Locale

/**
 * Local cache of the Radio Browser directory's full tag catalog (see [RadioTagDao]), backing the
 * Discover GENRE tab's offline autocomplete. Unlike [RadioBrowserApi], which this repository
 * deliberately does not wrap - syncing from the network is the caller's responsibility (see
 * [com.freqcast.ui.DiscoverStationsViewModel]), matching how that ViewModel already owns its own
 * [RadioBrowserApi] instance directly for search, rather than routing it through a repository.
 */
class RadioTagRepository(
    private val dao: RadioTagDao,
) {
    suspend fun isEmpty(): Boolean = dao.count() == 0

    suspend fun count(): Int = dao.count()

    suspend fun searchByPrefix(
        prefix: String,
        limit: Int = SUGGESTION_LIMIT,
    ): List<RadioTag> = dao.searchByPrefix(prefix.lowercase(Locale.ROOT), limit)

    suspend fun replaceAll(tags: List<RadioTag>) = dao.replaceAll(tags)

    companion object {
        const val SUGGESTION_LIMIT = 8

        fun create(context: Context): RadioTagRepository =
            RadioTagRepository(AppDatabase.getDatabase(context).radioTagDao())
    }
}
