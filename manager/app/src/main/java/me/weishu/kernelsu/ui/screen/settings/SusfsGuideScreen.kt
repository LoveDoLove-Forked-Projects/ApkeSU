package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.immersivePageColor
import me.weishu.kernelsu.ui.theme.immersiveScrolledTopBarColor
import me.weishu.kernelsu.ui.theme.immersiveSurfaceColor
import me.weishu.kernelsu.ui.theme.immersiveTopBarColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SusfsGuideScreen() {
    val navigator = LocalNavigator.current
    Scaffold(
        containerColor = immersivePageColor(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.susfs_guide_title)) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = immersiveTopBarColor(MaterialTheme.colorScheme.background),
                    scrolledContainerColor = immersiveScrolledTopBarColor(MaterialTheme.colorScheme.surface),
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null)
                    Text(
                        stringResource(R.string.susfs_guide_intro),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            GuideSection(R.string.susfs_guide_section_paths, R.string.susfs_guide_paths, initiallyExpanded = true)
            GuideSection(R.string.susfs_guide_section_kernel, R.string.susfs_guide_kernel)
            GuideSection(R.string.susfs_guide_section_apply, R.string.susfs_guide_apply)
            GuideSection(R.string.susfs_guide_section_safety, R.string.susfs_guide_safety)
            Text(
                text = stringResource(R.string.susfs_guide_section_faq),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            GuideSection(R.string.susfs_guide_faq_tool_question, R.string.susfs_guide_faq_tool)
            GuideSection(R.string.susfs_guide_faq_reboot_question, R.string.susfs_guide_faq_reboot)
            GuideSection(R.string.susfs_guide_faq_partial_question, R.string.susfs_guide_faq_partial)
            GuideSection(R.string.susfs_guide_faq_618_question, R.string.susfs_guide_faq_618)
        }
    }
}

@Composable
private fun GuideSection(
    titleRes: Int,
    bodyRes: Int,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(titleRes) { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = immersiveSurfaceColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(titleRes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(bodyRes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
