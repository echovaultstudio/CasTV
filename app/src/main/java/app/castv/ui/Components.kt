package app.castv.ui

import android.view.KeyEvent as AKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun isSelectKey(code: Int) = code == AKeyEvent.KEYCODE_DPAD_CENTER || code == AKeyEvent.KEYCODE_ENTER ||
    code == AKeyEvent.KEYCODE_NUMPAD_ENTER || code == AKeyEvent.KEYCODE_BUTTON_A

/**
 * Click with the remote's OK button (short and long press) and with touch. Long press fires once while the button
 * is held; the short click then doesn't fire.
 */
fun Modifier.tvClickable(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val press = remember { KeyPress() }
    val latestClick by rememberUpdatedState(onClick)
    val latestLong by rememberUpdatedState(onLongClick)
    this
        .onPreviewKeyEvent { ev ->
            val code = ev.nativeKeyEvent.keyCode
            if (!isSelectKey(code)) return@onPreviewKeyEvent false
            when (press.handle(code, ev.type == KeyEventType.KeyDown, ev.type == KeyEventType.KeyUp, ev.nativeKeyEvent.repeatCount, latestLong != null)) {
                1 -> latestClick()
                2 -> latestLong?.invoke()
            }
            true
        }
        .clickable(interactionSource = null, indication = null, onClick = onClick)
}

/** A row in menus and settings: highlighted when focused. */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val ui = LocalUi.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) ui.focus else if (selected) ui.selection else Color.Transparent)
            .tvClickable(onLongClick, onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content(focused) }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, size: Int = 16, color: Color = LocalUi.current.text, bold: Boolean = false, maxLines: Int = 1) {
    Text(
        text, modifier = modifier, color = color, fontSize = size.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
fun Dim(text: String, modifier: Modifier = Modifier, size: Int = 13, maxLines: Int = 1) =
    Label(text, modifier, size, LocalUi.current.dim, maxLines = maxLines)

/** A simple progress bar (fraction 0..1). */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, color: Color = LocalUi.current.accent) {
    Box(modifier.height(height).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun CheckMark(on: Boolean) {
    val ui = LocalUi.current
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).border(2.dp, if (on) ui.text else ui.dim, RoundedCornerShape(4.dp))
            .background(if (on) ui.accent else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) { if (on) Label("✓", size = 13, bold = true) }
}

@Composable
fun Switch(on: Boolean) {
    val ui = LocalUi.current
    Box(Modifier.width(38.dp).height(20.dp).clip(CircleShape).background(if (on) ui.accent.copy(alpha = 0.6f) else Color(0x55FFFFFF))) {
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(2.dp).size(16.dp).clip(CircleShape).background(if (on) Color.White else Color(0xFFBBBBBB)))
    }
}

@Composable
fun Logo(url: String, name: String, modifier: Modifier = Modifier.size(width = 56.dp, height = 36.dp)) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
        Label(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(), size = 12, color = LocalUi.current.dim)
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize().padding(2.dp))
    }
}

@Composable
fun Clock(showDate: Boolean, size: Int = 18) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000L * (60 - (System.currentTimeMillis() / 1000) % 60)); now = System.currentTimeMillis() } }
    val f = if (showDate) "EEE d MMM  HH:mm" else "HH:mm"
    Label(SimpleDateFormat(f, Locale.getDefault()).format(Date(now)), size = size, bold = true)
}

fun hm(epoch: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch * 1000))
fun dayLabel(epoch: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = epoch * 1000 }
    val today = java.util.Calendar.getInstance()
    val diff = (c.get(java.util.Calendar.YEAR) - today.get(java.util.Calendar.YEAR)) * 366 +
        (c.get(java.util.Calendar.DAY_OF_YEAR) - today.get(java.util.Calendar.DAY_OF_YEAR))
    return when (diff) {
        0 -> "Today"; 1 -> "Tomorrow"; -1 -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(epoch * 1000))
    }
}
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A panel dialog for the remote. */
@Composable
fun TvDialog(title: String, onDismiss: () -> Unit, width: Dp = 520.dp, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = width).fillMaxWidth(0.9f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF181B21)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title.isNotEmpty()) Label(title, size = 20, bold = true, maxLines = 2)
            content()
        }
    }
}

/** A list of options; the current one is marked. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T?, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    val first = remember { FocusRequester() }
    val start = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val state = rememberLazyListState(start)
    TvDialog(title, onDismiss) {
        LazyColumn(Modifier.heightIn(max = 460.dp), state = state) {
            itemsIndexed(options) { i, (value, label) ->
                FocusRow(selected = value == selected, focusRequester = if (i == start) first else null, onClick = { onPick(value) }) {
                    Label(label, Modifier.weight(1f), maxLines = 2)
                    if (value == selected) Label("✓", bold = true)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Menu of actions. */
@Composable
fun ActionsDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            itemsIndexed(actions) { i, (label, run) ->
                FocusRow(focusRequester = if (i == 0) first else null, onClick = { run(); onDismiss() }) { Label(label) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String = "OK", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        if (message.isNotEmpty()) Dim(message, size = 15, maxLines = 6)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { FocusRow(focusRequester = first, onClick = { onDismiss(); onConfirm() }) { Label(confirm) } }
            Box(Modifier.weight(1f)) { FocusRow(onClick = onDismiss) { Label("Cancel") } }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        if (hint.isNotEmpty()) Dim(hint, maxLines = 4)
        OutlinedTextField(
            value = value, onValueChange = { value = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(field),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDismiss(); onDone(value.trim()) }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { FocusRow(onClick = { onDismiss(); onDone(value.trim()) }) { Label("Save") } }
            Box(Modifier.weight(1f)) { FocusRow(onClick = onDismiss) { Label("Cancel") } }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

/** PIN entry with an on-screen keypad (works with any remote) and the number buttons. */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    fun press(d: String) {
        if (pin.length < 8) pin += d
        if (pin.length == 4) { val p = pin; pin = ""; onPin(p) }
    }
    TvDialog(title, onDismiss, width = 380.dp) {
        Label(if (pin.isEmpty()) "• • • •" else "●".repeat(pin.length), size = 26, bold = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕"))
        Column(
            Modifier.onPreviewKeyEvent { ev ->
                val c = ev.nativeKeyEvent.keyCode
                if (ev.type == KeyEventType.KeyDown && c in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9) { press((c - AKeyEvent.KEYCODE_0).toString()); true } else false
            },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((r, row) in rows.withIndex()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((c, k) in row.withIndex()) Box(Modifier.weight(1f)) {
                    FocusRow(focusRequester = if (r == 0 && c == 0) first else null, onClick = {
                        when (k) { "⌫" -> pin = pin.dropLast(1); "✕" -> onDismiss(); else -> press(k) }
                    }) { Label(k, Modifier.weight(1f), size = 20, bold = true) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Number picker for settings like minutes or seconds. */
@Composable
fun NumberDialog(title: String, value: Int, range: IntRange, step: Int = 1, suffix: String = "", onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val options = (range step step).map { it to "$it$suffix" }
    ChoiceDialog(title, options, value.coerceIn(range), onDismiss, onPick)
}

/** Remembers whether a key long-press fired, for screens that read keys directly. */
/**
 * Press state for one key-reading element. [downCode] is the key that went down here; a key release only counts as a
 * click when its press started on the same element. Without this, the release of a long press that opened a menu
 * lands on the menu's first item and "clicks" it, closing the menu again.
 */
class KeyPress {
    var longFired = false
    var downAt = 0L
    var downCode = -1

    /** Call for every event of a press/long-press key. Returns what to do: 0 nothing, 1 click, 2 long press. */
    fun handle(code: Int, isDown: Boolean, isUp: Boolean, repeat: Int, hasLong: Boolean): Int {
        if (isDown) {
            if (repeat == 0) { downCode = code; longFired = false; return 0 }
            if (downCode == code && !longFired && hasLong) { longFired = true; return 2 }
            return 0
        }
        if (isUp) {
            val click = downCode == code && !longFired
            downCode = -1; longFired = false
            return if (click) 1 else 0
        }
        return 0
    }
}

@Composable
fun rememberCounter() = remember { mutableIntStateOf(0) }
