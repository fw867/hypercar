package io.github.hypercar.carui

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Display

/** Observed on installed XiaoAi 8.2.70; explicit user action only, not a stable SDK contract. */
internal object XiaoAiTextBridge {
    fun send(context: Context, text: String) {
        require(text.isNotBlank() && text.length <= 300) { "识别文字为空或过长" }
        val component = ComponentName("com.miui.voiceassist", "com.xiaomi.voiceassistant.ShareReceiverActivity")
        val info = context.packageManager.getActivityInfo(component, 0)
        check(info.enabled && info.exported && info.applicationInfo.enabled) { "当前小爱文本入口不可用" }
        check(info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED) {
            "当前小爱文本入口需要本应用没有的权限"
        }
        val intent = Intent("com.miui.voiceassist.action.PUSH_QUERY")
            .setComponent(component).setType("text/plain").putExtra("assist_query", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Keep our own D1 subtitle visible; open the phone assistant on the phone display.
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        context.applicationContext.startActivity(intent, options)
    }
}
