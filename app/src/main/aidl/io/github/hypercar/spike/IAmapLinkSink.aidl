// 车机自绘 UI 的导航数据通道：由 shell 进程（uid 2000）代 App 绑定高德 LinkSDK，
// 再把回调原样转回 App（AES-GCM 信封解密仍在 App 侧做）。
package io.github.hypercar.spike;

interface IAmapLinkSink {
    /** code 1 = onAmapCallback(String)，code 2 = onAmapCallbackWithFd（已读成 String）。 */
    void onCallback(int code, String payload);

    /** 连接状态：binding / connected / auth:<status> / registered:<bool> / disconnected / error:<msg>。 */
    void onState(String state);
}
