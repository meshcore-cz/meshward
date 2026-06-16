package cz.meshcore.meshward

import android.app.Application
import cz.meshcore.meshward.notify.HeadlessMessageNotifier
import cz.meshcore.sidepath.service.IncomingMessageBridge

class ChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Register the always-resident notification fallback. It posts chat notifications for messages
        // that arrive while the UI (ViewModel) is dead but the foreground mesh service is still alive,
        // so notifications keep coming even when the app's task has been swiped away.
        IncomingMessageBridge.listener = HeadlessMessageNotifier(this)
    }
}
