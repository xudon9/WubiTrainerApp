package com.xudong.wubitrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xudong.wubitrainer.AppController
import com.xudong.wubitrainer.data.AcceptMode
import com.xudong.wubitrainer.data.CharEntry
import com.xudong.wubitrainer.data.POOL_SIZE_OPTIONS
import com.xudong.wubitrainer.data.SessionMode
import com.xudong.wubitrainer.engine.PrefixState
import com.xudong.wubitrainer.engine.WrongReason
import com.xudong.wubitrainer.ui.theme.CodeTextStyle
import com.xudong.wubitrainer.ui.theme.GlyphFont
import com.xudong.wubitrainer.ui.theme.PracticePalette
import com.xudong.wubitrainer.ui.theme.family
import kotlinx.coroutines.delay

/**
 * Width of each of the two drill slots, as a fraction of the screen.
 *
 * Two thirds, one aligned to each side, is what puts the character's centre on the 1/3 line and
 * the encoding table's centre on the 2/3 line: a container centred within a two-thirds span has
 * its centre exactly one third in from that span's far edge. The slots therefore overlap in the
 * middle third, which is harmless because the character is capped away from the table.
 */
private const val THIRDS_SLOT_FRACTION = 2f / 3f

/**
 * Half of the encoding table plus a gap.
 *
 * Reserved on each side of the character so the two overlapping slots can never collide — the
 * table is about 105 dp wide, and this leaves a visible gap on top of that.
 */
private val GLYPH_SIDE_RESERVE = 64.dp

/** Font size of an encoding in the right-hand column. */
private const val CODE_FONT_SP = 22f

/**
 * Width of the code cell, in ems of [CODE_FONT_SP].
 *
 * Four monospace letters plus [CodeTextStyle]'s 2 sp letter spacing is about 2.8 em, and a
 * fixed cell is what keeps the codes in one column.
 */
private const val CODE_CELL_EM = 2.8f

/** Font size of an encoding's 一级/二级/三级/全码 label, in the left-hand column. */
private const val LABEL_FONT_SP = 11f

/** Width of the label cell, in ems of [LABEL_FONT_SP]. Four characters: 一级简码. */
private const val LABEL_CELL_EM = 4.4f

/** Gap between the label cell and the code cell. */
private val LABEL_CODE_GAP = 10.dp

/**
 * The practice screen (PRD §8.1).
 *
 * Layout, top to bottom: the counters and mode chips, the drill area (the character, the
 * hidden-or-revealed encoding, the input buffer), then the keypad.
 *
 * The character area is replaced — never merely overlaid — by the correction card after a
 * mistake, so the learner cannot keep typing against a stale prompt.
 */
@Composable
fun PracticeScreen(
    app: AppController,
    showKeypad: Boolean,
    modifier: Modifier = Modifier,
) {
    // Measure the window rather than trusting a configuration qualifier: this is the height the
    // layout actually has, and it is what decides whether the keypad and the headline shrink.
    BoxWithConstraints(modifier.fillMaxSize()) {
        PracticeBody(app = app, showKeypad = showKeypad, compact = maxHeight < 560.dp)
    }
}

@Composable
private fun PracticeBody(app: AppController, showKeypad: Boolean, compact: Boolean) {


    Column(Modifier.fillMaxSize()) {
        PracticeHeader(app)
        ThinDivider()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                app.pendingWrong != null -> CorrectionCard(app, compact, showKeypad)
                app.current == null -> CompletionCard(app)
                else -> DrillArea(app, compact)
            }
        }
        if (showKeypad) {
            Keypad(app, compact, enabled = app.current != null)
        } else {
            VSpace(6.dp)
            Text(
                "屏幕键盘已隐藏（设置里可打开）· 使用实体键盘输入，Z 显示答案，; 查看解释，Enter 提交",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            VSpace(6.dp)
        }
    }
}

/**
 * Counters and mode controls. The counters live here, in the header, where they were — the bottom
 * navigation bar belongs to navigation, and mixing the two makes both harder to find.
 */
@Composable
private fun PracticeHeader(app: AppController) {
    val slice = app.poolSize.coerceAtMost(app.settings.poolSize)
    // Watch the controller's flash counter and time the pop out here, so the controller stays a
    // plain state holder with no clock of its own.
    var shown by remember { mutableStateOf<AppController.AnswerFlash?>(null) }
    LaunchedEffect(app.answerFlash?.seq) {
        val flash = app.answerFlash ?: return@LaunchedEffect
        shown = flash
        delay(1500)
        shown = null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleChip("常规", app.mode == SessionMode.REGULAR) { app.switchMode(SessionMode.REGULAR) }
            Spacer(Modifier.width(6.dp))
            ToggleChip("错题", app.mode == SessionMode.MISTAKE) { app.switchMode(SessionMode.MISTAKE) }
            Spacer(Modifier.weight(1f))
            // Strict mode belongs with the mode chips: it changes how every answer is judged.
            ToggleChip("仅全码", app.settings.fullCodeOnly) { app.toggleFullCodeOnly() }
        }
        VSpace(6.dp)
        // The row's height is taken from the CHIPS, not from the pill: `IntrinsicSize.Min` sizes it
        // to the tallest minimum intrinsic height among the children, and the chips are the taller,
        // permanent element. The pill is put in a `fillMaxHeight` box instead of contributing its
        // own height, so it cannot grow the row and push the divider down — while the chips keep
        // their full height and are never clipped.
        //
        // The earlier attempt hard-coded the row to the PILL's height, which was simply wrong: it
        // was shorter than the chips and cut their bottom line off.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The pill takes a WEIGHTED slot, so the chips are measured first at their natural
            // width and the pill gets only what is left over. The other way round — pill first,
            // then a weighted spacer — let the pill squeeze the chips, and 本轮正确率 wrapped onto
            // two lines, which grew the chip, the row, and the divider below it.
            Box(
                Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                AnswerPill(shown)
            }
            StatChip("已通过", "${app.stats.passed}/$slice")
            StatChip(
                "错题",
                "${app.stats.inMistake}",
                tint = if (app.stats.inMistake > 0) PracticePalette.wrong else MaterialTheme.colorScheme.primary,
            )
            StatChip("本轮正确率", "${(app.session.accuracy * 100).toInt()}%")
        }

    }
}

/** Type size of the transient answer pill. Big on purpose — see [AnswerPill]. */
private const val PILL_TEXT_SP = 18f

/**
 * The transient answer pill (FR-35).
 *
 * Big and bold on purpose: this is the only confirmation the learner gets that an answer landed,
 * and it has to be readable at a glance while their eyes are down on the keypad. It says the three
 * things worth interrupting for — correct, passed, and **wrong** — and takes its colour from which
 * one it is.
 */
@Composable
private fun AnswerPill(flash: AppController.AnswerFlash?) {
    if (flash == null) return
    val tint = if (flash.good) PracticePalette.correct else PracticePalette.wrong
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 3.dp),
    ) {
        Text(
            flash.text,
            fontSize = PILL_TEXT_SP.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The drill: the character owns the upper area, the input sits **directly on top of the keypad**.
 *
 * That split is the point. The buffer is the thing you watch while typing and the keypad is the
 * thing you touch, so putting them next to each other keeps both inside one field of view — the
 * earlier arrangement had the buffer stranded in the middle of the screen, a thumb's length away
 * from the keys it belonged to.
 */
@Composable
private fun DrillArea(app: AppController, compact: Boolean) {
    val entry = app.current ?: return
    Column(Modifier.fillMaxSize()) {
        // No horizontal padding here on purpose: the two slots below are sized as fractions of
        // the full screen, so the character's centre lands on the 1/3 line and the encoding
        // table's centre lands on the 2/3 line, measured from the screen — not from an inset box.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Both caps come from the LAYOUT only — never from whether the answer is showing.
            // That is what keeps the character's position and size identical across a `z` press.
            val density = LocalDensity.current
            val heightCapSp = with(density) { maxHeight.toSp().value } * 0.9f
            // The two slots overlap in the middle third, so the character's width is capped to
            // keep clear of the table: half a table plus a gap, reserved on each side of it.
            val widthCapSp = with(density) {
                (maxWidth * THIRDS_SLOT_FRACTION - GLYPH_SIDE_RESERVE * 2).toSp().value
            }

            Box(Modifier.fillMaxSize()) {
                // Centred in the LEFT two thirds → its centre sits on the 1/3 line.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(THIRDS_SLOT_FRACTION),
                    contentAlignment = Alignment.Center,
                ) {
                    CharacterGlyph(
                        entry.char,
                        base = if (compact) 68f else 132f,
                        minSize = if (compact) 44f else 88f,
                        capSp = minOf(heightCapSp, widthCapSp),
                        font = app.settings.glyphFont,
                    )
                }
                // Centred in the RIGHT two thirds → its centre sits on the 2/3 line.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(THIRDS_SLOT_FRACTION),
                    contentAlignment = Alignment.Center,
                ) {
                    AnswerPanel(app = app, entry = entry)
                }
            }
        }
        VSpace(8.dp)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            InputBuffer(app)
            VSpace(4.dp)
            // The two "I can't do this one" actions sit beside the tally rather than in a row of
            // their own: the tally line was already short text with dead space to its right, so
            // this costs about 20 dp instead of the ~48 dp a new row would.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TallyLine(app, entry)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { app.skipCurrent() }) { Text("跳过") }
                TextButton(onClick = { app.addCurrentToMistakeSet() }) { Text("加入错题集") }
            }
        }
        VSpace(6.dp)
    }
}

/** The headline character, sized within a clamp so an extreme font scale cannot break the layout. */
@Composable
private fun CharacterGlyph(
    char: String,
    base: Float,
    minSize: Float,
    /** Never exceed this sp size, even if [base] would — i.e. the room actually available. */
    capSp: Float = Float.MAX_VALUE,
    font: GlyphFont = GlyphFont.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val size = (base * density.fontScale)
        .coerceIn(minSize, 210f)
        .coerceAtMost(capSp)
        // An absolute floor, so a cramped layout shrinks the headline rather than deleting it.
        .coerceAtLeast(24f)
        .sp
    Text(
        char,
        fontSize = size,
        lineHeight = (size.value * 1.05f).sp,
        textAlign = TextAlign.Center,
        fontFamily = font.family(),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}


/**
 * The answer, hidden by default (FR-02).
 *
 * It sits to the **right** of the character, one encoding per line, rather than underneath it.
 * That is not only a look: a strip below the glyph is a strip the glyph's box has to compete
 * with, so revealing the answer changed the box's height, which changed the glyph's size cap, and
 * the character visibly shrank and drifted the moment `z` was pressed. Beside the character, the
 * column grows into space the character was never using.
 *
 * Hidden, it shows only how *many* codes exist and which key reveals them — never a letter, so the
 * hidden state leaks nothing.
 */
@Composable
private fun AnswerPanel(app: AppController, entry: CharEntry, modifier: Modifier = Modifier) {
    val codes = entry.allowedCodes(app.settings.acceptScope)
    Column(modifier, horizontalAlignment = Alignment.Start) {
        if (!app.revealed) {
            Text(
                "👁 ${codes.size} 个编码",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
            if (app.settings.revealShortcut && !entry.hasZCode) {
                Text(
                    "（按 Z 显示）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
            return
        }
        // One line per encoding, each carrying its own 一级/二级/三级/全码 label — so the old
        // "加粗为全码 …" footnote is redundant and no longer needed.
        //
        // Two fixed-width cells per line: the label (RIGHT-aligned in its cell) then the code
        // (LEFT-aligned in its), so both columns line up:
        //
        //     二级简码  qd
        //     三级简码  qdo
        //         全码  qdou
        //
        // Both cells are sized for the widest case (four label characters, four monospace letters)
        // rather than for this character's own codes. That costs a little trailing space when the
        // longest code is three letters, and buys a grid whose x positions never change as the
        // drill moves from one character to the next.
        val density = LocalDensity.current
        val labelCellWidth = with(density) { (LABEL_FONT_SP * LABEL_CELL_EM).sp.toDp() }
        val codeCellWidth = with(density) { (CODE_FONT_SP * CODE_CELL_EM).sp.toDp() }
        Column(horizontalAlignment = Alignment.Start) {
            codes.forEach { code ->
                val isFull = entry.isFullCode(code)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.labelOf(code),
                        modifier = Modifier.width(labelCellWidth),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = LABEL_FONT_SP.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                    Spacer(Modifier.width(LABEL_CODE_GAP))
                    Text(
                        code,
                        modifier = Modifier.width(codeCellWidth),
                        style = CodeTextStyle.copy(fontSize = CODE_FONT_SP.sp),
                        fontWeight = if (isFull) FontWeight.Bold else FontWeight.Normal,
                        color = if (isFull) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBuffer(app: AppController) {
    val state = app.prefixState
    val tone = when (state) {
        PrefixState.ACCEPTABLE -> PracticePalette.correct
        PrefixState.DEAD -> PracticePalette.wrong
        PrefixState.VIABLE -> MaterialTheme.colorScheme.primary
        PrefixState.EMPTY -> MaterialTheme.colorScheme.outline
    }
    val width = if (state == PrefixState.DEAD) 2.dp else 1.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(200.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(width, tone, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (app.buffer.isEmpty()) {
                Text(
                    "输入编码 a–z",
                    style = MaterialTheme.typography.bodyMedium,
                    // onSurfaceVariant rather than outline: a hint sitting on the input's own
                    // surface needs to clear contrast in both themes, not just mark a border.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    app.buffer,
                    style = CodeTextStyle.copy(fontSize = 28.sp),
                    color = if (state == PrefixState.DEAD) PracticePalette.wrong else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        VSpace(6.dp)
        Text(
            text = when (state) {
                PrefixState.EMPTY -> "等待输入"
                PrefixState.VIABLE -> "继续输入…"
                PrefixState.ACCEPTABLE ->
                    if (app.settings.acceptMode == AcceptMode.ON_THE_FLY) "正确！"
                    else "可以提交（按 Enter）"

                PrefixState.DEAD -> "这串字母不可能是任何编码"
            },
            style = MaterialTheme.typography.labelMedium,
            color = tone,
        )
    }
}


/**
 * The character's own tally (FR-30).
 *
 * It used to alternate with a transient "✓ 正确" badge, which meant the tally vanished on every
 * correct answer and the confirmation lived down here by the keypad while "已通过" appeared at
 * the bottom of the screen. The tally now always shows the tally; both confirmations are announced
 * in the single place built for them, under the stats bar.
 */
@Composable
private fun TallyLine(app: AppController, entry: CharEntry) {
    val progress = app.progressFor(entry.char)
    Text(
        "本题 ${progress.correct}/${app.settings.passCount}　错 ${progress.wrong} 次",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** FR-07: the correction card. Nothing advances until the learner acknowledges it. */
@Composable
private fun CorrectionCard(app: AppController, compact: Boolean, showKeypad: Boolean) {
    val wrong = app.pendingWrong ?: return
    val entry = wrong.entry
    // Show every code the character has, not only the accepted ones, so a learner in 仅全码 mode
    // still sees the short codes they will meet in a real IME.
    val accepted = wrong.allowed.toSet()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CharacterGlyph(
            entry.char,
            base = if (compact) 52f else 96f,
            minSize = 40f,
            font = app.settings.glyphFont,
        )
        VSpace(10.dp)
        // No "❌ 正确编码" heading: the wrong-answer signal is the pill in the header now, and each
        // encoding below already carries its own 一级/二级/三级/全码 label.
        SectionCard {
            entry.codes.forEach { code ->
                CodeLine(
                    code = code,
                    label = entry.labelOf(code),
                    emphasised = entry.isFullCode(code),
                    trailing = if (code in accepted) null else "仅全码模式下需要",
                )
            }
            VSpace(6.dp)
            Text(
                when (wrong.reason) {
                    WrongReason.DEAD_PREFIX -> "你输入了 “${wrong.input}”，这已经不可能组成任何编码"
                    WrongReason.NOT_A_CODE ->
                        if (wrong.input.isEmpty()) "没有输入内容" else "你输入了 “${wrong.input}”"
                },
                style = MaterialTheme.typography.labelMedium,
                color = PracticePalette.wrong,
            )
            // No action buttons here while the keypad is showing: its `；解释` and `⏎ 下一个` keys do
            // exactly these two things, one row below, and the Enter key's label already switches
            // to 下一个 for this state. Duplicating them put two identical targets on one screen.
            //
            // They are kept for the keypad-hidden case, because then nothing else on screen can
            // acknowledge the card — the app has no text field, so its only touch affordances ARE
            // the keypad.
            if (!showKeypad) {
                VSpace(12.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (app.settings.explainShortcut) {
                        OutlinedButton(onClick = { app.openExplanation() }) { Text("；查看编码解释") }
                    }
                    Button(onClick = { app.acknowledgeWrong() }) { Text("⏎ 下一个") }
                }
            }
        }
    }
}

/** Everything in the active range is passed, or the 错题集 has been cleared. */
@Composable
private fun CompletionCard(app: AppController) {
    val isMistake = app.mode == SessionMode.MISTAKE
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (isMistake) "🎉 错题集已清空" else "🎉 全部通过",
            style = MaterialTheme.typography.headlineSmall,
        )
        VSpace(8.dp)
        Text(
            if (isMistake) {
                "错题集里的字都达标了，可以回到常规练习继续新字。"
            } else {
                "当前范围内的 ${app.poolSize.coerceAtMost(app.settings.poolSize)} 个字都练完了。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        VSpace(16.dp)
        if (!isMistake) {
            val currentIndex = POOL_SIZE_OPTIONS.indexOfFirst { it.first == app.settings.poolSize }
            val next = POOL_SIZE_OPTIONS.getOrNull(currentIndex + 1)
            if (next != null) {
                FilledTonalButton(
                    onClick = {
                        app.updateSettings(app.settings.copy(poolSize = next.first))
                        app.restartSession()
                    },
                ) { Text("扩大范围到${next.second}") }
                VSpace(10.dp)
            }
            OutlinedButton(onClick = { app.resetPassed() }) { Text("重置“已通过”记录") }
            VSpace(10.dp)
        }
        OutlinedButton(onClick = { app.switchMode(SessionMode.REGULAR) }) { Text("返回常规练习") }
    }
}

/**
 * The on-screen keypad.
 *
 * The letter arrangement mirrors the physical keyboard the learner will actually type on, and `z`
 * doubles as the reveal key — except for the handful of characters whose codes contain a `z`,
 * where it types normally instead.
 */
@Composable
private fun Keypad(app: AppController, compact: Boolean, enabled: Boolean) {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    // The learner picks the key size (设置 → 键盘高度); the row gap follows it so a tall keypad
    // does not end up with cramped rows or a short one with airy ones.
    val keyHeight = app.settings.keypadHeightDp.dp
    val rowGap = (app.settings.keypadHeightDp * 0.13f).dp
    val lettersEnabled = enabled && app.pendingWrong == null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        rows.forEachIndexed { rowIndex, letters ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The third row carries the backspace key, so it is inset a little.
                if (rowIndex == 2) Spacer(Modifier.width(12.dp))
                letters.forEach { letter ->
                    val isZ = letter == 'z'
                    val zShortcut = isZ && app.zIsShortcut()
                    KeyCap(
                        label = if (zShortcut) "z 👁" else "$letter",
                        onClick = {
                            if (zShortcut) app.toggleReveal() else app.typeLetter(letter)
                        },
                        height = keyHeight,
                        accent = if (zShortcut) MaterialTheme.colorScheme.primary else null,
                        enabled = lettersEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowIndex == 2) {
                    KeyCap(
                        label = "⌫",
                        onClick = { app.backspace() },
                        height = keyHeight,
                        enabled = lettersEnabled && app.buffer.isNotEmpty(),
                        modifier = Modifier
                            .weight(1.2f)
                            .semantics { contentDescription = "退格" },
                    )
                }
            }
        }
        Spacer(Modifier.height(rowGap))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyCap(
                label = "；解释",
                onClick = { app.openExplanation() },
                height = keyHeight,
                enabled = app.settings.explainShortcut,
                modifier = Modifier.weight(1.4f),
            )
            KeyCap(
                label = "清空",
                onClick = { app.clearBuffer() },
                height = keyHeight,
                enabled = lettersEnabled && app.buffer.isNotEmpty(),
                modifier = Modifier.weight(1.2f),
            )
            KeyCap(
                label = when {
                    app.pendingWrong != null -> "⏎ 下一个"
                    app.settings.acceptMode == AcceptMode.ON_ENTER -> "⏎ 提交"
                    else -> "⏎"
                },
                onClick = { app.pressEnter() },
                height = keyHeight,
                accent = MaterialTheme.colorScheme.primary,
                enabled = enabled,
                modifier = Modifier.weight(2f),
            )
        }
    }
}
