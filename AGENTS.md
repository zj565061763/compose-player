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
| `ComposePlayerCore.kt` | `ComposePlayerCore`、`ComposePlayerState`、`ComposePlayerBufferState` | 点播与直播共用的密封核心接口（含 `positionFlow` 进度采样）、公共状态枚举及协程等待扩展 |
| `BaseComposePlayer.kt` | `BaseComposePlayer` | 内部共享的 `ExoPlayer` 宿主基类，统一负责延迟初始化、媒体绑定跟踪、事件批次状态派生、进度采样、直播窗口恢复与主线程协程重试调度 |
| `UriUserInfoEncoder.kt` | `encodeUserInfoIfNeed`、`isValidRtspUri` | URI 凭据幂等百分比编码与 RTSP/RTSPT 地址合法性校验 |
| `ComposePlayer.kt` | `ComposePlayer`、`VodPlayerImpl`、`rememberComposePlayer`、`setDataSource` | 常规点播密封接口与实现，管理预加载、暂停、跳转、总时长、倍速、循环及 Raw/HTTP/惰性缓存数据源配置 |
| `ComposePlayerRtsp.kt` | `ComposePlayerRtsp`、`ComposePlayerRtspEvent`、`RtspPlayerImpl`、`rememberComposePlayerRtsp` | RTSP 直播密封接口与实现，内置低延迟缓冲配置、协程卡死看门狗、倍速追帧、短暂音频抑制恢复与事件流 |
| `ComposePlayerView.kt` | `ComposePlayerView`、`composePlayerViewRebindKeyOnResume` | 封装视口裁剪容器、Media3 `PlayerSurface`、首帧透明遮蔽与缩放适配，兼容 Preview 占位与多页面返回重绑表面 |
| `ComposePlayerCache.kt` | `ComposePlayerCache` | 延迟封装 Media3 `SimpleCache`，共享进程级数据库实例并按规范目录路径线程安全地复用单例（默认目录 `cacheDir/compose_player_cache`，上限 1GB） |
| `ComposePlayerException.kt` | `ComposePlayerException` 及其子类、`desc`、`isAuthError` | 密封播放异常体系，屏蔽底层 `PlaybackException`、精确识别 HTTP/RTSP 鉴权错误（保留底层 `cause`）并提供多语言错误描述扩展 |
| `PlayerExt.kt` | `newMediaAudioAttributes`、`extSetMute`、`extSetSpeed`、`extSetLooping` | 封装默认媒体音频属性构建及 `Player` 音量静音、倍速调整与单曲循环模式设置 |

`:lib` 单元测试位于 `lib/src/test/java/com/sd/lib/compose/player/`（测试辅助基座为 `FakeExoPlayerController.kt`）：

| 测试类 | 覆盖范围 |
|---|---|
| `UriUserInfoEncodeTest` | `encodeUserInfoIfNeed` 对普通凭据、含特殊字符的用户名与密码、省略默认端口的域名凭据、已编码凭据幂等性、路径/参数/片段含 `@` URL 的处理，以及 `isValidRtspUri` 对协议、端口、IPv4/IPv6 主机的校验 |
| `AuthErrorDetectTest` | `isAuthError` 与 `toComposePlayerException` 对 HTTP/RTSP `401`/`403`、缺失鉴权头 `ParserException`、`cause` 保留、路径/端口网络异常区分，以及 `desc` 多语言文案格式化 |
| `PlayerExtTest` | `newMediaAudioAttributes` 默认媒体音频属性构建，以及 `extSetMute`、`extSetSpeed`、`extSetLooping` 的状态同步与幂等去重 |
| `ComposePlayerCacheTest` | `ComposePlayerCache.create` 非正容量参数校验、`getDefault` 单例复用，以及等价规范目录路径共享同一底层 `SimpleCache` 实例 |
| `ComposePlayerCoreFlowTest` | `positionFlow` 非正间隔兜底、播放态按需轮询、非播放态挂起、跳转与不连续事件立即发射、重复位置去重，以及 `awaitPlayerState`、`awaitBufferReady`、`awaitRenderedFirstFrame`、`awaitDuration` 挂起等待扩展 |
| `ComposePlayerVodTest` | `VodPlayerImpl` 延迟初始化与预设属性继承、`setSpeed` 参数校验、空白/重复数据源处理、`prepare` 预加载与 `videoSizeDp` 像素宽高比折算、报错期切换、切源同步起播与防残留视频尺寸回写、`seekTo`/`seekDelta` 裁剪与暂停跳转起播及片尾回退重播、短暂音频抑制与焦点丢失、错误复位与同步重入时序、`BEHIND_LIVE_WINDOW` 恢复、自动重试与鉴权停试、异步 `onEvents` 本帧同步、多视图栈重绑置顶与表面清理、Raw 资源绑定与 `release` 复用 |
| `ComposePlayerRtspTest` | `RtspPlayerImpl` 协议前置校验、`Idle`/`Playing` 状态流转、流结束重拉与防残留视频尺寸回写、被动失去 `playWhenReady` 停止、短暂音频抑制看门狗启停与延迟超限重拉、连接期放宽阈值与播放期 `StuckPosition`、有效表面 `StuckRenderedFrame`、倍速追帧滞回与最小阈值恢复、重绑重试、鉴权停试、异常同步重入重连及 `TimeoutSocketFactory` TCP 建连超时清理 |

## 内部实现约定

### 公共宿主基类（`BaseComposePlayer`）

- `exoPlayer`、`videoSizeDp` 与 `isRenderedFirstFrame` 使用 Compose `mutableStateOf` 持有，首次调用 `preparePlayer()` 且数据源校验通过时才创建底层实例，并在 `syncStates` 中仅响应 `EVENT_VIDEO_SIZE_CHANGED` 维护像素宽高比折算尺寸、在绑定新媒体或进入 `STATE_IDLE` 时清空尺寸与首帧标记，供 `ComposePlayerView` 同帧原子感知实例、宽高比与首帧变化并避免切源缓冲期回写旧尺寸。
- `mainScope` 基于 `SupervisorJob() + Dispatchers.Main.immediate` 承载主线程错误重试与子类异步任务，`releasePlayer()` 时取消子协程并保持作用域可复用。
- `composePlayerViewRebindKeyOnResume` 仅在经历过 `onPause` 后恢复 `onResume` 时递增重绑键值，避免首次组合重复重建表面；`ComposePlayerView` 直接读取 `BaseComposePlayer` 的 `isRenderedFirstFrame` 与 `videoSizeDp` 控制表面透明度与源尺寸，并通过 `attachPlayerView`/`detachPlayerView` 维护有序活跃视图令牌栈（`rebindKeyProvider` 变化时重新调用 `attachPlayerView` 将当前视图移至栈顶）：栈顶视图卸载且仍有活跃视图时通知新栈顶重绑接管表面，无活跃视图时在下一帧调用 `clearVideoSurface()` 释放底层视图引用。
- `_isMediaBound` 跟踪当前数据源是否已绑定到底层 `ExoPlayer`：点播（`rebindMediaOnIdle = false`）在中途网络报错进入 `Player.STATE_IDLE` 后重试仅调用 `player.prepare()` 保留断点（`playerError` 为 `ERROR_CODE_BEHIND_LIVE_WINDOW` 时在下次 `prepare()` 前调用 `seekToDefaultPosition()`），仅在切换数据源、`stopPlayer()` 或 `releasePlayer()` 后重新绑定媒体项；直播（`rebindMediaOnIdle = true`）每次重连重新调用 `setMediaItem` 由 `RtspMediaSource.Factory` 重建数据源。
- `startPlayer()` 在底层处于 `Player.STATE_ENDED` 或当前进度已达已知总时长末尾时先执行 `seekTo(0)` 再播放，并立即调用 `syncPlayerState()` 同步本帧状态。
- `Player.Listener.onEvents` 在单次事件批次末尾通过 `syncStates` 与子类纯计算函数 `computePlayerState` 统一派生缓冲态、视频分辨率、首帧标记与播放态；发生异常时先复位状态并完成子类错误处理，最后发射 `exceptionFlow`，避免瞬态状态矛盾与同步收集器重入被覆盖。
- `preparePlayer()` 的空白/非法数据源异常与播放错误均转换为 `ComposePlayerException` 后分发子类，仅 `ComposePlayerException.Playback` 允许自动重试，其余异常立即复位播放意图。
- `encodeUserInfoIfNeed` 基于 RFC 3986 直接解析原始字符串中的用户名与密码并做幂等百分比编码，兼容含 `@`、`:`、`#`、`?`、`/`、`=`、`%` 等特殊字符的凭据，并排除无凭据地址路径或查询参数中的 `@` 字符。
- `isAuthError` 按异常类型精确识别 `HttpDataSource.InvalidResponseCodeException`（状态码 `401`、`403`）、`RtspMediaSource.RtspPlaybackException`（`METHOD 401/403` 状态行）与鉴权头缺失的 `ParserException`，不对通用异常消息做裸子串匹配。
- `extSetMute` 通过切换 `Player.volume`（`0f` / `1f`）实现无卡顿静音，避免动态禁用/启用音轨导致 RTSP 会话二次选轨报错或点播重缓冲。
- `positionFlow` 基于 `merge`、`transformLatest` 与 `flowOn(Dispatchers.Main.immediate)` 实现按需采样：仅在 `ComposePlayerState.Playing` 时按间隔轮询，在状态切换、`seekTo` 或 `onPositionDiscontinuity` 时通过 `_positionChangedFlow` 立即发射最新位置，非播放态挂起无轮询开销。

### 点播播放器（`VodPlayerImpl`）

- 继承 `BaseComposePlayer`，通过三态内部枚举 `VodPlayIntent`（`Idle`、`Playing`、`Paused`）表达用户意图，避免与输出态 `ComposePlayerState` 混杂；脱离首次加载 `Idle` 后的缓冲阶段只要意图为 `Playing` 且无音频抑制即保持或切入 `Playing`。
- `prepare()` 在已设置数据源且未绑定媒体或底层处于 `Player.STATE_IDLE` 时取消挂起重试并将意图置为 `Paused` 执行预加载；`pause()` 仅在 `VodPlayIntent.Playing` 时切入 `Paused`（若处于报错等待重试仅取消重试任务而不重新发起网络加载），空闲、停止或结束态调用 `pause()` 为空操作。
- 播放意图（`VodPlayIntent.Playing`）下切换数据源立即同步调用 `startPlayer()` 加载新源；暂停意图下切换数据源归为 `Idle`。
- 因失去音频焦点或拔出耳机被动失去 `playWhenReady` 时将意图置为 `Paused`；短暂音频抑制期间保持 `Playing` 意图但输出态派生为 `Paused`，抑制解除且脱离 `Idle` 后自动恢复 `Playing`；自然播放结束进入 `Ended` 时暂停底层 `playWhenReady` 并将意图归为 `Idle`（切换数据源保持 `Idle`，调用 `seekTo` 回退到片尾前时切为 `Paused`，已在片尾向后跳转保持 `Ended`）。
- `ComposePlayer.create` 使用 `DefaultDataSource.Factory` 包装 `DefaultHttpDataSource.Factory`（传入 `cache` 时通过惰性 `DataSource.Factory` 将 `SimpleCache` 初始化推迟到后台加载线程），支持本地/Raw 资源与 HTTP/HTTPS 缓存、请求头及音频焦点管理。
- `setLooping` 通过 `Player.REPEAT_MODE_ONE` / `REPEAT_MODE_OFF` 实现底层无缝循环，避免循环边界触发 `ComposePlayerState.Ended`。
- `setSpeed` 前置校验正有限浮点数，避免播放器延迟创建时才抛出非法参数异常。
- 媒体未绑定时调用 `seekTo` 暂存至 `pendingStartPositionMs`（由 `getCurrentPosition()` 同步返回并在首次 `setMediaItem` 时作为起播位置消费）；已绑定时直接调用 `player.seekTo`（已知总时长时限制在 `[0, duration]` 范围内），避免切源预跳转触发双次缓冲并兼容未知时长流。

### RTSP 直播播放器（`RtspPlayerImpl`）

- 继承 `BaseComposePlayer`，通过 `_isPlayingRequested` 管理拉流意图（状态流仅在 `Idle` 与 `Playing` 间流转），前置通过 `isValidRtspUri` 校验 `rtsp`/`rtspt` 协议与主机端口。
- 被动永久失去 `playWhenReady` 时执行 `stop()`；短暂音频抑制期间自动将输出态置回 `Idle` 并停止看门狗，抑制解除时若处于 `STATE_READY` 且累积缓冲延迟超限则调用 `restartPlay()` 追回实时画面，否则自动恢复看门狗；播放请求下流结束（`onStateEnded`）时调用 `restartPlay()` 重新拉流。
- `ComposePlayerRtsp.create` 将小于等于 0 的 `timeoutMs` 归一化为 `RtspMediaSource.DEFAULT_TIMEOUT_MS`（`8000ms`），同时通过 `TimeoutSocketFactory` 将 TCP 信令建连超时设为相同毫秒数以避免离线设备阻塞底层播放线程；`newLivePlayer` 将 `DefaultLoadControl` 设为最小缓冲 32ms、起播与恢复缓冲 0ms、无后置缓冲，并通过 `DefaultRenderersFactory.setAllowedVideoJoiningTimeMs(0L)` 降低首帧拼接延迟。
- `syncWatchdog` 在拉流绑定且无音频抑制的缓冲或播放阶段自动在 `mainScope` 上维护看门狗协程 `_watchdogJob`（连接与初始缓冲阶段将进度停滞阈值放宽至 `timeoutMs + 2000ms` 之后以避免打断底层超时报错，切入 `Playing` 时刷新基准时间），脱离上述状态时立即取消协程并将倍速恢复为 `1.0f`；进度停滞或播放态绑定有效渲染表面下的渲染帧停滞（读取 `DecoderCounters` 前调用 `ensureUpdated()`）超时后向 `eventFlow` 发射 `ComposePlayerRtspEvent` 并调用 `restartPlay()`；当缓冲差大于 `chaseLatency` 时以 `1.2f` 倍速追帧，回落至 `chaseLatency / 2` 及以下时恢复为 `1.0f`。

## 修改与审查约束

- 代码设计与审查聚焦常规业务场景，不考虑极端边缘场景，不为极端情况增加复杂度。
- `:lib` 所有 Media3 依赖均声明为 `implementation`，公开 API 严禁暴露任何 `androidx.media3.*` 类型；公开签名直接引用的 Compose 基础类型声明为 `api`。
- 状态与一次性事件统一通过 `StateFlow` / `Flow` 暴露，不引入命令式 `Callback` 监听接口。
- `ComposePlayerCore`、`ComposePlayer`、`ComposePlayerRtsp` 均为 `sealed interface`，新增实现类需继承 `BaseComposePlayer` 以接入 `ComposePlayerView`。
- `ExoPlayer` 实例操作、状态流更新、错误重试与 RTSP 看门狗均运行在主线程，禁止在后台线程直接调用 `exoPlayer`。
- `:lib` 字符串资源统一以 `lib_compose_player_` 为前缀，新增或修改文案时需同步维护 `values/strings.xml`、`values-en/strings.xml` 和 `values-zh-rTW/strings.xml`。
