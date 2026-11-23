# Changelog

## 1.1.0

### ⚠️ Breaking Changes

- 不再传递暴露 `androidx.media3:*` 依赖，对外屏蔽所有 Media3 类型
- `ComposePlayer` 与 `ComposePlayerRtsp` 均改为密封接口并继承新增的 `ComposePlayerCore`，抽取点播与直播共用的状态流、控制方法与挂起等待扩展
- `ComposePlayerRtsp` 不再继承 `ComposePlayer`，移除直播不支持的 `durationFlow`、`speedFlow`、`isLoopingFlow`、`pause`、`seekTo`、`getDuration`、`setSpeed`、`setLooping`、`awaitDuration`、`seekDelta` 及 `setDataSource(@RawRes Int)`
- 移除 `ComposePlayer.Callback`、`ComposePlayer.setCallback` 与 `ComposePlayerRtsp.EventCallback`、`ComposePlayerRtsp.setEventCallback`，统一通过 `Flow` 暴露状态与事件
- `ComposePlayerCache` 由 `object` 改为 `class`，`getDefault` 返回类型与 `ComposePlayer.create` 的 `cache` 参数类型由 Media3 `Cache` 改为 `ComposePlayerCache`
- `ComposePlayerException` 改为密封类，原顶层异常子类收敛为嵌套类 `DataSourceBlank`、`DataSource`、`Auth`、`Playback`，其中 `Playback` 移除 `exception` 属性，改为提供 `errorCode`、`errorCodeName` 与 `cause`
- `ComposePlayer.setSpeed` 对非正数或非有限浮点数立即抛出 `IllegalArgumentException`
- `ComposePlayerRtsp` 仅支持合法的 `rtsp://` 或 `rtspt://` 地址，其他协议或格式非法的地址直接发射 `ComposePlayerException.DataSource` 且不自动重试

### ✨ Improvements

- `ComposePlayerCore` 新增 `positionFlow(interval)`，仅在播放中按间隔轮询进度，状态切换或跳转时立即发射，非播放态自动挂起
- `ComposePlayer` 新增 `prepare()`，支持预加载数据源且不自动开始播放
- `ComposePlayer.create` 新增 `handleAudioFocus`、`handleAudioBecomingNoisy`、`httpHeaders`、`connectTimeoutMs`、`readTimeoutMs`、`allowCrossProtocolRedirects` 配置参数
- `ComposePlayerCache` 新增 `create(context, directory, maxBytes)`，支持自定义专用缓存目录与容量上限
- `ComposePlayerCache` 将磁盘缓存初始化推迟到后台加载时执行，避免主线程磁盘 I/O
- `ComposePlayerRtsp` 新增 `eventFlow`，暴露 `StuckPosition` 与 `StuckRenderedFrame` 卡死重连事件
- `ComposePlayerRtsp.create` 新增 `handleAudioFocus`（默认关闭）与 `timeoutMs`（同时作用于连接超时与无数据包超时，避免离线设备长时间阻塞连接）
- `ComposePlayerView` 的 `player` 参数类型放宽为 `ComposePlayerCore`，支持 Compose Preview 占位、首帧前画面隐藏与边界裁剪，并在多层视图叠加销毁上层后自动恢复下层画面绑定
- 点播支持音频焦点与短暂音频抑制处理：短暂抑制期间转为 `Paused` 并在解除后自动恢复播放，永久失去焦点或拔出耳机时自动暂停
- RTSP 直播支持音频焦点与短暂音频抑制处理：短暂抑制解除后若累积延迟过大则自动重连追回实时画面，永久失去焦点时自动停止拉流

### 🐛 Bug Fixes

- `ComposePlayer.create` 配置 `cache` 后无法播放本地文件、`content://`、`asset://` 与 Raw 资源；现在同时支持网络缓存与各类本地数据源
- `setMute` 切换静音时会触发点播重缓冲或导致部分 RTSP 流报错；现在切换即时生效且无卡顿
- `ComposePlayer.setLooping(true)` 在单曲循环交界处会短暂发出 `Ended` 状态；现在无缝循环并保持 `Playing`
- 起播前或总时长未知时调用 `seekTo`，`getCurrentPosition()` 仍返回 `0` 且需等加载完成后二次跳转；现在立即更新当前位置并直接从目标位置起播
- `Paused` 状态下调用 `setDataSource` 切换新地址会自动发起加载；现在播放中切源立即起播新源，暂停或结束态切源重置为 `Idle`
- 切换数据源或重连缓冲期间，`ComposePlayerView` 可能短暂残留上一个视频的宽高比；现在切源或进入空闲态时立即清空旧视频尺寸
- 非正方形像素视频（像素宽高比不为 1）在 `ComposePlayerView` 中画面比例失真；现在按像素宽高比折算显示尺寸
- 点播中途报错自动重试会丢失当前进度回到开头播放；现在重试保留当前播放进度
- 点播播放直播流（如 HLS）发生落后直播窗口错误后重试仍持续失败；现在重试前自动回到默认直播位置
- 播放出错时 `exceptionFlow` 早于状态复位发射，在收集器中读取到的仍是旧状态，且收集器内立即切源重播会被后续错误处理覆盖为 `Idle`；现在先复位状态再发射异常，同步切源重播正常生效
- 异常消息中的路径或端口号包含 `401`/`403` 时会被误判为 `Auth` 异常而停止自动重试，且 `Auth` 异常丢失了底层 `cause`；现在按 HTTP 与 RTSP 响应状态精确识别鉴权失败并保留 `cause`
- 地址凭据包含 `@`、`:`、`#`、`?`、`/`、`=`、`%` 等特殊字符时解析失败，已编码凭据会被重复编码，无凭据地址路径或参数含 `@` 时可能被误改写；现在对凭据做幂等百分比编码且不影响路径与参数
- RTSP 连接缓冲较慢时可能早于 `timeoutMs` 误报 `StuckPosition` 并打断超时报错；现在连接与初始缓冲阶段将进度停滞检测放宽至 `timeoutMs` 之后
- RTSP 未显示有效画面区域或正常播放期间可能误报 `StuckRenderedFrame`；现在仅在实际播放且画面区域有效时检测渲染帧停滞
- RTSP 追帧加速后停止拉流未恢复默认倍速；现在停止拉流或暂停检测时自动恢复原速
- `composePlayerViewRebindKeyOnResume` 在页面首次进入 `onResume` 时会触发一次多余的表面重绑；现在仅在经历过 `onPause` 后恢复时重绑
- 所有 `ComposePlayerView` 退出组合后播放器仍持有已销毁视图的画面表面引用；现在视图全部卸载后自动释放

### Migration

- 应用工程代码直接引用了 `androidx.media3.*` 类型的，需在应用模块自行添加对应的 Media3 依赖
- 同时接收点播与直播实例的函数或属性，将类型由 `ComposePlayer` 改为 `ComposePlayerCore`
- 在 `ComposePlayerRtsp` 上调用 `pause()` 的改为 `stop()`，调用了 `seekTo`、`setSpeed`、`setLooping`、`getDuration` 等点播专属方法的需移除
- 使用 `ComposePlayer.setCallback` 的，改为收集 `playerStateFlow`、`bufferStateFlow` 和 `exceptionFlow`
- 使用 `ComposePlayerRtsp.setEventCallback` 的，改为收集 `ComposePlayerRtsp.eventFlow`
- 向 `ComposePlayer.create` 传入自定义 Media3 `Cache` 的，改为传入 `ComposePlayerCache.create(context, directory, maxBytes)`；使用 `ComposePlayerCache.getDefault(context)` 的无需修改
- `ComposePlayerExceptionDataSourceBlank`、`ComposePlayerExceptionDataSource`、`ComposePlayerExceptionAuth` 分别改为 `ComposePlayerException.DataSourceBlank`、`ComposePlayerException.DataSource`、`ComposePlayerException.Auth`
- `ComposePlayerExceptionPlaybackException` 改为 `ComposePlayerException.Playback`，`exception.errorCode` 与 `exception.cause` 改为直接读取 `errorCode` 与 `cause`
