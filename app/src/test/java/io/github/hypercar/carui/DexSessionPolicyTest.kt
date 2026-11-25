package io.github.hypercar.carui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DexSessionPolicyTest {

    /** 普通自绘模式断开：DexCaster 没有任何资源，不能顺手清共享的音频/触摸/shell。 */
    @Test
    fun selfDrawnDisconnectOwnsNothing() {
        assertFalse(
            DexSessionPolicy.ownsResources(
                running = false, displayId = -1, mirrorDisplayId = -1,
                hasEncoder = false, overlayMode = false,
            )
        )
    }

    /** DeX 会话中（或残留资源）必须被清理。 */
    @Test
    fun dexSessionOwnsResources() {
        assertTrue(
            DexSessionPolicy.ownsResources(
                running = true, displayId = 14, mirrorDisplayId = 15,
                hasEncoder = true, overlayMode = true,
            )
        )
        // 单个资源残留也要清理，否则虚拟屏会一直挂着。
        assertTrue(
            DexSessionPolicy.ownsResources(
                running = false, displayId = -1, mirrorDisplayId = 15,
                hasEncoder = false, overlayMode = false,
            )
        )
    }

    /** 投屏进行中不能从诊断页再起第二个编码器。 */
    @Test
    fun debugEntryBlockedWhileStreaming() {
        assertFalse(DexSessionPolicy.canStartDebugDex(true, false, false, false))
        assertFalse(DexSessionPolicy.canStartDebugDex(false, true, false, false))
        assertFalse(DexSessionPolicy.canStartDebugDex(false, false, true, false))
        assertFalse(DexSessionPolicy.canStartDebugDex(false, false, false, true))
        assertTrue(DexSessionPolicy.canStartDebugDex(false, false, false, false))
    }

    /** 快速断开重连：旧会话排队的停止任务不能释放新会话刚建的屏。 */
    @Test
    fun staleStopCannotReleaseNewSessionDisplay() = runTest {
        val lifecycle = SessionResourceLifecycle()
        var display = 0
        val cleanup: suspend () -> Unit = { display = 0 }
        lifecycle.start(lifecycle.ticket(), { true }, cleanup) { display = 14 }

        val retired = lifecycle.retire()
        val slowRelease = CompletableDeferred<Unit>()
        val stop = async { lifecycle.stop(retired) { slowRelease.await(); display = 0 } }
        runCurrent()

        var newDisplay = -1
        val reconnect = async { lifecycle.start(lifecycle.ticket(), { true }, cleanup) { newDisplay = 15 } }
        newDisplay = 15
        slowRelease.complete(Unit)
        stop.await()
        assertEquals(15, newDisplay)
        assertEquals(0, display)
        reconnect.await()
        assertEquals(15, newDisplay)
    }

    /** 会话在启动过程中被取消时，已经建好的屏要回滚，避免下一轮拿着旧资源继续。 */
    @Test
    fun cancelledStartRollsBackOwnedDisplay() = runTest {
        val lifecycle = SessionResourceLifecycle()
        val created = CompletableDeferred<Unit>()
        var display = 0
        val start = async {
            lifecycle.start(lifecycle.ticket(), { false }, { display = 0 }) {
                created.await()
                display = 14
                "session 已停止"
            }
        }
        created.complete(Unit)
        assertNull(start.await())
        assertEquals(0, display)
    }
}
