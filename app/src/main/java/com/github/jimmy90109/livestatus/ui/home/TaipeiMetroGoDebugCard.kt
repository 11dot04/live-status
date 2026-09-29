package com.github.jimmy90109.livestatus.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.jimmy90109.livestatus.R
import com.github.jimmy90109.livestatus.ui.theme.LocalAppColors

@Composable
internal fun TaipeiMetroGoDebugCard(
    installed: Boolean,
    interactionEnabled: Boolean,
    onOpenDebug: () -> Unit,
) {
    val colors = LocalAppColors.current
    AppCard(
        appName = stringResource(R.string.taipei_metro_go_app_name),
        appPackageName = TAIPEI_METRO_GO_PACKAGE,
        fallbackIconRes = R.drawable.ic_metro_notification,
        title = stringResource(R.string.taipei_metro_go_debug_card_title),
        description = stringResource(R.string.taipei_metro_go_debug_card_description),
        supportedLanguages = listOf(stringResource(R.string.taipei_metro_go_debug_language)),
        installed = installed,
        enabled = true,
        interactionEnabled = interactionEnabled,
        onEnabledChange = {},
        showEnabledSwitch = false,
        cardColor = colors.taipeiMetroGoContainer,
        labelColor = colors.taipeiMetroGoSecondaryContainer,
        foregroundColor = colors.taipeiMetroGoText,
        actionColor = colors.taipeiMetroGoPrimary,
    ) {
        AppActionDivider(colors.taipeiMetroGoText)
        AppCardActionButton(
            stringResource(R.string.taipei_metro_go_debug_open_payload),
            colors.taipeiMetroGoPrimary,
            colors.taipeiMetroGoText,
            onClick = onOpenDebug,
        )
    }
}

private const val TAIPEI_METRO_GO_PACKAGE = "tw.com.trtc.is.android05"
