package ir.aut.supplementtracker.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing

enum class SupplementButtonVariant {
    /** The single main action of a screen. */
    Primary,

    /** Secondary actions next to a primary one. */
    Tonal,

    /** Low-emphasis actions. */
    Outlined,

    /** Destructive or alarming actions (e.g. report counterfeit). */
    Danger,
}

@Composable
fun SupplementButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = text,
    variant: SupplementButtonVariant = SupplementButtonVariant.Primary,
    leadingIcon: ImageVector? = null,
    loading: Boolean = false,
    fillWidth: Boolean = true,
) {
    val base = modifier
        .let { if (fillWidth) it.fillMaxWidth() else it }
        .heightIn(min = 52.dp)
        .semantics { this.contentDescription = contentDescription }
    val shape = MaterialTheme.shapes.medium
    val padding = PaddingValues(horizontal = SupplementSpacing.Lg, vertical = SupplementSpacing.Sm)
    val clickable = enabled && !loading
    val content: @Composable RowScope.() -> Unit = {
        ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading)
    }
    when (variant) {
        SupplementButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = clickable,
            modifier = base,
            shape = shape,
            contentPadding = padding,
            content = content,
        )
        SupplementButtonVariant.Tonal -> FilledTonalButton(
            onClick = onClick,
            enabled = clickable,
            modifier = base,
            shape = shape,
            contentPadding = padding,
            content = content,
        )
        SupplementButtonVariant.Outlined -> OutlinedButton(
            onClick = onClick,
            enabled = clickable,
            modifier = base,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            content = content,
        )
        SupplementButtonVariant.Danger -> OutlinedButton(
            onClick = onClick,
            enabled = clickable,
            modifier = base,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(
                1.dp,
                if (clickable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            content = content,
        )
    }
}

@Composable
private fun ButtonContent(
    text: String,
    leadingIcon: ImageVector?,
    loading: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            leadingIcon != null -> Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        if (loading || leadingIcon != null) {
            Spacer(Modifier.width(SupplementSpacing.Xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
