@file:OptIn(ExperimentalMaterial3Api::class)

package com.uriya.medreminder

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(this)
        Notifier.createChannels(this)
        Scheduler.reschedule(this)
        setContent {
            MedTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App()
                    }
                }
            }
        }
    }
}

@Composable
fun MedTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF8AB4FF),
            onPrimary = Color(0xFF00205F),
            primaryContainer = Color(0xFF1E3F8A),
            secondary = Color(0xFF5DD3B6),
            background = Color(0xFF111318),
            surface = Color(0xFF1A1D24),
            surfaceVariant = Color(0xFF262A33)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2F6FED),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE6FF),
            onPrimaryContainer = Color(0xFF0B2A6B),
            secondary = Color(0xFF12A594),
            background = Color(0xFFF4F6FB),
            surface = Color.White,
            surfaceVariant = Color(0xFFE9EDF5)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class TabItem(val label: String, val title: String, val icon: ImageVector)

@Composable
fun App() {
    val ctx = LocalContext.current
    val meds by Repo.meds.collectAsState()
    val logs by Repo.logs.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Medication?>(null) }

    // refresh statuses (due / missed) while the app is open
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = LocalDateTime.now()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.hasPermission(ctx)) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val tabs = listOf(
        TabItem("היום", "התרופות של היום", Icons.Filled.Home),
        TabItem("תרופות", "התרופות שלי", Icons.AutoMirrored.Filled.List),
        TabItem("דוח", "דוח היענות", Icons.Filled.DateRange),
        TabItem("הגדרות", "הגדרות", Icons.Filled.Settings)
    )

    val current = editing
    if (current != null) {
        EditMedScreen(
            initial = current,
            isNew = meds.none { it.id == current.id },
            onSave = {
                Repo.upsert(ctx, it)
                editing = null
            },
            onDelete = {
                Repo.delete(ctx, current.id)
                editing = null
            },
            onBack = { editing = null }
        )
    } else {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(title = { Text(tabs[tab].title, fontWeight = FontWeight.Bold) })
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0 || tab == 1) {
                    ExtendedFloatingActionButton(
                        onClick = { editing = Medication(id = System.currentTimeMillis(), name = "") },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("תרופה חדשה") }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> TodayScreen(now, meds, logs)
                    1 -> MedsScreen(meds) { editing = it }
                    2 -> ReportScreen(now, meds, logs)
                    else -> SettingsScreen()
                }
            }
        }
    }
}
