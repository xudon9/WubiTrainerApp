package com.xudong.wubitrainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xudong.wubitrainer.ui.theme.CodeTextStyle
import com.xudong.wubitrainer.ui.theme.PracticePalette

/**
 * The app's shared pieces, built on Material 3 primitives.
 *
 * A few things here are not stock Material — the Wubi key caps and the code rows have no
 * Material equivalent — but they are built *from* `Surface`/`Card`/`FilterChip` so they inherit
 * the theme, the ripple, the corner language and the elevation instead of re-inventing them.
 */

/** A small read-only statistic: value over label. */
@Composable
fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                color = tint,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            // `softWrap = false` is the important part: a squeezed chip must ellipsize, never wrap.
            // A wrapping label grew the chip, which grew its row, which moved the divider below it
            // — the very thing these chips are supposed to be immune to.
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * A single-choice or toggle chip. Material 3 [FilterChip] rather than a hand-rolled pill.
 *
 * `onClick` is deliberately the LAST parameter so the call sites can use trailing-lambda syntax —
 * putting it in the middle silently binds the lambda to whatever follows and fails to compile.
 */
@Composable
fun ToggleChip(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
    )
}

/** A Material 3 [Card] holding a titled group of settings or stats. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // The container tones sit deliberately close to the page in both themes, so without a
        // boundary the cards read as loose floating text. Material 3's own outlined-card border
        // is the intended fix, and it follows `outlineVariant` in either mode.
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                VSpace(8.dp)
            }
            content()
        }
    }
}

/**
 * One key of the on-screen keypad.
 *
 * Built on a clickable [Surface]: a Wubi key is not a Material button, but it should still ripple,
 * clip, dim when disabled and follow the theme like one.
 */
@Composable
fun KeyCap(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    accent: Color? = null,
    enabled: Boolean = true,
) {
    val container = when {
        accent != null -> accent.copy(alpha = if (enabled) 0.16f else 0.06f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        accent != null -> accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One row of a settings group: title (and optional description) on the left, control on the right. */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

/** A stepper for the small integer settings (N and K). */
@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - 1).coerceAtLeast(range.first)) },
            enabled = value > range.first,
            modifier = Modifier.width(48.dp),
        ) { Text("−") }
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceAtMost(range.last)) },
            enabled = value < range.last,
            modifier = Modifier.width(48.dp),
        ) { Text("+") }
    }
}

/** A set of mutually exclusive choices, as Material 3 filter chips. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    perRow: Int = 3,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(perRow).forEach { chunk ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chunk.forEach { option ->
                    FilterChip(
                        // `fill = false` caps each chip at its share of the row without
                        // stretching it, so a long label cannot push the row off screen.
                        modifier = Modifier.weight(1f, fill = false),
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(label(option)) },
                    )
                }
            }
        }
    }
}

/** A monospaced Wubi code with its 一级/二级/三级/全码 label. */
@Composable
fun CodeLine(code: String, label: String, emphasised: Boolean, trailing: String? = null) {
    val tone = if (emphasised) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            code,
            style = CodeTextStyle.copy(fontSize = 22.sp),
            color = tone,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Medium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasised) tone else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A block of monospaced text for the data-source and manifest readouts. */
@Composable
fun MonoBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text.ifBlank { "（未找到该文件）" },
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 10.sp,
            ),
        )
    }
}

/** A thin rule, used to separate the header from the drill area. */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))
