package com.festivalpub.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.festivalpub.admin.data.TableState

object PubColors {
    val Empty = Color(0xFFE3E7EA)
    val InUse = Color(0xFF43A047)
    val Imminent = Color(0xFFFB8C00)
    val Overtime = Color(0xFFE53935)
    val Vip = Color(0xFFFFB300)
    val VipBg = Color(0xFFFFF8E1)

    fun of(state: TableState): Color = when (state) {
        TableState.EMPTY -> Empty
        TableState.IN_USE -> InUse
        TableState.IMMINENT -> Imminent
        TableState.OVERTIME -> Overtime
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3949AB),
            primaryContainer = Color(0xFFDDE1FF),
            secondary = Color(0xFF5C6BC0),
            error = Color(0xFFD32F2F),
        ),
        content = content,
    )
}

/** 숫자 +/- 스테퍼 (터치 최적화) */
@Composable
fun Stepper(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    suffix: String = "",
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(
            onClick = { onChange((value - step).coerceIn(range)) },
            enabled = value > range.first,
            modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("−", fontSize = 22.sp) }
        Text(
            "$value$suffix",
            modifier = Modifier.widthIn(min = 56.dp),
            textAlign = TextAlign.Center,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        FilledTonalButton(
            onClick = { onChange((value + step).coerceIn(range)) },
            enabled = value < range.last,
            modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))
