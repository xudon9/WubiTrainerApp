package com.xudong.wubitrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xudong.wubitrainer.AppController
import com.xudong.wubitrainer.data.Screen
import com.xudong.wubitrainer.data.SessionMode
import com.xudong.wubitrainer.ui.theme.CodeTextStyle
import com.xudong.wubitrainer.ui.theme.PracticePalette

/**
 * 错题集 management (FR-20).
 *
 * Characters can be reviewed, removed, or added by hand — the hand-entry box accepts a run of Han
 * characters at once, so a learner can paste a whole line from anywhere, and it reports back
 * exactly which characters were added and which have no Wubi86 code at all.
 */
@Composable
fun MistakeScreen(app: AppController, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }
    val rows = app.mistakeEntries()

    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                "错一次就进来；独立答对 ${app.settings.mistakeClearCount} 次，或该字通过时，自动移出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(8.dp)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("添加汉字（可一次粘贴多个）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!app.fullTableReady) {
                Text(
                    "正在载入完整字表（70,944 字）…，载入前只能添加练习范围内的字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VSpace(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        report = app.addToMistakeSet(input)
                        input = ""
                    },
                ) { Text("添加") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        app.switchMode(SessionMode.MISTAKE)
                        app.openScreen(Screen.PRACTICE)
                    },
                    enabled = rows.isNotEmpty(),
                ) { Text("开始错题练习") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${rows.size} 个字",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            report?.let {
                VSpace(6.dp)
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        ThinDivider()

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "错题集是空的。答错会自动进来，也可以在上面手动添加。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.first.char }) { (progress, entry) ->
                MistakeRow(
                    char = progress.char,
                    codes = entry?.codes ?: emptyList(),
                    fullCodes = entry?.fullCodes ?: emptyList(),
                    wrong = progress.wrong,
                    mistakeCorrect = progress.mistakeCorrect,
                    target = app.settings.mistakeClearCount,
                    passed = progress.passed,
                    onRemove = { app.removeFromMistakeSet(progress.char) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun MistakeRow(
    char: String,
    codes: List<String>,
    fullCodes: List<String>,
    wrong: Int,
    mistakeCorrect: Int,
    target: Int,
    passed: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                char,
                fontSize = 40.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(56.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    codes.joinToString("  "),
                    style = CodeTextStyle.copy(fontSize = 16.sp),
                )
                VSpace(2.dp)
                Text(
                    "全码 ${fullCodes.joinToString(" / ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(2.dp)
                Text(
                    buildString {
                        append("错过 $wrong 次")
                        if (passed) append("　· 已通过")
                        append("　· 移出进度 $mistakeCorrect/$target")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mistakeCorrect > 0) PracticePalette.correct else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRemove) { Text("移除") }
        }
    }
}
