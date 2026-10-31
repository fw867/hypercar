package io.github.hypercar.carui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.tencent.qqmusic.third.api.contract.IQQMusicApi
import com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback
import io.github.hypercar.session.HyperCarRepo
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Runs as HyperCar's own identity; no partner credentials or package impersonation. */
internal object QqMusicAdapter {
    const val PACKAGE = "com.tencent.qqmusic"
    data class Song(val id: String, val title: String, val artist: String)
    data class Snapshot(
        val mode: MusicPlaybackMode? = null,
        val songs: List<Song> = emptyList(),
        val loading: Boolean = false,
        val queueError: String = "",
        val modeError: String = "",
        val hasMore: Boolean = false,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var connection: ServiceConnection? = null
    private var api: IQQMusicApi? = null
    private var generation = 0L
    private var page = 0
    private var queueJob: Job? = null
    private var modeJob: Job? = null
    private var changed: (() -> Unit)? = null
    var state = Snapshot()
        private set

    fun select(ctx: Context?, selected: Boolean, onChanged: () -> Unit) {
        if (!selected || ctx == null) { close(); return }
        changed = onChanged
        if (connection != null) return
        context = ctx.applicationContext
        val token = ++generation
        val owner = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (generation != token) return
                api = IQQMusicApi.Stub.asInterface(binder)
                scope.launch {
                    val result = request("hi", Bundle().apply {
                        putInt("sdkVersionCode", 10000); putString("platformType", "phone")
                    })
                    if (generation != token) return@launch
                    val code = code(result)
                    HyperCarRepo.log("QQ-API: hi code=$code")
                    if (code != 0) update(state.copy(queueError = QqMusicProtocol.error(code), modeError = QqMusicProtocol.error(code)))
                    else refreshMode()
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (generation == token) {
                    api = null
                    update(Snapshot(queueError = "QQ音乐连接已断开，请重试", modeError = "QQ音乐连接已断开，请重试"))
                }
            }
            override fun onNullBinding(name: ComponentName) {
                if (generation == token) update(Snapshot(queueError = "QQ音乐服务不可用", modeError = "QQ音乐服务不可用"))
            }
        }
        connection = owner
        val bound = runCatching { context!!.bindService(Intent("com.tencent.qqmusic.third.api.QQMusicApiService")
            .setPackage(PACKAGE).putExtra("packageName", ctx.packageName), owner, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            connection = null
            update(Snapshot(queueError = "无法连接QQ音乐专用服务", modeError = "无法连接QQ音乐专用服务"))
        }
    }

    fun close() {
        if (connection == null && context == null) return
        generation++
        queueJob?.cancel(); modeJob?.cancel()
        connection?.let { runCatching { context?.unbindService(it) } }
        api = null; connection = null; context = null; changed = null; state = Snapshot(); page = 0
    }

    private fun update(next: Snapshot) { state = next; changed?.invoke() }
    private fun code(result: Bundle?): Int = result?.getInt("code", -1) ?: -1
    private suspend fun request(method: String, params: Bundle? = null): Bundle? = withContext(Dispatchers.IO) {
        runCatching { api?.execute(method, params) }.getOrNull()
    }
    private suspend fun requestAsync(method: String, params: Bundle): Bundle? = withTimeoutOrNull(6_000) {
        val result = CompletableDeferred<Bundle?>()
        val remote = api ?: return@withTimeoutOrNull null
        val callback = object : IQQMusicApiCallback.Stub() {
            override fun onReturn(value: Bundle?) { result.complete(value) }
        }
        withContext(Dispatchers.IO) { remote.executeAsync(method, params, callback) }
        result.await()
    }

    fun refreshMode() {
        if (modeJob?.isActive == true) return
        val token = generation
        modeJob = scope.launch {
            val result = request("getPlayMode")
            if (generation != token) return@launch
            val status = code(result)
            val value = if (status == 0) result?.get("data")?.toString()?.toIntOrNull() else null
            val mode = value?.let(QqMusicProtocol::mode)
            HyperCarRepo.log("QQ-API: getPlayMode code=$status mode=${value ?: -1}")
            update(state.copy(mode = mode, modeError = if (status != 0) QqMusicProtocol.error(status)
                else if (mode == null) "QQ音乐返回未知播放模式" else ""))
        }
    }


    fun loadQueue(more: Boolean = false) {
        if (queueJob?.isActive == true || (more && !state.hasMore)) return
        val token = generation
        val targetPage = if (more) page + 1 else 0
        update(state.copy(loading = true, queueError = ""))
        queueJob = scope.launch {
            val result = runCatching { requestAsync("getPlayList", Bundle().apply { putInt("page", targetPage) }) }.getOrNull()
            if (generation != token) return@launch
            val status = code(result)
            HyperCarRepo.log("QQ-API: getPlayList page=$targetPage code=$status")
            if (status != 0) {
                update(state.copy(loading = false, queueError = QqMusicProtocol.error(status))); return@launch
            }
            val songs = runCatching {
                val array = JSONArray(result?.getString("data") ?: "[]")
                (0 until array.length()).map { index ->
                    val song = array.getJSONObject(index)
                    Song(song.optString("id"), song.optString("title"), song.optJSONObject("singer")?.optString("title").orEmpty())
                }.filter { it.id.isNotBlank() }
            }.getOrNull()
            if (songs == null) {
                update(state.copy(loading = false, queueError = "QQ音乐列表数据格式无法识别")); return@launch
            }
            page = targetPage
            val hasMore = result?.get("hasMore")?.let { it == true || it.toString() == "1" } ?: false
            update(state.copy(songs = if (more) (state.songs + songs).distinctBy { it.id } else songs,
                loading = false, queueError = "", hasMore = hasMore && songs.isNotEmpty()))
        }
    }

    fun setMode(mode: MusicPlaybackMode) {
        val value = QqMusicProtocol.modeValue(mode) ?: return
        if (state.mode == null || modeJob?.isActive == true) return
        val token = generation
        modeJob = scope.launch {
            val result = request("setPlayMode", Bundle().apply { putInt("playMode", value) })
            if (generation != token) return@launch
            val status = code(result)
            HyperCarRepo.log("QQ-API: setPlayMode code=$status requested=$value")
            if (status != 0) update(state.copy(modeError = QqMusicProtocol.error(status)))
            else {
                val confirmed = request("getPlayMode")
                if (generation != token) return@launch
                val actual = if (code(confirmed) == 0) confirmed?.get("data")?.toString()?.toIntOrNull()?.let(QqMusicProtocol::mode) else null
                update(state.copy(mode = actual, modeError = if (actual == mode) "" else "QQ音乐尚未确认模式切换，请刷新重试"))
            }
        }
    }

    fun playSong(id: String) {
        val songs = state.songs
        val index = songs.indexOfFirst { it.id == id }
        if (index < 0) return
        val token = generation
        scope.launch {
            val result = runCatching { requestAsync("playSongIdAtIndex", Bundle().apply {
                putStringArrayList("songIdList", ArrayList(songs.map { it.id })); putInt("index", index)
            }) }.getOrNull()
            if (generation == token && code(result) != 0) update(state.copy(queueError = QqMusicProtocol.error(code(result))))
        }
    }
}
