package io.github.hypercar.carui

/**
 * DeX 画面源的资源归属判定。
 *
 * DeX 与自绘 UI 共用一批全局资源（音频采集、native 触摸回调、Shizuku shell 用户服务、
 * 全局叠加屏设置），所以「该不该清理」不能只看调用时机，要看本次会话是否真的用过它们。
 * 这些判定放在纯函数里，便于单元测试覆盖断开/重连这类不容易实机复现的时序。
 */
internal object DexSessionPolicy {

    /**
     * 是否还有属于 DeX 会话的资源需要释放。
     *
     * 普通自绘模式断开时也会走到 [DexCaster] 的停止流程，此时四个状态全是空，
     * 必须直接返回 false，否则会把共享的音频采集、触摸回调和 shell 用户服务一起清掉
     * （地图屏与导航任务就建在同一个 shell 服务上）。
     */
    fun ownsResources(
        running: Boolean,
        displayId: Int,
        mirrorDisplayId: Int,
        hasEncoder: Boolean,
        overlayMode: Boolean,
    ): Boolean = running || displayId > 0 || mirrorDisplayId > 0 || hasEncoder || overlayMode

    /**
     * 诊断页「启动 DeX」是否可以执行。
     *
     * 调试入口必须与实际会话互斥：投屏中再起一个编码器会往同一条 native 视频链路送帧，
     * 同时 DeX 会替换全局触摸回调，导致花屏与触摸错投。
     */
    fun canStartDebugDex(
        sessionLive: Boolean,
        streaming: Boolean,
        dexRunning: Boolean,
        carUiRunning: Boolean,
    ): Boolean = !sessionLive && !streaming && !dexRunning && !carUiRunning
}
