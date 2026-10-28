# 2026-10-29 车机 UI / 推流检查

- 删除编码输出端的预测帧抽帧。H.264 P 帧可能被后续帧引用，随机抽掉会造成马赛克、红绿块，直到下一关键帧恢复。改用 Surface 输入端 `KEY_MAX_FPS_TO_ENCODER`，完整发送编码后的帧。
- 首页 / 全屏地图切源先停止旧源，再开放新源；新源等待关键帧才发送，避免交错两个编码器的参考链。
- 用 `VideoEncodingPlan` 统一首页、全屏地图、预览初始尺寸和触摸目标。会话运行中保持尺寸不变，设置修改在下次连接生效。
- 修复 `/info` 尺寸字段读取：优先 `widthPixels/heightPixels`，兼容旧 `width/height`。原先把 WinPlay 的有效尺寸误读为 0，导致比例、触摸都依赖手动兜底。
- 优先使用车机 `/info` 返回的显示区域比例；无有效尺寸时使用设置。最大宽 1920、高 1080、像素约 110 万，默认 30 fps，可手动选择 60 fps，偶数对齐。项目历史宽屏预设（未经宝马实车尺寸确认）2560×744 → 1920×558；原尺寸 60 fps 的每秒编码像素量降低约 72%。UI 密度随尺寸调整，地图在卡片 / 全屏切换中维持同一密度。
- 尝试 Android 14 的 30 Hz 虚拟屏后，此 ROM 出现首轮推流卡住，最终保留原建屏方式，只在编码输入端限帧。旧版本 Shizuku 副屏服务仅在尚未绑定时清理，避免杀掉已绑定服务而保留失效 Binder。
- “返回车机”发送加密 AirPlay `POST /command`、`{type: requestUI}`，由接收端显示原生系统 UI；不调用断开、停止推流或暂停音乐。
- 补齐默认音乐 App 优先选择和连接自动播放。自动播放等待音频采集就绪，等待媒体会话最多 15 秒；手动播放控制取消自动请求。此前已有的断连暂停 / 重连续播保留。

验证：签名 release 构建、4 项缩放与帧率测试通过（宝马、常见宽高比、超大分辨率、偶数对齐、低分辨率不放大、帧率限制）。WinPlay 已验证等比缩放、约 30 fps 编码、触摸和 requestUI 返回成功；音频 PCM 继续发送。原生宝马系统切换及偶发色块的长期复测仍需实车确认。

参考：
- [Android MediaFormat：输入帧率限制](https://developer.android.com/reference/android/media/MediaFormat#KEY_MAX_FPS_TO_ENCODER)
- [Android VirtualDisplayConfig：请求刷新率](https://developer.android.com/reference/android/hardware/display/VirtualDisplayConfig.Builder#setRequestedRefreshRate(float))

尺寸核实：2732×794 在本次 WinPlay 的 `/info` 中实测读到，也是项目 WinPlay 档案的历史触摸参数，不能据此认定为 BMW iD8.5 固定规格。2880×1080 面板和 CarPlay 可用区域需要区分；实车应以协议上报为准。已移除设置页将历史 2560×744 标成宝马标准的文字。

补充验证：2 项 Rust 尺寸解析测试已交叉编译并在连接手机运行通过；最新编码连续数分钟保持约 30 fps。

0.1.0.371：取消编码方案中强制 30 fps 的上限。默认仍为 30 fps，手动选择 60 fps 后，下次连接的首页与全屏地图编码器均使用 60 fps；设置页预计帧率随选项同步更新。保留编码前限帧，避免输出端抽取预测帧。
