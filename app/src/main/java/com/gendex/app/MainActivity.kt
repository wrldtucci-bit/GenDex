package com.gendex.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var controller: GenDexController
    private var uiState by mutableStateOf(GenDexUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = GenDexController(this)
        refresh()
        handleIntent(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    GenDexScreen(
                        state = uiState,
                        onSet800 = { runAction { controller.setSmallestWidthDp(800) } },
                        onRestore = { runAction { controller.restorePrevious() } },
                        onCustom = { value -> runAction { controller.setSmallestWidthDp(value) } },
                        onDeveloper = { controller.openDeveloperSettings(); refresh() },
                        onRotation = { controller.openRotationSettings() },
                        onGrantShizuku = { controller.requestShizukuPermission() },
                        onPin800 = { controller.pinSet800Shortcut() },
                        onRefresh = { refresh() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SET_800 -> runAction { controller.setSmallestWidthDp(800) }
            ACTION_RESTORE -> runAction { controller.restorePrevious() }
            ACTION_DEVELOPER_SETTINGS -> controller.openDeveloperSettings()
        }
    }

    private fun runAction(block: () -> ActionResult) {
        uiState = uiState.copy(isBusy = true)
        uiState = try {
            val result = block()
            uiState.copy(isBusy = false, message = result.message, isSuccess = result.success)
        } catch (t: Throwable) {
            uiState.copy(isBusy = false, message = "Gen Dex could not complete the action: ${t.message ?: t::class.simpleName}", isSuccess = false)
        }
        refresh(quiet = true)
    }

    private fun refresh(quiet: Boolean = false) {
        val info = controller.readDeviceInfo()
        uiState = uiState.copy(
            currentDp = info.currentSmallestWidthDp,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            density = "${info.densityDpi} dpi",
            resolution = "${info.widthPx} × ${info.heightPx} px",
            privileged = controller.hasShellAccess(),
            status = when (info.currentSmallestWidthDp) {
                800 -> "DESKTOP MODE ACTIVE ✓"
                null -> "CURRENT VALUE UNAVAILABLE"
                else -> "DESKTOP MODE INACTIVE"
            },
            message = if (quiet) uiState.message else "Ready. Gen Dex will never fake a successful system change."
        )
    }

    companion object {
        const val ACTION_SET_800 = "com.gendex.app.action.SET_800"
        const val ACTION_RESTORE = "com.gendex.app.action.RESTORE"
        const val ACTION_DEVELOPER_SETTINGS = "com.gendex.app.action.DEVELOPER_SETTINGS"
    }
}

data class GenDexUiState(
    val currentDp: Int? = null,
    val androidVersion: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val density: String = "",
    val resolution: String = "",
    val privileged: Boolean = false,
    val status: String = "",
    val message: String = "",
    val isSuccess: Boolean = false,
    val isBusy: Boolean = false
)

data class DeviceInfo(
    val currentSmallestWidthDp: Int?,
    val densityDpi: Int,
    val widthPx: Int,
    val heightPx: Int,
    val wmDensity: Int?,
    val wmDensityDefault: Int?
)

data class ActionResult(val success: Boolean, val message: String)

class GenDexController(private val context: Context) {
    private val prefs = context.getSharedPreferences("gendex", Context.MODE_PRIVATE)

    fun readDeviceInfo(): DeviceInfo {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        val wm = runShellBestEffort("wm", "density")
        val currentWmDensity = Regex("Override density: (\\d+)").find(wm)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("Physical density: (\\d+)").find(wm)?.groupValues?.get(1)?.toIntOrNull()
        val defaultWmDensity = Regex("\\nPhysical density: (\\d+)").find(wm)?.groupValues?.get(1)?.toIntOrNull()
        return DeviceInfo(
            currentSmallestWidthDp = config.smallestScreenWidthDp.takeIf { it > 0 },
            densityDpi = metrics.densityDpi,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            wmDensity = currentWmDensity,
            wmDensityDefault = defaultWmDensity
        )
    }

    fun hasShellAccess(): Boolean = runShellBestEffort("id").contains("uid=2000") || runShellBestEffort("id").contains("uid=0")

    fun setSmallestWidthDp(targetDp: Int): ActionResult {
        require(targetDp in 200..1200) { "Choose a Smallest width between 200 and 1200 dp." }
        val before = readDeviceInfo()
        if (!hasShellAccess()) {
            openDeveloperSettings()
            return ActionResult(false, "Android did not authorize shell access. Developer Options was opened so you can set $targetDp dp manually.")
        }
        val physical = getPhysicalSizePx()
        val minPx = minOf(physical.first, physical.second)
        if (minPx <= 0) return ActionResult(false, "Could not determine the physical display size. Developer Options was opened instead.")

        if (!prefs.contains(KEY_PREVIOUS_SAVED)) {
            prefs.edit().putBoolean(KEY_PREVIOUS_SAVED, true)
                .putBoolean(KEY_PREVIOUS_HAD_OVERRIDE, before.wmDensity != null && before.wmDensityDefault != null && before.wmDensity != before.wmDensityDefault)
                .putInt(KEY_PREVIOUS_DENSITY, before.wmDensity ?: before.densityDpi)
                .apply()
        }

        val targetDensity = (minPx * 160f / targetDp).roundToInt().coerceAtLeast(72)
        val result = runShell("wm", "density", targetDensity.toString())
        if (result.exitCode != 0) {
            openDeveloperSettings()
            return ActionResult(false, "Android rejected the display-density change. Developer Options was opened. ${result.stderr.ifBlank { result.stdout }}")
        }
        Thread.sleep(250)
        val after = readDeviceInfo()
        return if (after.currentSmallestWidthDp == targetDp) {
            ActionResult(true, "Smallest width: $targetDp dp ✓")
        } else {
            ActionResult(false, "Android applied a display-density override, but the reported Smallest width is ${after.currentSmallestWidthDp ?: "unavailable"} dp. No fake success was shown; open Developer Settings to fine-tune it.")
        }
    }

    fun restorePrevious(): ActionResult {
        if (!prefs.getBoolean(KEY_PREVIOUS_SAVED, false)) {
            openDeveloperSettings()
            return ActionResult(false, "No previous Gen Dex value is saved yet. Developer Options was opened.")
        }
        if (!hasShellAccess()) {
            openDeveloperSettings()
            return ActionResult(false, "Shell access is not authorized, so Gen Dex cannot restore automatically. Developer Options was opened.")
        }
        val hadOverride = prefs.getBoolean(KEY_PREVIOUS_HAD_OVERRIDE, false)
        val previousDensity = prefs.getInt(KEY_PREVIOUS_DENSITY, context.resources.displayMetrics.densityDpi)
        val result = if (hadOverride) runShell("wm", "density", previousDensity.toString()) else runShell("wm", "density", "reset")
        if (result.exitCode != 0) {
            openDeveloperSettings()
            return ActionResult(false, "Android rejected the restore. Developer Options was opened.")
        }
        Thread.sleep(250)
        val restored = readDeviceInfo()
        prefs.edit().clear().apply()
        return ActionResult(true, "Restored previous display setting${restored.currentSmallestWidthDp?.let { " (${it} dp)" } ?: ""} ✓")
    }

    fun openDeveloperSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return
            } catch (_: Throwable) { }
        }
    }

    fun openRotationSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return
            } catch (_: Throwable) { }
        }
    }

    fun pinSet800Shortcut(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            val shortcut = ShortcutInfo.Builder(context, "pinned_set_800")
                .setShortLabel("Set 800 DP")
                .setLongLabel("Gen Dex — Set 800 DP")
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut))
                .setIntent(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SET_800))
                .build()
            manager.requestPinShortcut(shortcut, null)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun requestShizukuPermission() {
        if (Build.VERSION.SDK_INT >= 23 && Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            }
        }
    }

    private fun getPhysicalSizePx(): Pair<Int, Int> {
        val out = runShellBestEffort("wm", "size")
        val match = Regex("Physical size: (\\d+)x(\\d+)").find(out)
        return if (match != null) match.groupValues[1].toInt() to match.groupValues[2].toInt()
        else context.resources.displayMetrics.widthPixels to context.resources.displayMetrics.heightPixels
    }

    private data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runShell(vararg command: String): ShellResult {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ShellResult(-1, "", "Shizuku not authorized")
            }
            val process = Shizuku.newProcess(command, null, null)
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()
            ShellResult(exit, stdout, stderr)
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message ?: "Unknown shell error")
        }
    }

    private fun runShellBestEffort(vararg command: String): String {
        val result = runShell(*command)
        return result.stdout + "\\n" + result.stderr
    }

    companion object {
        private const val KEY_PREVIOUS_SAVED = "previous_saved"
        private const val KEY_PREVIOUS_HAD_OVERRIDE = "previous_had_override"
        private const val KEY_PREVIOUS_DENSITY = "previous_density"
    }
}

@androidx.compose.runtime.Composable
fun GenDexScreen(
    state: GenDexUiState,
    onSet800: () -> Unit,
    onRestore: () -> Unit,
    onCustom: (Int) -> Unit,
    onDeveloper: () -> Unit,
    onRotation: () -> Unit,
    onGrantShizuku: () -> Unit,
    onPin800: () -> Unit,
    onRefresh: () -> Unit
) {
    var custom by androidx.compose.runtime.remember { mutableStateOf(700) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GEN DEX", style = MaterialTheme.typography.headlineMedium)
        Text("Smallest Width", style = MaterialTheme.typography.titleMedium)
        Text(state.currentDp?.let { "Current value: $it dp" } ?: "Current value: unavailable")
        Text(state.status, style = MaterialTheme.typography.titleMedium)

        Button(onClick = onSet800, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text(if (state.isBusy) "WORKING…" else "800 DP — DESKTOP MODE")
        }
        OutlinedButton(onClick = onRestore, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("DEFAULT — RESTORE")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = custom.toString(),
                onValueChange = { custom = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 },
                label = { Text("CUSTOM DP") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { if (custom in 200..1200) onCustom(custom) }, modifier = Modifier.height(56.dp)) { Text("SET") }
        }
        OutlinedButton(onClick = onDeveloper, modifier = Modifier.fillMaxWidth()) { Text("OPEN DEVELOPER SETTINGS") }
        OutlinedButton(onClick = onPin800, modifier = Modifier.fillMaxWidth()) { Text("ADD 800 DP TO HOME SCREEN") }
        OutlinedButton(onClick = onRotation, modifier = Modifier.fillMaxWidth()) { Text("OPEN ROTATION SETTINGS") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DEVICE", style = MaterialTheme.typography.titleSmall)
                Text(state.androidVersion)
                Text("Manufacturer: ${state.manufacturer}")
                Text("Model: ${state.model}")
                Text("Density: ${state.density}")
                Text("Resolution: ${state.resolution}")
                Text("Privileged access: ${if (state.privileged) "AUTHORIZED ✓" else "NOT AUTHORIZED"}")
                if (!state.privileged) {
                    OutlinedButton(onClick = onGrantShizuku) { Text("AUTHORIZE SHIZUKU") }
                }
            }
        }

        Text(state.message)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("REFRESH") }
        Text("Safety: Gen Dex does not use VPN, accessibility abuse, exploits, or hidden APIs to fake a settings change.", style = MaterialTheme.typography.bodySmall)
    }
}
