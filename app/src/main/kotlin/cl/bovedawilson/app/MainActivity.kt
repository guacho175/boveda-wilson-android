package cl.bovedawilson.app

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cl.bovedawilson.app.ui.backup.PendingBackupSafStore
import cl.bovedawilson.app.ui.screens.BackupScreen
import cl.bovedawilson.app.ui.screens.CloudAccessScreen
import cl.bovedawilson.app.ui.screens.CreateVaultScreen
import cl.bovedawilson.app.ui.screens.ItemEditorScreen
import cl.bovedawilson.app.ui.screens.ItemsListScreen
import cl.bovedawilson.app.ui.screens.ROUTE_BACKUP
import cl.bovedawilson.app.ui.screens.ROUTE_CLOUD_ACCESS
import cl.bovedawilson.app.ui.screens.ROUTE_CREATE_VAULT
import cl.bovedawilson.app.ui.screens.ROUTE_ITEMS
import cl.bovedawilson.app.ui.screens.ROUTE_ITEM_PATTERN
import cl.bovedawilson.app.ui.screens.ROUTE_SETTINGS
import cl.bovedawilson.app.ui.screens.ROUTE_UNLOCK
import cl.bovedawilson.app.ui.screens.SettingsScreen
import cl.bovedawilson.app.ui.screens.UnlockScreen
import cl.bovedawilson.app.ui.theme.BovedaWilsonTheme
import cl.bovedawilson.app.ui.util.clearSensitiveClipboard
import cl.bovedawilson.data.sync.session.AutoLockController
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * `FragmentActivity`, no `ComponentActivity`: `androidx.biometric.BiometricPrompt`
 * (1.1.0) exige un `FragmentActivity` o `Fragment` como huésped; internamente usa un
 * Fragment sin interfaz para sobrevivir a cambios de configuración durante el diálogo del
 * sistema. `setContent` de Compose funciona igual sobre `FragmentActivity`.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var autoLockController: AutoLockController

    @Inject
    lateinit var vaultSession: VaultSession

    @Inject
    lateinit var pendingBackupSafStore: PendingBackupSafStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No impide todas las capturas (una cámara externa sigue funcionando), pero saca el
        // contenido de la lista de recientes y de las capturas del sistema
        // (`SECURITY.md` §5).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            BovedaWilsonTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val processDeathMode = when {
                        BuildConfig.DEBUG && intent.getBooleanExtra(PROCESS_DEATH_PREPARE_EXTRA, false) ->
                            ProcessDeathMode.Prepare
                        BuildConfig.DEBUG && intent.getBooleanExtra(PROCESS_DEATH_VERIFY_EXTRA, false) ->
                            ProcessDeathMode.Verify
                        else -> ProcessDeathMode.None
                    }
                    Box {
                        BovedaWilsonNavigation(vaultSession, pendingBackupSafStore, processDeathMode)
                        if (processDeathMode == ProcessDeathMode.Prepare) Text(PROCESS_DEATH_CANARY)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        autoLockController.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // El bloqueo real (inmediato o no, según la preferencia guardada) lo decide
        // AutoLockController, que también gobierna el temporizador de inactividad
        // (`docs/architecture.md` §5). Ya no se bloquea aquí incondicionalmente.
        autoLockController.onAppBackgrounded()
    }

    /** Cualquier toque reinicia el temporizador de inactividad. Se intercepta aquí, no en
     * Compose, para no interferir con la resolución de gestos de cada pantalla. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            autoLockController.onUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val PROCESS_DEATH_PREPARE_EXTRA = "processDeathPrepare"
        const val PROCESS_DEATH_VERIFY_EXTRA = "processDeathVerify"
        const val PROCESS_DEATH_CANARY = "BW-PROCESS-DEATH-CANARY-FIXTURE"
    }
}

enum class ProcessDeathMode { None, Prepare, Verify }

@Composable
fun BovedaWilsonNavigation(
    vaultSession: VaultSession,
    pendingBackupSafStore: PendingBackupSafStore,
    processDeathMode: ProcessDeathMode = ProcessDeathMode.None,
) {
    val navController = rememberNavController()
    val sessionState by vaultSession.state.collectAsState()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val pendingBackupSaf by pendingBackupSafStore.pending.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(sessionState, currentRoute) {
        if (sessionState is SessionState.Locked) {
            clearSensitiveClipboard(context)
            if (currentRoute.isGloballySensitiveRoute()) {
                navController.navigate(ROUTE_UNLOCK) {
                    popUpTo(ROUTE_CLOUD_ACCESS) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(sessionState, pendingBackupSaf) {
        if (sessionState is SessionState.Unlocked && pendingBackupSaf != null && currentRoute != ROUTE_BACKUP) {
            navController.navigate(ROUTE_BACKUP) { launchSingleTop = true }
        }
    }

    val startDestination = if (processDeathMode == ProcessDeathMode.None) ROUTE_CLOUD_ACCESS else ROUTE_ITEMS
    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_CLOUD_ACCESS) { CloudAccessScreen(navController = navController) }
        composable(ROUTE_CREATE_VAULT) { CreateVaultScreen(navController = navController) }
        composable(ROUTE_UNLOCK) { UnlockScreen(navController = navController) }
        composable(ROUTE_ITEMS) { ItemsListScreen(navController = navController) }
        composable(ROUTE_SETTINGS) { SettingsScreen(navController = navController) }
        composable(ROUTE_BACKUP) { BackupScreen(navController = navController) }
        composable(
            route = ROUTE_ITEM_PATTERN,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { backStackEntry ->
            ItemEditorScreen(
                itemId = backStackEntry.arguments?.getString("itemId"),
                navController = navController
            )
        }
    }
}

private fun String?.isGloballySensitiveRoute(): Boolean =
    this == ROUTE_ITEMS || this == ROUTE_SETTINGS || this == ROUTE_ITEM_PATTERN
