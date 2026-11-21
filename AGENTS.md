# compose-player

基于 Jetpack Media3（ExoPlayer）封装的 Jetpack Compose 播放器库，支持常规音视频点播与 RTSP 低延迟直播。

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
| `ComposePlayer.kt` | `ComposePlayer`、`PlayerImpl`、`rememberComposePlayer` | 通用播放器接口、状态流、协程等待扩展及基于 `ExoPlayer` 的默认实现 |
| `ComposePlayerRtsp.kt` | `ComposePlayerRtsp`、`RtspPlayerImpl`、`rememberComposePlayerRtsp` | RTSP 直播播放器接口与实现，内置低延迟缓冲配置、卡死看门狗与倍速追帧 |
| `ComposePlayerView.kt` | `ComposePlayerView`、`composePlayerViewRebindKeyOnResume` | 封装 Media3 `PlayerSurface` 与缩放适配，支持多页面共用播放器时在 `onResume` 重绑渲染表面 |
| `ComposePlayerCache.kt` | `ComposePlayerCache` | 提供懒加载单例 `SimpleCache`（目录 `cacheDir/compose_player_cache`，LRU 上限 1GB） |
| `ComposePlayerException.kt` | `ComposePlayerException` 及其子类、`desc` | 播放异常定义、鉴权错误识别与多语言错误描述扩展 |
| `PlayerExt.kt` | `extSetMute`、`extSetSpeed` | 封装 `Player` 音轨禁用静音与倍速调整 |

## 内部实现约定

### 通用播放器（`PlayerImpl`）

- `_exoPlayer` 使用 Compose `mutableStateOf<ExoPlayer?>(null)` 持有，首次调用 `prepare()` 且数据源非空时才创建实例，使 `ComposePlayerView` 能自动响应底层实例的创建与释放。
- `_requireState` 记录调用方期望的目标状态（`Playing`、`Paused`、`Idle`、`Ended`），数据源切换、`Player.STATE_READY` 回调与错误重试均按 `_requireState` 同步底层播放器。
- `setDataSource` 会对 URI 执行 `trim()`，仅在与当前数据源不同时停止旧播放并按 `_requireState` 触发加载。
- `prepare()` 通过 `encodeUserInfoIfNeed` 拆分 URI `userInfo` 中的用户名与密码并分别做百分比编码，兼容含 `@` 等特殊字符的鉴权凭据。
- `ComposePlayer.create` 传入 `cache` 时，使用 `DefaultHttpDataSource.Factory` 作为上游数据源构建 `CacheDataSource` 并开启 `FLAG_IGNORE_CACHE_ON_ERROR`。
- `exoPlayer` 未创建或总时长未知时调用 `seekTo` 会暂存至 `_seekToPositionMs`，待进入 `Player.STATE_READY` 后自动限制在 `[0, duration]` 范围内执行跳转。
- `extSetMute` 通过 `TrackSelectionParameters` 禁用或启用 `C.TRACK_TYPE_AUDIO` 轨道实现静音，不修改播放器音量。
- `onPlayerError` 递归检查异常链消息是否含 `401` 或 `403`，命中时包装为 `ComposePlayerExceptionAuth` 且不发起重试。
- 非鉴权异常在 `_requireState` 为 `Playing` 或 `Paused` 且 `retryOnErrorInterval > 0` 时通过主线程 `Handler` 延迟重试；主动调用 `play()`、`pause()`、`stop()` 会取消待执行重试，进入 `Player.STATE_READY`、`stopPlayer()` 或 `release()` 时清空 `exceptionFlow`。

### RTSP 直播播放器（`RtspPlayerImpl`）

- 继承 `PlayerImpl`，`pause()` 直接委托 `super.stop()`，`seekTo` 与 `setLooping` 为空实现，流结束（`handleStateEnded`）时调用 `restartPlay()` 重新拉流。
- `newLivePlayer` 将 `DefaultLoadControl` 设为最小缓冲 32ms、起播与恢复缓冲 0ms、无后置缓冲，并将视频渲染器 `allowedVideoJoiningTimeMs` 设为 `0L` 以降低延迟。
- 播放开始（`onIsPlayingChanged(true)`）时启动主线程看门狗 `_playWatchdogJob`，`_requireState` 离开 `Playing` 或调用 `release()` 时停止；轮询间隔在启用追帧时为 `(chaseLatency / 2).coerceAtLeast(100)`，否则为 `1000ms`。
- 看门狗在 `currentPosition` 超过 `stuckPositionInterval` 未变化，或 `videoDecoderCounters.renderedOutputBufferCount` 超过 `stuckRenderedFrameInterval` 未增长时，触发 `EventCallback` 并调用 `restartPlay()`。
- 看门狗在 `bufferedPosition - currentPosition > chaseLatency` 时将倍速提升为 `speedFlow.value * 1.2f` 追帧，延迟回落至 `chaseLatency / 2` 以下时恢复为 `speedFlow.value`。

## 修改约束

- `:lib` 通过 `api` 暴露 `media3-common`、`media3-exoplayer`、`media3-exoplayer-rtsp` 和 `media3-ui-compose`，调整依赖或公开接口时需兼顾下游兼容性。
- `ComposePlayerView` 内部要求 `require(player is PlayerImpl)`，新增 `ComposePlayer` 实现时需继承 `PlayerImpl` 或同步调整视图绑定逻辑。
- `ExoPlayer` 实例操作、状态流更新、错误重试与 RTSP 看门狗均运行在主线程，禁止在后台线程直接调用 `exoPlayer`。
- `:lib` 字符串资源统一以 `lib_compose_player_` 为前缀，新增或修改文案时需同步维护 `values/strings.xml`、`values-en/strings.xml` 和 `values-zh-rTW/strings.xml`。
