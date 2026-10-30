package com.pianotype.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val PRESETS = listOf(
    Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFF5F5F5),
    Color(0xFF121212), Color(0xFF7E57C2), Color(0xFF1E88E5),
    Color(0xFF00897B), Color(0xFF43A047), Color(0xFFF4511E),
    Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF546E7A),
    Color(0xFFFFEB3B), Color(0xFF00ACC1), Color(0xFF8D6E63),
)

@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    var current by remember { mutableStateOf(initial) }
    var hex by remember { mutableStateOf(String.format("#%08X", initial.toArgb())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(current)
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                )

                Text("プリセット", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                val selected = c.toArgb() == current.toArgb()
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                                    else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            current = c
                                            hex = String.format("#%08X", c.toArgb())
                                        }
                                )
                            }
                        }
                    }
                }

                Text("HEX (#AARRGGBB または #RRGGBB)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = hex,
                    onValueChange = { s ->
                        hex = s
                        parseHex(s)?.let { current = it }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current) }) { Text("適用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

private fun parseHex(s: String): Color? {
    val t = s.trim().removePrefix("#")
    return try {
        val v = t.toLong(16)
        when (t.length) {
            6 -> Color(0xFF000000L or v)
            8 -> Color(v)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}