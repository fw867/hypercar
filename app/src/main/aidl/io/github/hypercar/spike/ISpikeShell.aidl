// SPIKE-P0：跑在 Shizuku UserService 进程（shell UID）里的最小接口。
// 只做「建一块能托管别人 App 的公共虚拟屏」+「把触摸按 displayId 注入进去」。
package io.github.hypercar.spike;

import android.view.Surface;
import android.os.ParcelFileDescriptor;

interface ISpikeShell {
    /** 返回 "OK id=<n> flags=0x<hex>" 或 "ERR ..."。 */
    String createDisplay(in Surface surface, int width, int height, int dpi, int flags, String name);

    String releaseDisplay();

    /** P2：只释放指定 display（D1 首页与 D2 地图同时存在时用）。 */
    String releaseDisplayById(int displayId);

    String status();

    /**
     * 物理主屏硬件断电/点亮（scrcpy -S 同款）：系统仍认为亮屏，不锁屏/不进 Doze，
     * 独立显示组的虚拟屏继续渲染。返回是否成功。
     */
    boolean setPhysicalScreenPower(boolean on);

    /** 实时流式注入单条 DOWN(0)/UP(1)/MOVE(2)/CANCEL(3) 事件（异步模式，跟手拖动用）。 */
    String motion(int displayId, int action, int x, int y, long downTimeMs);

    /** 单击。返回 "OK <耗时µs>" 或 "ERR ..."。 */
    String tap(int displayId, int x, int y);

    /** 单指拖动。 */
    String swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs);

    /** 双指缩放（两指沿水平方向对称开合）。 */
    String pinch(int displayId, int cx, int cy, int fromRadius, int toRadius, int steps);

    /** 输入通道是否可用（反射注入是否成功）。 */
    String inputState();

    /** Shell REMOTE_SUBMIX playback capture, 48 kHz stereo PCM16, bound to the caller's life. */
    ParcelFileDescriptor startAudioCapture(IBinder owner);
    void stopAudioCapture();
    String audioCaptureStatus();
    /** Keep the task/display alive while switching between the card texture and full-screen encoder. */
    String updateDisplaySurface(int displayId, in Surface surface, int width, int height, int dpi);
}
