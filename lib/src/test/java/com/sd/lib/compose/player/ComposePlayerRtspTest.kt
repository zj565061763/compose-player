package com.sd.lib.compose.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class ComposePlayerRtspTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun rejectsNonRtspOrMalformedUriWithoutCreatingExoPlayerOrRetrying() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      var createdCount = 0
      newRtspPlayer(
        context = context,
        retryOnErrorInterval = 1_000L,
        playerProvider = {
          createdCount++
          FakeExoPlayerController()
        },
      ).usePlayer { player ->
        player.setDataSource("https://example.com/live.mp4")
        player.play()

        assertEquals(0, createdCount)
        assertTrue(player.exceptionFlow.value is ComposePlayerException.DataSource)
        assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(0, createdCount)
      }
    }
  }

  @Test
  fun transitionsBetweenIdleAndPlayingAndRestartsWhenStreamEnds() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      newRtspPlayer(context = context, playerProvider = { controller }).usePlayer { player ->
        player.setDataSource("rtsp://admin:p@ss#1@192.168.1.100:554/live")
        player.play()
        runCurrent()

        assertEquals(
          "rtsp://admin:p%40ss%231@192.168.1.100:554/live",
          controller.mediaItems.single().localConfiguration?.uri.toString(),
        )
        assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)
        assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)

        controller.moveToReady()
        runCurrent()
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        // 已处于 Playing 时短暂重缓冲保持 Playing
        controller.moveToBuffering()
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        // 直播流意外收到 STATE_ENDED 时自动重新绑定媒体项拉流，且重拉缓冲期不回写旧残留视频尺寸
        controller.keepStaleVideoSizeOnStop = true
        controller.updateVideoSize(VideoSize(1920, 1080))
        assertEquals(Pair(1920, 1080), player.videoSizeFlow.value)

        controller.moveToReady()
        controller.moveToEnded()
        runCurrent()
        assertEquals(2, controller.setMediaItemCount)
        assertEquals(2, controller.prepareCount)
        assertNull(player.videoSizeFlow.value)
        assertNull(player.videoSizeDp)

        controller.updateVideoSize(VideoSize(1280, 720))
        assertEquals(Pair(1280, 720), player.videoSizeFlow.value)
      }
    }
  }

  @Test
  fun permanentPlayWhenReadyLossStopsStream() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      newRtspPlayer(context = context, playerProvider = { controller }).usePlayer { player ->
        player.setDataSource("rtsp://192.168.1.100:554/live")
        player.play()
        controller.moveToReady()
        runCurrent()
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        controller.losePlayWhenReady(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        runCurrent()
        assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)
        assertTrue(controller.mediaItems.isEmpty())
      }
    }
  }

  @Test
  fun transientAudioSuppressionSuspendsWatchdogAndRestartsOnlyWhenDriftExceedsLimit() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      newRtspPlayer(
        context = context,
        stuckPositionInterval = 2_000L,
        chaseLatency = 200L,
        playerProvider = { controller },
      ).usePlayer { player ->
        val events = mutableListOf<ComposePlayerRtspEvent>()
        val collector = backgroundScope.launch {
          player.eventFlow.collect { events.add(it) }
        }

        player.setDataSource("rtsp://192.168.1.100:554/live")
        player.play()
        controller.moveToReady()
        controller.currentPosition = 1_000L
        controller.bufferedPosition = 1_500L
        runCurrent()
        assertEquals(1.2f, controller.playbackParameters.speed)

        // 短暂音频抑制：状态回落 Idle，停止看门狗并将倍速重置回 1.0f
        controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        runCurrent()
        assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)
        assertEquals(1.0f, controller.playbackParameters.speed)

        // 抑制期间进度静止超过 stuckPositionInterval 不会误报卡死
        advanceRealtimeAndCoroutines(stepMs = 1_000L, steps = 4)
        assertTrue(events.isEmpty())
        assertEquals(1, controller.setMediaItemCount)

        // 抑制解除且累积延迟未超限（500ms <= 2000ms）：不重启拉流，直接恢复 Playing 与看门狗
        controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        runCurrent()
        assertEquals(1, controller.setMediaItemCount)
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        // 再次抑制并解除且累积延迟超限（3000ms > 2000ms）：立即触发 restartPlay 重新拉流
        controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        controller.bufferedPosition = 4_000L
        controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        runCurrent()
        assertEquals(2, controller.setMediaItemCount)

        collector.cancel()
      }
    }
  }

  @Test
  fun watchdogRelaxesConnectingStuckThresholdThenDetectsPlayingStuckPosition() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      newRtspPlayer(
        context = context,
        stuckPositionInterval = 3_000L,
        stuckRenderedFrameInterval = 0L,
        chaseLatency = 0L,
        timeoutMs = 8_000L,
        playerProvider = { controller },
      ).usePlayer { player ->
        val events = mutableListOf<ComposePlayerRtspEvent>()
        val collector = backgroundScope.launch {
          player.eventFlow.collect { events.add(it) }
        }

        player.setDataSource("rtsp://192.168.1.100:554/live")
        player.play()
        runCurrent()

        // 连接初始缓冲阶段阈值为 max(3000, 8000 + 2000) = 10000ms，推进 5s 不触发重连
        advanceRealtimeAndCoroutines(stepMs = 1_000L, steps = 5)
        assertTrue(events.isEmpty())
        assertEquals(1, controller.setMediaItemCount)

        // 推进至超过 10000ms 触发连接缓冲卡死重连
        advanceRealtimeAndCoroutines(stepMs = 1_000L, steps = 6)
        assertEquals(listOf(ComposePlayerRtspEvent.StuckPosition), events)
        assertEquals(2, controller.setMediaItemCount)

        // 进入 Playing 后阈值收敛为 3000ms
        controller.moveToReady()
        runCurrent()
        advanceRealtimeAndCoroutines(stepMs = 1_000L, steps = 4)
        assertEquals(
          listOf(ComposePlayerRtspEvent.StuckPosition, ComposePlayerRtspEvent.StuckPosition),
          events,
        )
        assertEquals(3, controller.setMediaItemCount)

        collector.cancel()
      }
    }
  }

  @Test
  fun watchdogDetectsStuckRenderedFrameOnlyWhenSurfaceIsValid() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      val counters = DecoderCounters().also { it.renderedOutputBufferCount = 10 }
      controller.videoDecoderCounters = counters

      newRtspPlayer(
        context = context,
        stuckPositionInterval = 10_000L,
        stuckRenderedFrameInterval = 2_000L,
        chaseLatency = 0L,
        playerProvider = { controller },
      ).usePlayer { player ->
        val events = mutableListOf<ComposePlayerRtspEvent>()
        val collector = backgroundScope.launch {
          player.eventFlow.collect { events.add(it) }
        }

        player.setDataSource("rtsp://192.168.1.100:554/live")
        player.play()
        controller.moveToReady()
        runCurrent()

        // 未绑定有效渲染表面（0x0）时不触发 StuckRenderedFrame
        repeat(4) {
          controller.currentPosition += 1_000L
          advanceRealtimeAndCoroutines(stepMs = 1_000L)
        }
        assertTrue(events.isEmpty())

        // 绑定有效渲染表面后，进度前进但渲染帧数停滞超过 2000ms 触发重连
        controller.surfaceSize = Size(1920, 1080)
        repeat(4) {
          controller.currentPosition += 1_000L
          advanceRealtimeAndCoroutines(stepMs = 1_000L)
        }
        assertEquals(listOf(ComposePlayerRtspEvent.StuckRenderedFrame), events)
        assertEquals(2, controller.setMediaItemCount)

        collector.cancel()
      }
    }
  }

  @Test
  fun watchdogAdjustsPlaybackSpeedToChaseLiveLatency() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      newRtspPlayer(
        context = context,
        stuckPositionInterval = 0L,
        stuckRenderedFrameInterval = 0L,
        chaseLatency = 200L,
        playerProvider = { controller },
      ).usePlayer { player ->
        player.setDataSource("rtsp://192.168.1.100:554/live")
        player.play()
        controller.moveToReady()
        controller.currentPosition = 1_000L
        controller.bufferedPosition = 1_300L
        runCurrent()
        assertEquals(1.2f, controller.playbackParameters.speed)

        // 介于 (chaseLatency / 2, chaseLatency] 滞回区间保持当前倍速
        controller.currentPosition = 1_150L
        advanceRealtimeAndCoroutines(stepMs = 100L)
        assertEquals(1.2f, controller.playbackParameters.speed)

        // 回落至 chaseLatency / 2 (100ms) 及以下恢复 1.0f
        controller.currentPosition = 1_200L
        advanceRealtimeAndCoroutines(stepMs = 100L)
        assertEquals(1.0f, controller.playbackParameters.speed)
      }

      // chaseLatency == 1ms 边界下追平至 0ms 仍可正常恢复 1.0f
      val minLatencyController = FakeExoPlayerController()
      newRtspPlayer(
        context = context,
        stuckPositionInterval = 0L,
        stuckRenderedFrameInterval = 0L,
        chaseLatency = 1L,
        playerProvider = { minLatencyController },
      ).usePlayer { player ->
        player.setDataSource("rtsp://192.168.1.100:554/live")
        player.play()
        minLatencyController.moveToReady()
        minLatencyController.currentPosition = 1_000L
        minLatencyController.bufferedPosition = 1_005L
        runCurrent()
        assertEquals(1.2f, minLatencyController.playbackParameters.speed)

        minLatencyController.currentPosition = 1_005L
        advanceRealtimeAndCoroutines(stepMs = 100L)
        assertEquals(1.0f, minLatencyController.playbackParameters.speed)
      }
    }
  }

  @Test
  fun playbackErrorRebindsMediaOnRetryWhileAuthErrorStopsRetrying() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      ComposePlayerRtsp.create(context = context, timeoutMs = 0L).release()

      val controller = FakeExoPlayerController()
      newRtspPlayer(
        context = context,
        retryOnErrorInterval = 2_000L,
        playerProvider = { controller },
      ).usePlayer { player ->
        player.setDataSource("rtsp://192.168.1.100:554/live")
        player.play()
        runCurrent()
        assertEquals(1, controller.setMediaItemCount)

        // 常规网络异常：按间隔重绑媒体项重试（rebindMediaOnIdle = true）
        controller.failWith(newTestPlaybackException())
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, controller.setMediaItemCount)

        // RTSP 鉴权异常：发射 Auth 异常并停止重试
        controller.failWith(
          newTestPlaybackException(cause = RtspMediaSource.RtspPlaybackException("DESCRIBE 401")),
        )
        assertTrue(player.exceptionFlow.value is ComposePlayerException.Auth)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(2, controller.setMediaItemCount)
      }
    }
  }

  @Test
  fun synchronousExceptionCollectorCanImmediatelyRestartStreamWithoutBeingStopped() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      newRtspPlayer(
        context = context,
        retryOnErrorInterval = 0L,
        playerProvider = { controller },
      ).usePlayer { player ->
        var handledAuth = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          player.exceptionFlow.collect { error ->
            if (error is ComposePlayerException.Auth && !handledAuth) {
              handledAuth = true
              player.setDataSource("rtsp://admin:newpass@192.168.1.100:554/live")
              player.play()
            }
          }
        }

        player.setDataSource("rtsp://admin:oldpass@192.168.1.100:554/live")
        player.play()
        runCurrent()
        assertEquals(1, controller.setMediaItemCount)

        controller.failWith(
          newTestPlaybackException(cause = RtspMediaSource.RtspPlaybackException("DESCRIBE 401")),
        )
        assertTrue(handledAuth)
        assertEquals(2, controller.setMediaItemCount)
        controller.moveToReady()
        runCurrent()
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        collector.cancel()
      }
    }
  }

  @Test
  fun resumesWatchdogAfterBufferingSuppressionAndSwitchesStreamOrStopsCleanly() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      newRtspPlayer(
        context = context,
        retryOnErrorInterval = 2_000L,
        stuckPositionInterval = 3_000L,
        stuckRenderedFrameInterval = 0L,
        chaseLatency = 0L,
        timeoutMs = 3_000L,
        playerProvider = { controller },
      ).usePlayer { player ->
        val events = mutableListOf<ComposePlayerRtspEvent>()
        val collector = backgroundScope.launch {
          player.eventFlow.collect { events.add(it) }
        }

        player.setDataSource("rtsp://192.168.1.100:554/ch1")
        player.play()
        runCurrent()

        // 缓冲态下经历短暂音频抑制再解除抑制，看门狗自动恢复并在超时后触发重连
        controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        runCurrent()
        controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        runCurrent()

        advanceRealtimeAndCoroutines(stepMs = 1_000L, steps = 6)
        assertEquals(listOf(ComposePlayerRtspEvent.StuckPosition), events)
        assertEquals(2, controller.setMediaItemCount)

        // 拉流请求下切换新地址立即重新绑定新流
        player.setDataSource("rtsp://192.168.1.100:554/ch2")
        runCurrent()
        assertEquals(3, controller.setMediaItemCount)
        assertEquals(
          "rtsp://192.168.1.100:554/ch2",
          controller.mediaItems.single().localConfiguration?.uri.toString(),
        )

        // 报错等待重试期间调用 stop() 取消重试与拉流请求
        controller.failWith(newTestPlaybackException())
        player.stop()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(3, controller.setMediaItemCount)
        assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)

        collector.cancel()
      }
    }
  }
}

private fun newRtspPlayer(
  context: Context,
  retryOnErrorInterval: Long = 5_000L,
  stuckPositionInterval: Long = 5_000L,
  stuckRenderedFrameInterval: Long = 5_000L,
  chaseLatency: Long = 200L,
  timeoutMs: Long = 8_000L,
  playerProvider: () -> FakeExoPlayerController,
): RtspPlayerImpl {
  return RtspPlayerImpl(
    context = context,
    retryOnErrorInterval = retryOnErrorInterval,
    stuckPositionInterval = stuckPositionInterval,
    stuckRenderedFrameInterval = stuckRenderedFrameInterval,
    chaseLatency = chaseLatency,
    timeoutMs = timeoutMs,
    playerProvider = { playerProvider().exoPlayer },
  )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.advanceRealtimeAndCoroutines(
  stepMs: Long,
  steps: Int = 1,
) {
  repeat(steps) {
    ShadowSystemClock.advanceBy(stepMs, TimeUnit.MILLISECONDS)
    advanceTimeBy(stepMs.milliseconds)
    runCurrent()
  }
}
