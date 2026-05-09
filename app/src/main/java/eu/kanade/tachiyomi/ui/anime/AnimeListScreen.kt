package eu.kanade.tachiyomi.ui.anime

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.formatTime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun AnimeListScreen(
    screenModel: AnimeListScreenModel,
) {
    val state by screenModel.state.collectAsState()
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            context.startActivity(PlayerActivity.newIntent(context, uri.toString()))
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(MR.strings.action_open_video))
            }
        },
    ) { contentPadding ->
        when (val s = state) {
            is AnimeListScreenModel.State.Loading -> {
                LoadingScreen(modifier = Modifier.padding(contentPadding))
            }
            is AnimeListScreenModel.State.Success -> {
                if (s.items.isEmpty()) {
                    EmptyScreen(
                        message = stringResource(MR.strings.anime_empty_message),
                        modifier = Modifier.padding(contentPadding),
                    )
                } else {
                    ScrollbarLazyColumn(
                        contentPadding = contentPadding,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = s.items,
                            key = { it.anime.id },
                        ) { item ->
                            AnimeListItem(
                                title = item.anime.title,
                                episodeName = item.lastEpisode?.name,
                                progress = item.lastEpisode?.let {
                                    if (it.totalSeconds > 0) {
                                        "${formatTime(it.lastSecondSeen)} / ${formatTime(it.totalSeconds)}"
                                    } else if (it.lastSecondSeen > 0) {
                                        formatTime(it.lastSecondSeen)
                                    } else {
                                        null
                                    }
                                },
                                onClick = {
                                    val episodeId = item.lastEpisode?.id ?: return@AnimeListItem
                                    context.startActivity(
                                        PlayerActivity.newIntent(context, item.anime.id, episodeId),
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        OpenVideoDialog(
            onDismiss = { showDialog = false },
            onOpenUrl = { url ->
                showDialog = false
                context.startActivity(PlayerActivity.newIntent(context, url))
            },
            onPickFile = {
                showDialog = false
                filePickerLauncher.launch(arrayOf("video/*"))
            },
        )
    }
}

@Composable
private fun AnimeListItem(
    title: String,
    episodeName: String?,
    progress: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episodeName != null) {
                Text(
                    text = episodeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (progress != null) {
            Text(
                text = progress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
