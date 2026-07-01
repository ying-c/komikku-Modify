package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderBottomBar(
    // SY -->
    enabledButtons: ImmutableSet<String>,
    // SY <--
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // SY -->
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    // SY <--
    // KMK: Continuous auto scroll -->
    continuousAutoScroll: Boolean = false,
    isContinuousScrollActive: Boolean = false,
    onClickContinuousScroll: () -> Unit = {},
    // KMK: Continuous auto scroll <--
    modifier: Modifier = Modifier,
) {
    // KMK -->
    val iconColor = MaterialTheme.colorScheme.primary
    // KMK <--
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SY -->
        if (ReaderBottomButton.ViewChapters.isIn(enabledButtons)) {
            IconButton(onClick = onClickChapterList) {
                Icon(
                    imageVector = Icons.Outlined.FormatListNumbered,
                    contentDescription = stringResource(MR.strings.chapters),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.WebView.isIn(enabledButtons) && onClickWebView != null) {
            IconButton(onClick = onClickWebView) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.Browser.isIn(enabledButtons) && onClickBrowser != null) {
            IconButton(onClick = onClickBrowser) {
                Icon(
                    imageVector = Icons.Outlined.Explore,
                    contentDescription = stringResource(MR.strings.action_open_in_browser),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.Share.isIn(enabledButtons) && onClickShare != null) {
            IconButton(onClick = onClickShare) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(MR.strings.action_share),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.ReadingMode.isIn(enabledButtons)) {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    painter = painterResource(readingMode.iconRes),
                    contentDescription = stringResource(MR.strings.viewer),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.Rotation.isIn(enabledButtons)) {
            IconButton(onClick = onClickOrientation) {
                Icon(
                    imageVector = orientation.icon,
                    contentDescription = stringResource(MR.strings.pref_rotation_type),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        val cropBorders = when (currentReadingMode) {
            ReadingMode.WEBTOON -> ReaderBottomButton.CropBordersWebtoon
            ReadingMode.CONTINUOUS_VERTICAL -> ReaderBottomButton.CropBordersContinuesVertical
            else -> ReaderBottomButton.CropBordersPager
        }
        if (cropBorders.isIn(enabledButtons)) {
            IconButton(onClick = onClickCropBorder) {
                Icon(
                    painter = painterResource(
                        if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp,
                    ),
                    contentDescription = stringResource(MR.strings.pref_crop_borders),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (
            !dualPageSplitEnabled &&
            ReaderBottomButton.PageLayout.isIn(enabledButtons) &&
            ReadingMode.isPagerType(currentReadingMode.flagValue)
        ) {
            IconButton(onClick = onClickPageLayout) {
                Icon(
                    painter = painterResource(R.drawable.ic_book_open_variant_24dp),
                    contentDescription = stringResource(SYMR.strings.page_layout),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (doublePages) {
            IconButton(onClick = onClickShiftPage) {
                Icon(
                    painter = painterResource(R.drawable.ic_page_next_outline_24dp),
                    contentDescription = stringResource(SYMR.strings.shift_double_pages),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        // KMK: Continuous auto scroll button -->
        // Pager 模式：自动翻页（按 interval 秒数定时翻页）
        // Webtoon 模式：连续滚动（按 px/s 速度平滑滚动）
        // 两种模式都用这个按钮，sheet 内部根据模式展示不同 UI
        if (ReaderBottomButton.ContinuousScroll.isIn(enabledButtons) && continuousAutoScroll) {
            IconButton(onClick = onClickContinuousScroll) {
                Icon(
                    imageVector = Icons.Outlined.PlayCircleOutline,
                    contentDescription = stringResource(KMR.strings.pref_continuous_auto_scroll),
                    // Active 状态时用 primary，未激活时用 onSurface
                    tint = if (isContinuousScrollActive) iconColor else iconColor.copy(alpha = 0.5f),
                )
            }
        }
        // KMK: Continuous auto scroll button <--

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
                // KMK -->
                tint = iconColor,
                // KMK <--
            )
        }
        // SY <--
    }
}
