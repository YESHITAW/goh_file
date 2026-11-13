package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Voicemail
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.answeringmachine.ui.AnsweringViewModel
import com.example.answeringmachine.ui.DeckScreen
import com.example.answeringmachine.ui.GreetingStudioScreen
import com.example.answeringmachine.ui.SetupScreen
import com.example.ui.theme.CallDeckTheme

private enum class CallDeckTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    DECK(R.string.nav_deck, Icons.Filled.Voicemail, Icons.Outlined.Voicemail, "nav_deck"),
    GREETING(R.string.nav_greeting, Icons.Filled.Mic, Icons.Outlined.Mic, "nav_greeting"),
    SETUP(R.string.nav_setup, Icons.Filled.Tune, Icons.Outlined.Tune, "nav_setup")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CallDeckTheme {
                CallDeckRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDeckRoot(
    viewModel: AnsweringViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var currentTab by rememberSaveable { mutableStateOf(CallDeckTab.DECK) }

    // Return to main Deck tab on back press from secondary tabs
    BackHandler(enabled = currentTab != CallDeckTab.DECK) {
        currentTab = CallDeckTab.DECK
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshSystemStatus()
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemStatus()
    }

    val importMp3Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCustomGreeting(uri)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.statusBannerMessage) {
        val message = uiState.statusBannerMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearBannerMessage()
        }
    }

    val requestDialerRole: () -> Unit = {
        val intent = viewModel.createDefaultDialerIntent()
        if (intent != null) {
            runCatching { roleLauncher.launch(intent) }
        }
    }

    val requestAllPermissions: () -> Unit = {
        permissionsLauncher.launch(viewModel.getRequiredPermissions())
    }

    val openAppSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        runCatching { context.startActivity(intent) }
    }

    val unheardCount = uiState.voicemails.count { !it.isHeard }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = when (currentTab) {
                                CallDeckTab.DECK -> stringResource(R.string.app_name)
                                CallDeckTab.GREETING -> "Greeting Studio"
                                CallDeckTab.SETUP -> "System Setup"
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        CallDeckTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                modifier = Modifier.testTag(tab.tag),
                                icon = {
                                    TabIcon(
                                        tab = tab,
                                        selected = selected,
                                        unheardCount = unheardCount,
                                        setupNeeded = !uiState.readiness.isFullyReady
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        CallDeckTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                modifier = Modifier.testTag(tab.tag),
                                icon = {
                                    TabIcon(
                                        tab = tab,
                                        selected = selected,
                                        unheardCount = unheardCount,
                                        setupNeeded = !uiState.readiness.isFullyReady
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }

                when (currentTab) {
                    CallDeckTab.DECK -> DeckScreen(
                        uiState = uiState,
                        onToggleAnswering = viewModel::setAnsweringEnabled,
                        onRequestDialerRole = requestDialerRole,
                        onRequestPermissions = requestAllPermissions,
                        onPlayPauseVoicemail = viewModel::toggleVoicemailPlayback,
                        onSeekVoicemail = viewModel::seekVoicemail,
                        onShareVoicemail = { item ->
                            val shareIntent = viewModel.buildShareIntent(item)
                            if (shareIntent != null) {
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Share Voicemail")
                                )
                            }
                        },
                        onDeleteVoicemail = viewModel::deleteVoicemail,
                        modifier = Modifier.weight(1f)
                    )

                    CallDeckTab.GREETING -> GreetingStudioScreen(
                        uiState = uiState,
                        onSelectCustomGreeting = viewModel::setUseCustomGreeting,
                        onTogglePreview = viewModel::toggleGreetingPreview,
                        onPlayTestBeep = viewModel::playTestBeep,
                        onStartRecording = viewModel::startRecordingCustomGreeting,
                        onStopRecording = viewModel::stopRecordingCustomGreeting,
                        onImportMp3Greeting = {
                            importMp3Launcher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
                        },
                        onDeleteCustomGreeting = viewModel::deleteCustomGreeting,
                        onSelectRingDelay = viewModel::setRingDelaySeconds,
                        onSelectMaxDuration = viewModel::setMaxRecordingSeconds,
                        onRequestMicPermission = requestAllPermissions,
                        modifier = Modifier.weight(1f)
                    )

                    CallDeckTab.SETUP -> SetupScreen(
                        readiness = uiState.readiness,
                        onRequestDialerRole = requestDialerRole,
                        onRequestPermissions = requestAllPermissions,
                        onOpenAppSettings = openAppSettings,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabIcon(
    tab: CallDeckTab,
    selected: Boolean,
    unheardCount: Int,
    setupNeeded: Boolean
) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    when {
        tab == CallDeckTab.DECK && unheardCount > 0 -> {
            BadgedBox(
                badge = {
                    Badge { Text(unheardCount.coerceAtMost(99).toString()) }
                }
            ) {
                Icon(imageVector = icon, contentDescription = stringResource(tab.labelRes))
            }
        }
        tab == CallDeckTab.SETUP && setupNeeded -> {
            BadgedBox(
                badge = {
                    Badge(containerColor = MaterialTheme.colorScheme.primary)
                }
            ) {
                Icon(imageVector = icon, contentDescription = stringResource(tab.labelRes))
            }
        }
        else -> {
            Icon(imageVector = icon, contentDescription = stringResource(tab.labelRes))
        }
    }
}
