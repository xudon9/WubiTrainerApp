package com.xudong.wubitrainer.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

import androidx.compose.ui.unit.dp
import com.xudong.wubitrainer.AppController
import com.xudong.wubitrainer.Notice
import com.xudong.wubitrainer.data.Screen

/**
 * The app shell: a Material 3 [Scaffold] with the three destinations in a bottom navigation bar,
 * transient feedback as a Snackbar, and the hardware-keyboard routing.
 *
 * The bottom bar is the only way to move between 练习 / 错题集 / 设置, so the screens themselves
 * carry no navigation — that is what the Material navigation pattern is for.
 *
 * Hardware keys (FR-29) are captured by a *preview* handler on the practice screen's root, so a
 * physical keyboard, an emulator, or a Bluetooth keyboard all drive the drill with no text field
 * and no IME involved. The handler is installed only while 练习 is up: 错题集 has a real text
 * field, and hijacking its keystrokes as drill input would be a bug.
 */
@Composable
fun WubiRoot(app: AppController) {
    val focusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val practiceActive = !app.loading && app.screen == Screen.PRACTICE

    LaunchedEffect(practiceActive, app.current?.char) {
        if (practiceActive) runCatching { focusRequester.requestFocus() }
    }

    val notice = app.notice
    LaunchedEffect(notice?.id) {
        if (notice == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = noticeMessage(notice),
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        app.dismissNotice(notice.id)
    }

    val keyModifier = if (practiceActive) {
        Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { handleKey(app, it) }
    } else {
        Modifier
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().then(keyModifier),
        containerColor = MaterialTheme.colorScheme.background,
        // No TopAppBar. The only thing it held was the destination's name, and the bottom bar
        // already shows which destination is selected — so it was 56 dp of chrome restating the
        // navigation. `contentWindowInsets` still keeps the content clear of the status bar.
        bottomBar = { WubiNavBar(app) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (app.loading) {
                LoadingScreen(app)
            } else {
                when (app.screen) {
                    Screen.PRACTICE -> PracticeScreen(
                        app = app,
                        showKeypad = app.settings.showKeypad,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Screen.MISTAKES -> MistakeScreen(app, Modifier.fillMaxSize())

                    Screen.SETTINGS -> SettingsScreen(app, Modifier.fillMaxSize())
                }
            }
        }
    }
}


@Composable
private fun WubiNavBar(app: AppController) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBarItem(
            selected = app.screen == Screen.PRACTICE,
            onClick = { app.openScreen(Screen.PRACTICE) },
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            label = { Text("练习") },
        )

        val mistakes = app.stats.inMistake
        NavigationBarItem(
            selected = app.screen == Screen.MISTAKES,
            onClick = { app.openScreen(Screen.MISTAKES) },
            icon = {
                if (mistakes > 0) {
                    // The count is worth surfacing where the learner can act on it. Badges are
                    // decorative for accessibility purposes — the label already says 错题集, and
                    // the practice header carries the number as text.
                    BadgedBox(badge = { Badge { Text("$mistakes") } }) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                    }
                } else {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                }
            },
            label = { Text("错题集") },
        )

        NavigationBarItem(
            selected = app.screen == Screen.SETTINGS,
            onClick = { app.openScreen(Screen.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("设置") },
        )
    }
}

@Composable
private fun LoadingScreen(app: AppController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(app.loadingMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Glyph + words, never colour alone. The Snackbar keeps Material's own container colours so it
 * reads correctly in both themes; the tone is carried by the leading mark and by the message.
 */
private fun noticeMessage(notice: Notice): String = when (notice.tone) {
    Notice.Tone.GOOD -> "✓ ${notice.text}"
    Notice.Tone.BAD -> "⚠ ${notice.text}"
    Notice.Tone.INFO -> notice.text
}

/**
 * Route one key event to the drill.
 *
 * `z` is the reveal shortcut unless the character on screen actually has a code containing `z`
 * (only 6 characters in the pool do), in which case it is ordinary input (FR-27). `;` is never a
 * Wubi letter, so it can always open the explanation page.
 */
private fun handleKey(app: AppController, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    when (event.key) {
        Key.Enter, Key.NumPadEnter -> {
            app.pressEnter()
            return true
        }

        Key.Backspace, Key.Delete -> {
            app.backspace()
            return true
        }

        Key.Escape -> {
            app.clearBuffer()
            return true
        }
    }

    val codePoint = event.utf16CodePoint
    if (codePoint == 0) return false
    val char = codePoint.toChar()

    if (char.lowercaseChar() in 'a'..'z') {
        val letter = char.lowercaseChar()
        if (letter == 'z' && app.zIsShortcut()) {
            app.toggleReveal()
        } else {
            app.typeLetter(letter)
        }
        return true
    }

    if (char == ';') {
        app.openExplanation()
        return true
    }

    return false
}
