package eu.kanade.tachiyomi.ui.browse.animesource.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.animesource.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BrowseAnimeSourceScreenModel(
    private val sourceId: Long,
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
) : StateScreenModel<BrowseAnimeSourceScreenModel.State>(State()) {

    private val source: AnimeCatalogueSource?
        get() = animeSourceManager.get(sourceId) as? AnimeCatalogueSource

    private var currentPage = 1
    @Volatile
    private var isLoadingMore = false

    fun loadPopular() {
        screenModelScope.launchIO {
            mutableState.value = State(isLoading = true)
            try {
                val source = source ?: run {
                    mutableState.value = State(error = true)
                    return@launchIO
                }
                val result = source.getPopularAnime(1)
                currentPage = 1
                mutableState.value = State(
                    items = result.animes,
                    hasNextPage = result.hasNextPage,
                )
            } catch (e: Exception) {
                mutableState.value = State(error = true)
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            loadPopular()
            return
        }
        screenModelScope.launchIO {
            mutableState.value = State(isLoading = true)
            try {
                val source = source ?: run {
                    mutableState.value = State(error = true)
                    return@launchIO
                }
                val result = source.getSearchAnime(1, query, AnimeFilterList())
                currentPage = 1
                mutableState.value = State(
                    items = result.animes,
                    hasNextPage = result.hasNextPage,
                )
            } catch (e: Exception) {
                mutableState.value = State(error = true)
            }
        }
    }

    fun loadNextPage() {
        if (isLoadingMore) return
        val currentState = state.value
        if (!currentState.hasNextPage) return

        isLoadingMore = true
        screenModelScope.launchIO {
            try {
                val source = source ?: return@launchIO
                val nextPage = currentPage + 1
                val result = source.getPopularAnime(nextPage)
                currentPage = nextPage
                mutableState.value = currentState.copy(
                    items = currentState.items + result.animes,
                    hasNextPage = result.hasNextPage,
                )
            } catch (_: Exception) {
                // Silently fail for pagination
            } finally {
                isLoadingMore = false
            }
        }
    }

    fun addAnimeToDatabase(sAnime: SAnime, onAdded: (Long) -> Unit) {
        screenModelScope.launchIO {
            val existingAnime = getAnime.await(sAnime.url, sourceId)
            if (existingAnime != null) {
                onAdded(existingAnime.id)
                return@launchIO
            }

            val anime = Anime.create().copy(
                source = sourceId,
                url = sAnime.url,
                title = sAnime.title,
                artist = sAnime.artist,
                author = sAnime.author,
                description = sAnime.description,
                genre = sAnime.getGenres(),
                thumbnailUrl = sAnime.thumbnail_url,
                status = sAnime.status.toLong(),
                initialized = true,
            )
            val id = animeRepository.insert(anime)
            onAdded(id)
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = false,
        val items: List<SAnime> = emptyList(),
        val hasNextPage: Boolean = false,
        val error: Boolean = false,
    )
}
