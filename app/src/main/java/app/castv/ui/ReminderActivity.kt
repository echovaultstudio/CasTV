package app.castv.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.castv.CasApp
import app.castv.MainActivity
import app.castv.data.ReminderEntity
import kotlinx.coroutines.delay

/** Shown over other apps when a reminder is due and CasTV isn't open. */
class ReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val id = intent.getLongExtra("id", 0)
        val app = CasApp.get(this)
        setContent {
            CasTheme(uiFrom(app.settings)) {
                val r by produceState<ReminderEntity?>(null) { value = app.repo.db.activity().reminder(id) }
                var left by remember { mutableIntStateOf(app.settings.reminderPopupSec) }
                val first = remember { FocusRequester() }
                fun watch(rem: ReminderEntity) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("playlistId", rem.playlistId).putExtra("channelKey", rem.channelKey)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    finish()
                }
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    val rem = r
                    Column(Modifier.width(520.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF181B21)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Reminder", size = 14, color = LocalUi.current.accent)
                        Label(rem?.title ?: "…", size = 22, bold = true, maxLines = 2)
                        if (rem != null) Dim("${rem.channelName} · ${hm(rem.start)}", size = 15)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { FocusRow(focusRequester = first, onClick = { rem?.let { watch(it) } }) { Label("Watch") } }
                            Box(Modifier.weight(1f)) { FocusRow(onClick = { finish() }) { Label("Dismiss ($left)") } }
                        }
                    }
                }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                LaunchedEffect(r) {
                    while (left > 0) { delay(1000); left-- }
                    val rem = r
                    if (rem != null && app.settings.reminderDefaultAction == "watch") watch(rem) else finish()
                }
            }
        }
    }
}
