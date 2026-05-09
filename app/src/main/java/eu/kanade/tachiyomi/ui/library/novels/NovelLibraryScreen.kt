package eu.kanade.tachiyomi.ui.library.novels

import android.content.Context

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.screens.EmptyScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.NovelCategory
import eu.kanade.presentation.library.components.LibraryTabs
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.tachiyomi.ui.library.novels.ChimaReaderActivity
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@Composable
fun NovelLibraryScreen() {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { NovelLibraryScreenModel() }
    val state by screenModel.state.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { scrollBehavior ->
            LibraryToolbar(
                hasActiveFilters = state.hasActiveFilters,
                selectedCount = state.selection.size,
                title = LibraryToolbarTitle(
                    text = stringResource(MR.strings.label_novels),
                    numberOfManga = if (state.activeCategory != null) state.getItemCountForCategory(state.activeCategory!!) else null,
                ),
                onClickUnselectAll = screenModel::clearSelection,
                onClickSelectAll = screenModel::selectAll,
                onClickInvertSelection = screenModel::invertSelection,
                onClickFilter = screenModel::showSortDialog,
                onClickRefresh = { screenModel.loadLibrary() },
                onClickGlobalUpdate = {},
                onClickOpenRandomManga = {},
                onClickSyncNow = {},
                onClickSyncExh = null,
                isSyncEnabled = false,
                searchQuery = state.searchQuery,
                onSearchQueryChange = screenModel::search,
                scrollBehavior = scrollBehavior,
                onInvalidateDownloadCache = {},
                onClickEditCategories = { navigator.push(NovelCategoryScreen()) },
            )
        },
        bottomBar = {
            LibraryBottomActionMenu(
                visible = state.selectionMode,
                onChangeCategoryClicked = screenModel::showChangeCategoryDialog,
                onMarkAsReadClicked = { screenModel.resetStatsForSelected() }, // Reusing stats reset as a "mark as read" equivalent for novels
                onMarkAsUnreadClicked = {},
                onDownloadClicked = null,
                onDeleteClicked = screenModel::showDeleteConfirmDialog,
                onMigrateClicked = {},
                onMergeClicked = {},
                onSelectionUpdateClicked = {},
                onClickCleanTitles = null,
                onClickCollectRecommendations = null,
                onClickAddToMangaDex = null,
                onClickResetInfo = null,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        if (state.isLoading) {
            LoadingScreen(Modifier.padding(contentPadding))
        } else if (state.isLibraryEmpty) {
            EmptyScreen(
                stringRes = MR.strings.information_empty_library,
                modifier = Modifier.padding(contentPadding)
            )
        } else {
            NovelLibraryContent(
                state = state,
                screenModel = screenModel,
                contentPadding = contentPadding,
                onCategoryChange = screenModel::updateActiveCategoryIndex,
                onClickBook = { book ->
                    // Open novel reader
                    val bookDir = com.canopus.chimareader.data.BookStorage.getBookDirectory(context, book.id)
                    com.canopus.chimareader.ui.reader.NovelReaderActivity.launch(context, bookDir)
                }
            )
        }
    }

    when (val dialog = state.dialog) {
        is NovelLibraryScreenModel.Dialog.ChangeCategory -> {
            eu.kanade.presentation.category.components.ChangeCategoryDialog(
                initialSelection = state.categories.map { cat ->
                    tachiyomi.core.common.preference.CheckboxState.State.None(
                        tachiyomi.domain.category.model.Category(
                            id = cat.id.hashCode().toLong(),
                            name = cat.name,
                            order = cat.order.toLong(),
                            flags = cat.flags,
                            hidden = false
                        )
                    )
                }.toImmutableList(),
                onDismissRequest = screenModel::closeDialog,
                onEditCategories = { /* TODO */ },
                onConfirm = { included: List<Long>, _ ->
                    val selectedCategory = state.categories.find { it.id.hashCode().toLong() == included.firstOrNull() }
                    if (selectedCategory != null) {
                        screenModel.moveSelectedToCategory(selectedCategory.id)
                    } else {
                        screenModel.closeDialog()
                    }
                },
            )
        }
        is NovelLibraryScreenModel.Dialog.DeleteConfirm -> {
            AlertDialog(
                onDismissRequest = screenModel::closeDialog,
                title = { Text(stringResource(MR.strings.action_delete)) },
                text = { Text(stringResource(MR.strings.action_delete)) },
                confirmButton = {
                    TextButton(onClick = {
                        screenModel.deleteSelected()
                        screenModel.closeDialog()
                    }) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = screenModel::closeDialog) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                }
            )
        }
        is NovelLibraryScreenModel.Dialog.SortFilter -> {
            AlertDialog(
                onDismissRequest = screenModel::closeDialog,
                title = { Text(stringResource(MR.strings.action_sort)) },
                text = {
                    Column {
                        NovelLibraryScreenModel.SortMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { screenModel.setSort(mode, state.sortDescending) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.sortMode == mode,
                                    onClick = { screenModel.setSort(mode, state.sortDescending) }
                                )
                                Text(
                                    text = when (mode) {
                                        NovelLibraryScreenModel.SortMode.Alphabetical -> stringResource(MR.strings.action_sort_alpha)
                                        NovelLibraryScreenModel.SortMode.DateAdded -> stringResource(MR.strings.action_sort_date_added)
                                        NovelLibraryScreenModel.SortMode.LastRead -> stringResource(MR.strings.action_sort_last_read)
                                    },
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        androidx.compose.material3.Divider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { screenModel.setSort(state.sortMode, !state.sortDescending) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = state.sortDescending,
                                onCheckedChange = { screenModel.setSort(state.sortMode, it) }
                            )
                            Text(
                                text = "Descending",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = screenModel::closeDialog) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                }
            )
        }
        null -> {}
    }
}


@Composable
fun NovelLibraryContent(
    state: NovelLibraryScreenModel.State,
    screenModel: NovelLibraryScreenModel,
    contentPadding: PaddingValues,
    onCategoryChange: (Int) -> Unit,
    onClickBook: (BookMetadata) -> Unit,
) {
    val pagerState = rememberPagerState(state.activeCategoryIndex) { state.categories.size }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
        if (state.categories.size >= 1) {
            LibraryTabs(
                categories = state.categories.map { tachiyomi.domain.category.model.Category(id = 0, name = it.name, order = it.order.toLong(), flags = it.flags, hidden = false) },
                pagerState = pagerState,
                getItemCountForCategory = { cat -> state.categories.find { it.name == cat.name }?.let { state.getItemCountForCategory(it) } },
                onTabItemClick = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            val category = state.categories[page]
            val books = state.getBooksForCategory(category)

            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                )
            ) {
                items(books) { book ->
                    eu.kanade.presentation.library.components.MangaCompactGridItem(
                        isSelected = state.selection.contains(book.id),
                        title = book.title,
                        coverData = tachiyomi.domain.manga.model.MangaCover(
                            mangaId = book.id.hashCode().toLong(), // Mock ID
                            sourceId = -1L,
                            isMangaFavorite = true,
                            ogUrl = book.cover,
                            lastModified = 0L,
                        ),
                        onLongClick = { screenModel.toggleSelection(book.id) },
                        onClick = {
                            if (state.selectionMode) {
                                screenModel.toggleSelection(book.id)
                            } else {
                                onClickBook(book)
                            }
                        }
                    )
                }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onCategoryChange(pagerState.currentPage)

    }
}
