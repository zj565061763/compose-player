# compose-player

基于 Jetpack Media3（ExoPlayer）封装的 Jetpack Compose 播放器库，支持常规音视频点播与 RTSP 低延迟直播，对外完全屏蔽 Media3 类型并统一采用 `Flow` 暴露状态与事件。

## 模块与构建

| 模块 | 命名空间 | 说明 |
|---|---|---|
| `:lib` | `com.sd.lib.compose.player` | 核心库，Maven 坐标 `io.github.zj565061763.android:compose-player`，版本号与发布配置位于 `lib/gradle.properties` |
| `:app` | `com.sd.demo.compose.player` | 示例应用，包含常规播放示例 `SamplePlayer` 与 RTSP 直播示例 `SamplePlayerRtsp` |

- 环境配置：`minSdk = 23`，`compileSdk = 35`，Java/Kotlin 目标版本 `1.8`。
- 编译核心库：`./gradlew :lib:assembleRelease`。
- 编译示例应用：`./gradlew :app:assembleDebug`。
- 运行单元测试：`./gradlew test`。

## 核心文件

`:lib` 源码位于 `lib/src/main/java/com/sd/lib/compose/player/`：

| 文件 | 核心符号 | 职责 |
|---|---|---|
| `ComposePlayerCore.kt` | `ComposePlayerCore`、`ComposePlayerState`、`ComposePlayerBufferState`、`positionFlow` | 点播与直播共用的密封核心接口、公共状态枚举及协程等待与进度采样扩展 |
| `BaseComposePlayer.kt` | `BaseComposePlayer` | 内部共享的 `ExoPlayer` 宿主基类，统一负责延迟初始化、媒体绑定跟踪、共用状态流同步、进度采样、直播窗口恢复与错误重试调度 |
| `UriUserInfoEncoder.kt` | `encodeUserInfoIfNeed`、`isValidRtspUri` | URI 凭据幂等百分比编码与 RTSP/RTSPT 地址合法性校验 |
| `ComposePlayer.kt` | `ComposePlayer`、`VodPlayerImpl`、`rememberComposePlayer`、`setDataSource` | 常规点播密封接口与实现，管理预加载、暂停、跳转、总时长、倍速、循环及 Raw/HTTP/缓存数据源配置 |
| `ComposePlayerRtsp.kt` | `ComposePlayerRtsp`、`ComposePlayerRtspEvent`、`RtspPlayerImpl`、`rememberComposePlayerRtsp` | RTSP 直播密封接口与实现，内置低延迟缓冲配置、卡死看门狗、倍速追帧、短暂音频抑制恢复与事件流 |
| `ComposePlayerView.kt` | `ComposePlayerView`、`composePlayerViewRebindKeyOnResume` | 封装视口裁剪容器、Media3 `PlayerSurface` 与缩放适配，兼容 Preview 占位与多页面返回重绑表面 |
| `ComposePlayerCache.kt` | `ComposePlayerCache` | 延迟封装 Media3 `SimpleCache`，按规范目录路径线程安全地共享单例（默认目录 `cacheDir/compose_player_cache`，上限 1GB） |
| `ComposePlayerException.kt` | `ComposePlayerException` 及其子类、`desc`、`isAuthError` | 密封播放异常体系，屏蔽底层 `PlaybackException`、精确识别 HTTP/RTSP 鉴权错误（保留底层 `cause`）并提供多语言错误描述扩展 |
| `PlayerExt.kt` | `newMediaAudioAttributes`、`extSetMute`、`extSetSpeed`、`extSetLooping` | 封装默认媒体音频属性构建及 `Player` 音量静音、倍速调整与单曲循环模式设置 |

`:lib` 单元测试位于 `lib/src/test/java/com/sd/lib/compose/player/`：

| 测试类 | 覆盖范围 |
|---|---|
| `UriUserInfoEncodeTest` | `encodeUserInfoIfNeed` 对普通凭据、特殊字符及数字/含点前缀密码、已编码凭据幂等性、路径/参数含 `@` URL 的处理，以及 `isValidRtspUri` 地址校验 |
| `AuthErrorDetectTest` | `isAuthError` 与 `toComposePlayerException` 对 HTTP/RTSP `401`/`403`、缺失鉴权头 `ParserException`、`cause` 保留及含 `401`/`403` 路径或端口网络异常的区分 |

## 内部实现约定

### 公共宿主基类（`BaseComposePlayer`）

- `exoPlayer` 使用 Compose `mutableStateOf<ExoPlayer?>(null)` 持有，首次调用 `preparePlayer()` 且数据源校验通过时才创建实例，供 `ComposePlayerView` 响应式感知底层实例变化。
- `composePlayerViewRebindKeyOnResume` 仅在经历过 `onPause` 后恢复 `onResume` 时递增重绑键值，避免首次组合重复重建表面。
- `_isMediaBound` 跟踪当前数据源是否已绑定到底层 `ExoPlayer`：点播（`rebindMediaOnIdle = false`）在中途网络报错进入 `Player.STATE_IDLE` 后重试仅调用 `player.prepare()` 保留断点（遇到 `ERROR_CODE_BEHIND_LIVE_WINDOW` 时先调用 `seekToDefaultPosition()`），仅在切换数据源、`stopPlayer()` 或 `releasePlayer()` 后重新绑定媒体项；直播（`rebindMediaOnIdle = true`）每次重连重新调用 `setMediaItem` 由 `RtspMediaSource.Factory` 重建数据源。
- `startPlayer()` 在底层处于 `Player.STATE_ENDED` 或当前进度已达已知总时长末尾时先执行 `seekTo(0)` 再播放。
- `resetIdleStates` 统一清空播放态、缓冲态、视频分辨率与首帧标记，供 `stopPlayer()`、`releasePlayer()` 与 `Player.STATE_IDLE` 回调复用。
- `preparePlayer()` 的空白/非法数据源异常与 `onPlayerError` 均转换为 `ComposePlayerException` 后分发子类，仅 `ComposePlayerException.Playback` 允许自动重试，其余异常立即复位播放意图。
- `encodeUserInfoIfNeed` 基于 RFC 3986 直接解析原始字符串中的凭据段并做幂等百分比编码，兼容密码中包含 `@`、`:`、`#`、`?`、`/`、`=`、`%` 及数字或含点前缀截断等场景，并排除内网 `host:port` 路径或查询参数中的邮箱地址。
- `isAuthError` 按异常类型精确识别 `HttpDataSource.InvalidResponseCodeException`（状态码 `401`、`403`）、`RtspMediaSource.RtspPlaybackException`（`METHOD 401/403` 状态行）与鉴权头缺失的 `ParserException`，不对通用异常消息做裸子串匹配。
- `extSetMute` 通过切换 `Player.volume`（`0f` / `1f`）实现无卡顿静音，避免动态禁用/启用音轨导致 RTSP 会话二次选轨报错或点播重缓冲。
- `observePosition` 基于 `channelFlow` 与 `flowOn(Dispatchers.Main.immediate)` 实现按需采样：仅在 `ComposePlayerState.Playing` 时按间隔轮询，在状态切换、`seekTo` 或 `onPositionDiscontinuity` 时通过 `_positionChangedFlow` 立即发射最新位置，非播放态挂起无轮询开销。

### 点播播放器（`VodPlayerImpl`）

- 继承 `BaseComposePlayer`，通过三态内部枚举 `VodPlayIntent`（`Idle`、`Playing`、`Paused`）表达用户意图，避免与输出态 `ComposePlayerState` 混杂。
- `prepare()` 显式触发预加载而不自动播放；`pause()` 仅在 `VodPlayIntent.Playing` 时切入 `Paused`（若处于报错等待重试仅取消重试任务而不重新发起网络加载），空闲、停止或结束态调用 `pause()` 为空操作。
- 因失去音频焦点或拔出耳机被动暂停时同步将意图及播放态置为 `Paused`；自然播放结束进入 `Ended` 时暂停底层 `playWhenReady` 并将意图归为 `Idle`（切换数据源保持 `Idle`，调用 `seekTo` 回退到片尾前时切为 `Paused`，已在片尾向后跳转保持 `Ended`）。
- `ComposePlayer.create` 使用 `DefaultDataSource.Factory` 包装 `DefaultHttpDataSource.Factory`（传入 `cache` 时中间叠加延迟初始化的 `CacheDataSource.Factory`），支持本地/Raw 资源与 HTTP/HTTPS 缓存、请求头及音频焦点管理。
- `setLooping` 通过 `Player.REPEAT_MODE_ONE` / `REPEAT_MODE_OFF` 实现底层无缝循环，避免循环边界触发 `ComposePlayerState.Ended`。
- `setSpeed` 前置校验正有限浮点数，避免播放器延迟创建时才抛出非法参数异常。
- 媒体未绑定时调用 `seekTo` 暂存至 `_seekToPositionMs`（由 `getCurrentPosition()` 同步返回并在首次 `setMediaItem` 时作为起播位置消费）；已绑定时直接调用 `player.seekTo`（已知总时长时限制在 `[0, duration]` 范围内），避免切源预跳转触发双次缓冲并兼容未知时长流。

### RTSP 直播播放器（`RtspPlayerImpl`）

- 继承 `BaseComposePlayer`，通过 `_isPlayingRequested` 管理拉流意图（状态流仅在 `Idle` 与 `Playing` 间流转），前置通过 `isValidRtspUri` 校验 `rtsp`/`rtspt` 协议与主机端口。
- 被动永久失去 `playWhenReady` 时执行 `stop()`；短暂音频抑制期间停止看门狗并将对外状态置回 `Idle`，抑制解除时若累积缓冲延迟超限则调用 `restartPlay()` 重新追回实时画面；播放请求下流结束（`onStateEnded`）时调用 `restartPlay()` 重新拉流。
- `ComposePlayerRtsp.create` 将小于等于 0 的 `timeoutMs` 归一化为 `RtspMediaSource.DEFAULT_TIMEOUT_MS`（`8000ms`），`newLivePlayer` 将 `DefaultLoadControl` 设为最小缓冲 32ms、起播与恢复缓冲 0ms、无后置缓冲，并通过 `DefaultRenderersFactory.setAllowedVideoJoiningTimeMs(0L)` 降低首帧拼接延迟。
- 媒体绑定发起拉流后即启动主线程看门狗 `_playWatchdogJob`（首次连接/缓冲阶段将进度停滞阈值放宽至 `timeoutMs + 2000ms` 之后以避免打断底层超时报错）：进入 `Player.STATE_IDLE`、触发播放异常或在 `Player.STATE_READY` 下暂停渲染时立即停止看门狗并将倍速恢复为 `1.0f`；进度停滞或播放态绑定有效渲染表面下的渲染帧停滞超时后向 `eventFlow` 发射 `ComposePlayerRtspEvent` 并调用 `restartPlay()`；当缓冲差大于 `chaseLatency` 时以 `1.2f` 倍速追帧，回落至 `chaseLatency / 2` 以下时恢复为 `1.0f`。

## 修改约束

- `:lib` 所有 Media3 依赖均声明为 `implementation`，公开 API 严禁暴露任何 `androidx.media3.*` 类型；公开签名直接引用的 Compose 基础类型声明为 `api`。
- 状态与一次性事件统一通过 `StateFlow` / `Flow` 暴露，不引入命令式 `Callback` 监听接口。
- `ComposePlayerCore`、`ComposePlayer`、`ComposePlayerRtsp` 均为 `sealed interface`，新增实现类需继承 `BaseComposePlayer` 以接入 `ComposePlayerView` 与 `positionFlow`。
- `ExoPlayer` 实例操作、状态流更新、错误重试与 RTSP 看门狗均运行在主线程，禁止在后台线程直接调用 `exoPlayer`。
- `:lib` 字符串资源统一以 `lib_compose_player_` 为前缀，新增或修改文案时需同步维护 `values/strings.xml`、`values-en/strings.xml` 和 `values-zh-rTW/strings.xml`。
