package net.rpcs3.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcs3.R
import net.rpcs3.RPCS3
import net.rpcs3.dialogs.AlertDialogQueue
import net.rpcs3.ui.components.PaneSectionTitle
import net.rpcs3.ui.drivers.DriverItem
import net.rpcs3.utils.GpuDriverHelper

const val DriverCategory = "GPU Driver"

private const val DriverPathKey = "Video@@Vulkan@@Custom Driver@@Path"
private const val DriverDataDirKey = "Video@@Vulkan@@Custom Driver@@Internal Data Directory"

@Composable
fun GameDriverSettings(titleId: String) {
    val context = LocalContext.current
    val drivers = remember { GpuDriverHelper.getInstalledDrivers(context) }
    var selectedPath by remember(titleId) { mutableStateOf<String?>(null) }

    LaunchedEffect(titleId) {
        selectedPath = withContext(Dispatchers.IO) {
            runCatching {
                RPCS3.instance.settingsGet(DriverPathKey, titleId).trim().trim('"')
            }.getOrDefault("")
        }
    }

    PaneSectionTitle(stringResource(R.string.settings_driver_per_game))

    drivers.entries.toList().forEach { (file, metadata) ->
        val path = if (metadata.name == "Default") "" else file.path

        DriverItem(
            file = file,
            metadata = metadata,
            isSelected = selectedPath != null && path == selectedPath,
            onSelect = {
                selectedPath = path
                // Deliberately NOT rememberCoroutineScope(): that scope is
                // cancelled the instant this composable leaves composition,
                // which is exactly what Save's navigateUp() does right
                // after kicking off its own flush. Selecting a driver and
                // immediately tapping Save could cancel this write
                // mid-flight before it ever reached settingsSet/flush, so
                // the pick silently never persisted no matter how quickly
                // Save flushed afterwards. settingsWriter is a top-level
                // scope (same one every other setting in this screen
                // already uses) that outlives navigation.
                settingsWriter.launch {
                    // Neither settingsSet's return value nor a post-flush
                    // save failure were being checked here, unlike every
                    // other setting on this screen (see SettingItem.kt's
                    // writeSetting). A rejected or unwritten value would
                    // silently do nothing, and the next time this screen
                    // loads, LaunchedEffect reads back whatever is actually
                    // on disk - the old selection - which looks exactly
                    // like "the pick didn't stick" with zero indication
                    // why. Surfacing both here either fixes that directly
                    // (if this was the cause) or rules it out for the next
                    // test by making a real failure visible instead of silent.
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
            },
            onDelete = null
        )
    }

    Spacer(Modifier.height(18.dp))

    DriverFlagsSection(titleId)

    Spacer(Modifier.height(14.dp))
}

