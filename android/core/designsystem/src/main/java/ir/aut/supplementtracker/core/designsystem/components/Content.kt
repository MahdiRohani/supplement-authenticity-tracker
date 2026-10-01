package ir.aut.supplementtracker.core.designsystem.components

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.R
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementMonoFamily
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.ToneColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Label/value pair; values are selectable and optionally copyable. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    monospace: Boolean = false,
    copyable: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = if (monospace) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = SupplementMonoFamily)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (copyable) {
            CopyButton(value = value, label = label)
        }
    }
}

@Composable
fun CopyButton(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value))) }
            copied = true
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (copied) SupplementIcons.Success else SupplementIcons.Copy,
            contentDescription = stringResource(if (copied) R.string.cd_copied else R.string.cd_copy, label),
            tint = if (copied) SupplementTheme.statusColors.success.accent else MaterialTheme.colorScheme.primary,
        )
    }
}

enum class NoticeTone { Info, Success, Warning, Danger }

@Composable
private fun NoticeTone.colors(): ToneColors =
    when (this) {
        NoticeTone.Info -> SupplementTheme.statusColors.info
        NoticeTone.Success -> SupplementTheme.statusColors.success
        NoticeTone.Warning -> SupplementTheme.statusColors.warning
        NoticeTone.Danger -> SupplementTheme.statusColors.danger
    }

private fun NoticeTone.defaultIcon(): ImageVector =
    when (this) {
        NoticeTone.Info -> SupplementIcons.Info
        NoticeTone.Success -> SupplementIcons.Success
        NoticeTone.Warning -> SupplementIcons.Warning
        NoticeTone.Danger -> SupplementIcons.Error
    }

/** Inline, tinted message: errors, confirmations, and guidance. */
@Composable
fun NoticeCard(
    message: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    title: String? = null,
    icon: ImageVector = tone.defaultIcon(),
    content: @Composable () -> Unit = {},
) {
    val colors = tone.colors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Row(
            modifier = Modifier.padding(SupplementSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xxs)) {
                title?.let { Text(text = it, style = MaterialTheme.typography.titleSmall) }
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                content()
            }
        }
    }
}

/** Placeholder for lists with nothing to show yet. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SupplementSpacing.Xl, horizontal = SupplementSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        IconBadge(
            icon = icon,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 72.dp,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action()
    }
}

/** One product unit in a list: id badge, name, batch, and lifecycle status. */
@Composable
fun ProductListItem(
    productId: String,
    title: String,
    subtitle: String?,
    status: AuthenticityStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(SupplementSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "#$productId",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = SupplementMonoFamily),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StatusChip(status = status)
        }
    }
}

/** One step of a vertical timeline; the connector joins consecutive entries. */
@Composable
fun TimelineEntry(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Column(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(SupplementSpacing.Sm)
                    .background(if (isFirst) Color.Transparent else line),
            )
            Box(
                Modifier
                    .size(14.dp)
                    .background(dot, CircleShape),
            )
            Box(
                Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (isLast) Color.Transparent else line),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else SupplementSpacing.Md),
        ) {
            Spacer(Modifier.height(SupplementSpacing.Xs))
            content()
        }
    }
}

/** Headline number with a caption, for dashboards. */
@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(SupplementSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xxs),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = value, style = MaterialTheme.typography.headlineMedium)
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Radio-style option card with icon, title, and explanation. */
@Composable
fun SelectableCard(
    selected: Boolean,
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        selected = selected,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(SupplementSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Md),
        ) {
            IconBadge(
                icon = icon,
                containerColor = if (selected) scheme.primary else scheme.surfaceContainerHighest,
                contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                size = 44.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    imageVector = SupplementIcons.Success,
                    contentDescription = null,
                    tint = scheme.primary,
                )
            }
        }
    }
}

/** `0xf39Fd6e5…b92266` style shortening for hashes and addresses. */
fun String.shortenMiddle(head: Int = 6, tail: Int = 4): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"
