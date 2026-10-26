package io.github.hypercar.spike

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.hypercar.core.RustCoreBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * P0 spike 控制台（SPIKE-P0，调试用、从 adb 显式启动）：
 *
 * ```
 * adb shell am start -n io.github.hypercar/.spike.SpikeConsoleActivity
 * adb shell am start -n io.github.hypercar/.spike.SpikeConsoleActivity --es auto s1
 * ```
 *
 * 为什么用 Activity 而不是广播：VirtualDisplay/编码器都要求进程在前台且不被回收，
 * 而且后台启动 Activity 会被系统拦；直接从 shell 起控制台最省事。
 * 它**不接入**生产导航，P0 结束后可整包删掉（或改成 debuggable 才注册）。
 */
class SpikeConsoleActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private var job: Job? = null
    private val buttons = ArrayList<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 24)
            setBackgroundColor(Color.rgb(6, 10, 18))
        }

        val title = TextView(this).apply {
            text = "HyperCar · P0 Spike 控制台"
            textSize = 22f
            setTextColor(Color.rgb(120, 220, 255))
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        status = TextView(this).apply {
            text = "空闲。S1 建副屏跑高德；S2 注入；S3 编码上屏。"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 12)
        }
        root.addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        root.addView(buttonRow("S1 副屏跑高德") { runTask("S1") { SpikeRunner.s1(this) } })
        root.addView(buttonRow("S2 Shizuku 注入") { runTask("S2") { SpikeRunner.s2(this) } })
        root.addView(buttonRow("S3 编码上屏 120s") { runTask("S3") { SpikeRunner.s3(this, seconds = 120) } })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("D2 快照") { runTask("快照") { SpikeRunner.snapshotNow(this) } }, weight())
        row2.addView(button("触摸自检") { runTask("触摸自检") { SpikeRunner.touchSelfTest() } }, weight())
        root.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("停止/释放") {
            SpikeRunner.stopAll()
            status.text = "已停止并释放 D2/编码器"
            detail.text = SpikeReport.tail(20)
        }, weight())
        row3.addView(button("导出报告") {
            val path = SpikeReport.write(this, "manual")
            status.text = "报告 → $path"
        }, weight())
        root.addView(row3)

        detail = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.rgb(170, 200, 210))
            setPadding(0, 20, 0, 0)
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(
            ScrollView(this).apply { addView(detail) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)

        SpikeReport.add("CONSOLE", "启动 ${io.github.hypercar.session.DeviceInfo.fullSummary(this)} " +
            "SDK_INT=${android.os.Build.VERSION.SDK_INT} " +
            "fingerprint=${android.os.Build.FINGERPRINT} " +
            "rust=${RustCoreBridge.nativeIsRunning()} " +
            "shizuku=${io.github.hypercar.shizuku.ShizukuTools.isReady}")
        detail.text = SpikeReport.tail(20)

        when (intent?.getStringExtra("auto")) {
            "s1" -> runTask("S1") { SpikeRunner.s1(this) }
            "s2" -> runTask("S2") { SpikeRunner.s2(this) }
            "s3" -> runTask("S3") { SpikeRunner.s3(this, seconds = 120) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra("auto")) {
            "s1" -> runTask("S1") { SpikeRunner.s1(this) }
            "s2" -> runTask("S2") { SpikeRunner.s2(this) }
            "s3" -> runTask("S3") { SpikeRunner.s3(this, seconds = 120) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.cancel()
    }

    private fun runTask(label: String, block: suspend () -> String) {
        if (job?.isActive == true) {
            status.text = "另一个任务还在跑（$label 排队中）——先等它结束"
            return
        }
        setBusy(true)
        status.text = "$label 运行中…"
        job = scope.launch {
            val result = runCatching { block() }
                .getOrElse { "异常：${it.javaClass.simpleName}: ${it.message}" }
            status.text = result
            detail.text = SpikeReport.tail(40)
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
    }

    private fun buttonRow(text: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(text, onClick), weight())
        }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setOnClickListener { onClick() }
        buttons.add(this)
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER
            setMargins(4, 4, 4, 4)
        }
}
