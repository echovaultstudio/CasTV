package app.castv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.castv.CasApp
import kotlinx.coroutines.launch

/** Opening a backup file from a file manager restores it (after confirming). */
class RestoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        val app = CasApp.get(this)
        setContent {
            CasTheme(uiFrom(app.settings)) {
                val scope = rememberCoroutineScope()
                var status by remember { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                val st = status
                if (st == null) ConfirmDialog(
                    "Restore this backup?", "Your current CasTV data and settings will be replaced, then the app restarts.", "Restore",
                    onDismiss = { finish() },
                ) {
                    status = "Restoring…"
                    scope.launch {
                        runCatching { app.backup.restore(app.backup.readFrom(uri.toString())) }
                            .onSuccess {
                                val i = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(i)
                                Runtime.getRuntime().exit(0)
                            }
                            .onFailure { status = "Restore failed: ${it.message}" }
                    }
                } else TvDialog("Backup", onDismiss = { finish() }) { Label(st, maxLines = 4) }
            }
        }
    }
}
