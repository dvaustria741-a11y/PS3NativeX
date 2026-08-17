package net.rpcs3.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcs3.ControllerSkinStore
import net.rpcs3.R
import net.rpcs3.SkinRepo
import net.rpcs3.ui.components.GhostButton
import net.rpcs3.ui.components.InfoRow
import net.rpcs3.ui.components.SectionLabel
import net.rpcs3.ui.components.SettingGroup
import net.rpcs3.ui.theme.Dimens
import net.rpcs3.ui.theme.Rpcs
import net.rpcs3.ui.theme.SettingsStyle

const val SkinsCategory = "Skins"

private enum class RemoteLoadState { Idle, Loading, Loaded, Failed }

@Composable
fun SkinsSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeSkinId by remember { mutableStateOf(ControllerSkinStore.activeSkinId) }
    var installed by remember { mutableStateOf(ControllerSkinStore.list(context)) }

    fun refreshInstalled() {
        installed = ControllerSkinStore.list(context)
        activeSkinId = ControllerSkinStore.activeSkinId
    }

    var remoteState by remember { mutableStateOf(RemoteLoadState.Idle) }
    var remoteSkins by remember { mutableStateOf<List<SkinRepo.RemoteSkin>>(emptyList()) }
    var downloadingUrl by remember { mutableStateOf<String?>(null) }

    fun loadRemote() {
        remoteState = RemoteLoadState.Loading
        scope.launch {
            val result = withContext(Dispatchers.IO) { SkinRepo.fetch(context) }
            remoteSkins = result
            remoteState = if (result.isEmpty()) RemoteLoadState.Failed else RemoteLoadState.Loaded
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val id = withContext(Dispatchers.IO) { ControllerSkinStore.importFromZip(context, uri) }
            if (id != null) {
                ControllerSkinStore.setActive(context, id)
                refreshInstalled()
                Toast.makeText(context, context.getString(R.string.skins_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.skins_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap)
    ) {
        SectionLabel(text = stringResource(R.string.skins_section_active))

        SettingGroup {
            InfoRow(
                label = stringResource(R.string.skins_current),
                value = installed.find { it.id == activeSkinId }?.name
                    ?: stringResource(R.string.skins_builtin)
            )

            if (activeSkinId != null) {
                GhostButton(
                    label = stringResource(R.string.skins_use_builtin),
                    icon = Icons.Outlined.RadioButtonUnchecked,
                    onClick = {
                        ControllerSkinStore.setActive(context, null)
                        refreshInstalled()
                    }
                )
            }

            Text(
                text = stringResource(R.string.skins_apply_note),
                color = SettingsStyle.TextDim,
                fontSize = Dimens.ValueSize
            )
        }

        SectionLabel(text = stringResource(R.string.skins_section_installed))

        SettingGroup {
            if (installed.isEmpty()) {
                Text(
                    text = stringResource(R.string.skins_none_installed),
                    color = SettingsStyle.TextSecondary,
                    fontSize = Dimens.ValueSize
                )
            } else {
                installed.forEach { skin ->
                    InstalledSkinRow(
                        name = skin.name,
                        buttons = skin.imageCount,
                        active = skin.id == activeSkinId,
                        onApply = {
                            ControllerSkinStore.setActive(context, skin.id)
                            refreshInstalled()
                        },
                        onDelete = {
                            ControllerSkinStore.delete(context, skin.id)
                            refreshInstalled()
                        }
                    )
                }
            }

            GhostButton(
                label = stringResource(R.string.skins_import_zip),
                icon = Icons.Outlined.FileUpload,
                onClick = { importLauncher.launch("application/zip") }
            )
        }

        SectionLabel(text = stringResource(R.string.skins_section_browse))

        SettingGroup {
            when (remoteState) {
                RemoteLoadState.Idle -> {
                    Text(
                        text = stringResource(R.string.skins_browse_intro),
                        color = SettingsStyle.TextSecondary,
                        fontSize = Dimens.ValueSize
                    )
                    GhostButton(
                        label = stringResource(R.string.skins_browse_load),
                        icon = Icons.Outlined.CloudDownload,
                        onClick = { loadRemote() }
                    )
                }

                RemoteLoadState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = SettingsStyle.AccentBlue)
                    }
                }

                RemoteLoadState.Failed -> {
                    Text(
                        text = stringResource(R.string.skins_browse_failed),
                        color = SettingsStyle.DangerRed,
                        fontSize = Dimens.ValueSize
                    )
                    GhostButton(
                        label = stringResource(R.string.skins_browse_retry),
                        icon = Icons.Outlined.Refresh,
                        onClick = { loadRemote() }
                    )
                }

                RemoteLoadState.Loaded -> {
                    remoteSkins.forEach { skin ->
                        RemoteSkinRow(
                            skin = skin,
                            alreadyInstalled = installed.any { it.name.equals(skin.name, ignoreCase = true) },
                            downloading = downloadingUrl == skin.downloadUrl,
                            onDownload = {
                                downloadingUrl = skin.downloadUrl
                                scope.launch {
                                    val id = withContext(Dispatchers.IO) {
                                        SkinRepo.install(context, skin)
                                    }
                                    downloadingUrl = null
                                    if (id != null) {
                                        refreshInstalled()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.skins_imported),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.skins_import_failed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                    GhostButton(
                        label = stringResource(R.string.skins_browse_refresh),
                        icon = Icons.Outlined.Refresh,
                        onClick = { loadRemote() }
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledSkinRow(
    name: String,
    buttons: Int,
    active: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (active) Rpcs.SelectionFill else SettingsStyle.InputSurface,
                RoundedCornerShape(SettingsStyle.InputCorner)
            )
            .border(
                1.dp,
                if (active) Rpcs.SelectionBorder else SettingsStyle.InputBorder,
                RoundedCornerShape(SettingsStyle.InputCorner)
            )
            .clickable(enabled = !active) { onApply() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (active) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (active) SettingsStyle.AccentBlue else SettingsStyle.TextDim,
            modifier = Modifier.size(Dimens.IconSize)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = SettingsStyle.TextPrimary,
                fontSize = Dimens.ValueSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.skins_button_count, buttons),
                color = SettingsStyle.TextDim,
                fontSize = Dimens.SectionLabelSize
            )
        }
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.skins_delete),
            tint = SettingsStyle.DangerRed,
            modifier = Modifier
                .size(Dimens.IconSize)
                .clickable { onDelete() }
        )
    }
}

@Composable
private fun RemoteSkinRow(
    skin: SkinRepo.RemoteSkin,
    alreadyInstalled: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    var previewFile by remember(skin.filePath) { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(skin.filePath) {
        if (skin.previewUrl != null) {
            previewFile = withContext(Dispatchers.IO) { SkinRepo.preview(context, skin) }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsStyle.InputSurface, RoundedCornerShape(SettingsStyle.InputCorner))
            .border(1.dp, SettingsStyle.InputBorder, RoundedCornerShape(SettingsStyle.InputCorner))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(SettingsStyle.CardSurface, RoundedCornerShape(8.dp))
        ) {
            if (previewFile != null) {
                AsyncImage(
                    model = previewFile,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skin.name,
                color = SettingsStyle.TextPrimary,
                fontSize = Dimens.ValueSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.skins_by_author, skin.author),
                color = SettingsStyle.TextDim,
                fontSize = Dimens.SectionLabelSize
            )
        }
        when {
            downloading -> CircularProgressIndicator(
                color = SettingsStyle.AccentBlue,
                modifier = Modifier.size(Dimens.IconSize)
            )
            alreadyInstalled -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = SettingsStyle.AccentBlue,
                modifier = Modifier.size(Dimens.IconSize)
            )
            else -> Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = stringResource(R.string.skins_download),
                tint = SettingsStyle.TextSecondary,
                modifier = Modifier
                    .size(Dimens.IconSize)
                    .clickable { onDownload() }
            )
        }
    }
}
