package io.github.hypercar.spike

import io.github.hypercar.shizuku.ShizukuTools

/**
 * 让竖屏锁定的 App（高德手机版）在副屏上横屏铺满 —— cardeck 的 `AppOrientationCompat` 方案。
 *
 * 两个 AOSP 标准 ChangeId（Android 14+ `ActivityInfo`，由 LetterboxUiController 消费）：
 *  - `OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR` = 265451093
 *  - `OVERRIDE_ANY_ORIENTATION`               = 265464455
 *
 * 实测（本机 Android 17 / HyperOS 4，shell 身份）：
 *  - 两条 `am compat enable` 后，高德手机版从 590×720 竖条变成 1920×720 全屏横屏；
 *  - `am compat` 是**持久系统覆盖**，会立刻杀掉目标应用（所以必须先 enable、再 force-stop/启动）；
 *  - 不用时必须 `disable`，否则用户手机上的高德也会一直横屏（cardeck 是会话结束统一复原）。
 *
 * 注意：cardeck 文档里提到 user build + 非 debuggable 应用可能被 PlatformCompat 拒绝
 * （`Cannot override a change on a non-debuggable app and user build.`）；本机实测放行。
 * 失败时把原始输出写进报告，不要静默。
 */
object MapCompat {

    private const val CHANGE_UNDEFINED_TO_NOSENSOR = 265451093L
    private const val CHANGE_ANY_ORIENTATION = 265464455L

    suspend fun enable(pkg: String): String = run(pkg, play = true)

    suspend fun disable(pkg: String): String = run(pkg, play = false)

    private suspend fun run(pkg: String, play: Boolean): String {
        if (!ShizukuTools.isReady) return "Shizuku 未就绪，跳过 am compat"
        val action = if (play) "enable" else "disable"
        val first = ShizukuTools.exec("am compat $action $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", 6_000)
        val second = ShizukuTools.exec("am compat $action $CHANGE_ANY_ORIENTATION $pkg", 6_000)
        return "am compat $action $pkg → exit=${first.code}/${second.code} " +
            "out=${(first.out + " | " + second.out).take(400)}"
    }
}
