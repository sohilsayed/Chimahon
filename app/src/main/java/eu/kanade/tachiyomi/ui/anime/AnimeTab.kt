package eu.kanade.tachiyomi.ui.anime

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object AnimeTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 6u,
                title = stringResource(MR.strings.label_anime),
                icon = rememberVectorPainter(Icons.Outlined.PlayCircle),
            )
        }

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { AnimeListScreenModel() }
        AnimeListScreen(screenModel)
    }
}
