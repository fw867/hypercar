// SPIKE-P0：跑在 Shizuku UserService 进程（shell UID）里的最小接口。
// 只做「建一块能托管别人 App 的公共虚拟屏」+「把触摸按 displayId 注入进去」。
package io.github.hypercar.spike;

import android.view.Surface;

interface ISpikeShell {
    /** 返回 "OK id=<n> flags=0x<hex>" 或 "ERR ..."。 */
    String createDisplay(in Surface surface, int width, int height, int dpi, int flags, String name);

    String releaseDisplay();

    String status();

    /** 单击。返回 "OK <耗时µs>" 或 "ERR ..."。 */
    String tap(int displayId, int x, int y);

    /** 单指拖动。 */
    String swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs);

    /** 双指缩放（两指沿水平方向对称开合）。 */
    String pinch(int displayId, int cx, int cy, int fromRadius, int toRadius, int steps);

    /** 输入通道是否可用（反射注入是否成功）。 */
    String inputState();
}
