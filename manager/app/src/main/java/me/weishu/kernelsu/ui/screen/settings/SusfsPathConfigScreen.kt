package me.weishu.kernelsu.ui.screen.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.immersivePageColor
import me.weishu.kernelsu.ui.theme.immersiveScrolledTopBarColor
import me.weishu.kernelsu.ui.theme.immersiveSurfaceColor
import me.weishu.kernelsu.ui.theme.immersiveTopBarColor
import me.weishu.kernelsu.ui.util.SusfsCapabilities
import me.weishu.kernelsu.ui.util.SusfsKstatEntry
import me.weishu.kernelsu.ui.util.SusfsOpenRedirectEntry
import me.weishu.kernelsu.ui.util.SusfsPathConfigState
import me.weishu.kernelsu.ui.util.buildSusfsBackupJson
import me.weishu.kernelsu.ui.util.getSusfsPathConfig
import me.weishu.kernelsu.ui.util.normalizeSusfsMapPath
import me.weishu.kernelsu.ui.util.normalizeSusfsPath
import me.weishu.kernelsu.ui.util.parseSusfsBackupJson
import me.weishu.kernelsu.ui.util.saveAndApplySusfsConfig

private const val SUSFS_IMPORT_MAX_CHARS = 256 * 1024

private enum class SusfsPage {
    RuntimePolicy,
    PathMasking,
    KernelSpoofing,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SusfsPathConfigScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var runtime by remember { mutableStateOf(SusfsPathConfigState()) }
    var draft by remember { mutableStateOf(SusfsPathConfigState()) }
    var baseline by remember { mutableStateOf<SusfsPathConfigState?>(null) }
    var loading by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf("") }
    var importWarnings by remember { mutableStateOf(emptyList<String>()) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf("") }
    var selectedPageIndex by rememberSaveable { mutableStateOf(0) }

    val selectedPage = SusfsPage.entries[selectedPageIndex.coerceIn(SusfsPage.entries.indices)]
    val runtimeScrollState = rememberScrollState()
    val pathsScrollState = rememberScrollState()
    val kernelScrollState = rememberScrollState()

    val dirty = baseline?.let { !editableSusfsConfigEquals(it, draft) } == true

    fun replaceWithRuntime(refreshed: SusfsPathConfigState) {
        runtime = refreshed
        draft = refreshed
        baseline = refreshed
        actionError = ""
        importWarnings = emptyList()
    }

    fun refresh() {
        if (loading || applying || importing || dirty) return
        scope.launch {
            loading = true
            replaceWithRuntime(getSusfsPathConfig())
            loading = false
        }
    }

    fun leavePage() {
        if (dirty) showDiscardDialog = true else navigator.pop()
    }

    fun apply() {
        if (applying || !runtime.available) return
        scope.launch {
            applying = true
            actionError = ""
            val result = saveAndApplySusfsConfig(draft)
            if (result.success) {
                val refreshed = getSusfsPathConfig()
                if (refreshed.available) {
                    replaceWithRuntime(refreshed)
                } else {
                    val saved = draft.copy(
                        available = runtime.available,
                        toolPath = runtime.toolPath,
                        capabilities = runtime.capabilities,
                        error = runtime.error,
                    )
                    runtime = saved
                    draft = saved
                    baseline = saved
                }
                Toast.makeText(
                    context,
                    if (result.requiresReboot) {
                        resources.getString(R.string.susfs_path_apply_reboot)
                    } else {
                        resources.getString(R.string.susfs_path_apply_success, result.appliedCount)
                    },
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                actionError = result.error.ifBlank { "apply_failed" }
            }
            applying = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                actionError = ""
                val imported = runCatching {
                    withContext(Dispatchers.IO) {
                        val input = requireNotNull(context.contentResolver.openInputStream(uri))
                        input.use(::readTextLimited)
                    }
                }.map(::parseSusfsBackupJson)
                imported.onSuccess { result ->
                    val config = result.config
                    if (config == null || result.error.isNotBlank()) {
                        actionError = result.error.ifBlank { "invalid_backup" }
                    } else {
                        draft = config.copy(
                            available = runtime.available,
                            toolPath = runtime.toolPath,
                            capabilities = runtime.capabilities,
                            error = runtime.error,
                        )
                        importWarnings = result.warnings
                        Toast.makeText(context, R.string.susfs_import_ready, Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    actionError = error.message.orEmpty().ifBlank { "import_failed" }
                }
                importing = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null && pendingExport.isNotEmpty()) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                            output.write(pendingExport.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) R.string.susfs_export_success else R.string.susfs_export_failed,
                    Toast.LENGTH_LONG,
                ).show()
                pendingExport = ""
            }
        }
    }

    BackHandler(enabled = dirty, onBack = { showDiscardDialog = true })

    LaunchedEffect(Unit) {
        replaceWithRuntime(getSusfsPathConfig())
        loading = false
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.susfs_discard_title)) },
            text = { Text(stringResource(R.string.susfs_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        navigator.pop()
                    },
                ) { Text(stringResource(R.string.susfs_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        containerColor = immersivePageColor(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_susfs_path_config)) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { leavePage() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                        enabled = !loading && !applying && !importing,
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = stringResource(R.string.susfs_import))
                    }
                    IconButton(
                        onClick = {
                            pendingExport = buildSusfsBackupJson(draft)
                            exportLauncher.launch("apkesu-susfs-backup.json")
                        },
                        enabled = !loading && !applying && !importing,
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.susfs_export))
                    }
                    IconButton(onClick = ::refresh, enabled = !loading && !applying && !importing && !dirty) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.susfs_path_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = immersiveTopBarColor(MaterialTheme.colorScheme.background),
                    scrolledContainerColor = immersiveScrolledTopBarColor(MaterialTheme.colorScheme.surface),
                ),
            )
        },
        bottomBar = {
            Surface(
                color = immersiveSurfaceColor(
                    defaultColor = MaterialTheme.colorScheme.surface,
                    darkAlpha = 0.70f,
                    lightAlpha = 0.76f,
                ),
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.imePadding()) {
                    Button(
                        onClick = ::apply,
                        enabled = runtime.available && dirty && !loading && !applying && !importing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (applying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.susfs_path_apply))
                    }
                    ShortNavigationBar(
                        containerColor = immersiveSurfaceColor(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        SusfsPage.entries.forEachIndexed { index, page ->
                            val label = stringResource(
                                when (page) {
                                    SusfsPage.RuntimePolicy -> R.string.susfs_nav_runtime
                                    SusfsPage.PathMasking -> R.string.susfs_nav_paths
                                    SusfsPage.KernelSpoofing -> R.string.susfs_nav_kernel
                                },
                            )
                            ShortNavigationBarItem(
                                selected = selectedPage == page,
                                onClick = { selectedPageIndex = index },
                                icon = {
                                    Icon(
                                        imageVector = when (page) {
                                            SusfsPage.RuntimePolicy -> Icons.Rounded.Policy
                                            SusfsPage.PathMasking -> Icons.Rounded.VisibilityOff
                                            SusfsPage.KernelSpoofing -> Icons.Rounded.Memory
                                        },
                                        contentDescription = label,
                                    )
                                },
                                label = {
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(
                    when (selectedPage) {
                        SusfsPage.RuntimePolicy -> runtimeScrollState
                        SusfsPage.PathMasking -> pathsScrollState
                        SusfsPage.KernelSpoofing -> kernelScrollState
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SusfsStatusPanel(
                state = runtime,
                loading = loading || importing,
                page = selectedPage,
            )

            if (importWarnings.isNotEmpty()) {
                SusfsNoticeCard(
                    title = stringResource(R.string.susfs_import_warning_title),
                    message = stringResource(
                        R.string.susfs_import_warning_message,
                        importWarnings.size,
                        importWarnings.take(3).joinToString("\n"),
                    ),
                    warning = true,
                )
            }

            if (selectedPage == SusfsPage.RuntimePolicy) {
                SusfsSection(title = stringResource(R.string.susfs_runtime_policy)) {
                    PolicyToggle(
                        title = stringResource(R.string.susfs_enabled),
                        summary = stringResource(R.string.susfs_enabled_summary),
                        checked = draft.enabled,
                        enabled = runtime.available && !applying,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                    HorizontalDivider()
                    PolicyToggle(
                        title = stringResource(R.string.susfs_logging),
                        summary = stringResource(R.string.susfs_logging_summary),
                        checked = draft.logging,
                        enabled = runtime.available && draft.enabled && !applying,
                        onCheckedChange = { draft = draft.copy(logging = it) },
                    )
                    HorizontalDivider()
                    PolicyToggle(
                        title = stringResource(R.string.susfs_avc_spoofing),
                        summary = stringResource(R.string.susfs_avc_spoofing_summary),
                        checked = draft.avcLogSpoofing,
                        enabled = runtime.available && draft.enabled && runtime.capabilities.supportsAvcLogSpoofing && !applying,
                        onCheckedChange = { draft = draft.copy(avcLogSpoofing = it) },
                    )
                    HorizontalDivider()
                    PolicyToggle(
                        title = stringResource(R.string.susfs_hide_mounts_non_su),
                        summary = stringResource(R.string.susfs_hide_mounts_non_su_summary),
                        checked = draft.hideSusMntsForNonSuProcs,
                        enabled = runtime.available && draft.enabled && runtime.capabilities.supportsHideSusMounts && !applying,
                        onCheckedChange = {
                            draft = draft.copy(
                                hideSusMntsForNonSuProcs = it,
                                hideSusMntsForAllProcs = if (it) false else draft.hideSusMntsForAllProcs,
                            )
                        },
                    )
                    HorizontalDivider()
                    PolicyToggle(
                        title = stringResource(R.string.susfs_hide_mounts_all),
                        summary = stringResource(R.string.susfs_hide_mounts_all_summary),
                        checked = draft.hideSusMntsForAllProcs,
                        enabled = runtime.available && draft.enabled && runtime.capabilities.supportsHideSusMounts && !applying,
                        onCheckedChange = {
                            draft = draft.copy(
                                hideSusMntsForAllProcs = it,
                                hideSusMntsForNonSuProcs = if (it) false else draft.hideSusMntsForNonSuProcs,
                            )
                        },
                    )
                }

                SusfsNoticeCard(
                    title = stringResource(R.string.susfs_runtime_notice_title),
                    message = stringResource(R.string.susfs_runtime_notice_summary),
                    warning = false,
                )
            }

            if (selectedPage == SusfsPage.PathMasking) {
                SusfsStringListEditor(
                    title = stringResource(R.string.susfs_normal_paths),
                    summary = stringResource(R.string.susfs_normal_paths_summary),
                    values = draft.paths,
                    inputLabel = stringResource(R.string.susfs_path_input_label),
                    inputHint = stringResource(R.string.susfs_path_input_hint),
                    enabled = runtime.available && draft.enabled && !applying,
                    normalizer = ::normalizeSusfsPath,
                    onValuesChange = { draft = draft.copy(paths = it) },
                )

                SusfsStringListEditor(
                    title = stringResource(R.string.susfs_loop_paths),
                    summary = stringResource(R.string.susfs_loop_paths_summary),
                    values = draft.loopPaths,
                    inputLabel = stringResource(R.string.susfs_path_input_label),
                    inputHint = stringResource(R.string.susfs_loop_paths_hint),
                    enabled = runtime.available && draft.enabled && runtime.capabilities.supportsPathLoop && !applying,
                    normalizer = ::normalizeSusfsPath,
                    onValuesChange = { draft = draft.copy(loopPaths = it) },
                )

                SusfsStringListEditor(
                    title = stringResource(R.string.susfs_map_paths),
                    summary = stringResource(R.string.susfs_map_paths_summary),
                    values = draft.susMaps,
                    inputLabel = stringResource(R.string.susfs_path_input_label),
                    inputHint = stringResource(R.string.susfs_map_paths_hint),
                    enabled = runtime.available && draft.enabled && runtime.capabilities.supportsSusMap && !applying,
                    normalizer = ::normalizeSusfsMapPath,
                    onValuesChange = { draft = draft.copy(susMaps = it) },
                )
            }

            if (selectedPage == SusfsPage.KernelSpoofing) {
                SusfsIdentityEditor(
                    draft = draft,
                    enabled = runtime.available && draft.enabled && !applying,
                    onChange = { draft = it },
                )
            }

            if (selectedPage == SusfsPage.PathMasking) {
                SusfsRedirectEditor(
                    values = draft.openRedirects,
                    enabled = runtime.available && draft.enabled && runtime.capabilities.supportsOpenRedirect && !applying,
                    onValuesChange = { draft = draft.copy(openRedirects = it) },
                )
            }

            if (selectedPage == SusfsPage.KernelSpoofing) {
                SusfsKstatEditor(
                    values = draft.kstatEntries,
                    enabled = runtime.available && draft.enabled && runtime.capabilities.supportsKstat && !applying,
                    onValuesChange = { draft = draft.copy(kstatEntries = it) },
                )
            }

            if (actionError.isNotBlank()) {
                SusfsNoticeCard(
                    title = stringResource(R.string.susfs_apply_error_title),
                    message = stringResource(R.string.susfs_path_apply_failed, actionError),
                    warning = true,
                )
            }

            when (selectedPage) {
                SusfsPage.RuntimePolicy -> Unit
                SusfsPage.PathMasking -> SusfsNoticeCard(
                    title = stringResource(R.string.susfs_path_notes_title),
                    message = stringResource(R.string.susfs_path_notes_summary),
                    warning = false,
                )
                SusfsPage.KernelSpoofing -> SusfsNoticeCard(
                    title = stringResource(R.string.susfs_kernel_notice_title),
                    message = stringResource(R.string.susfs_kernel_notice_summary),
                    warning = false,
                )
            }
        }
    }
}

@Composable
private fun SusfsStatusPanel(
    state: SusfsPathConfigState,
    loading: Boolean,
    page: SusfsPage,
) {
    val available = state.available && !loading
    val message = when {
        loading -> stringResource(R.string.processing)
        state.available -> stringResource(R.string.susfs_path_available, state.toolPath)
        state.error == "gki_mode_required" -> stringResource(R.string.susfs_path_gki_required)
        state.error == "root_unavailable" -> stringResource(R.string.susfs_path_root_unavailable)
        state.error == "path_feature_unavailable" -> stringResource(R.string.susfs_path_feature_unavailable)
        else -> stringResource(R.string.susfs_path_tool_unavailable)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (available) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = if (!available) {
                        Icons.Rounded.ErrorOutline
                    } else {
                        when (page) {
                            SusfsPage.RuntimePolicy -> Icons.Rounded.Policy
                            SusfsPage.PathMasking -> Icons.Rounded.VisibilityOff
                            SusfsPage.KernelSpoofing -> Icons.Rounded.Memory
                        }
                    },
                    contentDescription = null,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.susfs_path_status),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = message, style = MaterialTheme.typography.bodySmall)
                if (!loading && state.toolPath.isNotBlank()) {
                    Text(
                        text = if (state.capabilities.version.isBlank()) {
                            stringResource(R.string.susfs_path_legacy_probe)
                        } else {
                            stringResource(R.string.susfs_path_version, state.capabilities.version)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = when (page) {
                            SusfsPage.RuntimePolicy -> stringResource(R.string.susfs_runtime_page_summary)
                            SusfsPage.PathMasking -> stringResource(
                                R.string.susfs_path_counts,
                                state.paths.size,
                                state.loopPaths.size,
                                state.susMaps.size,
                            )
                            SusfsPage.KernelSpoofing -> stringResource(
                                R.string.susfs_kernel_page_summary,
                                state.kstatEntries.size,
                            )
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SusfsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = immersiveSurfaceColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PolicyToggle(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SusfsStringListEditor(
    title: String,
    summary: String,
    values: List<String>,
    inputLabel: String,
    inputHint: String,
    enabled: Boolean,
    normalizer: (String) -> String?,
    onValuesChange: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    var input by rememberSaveable(title) { mutableStateOf("") }

    fun addValue() {
        val normalized = normalizer(input)
        when {
            normalized == null -> Toast.makeText(context, R.string.susfs_path_invalid, Toast.LENGTH_LONG).show()
            normalized in values -> Toast.makeText(context, R.string.susfs_path_duplicate, Toast.LENGTH_SHORT).show()
            else -> {
                onValuesChange(values + normalized)
                input = ""
            }
        }
    }

    SusfsSection(title = title) {
        Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text(inputLabel) },
                placeholder = { Text(inputHint) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addValue() }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = ::addValue, enabled = enabled && input.isNotBlank()) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.susfs_path_add))
            }
        }
        if (values.isEmpty()) {
            Text(
                text = stringResource(R.string.susfs_path_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            values.forEachIndexed { index, value ->
                if (index > 0) HorizontalDivider()
                SusfsListRow(value = value, enabled = enabled, onRemove = { onValuesChange(values - value) })
            }
        }
    }
}

@Composable
private fun SusfsIdentityEditor(
    draft: SusfsPathConfigState,
    enabled: Boolean,
    onChange: (SusfsPathConfigState) -> Unit,
) {
    SusfsSection(title = stringResource(R.string.susfs_identity_spoofing)) {
        Text(
            stringResource(R.string.susfs_identity_spoofing_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft.unameRelease,
            onValueChange = { onChange(draft.copy(unameRelease = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.susfs_uname_release)) },
            singleLine = true,
            enabled = enabled && (draft.capabilities.supportsUnameSpoof || draft.unameRelease.isNotBlank()),
        )
        OutlinedTextField(
            value = draft.unameVersion,
            onValueChange = { onChange(draft.copy(unameVersion = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.susfs_uname_version)) },
            singleLine = true,
            enabled = enabled && (draft.capabilities.supportsUnameSpoof || draft.unameVersion.isNotBlank()),
        )
        OutlinedTextField(
            value = draft.cmdlineOrBootconfig,
            onValueChange = { onChange(draft.copy(cmdlineOrBootconfig = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.susfs_cmdline_file)) },
            supportingText = { Text(stringResource(R.string.susfs_cmdline_file_summary)) },
            singleLine = true,
            enabled = enabled && (draft.capabilities.supportsCmdlineSpoof || draft.cmdlineOrBootconfig.isNotBlank()),
        )
    }
}

@Composable
private fun SusfsRedirectEditor(
    values: List<SusfsOpenRedirectEntry>,
    enabled: Boolean,
    onValuesChange: (List<SusfsOpenRedirectEntry>) -> Unit,
) {
    val context = LocalContext.current
    var original by rememberSaveable { mutableStateOf("") }
    var redirected by rememberSaveable { mutableStateOf("") }
    var uidScheme by rememberSaveable { mutableStateOf("") }

    fun addRedirect() {
        val source = normalizeSusfsPath(original)
        val target = normalizeSusfsPath(redirected)
        if (source == null || target == null || (uidScheme.isNotBlank() && uidScheme.any { !it.isDigit() })) {
            Toast.makeText(context, R.string.susfs_redirect_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val entry = SusfsOpenRedirectEntry(source, target, uidScheme.trim())
        if (entry in values) {
            Toast.makeText(context, R.string.susfs_path_duplicate, Toast.LENGTH_SHORT).show()
            return
        }
        onValuesChange(values + entry)
        original = ""
        redirected = ""
        uidScheme = ""
    }

    SusfsSection(title = stringResource(R.string.susfs_open_redirect)) {
        Text(
            stringResource(R.string.susfs_open_redirect_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = original,
            onValueChange = { original = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.susfs_redirect_source)) },
            singleLine = true,
            enabled = enabled,
        )
        OutlinedTextField(
            value = redirected,
            onValueChange = { redirected = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.susfs_redirect_target)) },
            singleLine = true,
            enabled = enabled,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uidScheme,
                onValueChange = { uidScheme = it.filter(Char::isDigit) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.susfs_redirect_uid_scheme)) },
                singleLine = true,
                enabled = enabled,
            )
            OutlinedButton(
                onClick = ::addRedirect,
                enabled = enabled && original.isNotBlank() && redirected.isNotBlank(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.susfs_path_add))
            }
        }
        values.forEachIndexed { index, value ->
            if (index > 0) HorizontalDivider()
            val label = buildString {
                append(value.originalPath)
                append(" -> ")
                append(value.redirectedPath)
                if (value.uidScheme.isNotBlank()) append(" [uid=${value.uidScheme}]")
            }
            SusfsListRow(label, enabled) { onValuesChange(values - value) }
        }
    }
}

@Composable
private fun SusfsKstatEditor(
    values: List<SusfsKstatEntry>,
    enabled: Boolean,
    onValuesChange: (List<SusfsKstatEntry>) -> Unit,
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }

    fun addEntry() {
        val args = input.split('|').map(String::trim)
        if (args.size != 13 || args.any(String::isEmpty)) {
            Toast.makeText(context, R.string.susfs_kstat_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val entry = SusfsKstatEntry(args)
        if (entry in values) {
            Toast.makeText(context, R.string.susfs_path_duplicate, Toast.LENGTH_SHORT).show()
            return
        }
        onValuesChange(values + entry)
        input = ""
    }

    SusfsSection(title = stringResource(R.string.susfs_static_kstat)) {
        Text(
            stringResource(R.string.susfs_static_kstat_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.susfs_static_kstat_input)) },
                singleLine = true,
                enabled = enabled,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = ::addEntry, enabled = enabled && input.isNotBlank()) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.susfs_path_add))
            }
        }
        values.forEachIndexed { index, value ->
            if (index > 0) HorizontalDivider()
            SusfsListRow(value.arguments.joinToString(" | "), enabled) { onValuesChange(values - value) }
        }
    }
}

@Composable
private fun SusfsListRow(
    value: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.susfs_path_remove, value))
        }
    }
}

@Composable
private fun SusfsNoticeCard(
    title: String,
    message: String,
    warning: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (warning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            immersiveSurfaceColor(MaterialTheme.colorScheme.surfaceContainerLow)
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (warning) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun editableSusfsConfigEquals(
    first: SusfsPathConfigState,
    second: SusfsPathConfigState,
): Boolean = first.copy(
    available = false,
    toolPath = "",
    capabilities = SusfsCapabilities(),
    error = "",
) == second.copy(
    available = false,
    toolPath = "",
    capabilities = SusfsCapabilities(),
    error = "",
)

private fun readTextLimited(input: InputStream): String {
    val reader = input.bufferedReader(Charsets.UTF_8)
    val output = StringBuilder()
    val buffer = CharArray(4096)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        if (output.length + count > SUSFS_IMPORT_MAX_CHARS) error("backup_too_large")
        output.append(buffer, 0, count)
    }
    return output.toString()
}
