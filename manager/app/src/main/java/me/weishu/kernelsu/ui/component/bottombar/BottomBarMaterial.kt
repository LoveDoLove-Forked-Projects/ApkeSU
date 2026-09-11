package me.weishu.kernelsu.ui.component.bottombar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalMainPagerState
import me.weishu.kernelsu.ui.component.CustomNavigationIconImage
import me.weishu.kernelsu.ui.util.CustomNavigationIconState
import me.weishu.kernelsu.ui.util.LocalCustomNavigationIcons
import me.weishu.kernelsu.ui.util.rootAvailable

@Composable
fun BottomBarMaterial(
    navigationBadge: NavigationBadgeState,
    destinations: List<MainDestination>,
) {
    val isManager = Natives.isManager
    val fullFeatured = isManager && !Natives.requireNewKernel() && rootAvailable()
    val mainPagerState = LocalMainPagerState.current
    val customIcons = LocalCustomNavigationIcons.current

    if (!fullFeatured) return

    ShortNavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        )
    ) {
        destinations.forEachIndexed { index, destination ->
            val selected = mainPagerState.selectedPage == index
            val label = customIcons.labelFor(destination, stringResource(destination.label))
            ShortNavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        mainPagerState.animateToPage(index)
                    }
                },
                icon = {
                    NavigationIconWithBadge(
                        destination = destination,
                        state = customIcons.stateFor(destination),
                        contentDescription = label,
                        badge = badgeFor(destination, navigationBadge),
                    )
                },
                label = {
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
internal fun NavigationIconWithBadge(
    destination: MainDestination,
    state: CustomNavigationIconState,
    contentDescription: String?,
    badge: NavBadge?,
) {
    @Composable
    fun navigationIcon() {
        NavigationDestinationIcon(
            destination = destination,
            state = state,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
        )
    }

    if (badge != null) {
        BadgedBox(
            badge = {
                when (badge.tone) {
                    BadgeTone.Alert -> Badge {
                        Text(badge.count.toString())
                    }

                    BadgeTone.Accent -> Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(badge.count.toString())
                    }
                }
            }
        ) {
            navigationIcon()
        }
    } else {
        navigationIcon()
    }
}

@Composable
internal fun NavigationDestinationIcon(
    destination: MainDestination,
    state: CustomNavigationIconState,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier.size(24.dp),
    alpha: Float = 1f,
) {
    CustomNavigationIconImage(
        state = state,
        contentDescription = contentDescription,
        modifier = modifier,
        alpha = alpha,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
    }
}
