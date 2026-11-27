package io.github.hypercar.yadea

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.ui.theme.HyperCarTheme
import kotlin.math.min

class YadeaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperCarTheme(mode = SettingsStore.appThemeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    YadeaPage(onBack = ::finish)
                }
            }
        }
    }
}

@Composable
private fun YadeaPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by YadeaProjectionService.sessionState.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf("") }
    var sourceName by rememberSaveable {
        mutableStateOf(if (YadeaSource.CarWith.installed(context)) YadeaSource.CarWith.name else YadeaSource.SystemCarLife.name)
    }
    val source = YadeaSource.valueOf(sourceName)
    var requestedRoute by rememberSaveable { mutableStateOf<Int?>(null) }
    var routeMenu by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf(yadeaAudioOutputs(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refresh() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refresh() }
            private fun refresh() { routes = yadeaAudioOutputs(context) }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("雅迪仪表投屏", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.status, style = MaterialTheme.typography.bodyLarge)
            val frame = state.preview
            val ratio = frame?.let { (it.width.toFloat() / it.height).coerceIn(1f, 2.5f) } ?: 16f / 9f
            AndroidView(
                factory = ::YadeaPreview,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                update = { it.show(frame) },
            )
            if (frame != null) Text("直接在手机预览上点按或拖动。", style = MaterialTheme.typography.bodySmall)
            state.display?.let { display ->
                val origin = if (display.negotiated) "仪表确认" else "本地回退"
                Text("${display.width} × ${display.height} · ${display.fps} fps（$origin）")
            }
            state.requestedSize?.let { (w, h) ->
                val actual = state.sourceSize?.let { "${it.first} × ${it.second}" } ?: "等待来源确认"
                Text("来源请求 $w × $h · 实际 $actual", style = MaterialTheme.typography.bodySmall)
            }
            if (!state.active) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text("仪表 IP 地址") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YadeaSource.entries.forEach { option ->
                        FilterChip(selected = option == source, onClick = { sourceName = option.name },
                            label = { Text(option.title) })
                    }
                }
                if (source == YadeaSource.SystemCarLife) {
                    Text("启动后，在手机车联中打开 CarLife+ 的无线连接。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Box {
                val selectedRoute = if (state.active) state.routeId else requestedRoute
                val routeLabel = if (selectedRoute == null) "跟随系统"
                    else routes.firstOrNull { it.id == selectedRoute }?.label ?: "选定设备已断开"
                TextButton(onClick = { routeMenu = true }) { Text("媒体音频 · $routeLabel") }
                DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }) {
                    DropdownMenuItem(text = { Text("跟随系统") }, onClick = {
                        requestedRoute = null
                        YadeaProjectionService.setRoute(null)
                        routeMenu = false
                    })
                    routes.forEach { route ->
                        DropdownMenuItem(text = { Text(route.label) }, onClick = {
                            requestedRoute = route.id
                            YadeaProjectionService.setRoute(route.id)
                            routeMenu = false
                        })
                    }
                }
            }
            if (state.active) {
                Text(state.route, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { YadeaProjectionService.stop() }) { Text("停止投屏") }
            } else {
                Button(onClick = { YadeaProjectionService.start(context, host, source, requestedRoute) },
                    enabled = host.isNotBlank()) { Text("开始投屏") }
            }
        }
    }
}

private class YadeaPreview(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val content = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var lastMoveAt = 0L

    init { contentDescription = "导航预览，点按或拖动以操作导航" }

    fun show(frame: Bitmap?) {
        if (frame === bitmap) return
        if (frame == null || frame.width != bitmap?.width || frame.height != bitmap?.height) cancelGesture()
        bitmap = frame
        updateContent()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        updateContent()
    }

    private fun updateContent() {
        val frame = bitmap ?: return content.setEmpty()
        val scale = min(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val w = frame.width * scale
        val h = frame.height * scale
        content.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(android.graphics.Color.BLACK)
        bitmap?.let { canvas.drawBitmap(it, null, content, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            if (!content.contains(event.x, event.y) || !send(action, event.x, event.y)) return false
            pointer = event.getPointerId(0)
            lastMoveAt = event.eventTime
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (pointer == MotionEvent.INVALID_POINTER_ID) return false
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelGesture()
            return true
        }
        val index = event.findPointerIndex(pointer)
        if (index < 0) {
            cancelGesture()
            return true
        }
        if (action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) {
            send(MotionEvent.ACTION_UP, event.getX(index), event.getY(index))
            endGesture()
            performClick()
        } else if (action == MotionEvent.ACTION_MOVE && event.eventTime - lastMoveAt >= 16) {
            send(action, event.getX(index), event.getY(index))
            lastMoveAt = event.eventTime
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    private fun send(action: Int, x: Float, y: Float) =
        !content.isEmpty && YadeaProjectionService.touch(action,
            (x - content.left) / content.width(), (y - content.top) / content.height())

    private fun cancelGesture() {
        if (pointer != MotionEvent.INVALID_POINTER_ID) {
            YadeaProjectionService.touch(MotionEvent.ACTION_CANCEL, 0f, 0f)
            endGesture()
        }
    }

    private fun endGesture() {
        pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
