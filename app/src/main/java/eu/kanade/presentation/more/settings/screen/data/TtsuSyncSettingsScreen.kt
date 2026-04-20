package eu.kanade.presentation.more.settings.screen.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.canopus.chimareader.data.StatisticsSyncMode
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.sync.ttsu.TtsuOAuthService
import eu.kanade.tachiyomi.data.sync.ttsu.TtsuSyncManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TtsuSyncSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val syncPreferences = remember { Injekt.get<SyncPreferences>() }

        val syncEnabled by syncPreferences.ttuSyncEnabled().collectAsState()
        val syncProgress by syncPreferences.ttuSyncProgress().collectAsState()
        val syncStatistics by syncPreferences.ttuSyncStatistics().collectAsState()
        val statisticsMode by syncPreferences.ttuStatisticsSyncMode().collectAsState()
        val syncOnOpen by syncPreferences.ttuSyncOnOpen().collectAsState()
        val syncOnClose by syncPreferences.ttuSyncOnClose().collectAsState()
        val periodicInterval by syncPreferences.ttuPeriodicSyncInterval().collectAsState()
        val clientId by syncPreferences.ttuClientId().collectAsState()
        val clientSecret by syncPreferences.ttuClientSecret().collectAsState()
        val accessToken by syncPreferences.ttuAccessToken().collectAsState()
        val refreshToken by syncPreferences.ttuRefreshToken().collectAsState()

        val isConnected = accessToken.isNotBlank() && refreshToken.isNotBlank()
        val statusText = when {
            clientId.isBlank() -> "Client ID is missing"
            !isConnected -> "Ready to sign in"
            syncEnabled -> "Connected and syncing"
            else -> "Connected"
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "TTSU / Hoshi Sync",
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            PreferenceScreen(
                contentPadding = contentPadding,
                items = listOf(
                    Preference.PreferenceGroup(
                        title = "Credentials",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.InfoPreference(
                                "Use your own Google OAuth client for TTU/Hoshi-compatible sync.\n" +
                                    "Redirect URI: ${TtsuOAuthService.REDIRECT_URI}\n" +
                                    "Client Secret is optional and mainly needed for Web clients.\n" +
                                    "If you use a custom client, make sure it matches this redirect URI.",
                            ),
                            Preference.PreferenceItem.EditTextPreference(
                                preference = syncPreferences.ttuClientId(),
                                title = "Client ID",
                                subtitle = if (clientId.isBlank()) "Required for Google sign-in" else "Configured",
                                onValueChanged = { newValue ->
                                    syncPreferences.ttuClientId().set(newValue.trim())
                                    true
                                },
                            ),
                            Preference.PreferenceItem.EditTextPreference(
                                preference = syncPreferences.ttuClientSecret(),
                                title = "Client Secret",
                                subtitle = if (clientSecret.isBlank()) "Optional" else "Configured",
                                onValueChanged = { newValue ->
                                    syncPreferences.ttuClientSecret().set(newValue.trim())
                                    true
                                },
                            ),
                            Preference.PreferenceItem.TextPreference(
                                title = if (isConnected) "Disconnect Google Drive" else "Connect Google Drive",
                                subtitle = statusText,
                                onClick = {
                                    if (clientId.isBlank()) {
                                        context.toast("Enter a Client ID first")
                                        return@TextPreference
                                    }

                                    val service = TtsuOAuthService(context)
                                    if (isConnected) {
                                        service.signOut()
                                        context.toast("TTSU / Hoshi sync disconnected")
                                    } else {
                                        context.startActivity(service.getSignInIntent())
                                    }
                                },
                            ),
                            Preference.PreferenceItem.TextPreference(
                                title = "Reset saved credentials",
                                subtitle = "Clear client id, secret and OAuth tokens",
                                enabled = clientId.isNotBlank() || clientSecret.isNotBlank() || isConnected,
                                onClick = {
                                    scope.launch {
                                        syncPreferences.ttuSyncEnabled().set(false)
                                        syncPreferences.ttuClientId().set("")
                                        syncPreferences.ttuClientSecret().set("")
                                        syncPreferences.ttuAccessToken().set("")
                                        syncPreferences.ttuRefreshToken().set("")
                                        context.toast("TTSU / Hoshi sync credentials cleared")
                                    }
                                },
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Behavior",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.SwitchPreference(
                                preference = syncPreferences.ttuSyncEnabled(),
                                title = "Enable TTSU / Hoshi sync",
                                subtitle = "Keep novel progress and statistics in Google Drive",
                                enabled = isConnected,
                            ),
                            Preference.PreferenceItem.SwitchPreference(
                                preference = syncPreferences.ttuSyncProgress(),
                                title = "Sync reading progress",
                                enabled = syncEnabled,
                            ),
                            Preference.PreferenceItem.SwitchPreference(
                                preference = syncPreferences.ttuSyncStatistics(),
                                title = "Sync statistics",
                                enabled = syncEnabled,
                            ),
                            Preference.PreferenceItem.ListPreference(
                                preference = syncPreferences.ttuStatisticsSyncMode(),
                                entries = persistentMapOf(
                                    StatisticsSyncMode.MERGE.value to "Merge local and cloud days",
                                    StatisticsSyncMode.REPLACE.value to "Replace with latest side",
                                ),
                                title = "Statistics conflict handling",
                                enabled = syncEnabled && syncStatistics,
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Triggers",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.SwitchPreference(
                                preference = syncPreferences.ttuSyncOnOpen(),
                                title = "Pull from cloud on reader open",
                                subtitle = "Best for continuing on another device",
                                enabled = syncEnabled,
                            ),
                            Preference.PreferenceItem.SwitchPreference(
                                preference = syncPreferences.ttuSyncOnClose(),
                                title = "Push to cloud on reader close",
                                subtitle = "Saves progress when leaving the reader",
                                enabled = syncEnabled,
                            ),
                            Preference.PreferenceItem.ListPreference(
                                preference = syncPreferences.ttuPeriodicSyncInterval(),
                                entries = persistentMapOf(
                                    0 to "Off",
                                    5 to "Every 5 minutes",
                                    10 to "Every 10 minutes",
                                    15 to "Every 15 minutes",
                                    30 to "Every 30 minutes",
                                    60 to "Every hour",
                                ),
                                title = "Periodic sync while reading",
                                enabled = syncEnabled,
                            ),
                        ),
                    ),
                    Preference.PreferenceGroup(
                        title = "Manual actions",
                        preferenceItems = persistentListOf(
                            Preference.PreferenceItem.TextPreference(
                                title = "Pull all novels from cloud now",
                                subtitle = "Refresh progress and statistics for your local novel library",
                                enabled = syncEnabled,
                                onClick = {
                                    scope.launch {
                                        val updated = TtsuSyncManager(context).pullAllFromCloud()
                                        context.toast(
                                            if (updated) {
                                                "Novel data pulled from cloud"
                                            } else {
                                                "No newer cloud novel data found"
                                            },
                                        )
                                    }
                                },
                            ),
                        ),
                    ),
                ),
            )
        }
    }
}
