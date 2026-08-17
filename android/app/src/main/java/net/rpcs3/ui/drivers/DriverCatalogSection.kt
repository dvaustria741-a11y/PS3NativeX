package net.rpcs3.ui.drivers

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcs3.R
import net.rpcs3.ui.components.PaneSectionTitle
import net.rpcs3.utils.GpuDriverCatalogRepository
import net.rpcs3.utils.GpuDriverHelper
import net.rpcs3.utils.GpuDriverInstallResult
import net.rpcs3.utils.GpuDriverMatch
import net.rpcs3.utils.GpuDriverRecommendations
import net.rpcs3.utils.RemoteGpuDriver
import net.rpcs3.utils.SnapdragonGpuProfile

private enum class CatalogLoadState { Idle, Loading, Loaded, Failed }

/**
 * Curated, no-typing-required driver catalog with per-device "Best match"
 * recommendations -- the piece the existing custom-repo-URL flow above
 * doesn't have. Points at the same public catalog EmuCoreC uses
 * (see GpuDriverCatalogRepository); the compatibility badges are computed
 * on-device from Build.SOC_MODEL/HARDWARE/BOARD, nothing is sent anywhere.
 *
 * [installedLabels] is the ground truth "what's actually on disk right now"
 * (from GpuDriverHelper.getInstalledDrivers, same as the "Installed
 * drivers" list above), and [onInstalled] lets this stay in sync with that
 * list without owning that state itself. It's deliberately *not* used to
 * decide "is this catalog entry installed" directly -- see the id-based
 * tracking below -- only to catch the case where a driver that was
 * installed through this catalog got deleted some other way (e.g. swiped
 * away in the "Installed drivers" list above), so a stale "Downloaded"
 * badge doesn't linger for something no longer on disk.
 *
 * [onApply] and [onRemove] receive the actual installed *label*
 * (GpuDriverMetadata.label, e.g. "Turnip v26.2.0-R5-v1\nmesa main"), not
 * the catalog's display name -- those two don't reliably match (see
 * GpuDriverCatalogRepository), and the label is exactly what the parent
 * needs to look the driver up in its own installed-drivers map.
 */
@Composable
fun DriverCatalogSection(
    installedLabels: Set<String>,
    onInstalled: () -> Unit,
    onApply: (installedLabel: String) -> Unit,
    onRemove: (installedLabel: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { GpuDriverCatalogRepository(context) }
    val deviceProfile = remember { GpuDriverRecommendations.currentDeviceProfile() }

    var loadState by remember { mutableStateOf(CatalogLoadState.Idle) }
    var catalog by remember { mutableStateOf<List<RemoteGpuDriver>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    // Bumped whenever a catalog id -> installed-label record is written or
    // cleared, so the per-card installedLabelFor() lookups below (which
    // read SharedPreferences directly, not Compose state) actually
    // recompose. SharedPreferences reads aren't observable to Compose on
    // their own.
    var recordVersion by remember { mutableStateOf(0) }

    /** Installed label for [driver], or null if this catalog entry either
     *  was never installed through here, or was but has since been removed
     *  some other way (checked against the live [installedLabels] so a
     *  stale record can't show a "Downloaded" badge for something that's
     *  no longer actually on disk). */
    fun installedLabelFor(driver: RemoteGpuDriver): String? {
        // Reading recordVersion (even just for its value, unused otherwise)
        // is what makes this recompose when the version bumps.
        @Suppress("UNUSED_EXPRESSION") recordVersion
        val recorded = GpuDriverCatalogRepository.installedLabelFor(context, driver.id) ?: return null
        if (recorded !in installedLabels) {
            // Recorded but no longer actually installed -- was removed
            // outside this screen's own Remove button. Drop the stale
            // record rather than keep re-checking it every recomposition.
            GpuDriverCatalogRepository.clearInstalled(context, driver.id)
            return null
        }
        return recorded
    }


    fun load() {
        loadState = CatalogLoadState.Loading
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.loadCatalog() } }
            result.onSuccess { drivers ->
                // Best-match entries first, then everything else, each
                // group alphabetical -- matches how the recommendation is
                // actually meant to be read: the thing that fits this
                // phone should be the first thing you see.
                catalog = drivers.sortedWith(
                    compareByDescending<RemoteGpuDriver> {
                        GpuDriverRecommendations.match(it, deviceProfile) == GpuDriverMatch.COMPATIBLE
                    }.thenBy { it.name.lowercase() }
                )
                loadState = CatalogLoadState.Loaded
            }.onFailure {
                errorMessage = it.message ?: context.getString(R.string.drivers_catalog_load_failed)
                loadState = CatalogLoadState.Failed
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    fun downloadAndApply(driver: RemoteGpuDriver) {
        if (downloadingId != null) return
        downloadingId = driver.id
        downloadProgress = 0f
        scope.launch {
            val beforeLabels = withContext(Dispatchers.IO) {
                GpuDriverHelper.getInstalledDrivers(context).values.map { it.label }.toSet()
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val archive = repo.downloadDriver(driver) { p ->
                        downloadProgress = p
                    }
                    GpuDriverHelper.installDriver(context, archive)
                }
            }
            downloadingId = null
            val result = outcome.getOrNull()
            if (outcome.isFailure || result != GpuDriverInstallResult.Success) {
                Toast.makeText(
                    context,
                    context.getString(R.string.drivers_catalog_install_failed, driver.name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // The catalog's display name and the archive's own
                // meta.json name don't reliably agree (third-party data
                // neither side controls), so the newly-installed label is
                // found by diffing before/after rather than assumed from
                // driver.name -- and recorded against driver.id so future
                // "is this installed" checks don't depend on name matching
                // at all.
                val afterLabels = withContext(Dispatchers.IO) {
                    GpuDriverHelper.getInstalledDrivers(context).values.map { it.label }.toSet()
                }
                val newLabel = (afterLabels - beforeLabels).firstOrNull()
                if (newLabel != null) {
                    GpuDriverCatalogRepository.recordInstalled(context, driver.id, newLabel)
                    recordVersion++
                }
                onInstalled()
                if (newLabel != null) onApply(newLabel)
                Toast.makeText(
                    context,
                    context.getString(R.string.drivers_catalog_install_success, driver.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Spacer(Modifier.height(18.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        PaneSectionTitle(stringResource(R.string.drivers_catalog_title))
        OutlinedButton(onClick = { load() }, enabled = loadState != CatalogLoadState.Loading) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.drivers_catalog_refresh))
        }
    }

    Spacer(Modifier.height(8.dp))

    if (deviceProfile == null && loadState == CatalogLoadState.Loaded) {
        Text(
            text = stringResource(R.string.drivers_catalog_unknown_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    when (loadState) {
        CatalogLoadState.Loading -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
        }

        CatalogLoadState.Failed -> Column {
            Text(
                text = errorMessage ?: stringResource(R.string.drivers_catalog_load_failed),
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { load() }) {
                Text(stringResource(R.string.drivers_catalog_retry))
            }
        }

        CatalogLoadState.Idle -> Unit

        CatalogLoadState.Loaded -> {
            if (catalog.isEmpty()) {
                Text(
                    text = stringResource(R.string.drivers_catalog_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            catalog.forEach { driver ->
                val installedLabel = installedLabelFor(driver)
                CatalogDriverCard(
                    driver = driver,
                    match = GpuDriverRecommendations.match(driver, deviceProfile),
                    deviceProfile = deviceProfile,
                    isInstalled = installedLabel != null,
                    isDownloading = downloadingId == driver.id,
                    downloadProgress = downloadProgress,
                    expanded = expandedId == driver.id,
                    onToggleExpand = {
                        expandedId = if (expandedId == driver.id) null else driver.id
                    },
                    onApply = { installedLabel?.let(onApply) },
                    onRemove = {
                        GpuDriverCatalogRepository.clearInstalled(context, driver.id)
                        recordVersion++
                        installedLabel?.let(onRemove)
                    },
                    onDownloadAndApply = { downloadAndApply(driver) }
                )
            }
        }
    }
}

@Composable
private fun CatalogDriverCard(
    driver: RemoteGpuDriver,
    match: GpuDriverMatch,
    deviceProfile: SnapdragonGpuProfile?,
    isInstalled: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onApply: () -> Unit,
    onRemove: () -> Unit,
    onDownloadAndApply: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = driver.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (match == GpuDriverMatch.COMPATIBLE) {
                    CatalogBadge(
                        text = stringResource(R.string.drivers_catalog_best_match),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isInstalled) {
                    CatalogBadge(
                        text = stringResource(R.string.drivers_catalog_downloaded),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = driver.gpu,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded && driver.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = driver.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded && driver.credits.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.drivers_catalog_credits, driver.credits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = onApply, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.drivers_catalog_apply))
                    }
                    OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.drivers_catalog_remove))
                    }
                }
            } else {
                Button(onClick = onDownloadAndApply, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.width(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.drivers_catalog_download_apply))
                }
            }
        }
    }
}

@Composable
private fun CatalogBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
