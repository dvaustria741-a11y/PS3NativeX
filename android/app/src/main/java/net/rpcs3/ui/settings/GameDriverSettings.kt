package net.rpcs3.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcs3.R
import net.rpcs3.RPCS3
import net.rpcs3.dialogs.AlertDialogQueue
import net.rpcs3.ui.components.SettingDropdown
import net.rpcs3.utils.GpuDriverHelper

const val DriverCategory = "GPU Driver"

private const val DriverPathKey = "Video@@Vulkan@@Custom Driver@@Path"
private const val DriverDataDirKey = "Video@@Vulkan@@Custom Driver@@Internal Data Directory"

@Composable
fun GameDriverSettings(titleId: String) {
    val context = LocalContext.current
    val drivers = remember { GpuDriverHelper.getInstalledDrivers(context).entries.toList() }
    // Display names in the same order as `drivers`. Default (the system
    // driver) is always entry 0 -- GpuDriverHelper.getInstalledDrivers
    // inserts "/system/vendor" first, before scanning installed drivers.
    val entryNames = remember(drivers) { drivers.map { (_, metadata) -> metadata.name } }
    var selectedPath by remember(titleId) { mutableStateOf<String?>(null) }

    LaunchedEffect(titleId) {
        selectedPath = withContext(Dispatchers.IO) {
            runCatching {
                RPCS3.instance.settingsGet(DriverPathKey, titleId).trim().trim('"')
            }.getOrDefault("")
        }
    }

    // selectedPath starts null (still loading) and, once loaded, might not
    // match any installed entry (e.g. a previously picked driver got
    // uninstalled) -- coerceAtLeast(0) means both of those fall back to
    // showing Default selected instead of an ambiguous/blank dropdown,
    // same "default is the default selected" behavior as before, without
    // waiting on the settingsGet round-trip to paint the first frame.
    val selectedIndex = drivers.indexOfFirst { (file, metadata) ->
        val path = if (metadata.name == "Default") "" else file.path
        path == selectedPath
    }.coerceAtLeast(0)

    SettingDropdown(
        label = stringResource(R.string.settings_driver_per_game),
        entries = entryNames,
        selectedIndex = selectedIndex,
        onSelected = { index ->
            val (file, metadata) = drivers.getOrNull(index) ?: return@SettingDropdown
            val path = if (metadata.name == "Default") "" else file.path
            selectedPath = path
            // Deliberately NOT rememberCoroutineScope(): that scope is
            // cancelled the instant this composable leaves composition,
            // which is exactly what Save's navigateUp() does right
            // after kicking off its own flush. Selecting a driver and
            // immediately tapping Save could cancel this write
            // mid-flight before it ever reached settingsSet/flush, so
            // the pick silently never persisted no matter how quickly
            // Save flushed afterwards. settingsWriter is a top-level,
            // single-threaded scope (same one every other setting in
            // this screen, including Save's own flush, already uses)
            // that outlives navigation and strictly orders every write.
            settingsWriter.launch {
                // Neither settingsSet's return value nor a post-flush
                // save failure were being checked here, unlike every
                // other setting on this screen (see SettingItem.kt's
                // commit()). A rejected or unwritten value would
                // silently do nothing, and the next time this screen
                // loads, LaunchedEffect reads back whatever is actually
                // on disk -- the old selection -- which looks exactly
                // like "the pick didn't stick" with zero indication why.
                val pathOk = RPCS3.instance.settingsSet(DriverPathKey, "\"" + path + "\"", titleId)
                val dirOk = RPCS3.instance.settingsSet(
                    DriverDataDirKey, "\"" + context.filesDir + "\"", titleId
                )

                if (!pathOk || !dirOk) {
                    withContext(Dispatchers.Main) {
                        AlertDialogQueue.showDialog(
                            context.getString(R.string.settings_error_title),
                            context.getString(R.string.settings_error_assign, metadata.name)
                        )
                    }
                    return@launch
                }

                RPCS3.instance.settingsFlush()

                RPCS3.instance.takeSettingsSaveFailure()?.let { target ->
                    withContext(Dispatchers.Main) {
                        AlertDialogQueue.showDialog(
                            context.getString(R.string.settings_not_saved_title),
                            context.getString(R.string.settings_not_saved_message, target)
                        )
                    }
                }
            }
        }
    )

    Spacer(Modifier.height(18.dp))

    DriverFlagsSection(titleId)

    Spacer(Modifier.height(14.dp))
}
