package com.sd.lib.compose.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 播放器核心接口，定义常规点播与直播共用的状态及控制能力 */
sealed interface ComposePlayerCore {
  /** 播放器状态 */
  val playerStateFlow: StateFlow<ComposePlayerState>

  /** 缓冲状态 */
  val bufferStateFlow: StateFlow<ComposePlayerBufferState>

  /** 播放异常，加载成功后清空为 null */
  val exceptionFlow: StateFlow<ComposePlayerException?>

  /** 视频分辨率（宽，高），null 表示未知，非 null 时宽高一定大于 0 */
  val videoSizeFlow: StateFlow<Pair<Int, Int>?>

  /** 是否已经渲染了首帧 */
  val isRenderedFirstFrameFlow: StateFlow<Boolean>

  /** 是否静音 */
  val isMutedFlow: StateFlow<Boolean>

  /** 设置数据源 */
  fun setDataSource(uri: String)

  /** 开始播放 */
  fun play()

  /** 停止播放 */
  fun stop()

  /** 当前播放进度时间点（毫秒） */
  fun getCurrentPosition(): Long

  /** 设置静音 */
  fun setMute(mute: Boolean)

  /** 释放播放器资源 */
  fun release()
}

/** 播放器状态 */
enum class ComposePlayerState {
  /** 空闲 */
  Idle,

  /** 播放中 */
  Playing,

  /** 暂停 */
  Paused,

  /** 播放结束 */
  Ended,
}

/** 播放器缓冲状态 */
enum class ComposePlayerBufferState {
  /** 空闲 */
  None,

  /** 缓冲中 */
  Buffering,

  /** 准备完毕 */
  Ready,
}

/** 挂起等待播放器处于指定状态[state] */
suspend fun ComposePlayerCore.awaitPlayerState(state: ComposePlayerState) {
  playerStateFlow.first { it == state }
}

/** 挂起等待缓冲准备完毕[ComposePlayerBufferState.Ready] */
suspend fun ComposePlayerCore.awaitBufferReady() {
  bufferStateFlow.first { it == ComposePlayerBufferState.Ready }
}

/** 挂起等待首帧渲染完毕 */
suspend fun ComposePlayerCore.awaitRenderedFirstFrame() {
  isRenderedFirstFrameFlow.first { it }
}

/**
 * 按[interval]采样当前播放进度（毫秒），仅在播放中定时轮询，状态切换或跳转时立即发射
 *
 * @param interval 播放中的采样时间间隔，默认 200ms
 */
fun ComposePlayerCore.positionFlow(
  interval: Duration = 200.milliseconds,
): Flow<Long> {
  return (this as BaseComposePlayer).observePosition(interval)
}
