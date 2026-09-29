package com.xudong.wubitrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xudong.wubitrainer.AppController
import com.xudong.wubitrainer.data.AcceptMode
import com.xudong.wubitrainer.data.AcceptScope
import com.xudong.wubitrainer.data.FreqSource
import com.xudong.wubitrainer.data.KEYPAD_HEIGHT_OPTIONS
import com.xudong.wubitrainer.data.POOL_SIZE_OPTIONS
import com.xudong.wubitrainer.data.keypadHeightLabel
import com.xudong.wubitrainer.ui.theme.GlyphFont
import com.xudong.wubitrainer.ui.theme.PracticePalette
import com.xudong.wubitrainer.ui.theme.family

/** 设置 (PRD §7) plus the progress and about sections. */
@Composable
fun SettingsScreen(app: AppController, modifier: Modifier = Modifier) {
    var confirmReset by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var attribution by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf("") }

    LaunchedEffect(showAbout) {
        if (showAbout && attribution.isEmpty()) {
            attribution = app.readAttribution()
            manifest = app.readManifest()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(title = "练习规则") {
            SettingRow(
                "通过所需正确次数 N",
                "同一个字独立答对 N 次即通过；通过前答错一次，次数清零",
            ) { Stepper(app.settings.passCount, 1..10) { app.updateSettings(app.settings.copy(passCount = it)) } }

            SettingRow(
                "错题移出所需正确次数 K",
                "错题集中独立答对 K 次后自动移出；该字通过时也会立即移出；期间答错则重新计数",
            ) { Stepper(app.settings.mistakeClearCount, 1..10) { app.updateSettings(app.settings.copy(mistakeClearCount = it)) } }

            VSpace(6.dp)
            Text("判定方式", style = MaterialTheme.typography.bodyMedium)
            VSpace(4.dp)
            ChoiceRow(
                options = listOf(AcceptMode.ON_THE_FLY, AcceptMode.ON_ENTER),
                selected = app.settings.acceptMode,
                label = { if (it == AcceptMode.ON_THE_FLY) "即时判定" else "回车判定" },
                onSelect = { app.updateSettings(app.settings.copy(acceptMode = it)) },
                perRow = 2,
            )
            VSpace(4.dp)
            Text(
                if (app.settings.acceptMode == AcceptMode.ON_THE_FLY) {
                    "输入一变成合法编码就立即接受并出下一题"
                } else {
                    "输入不会自动提交，按 Enter 才判定"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VSpace(10.dp)
            Text("答案接受范围", style = MaterialTheme.typography.bodyMedium)
            VSpace(4.dp)
            ChoiceRow(
                options = listOf(AcceptScope.ANY_CODE, AcceptScope.EXCLUDE_LEVEL_ONE, AcceptScope.FULL_CODE_ONLY),
                selected = app.settings.acceptScope,
                label = {
                    when (it) {
                        AcceptScope.ANY_CODE -> "任意编码"
                        AcceptScope.EXCLUDE_LEVEL_ONE -> "排除一级简码"
                        AcceptScope.FULL_CODE_ONLY -> "仅全码"
                    }
                },
                onSelect = { app.updateSettings(app.settings.withAcceptScope(it)) },
            )
            VSpace(4.dp)
            Text(
                when (app.settings.acceptScope) {
                    AcceptScope.ANY_CODE -> "简码和全码都算对（例如 工：a / aaa / aaaa）"
                    AcceptScope.EXCLUDE_LEVEL_ONE -> "一级简码不算对（工：aaa / aaaa）"
                    AcceptScope.FULL_CODE_ONLY -> "只有最长的编码算对（工：aaaa；了：bnh）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VSpace(10.dp)
            SettingRow("不可能的输入立即判错", "输入无法继续组成任何编码时立刻判错，不必等 Enter") {
                Switch(
                    checked = app.settings.failOnDeadPrefix,
                    onCheckedChange = { app.updateSettings(app.settings.copy(failOnDeadPrefix = it)) },
                )
            }
        }

        SectionCard(title = "出题范围与抽样") {
            VSpace(2.dp)
            Text("练习范围", style = MaterialTheme.typography.bodyMedium)
            VSpace(4.dp)
            ChoiceRow(
                options = POOL_SIZE_OPTIONS.map { it.first },
                selected = POOL_SIZE_OPTIONS.firstOrNull { it.first == app.settings.poolSize }?.first
                    ?: app.settings.poolSize,
                label = { size -> POOL_SIZE_OPTIONS.firstOrNull { it.first == size }?.second ?: "$size" },
                onSelect = {
                    app.updateSettings(app.settings.copy(poolSize = it))
                    app.restartSession()
                },
                perRow = 3,
            )
            VSpace(4.dp)
            Text(
                "字表共 ${app.poolSize} 个字可用；已通过的字不再出现",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VSpace(10.dp)
            Text("字频表", style = MaterialTheme.typography.bodyMedium)
            VSpace(4.dp)
            ChoiceRow(
                options = listOf(FreqSource.MODERN, FreqSource.OVERALL),
                selected = app.settings.freqSource,
                label = { if (it == FreqSource.MODERN) "现代汉语（默认）" else "网站默认表" },
                onSelect = { app.updateSettings(app.settings.copy(freqSource = it)) },
                perRow = 2,
            )
            VSpace(4.dp)
            Text(
                "现代表 9,933 字（榜首 的）；网站默认表 11,115 字（榜首 之，语料更偏书面）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            VSpace(10.dp)
            Text("抽样权重", style = MaterialTheme.typography.bodyMedium)
            VSpace(4.dp)
            ChoiceRow(
                options = listOf(1.0, 0.5, 0.0),
                selected = app.settings.weightAlpha,
                label = { if (it == 1.0) "纯字频" else if (it == 0.5) "缓和（√）" else "均匀" },
                onSelect = { app.updateSettings(app.settings.copy(weightAlpha = it)) },
            )
            VSpace(4.dp)
            Text(
                "高频字出现更多，但不会淹没低频字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "汉字显示") {
            Text("字体", style = MaterialTheme.typography.bodyMedium)
            VSpace(4.dp)
            ChoiceRow(
                options = GlyphFont.entries,
                selected = app.settings.glyphFont,
                label = { it.labelZh },
                onSelect = { app.updateSettings(app.settings.copy(glyphFont = it)) },
            )
            VSpace(8.dp)
            // A live sample, in the chosen face. Android resolves CJK through its own fallback
            // chain, so whether these options differ from one another is the device's answer —
            // showing it beats describing it. 永 is the traditional type-specimen character:
            // it is built from the eight basic strokes.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "永",
                    fontSize = 56.sp,
                    fontFamily = app.settings.glyphFont.family(),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "字体由系统字体链决定；如果本机没有相应的中文字体，它看起来会和「默认」一样。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard(title = "输入与快捷键") {
            SettingRow("屏幕键盘", "在手机上使用；只用实体键盘时可以关掉") {
                Switch(
                    checked = app.settings.showKeypad,
                    onCheckedChange = { app.updateSettings(app.settings.copy(showKeypad = it)) },
                )
            }
            VSpace(6.dp)
            Text("键盘高度", style = MaterialTheme.typography.bodyMedium)
            VSpace(4.dp)
            ChoiceRow(
                options = KEYPAD_HEIGHT_OPTIONS.map { it.first },
                selected = KEYPAD_HEIGHT_OPTIONS.firstOrNull { it.first == app.settings.keypadHeightDp }?.first
                    ?: app.settings.keypadHeightDp,
                label = { dp -> keypadHeightLabel(dp) },
                onSelect = { app.updateSettings(app.settings.copy(keypadHeightDp = it)) },
                perRow = 4,
            )
            VSpace(4.dp)
            Text(
                if (app.settings.showKeypad) {
                    "当前 ${keypadHeightLabel(app.settings.keypadHeightDp)}（${app.settings.keypadHeightDp} dp）· 设置页看不到效果，回练习页即可"
                } else {
                    "屏幕键盘已关闭，此项暂不生效"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingRow("Z 显示答案", "五笔编码几乎不用 z，因此 z 用作显示/隐藏答案") {
                Switch(
                    checked = app.settings.revealShortcut,
                    onCheckedChange = { app.updateSettings(app.settings.copy(revealShortcut = it)) },
                )
            }
            SettingRow("; 打开编码解释", "在浏览器打开 search-wubi 的解释页") {
                Switch(
                    checked = app.settings.explainShortcut,
                    onCheckedChange = { app.updateSettings(app.settings.copy(explainShortcut = it)) },
                )
            }
        }

        SectionCard(title = "统计") {
            val s = app.stats
            StatLine("练过的字", "${s.distinctPractised}")
            StatLine("答题总数", "${s.totalAnswers}")
            StatLine("总正确率", "${(s.accuracy * 100).toInt()}%")
            StatLine("已通过", "${s.passed}")
            StatLine("错题集", "${s.inMistake}")
            StatLine("本轮", "${app.session.answered} 题，正确 ${app.session.correct}")
        }

        SectionCard(title = "进度文件") {
            Text(
                app.progressFilePath,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(6.dp)
            Text(
                "单一的 TSV 文件，随时可关闭应用；写入采用“临时文件 + 原子改名 + 备份”。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { app.exportProgress()?.let { app.startShare(it) } }) {
                    Text("导出进度 TSV")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { confirmReset = true }) { Text("重置…") }
            }
            if (app.loadWarnings.isNotEmpty()) {
                VSpace(8.dp)
                app.loadWarnings.forEach {
                    Text(
                        "⚠ $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = PracticePalette.caution,
                    )
                }
            }
        }

        SectionCard(title = "关于") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showAbout = !showAbout }) {
                    Text(if (showAbout) "收起数据来源" else "展开数据来源")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "练习池 ${app.poolSize} 字" +
                        if (app.fullTableReady) "，全表 ${app.fullTableSize} 字" else "，全表载入中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showAbout) {
                VSpace(8.dp)
                MonoBlock(attribution)
                VSpace(8.dp)
                MonoBlock(manifest)
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置进度") },
            text = {
                Column {
                    Text(
                        "会先把当前文件保留一份备份，再执行重置。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { app.resetPassed(); confirmReset = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重置“已通过”记录（保留错题集）") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { app.resetMistakes(); confirmReset = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("只清空错题集") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { app.resetEverything(); confirmReset = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("全部重置") }
                }
            },
            confirmButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
