package com.sd.lib.compose.player

import android.content.Context
import androidx.compose.ui.geometry.Size
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComposePlayerVodTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun delaysExoPlayerCreationAndInheritsPresetOptions() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var createdCount = 0
    val controller = FakeExoPlayerController()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 5_000L,
      playerProvider = {
        createdCount++
        controller.exoPlayer
      },
    ).usePlayer { player ->
      player.setDataSource("https://example.com/video.mp4")
      player.setMute(true)
      player.setSpeed(1.5f)
      player.setLooping(true)
      player.seekTo(3_500L)

      assertEquals(0, createdCount)
      assertNull(player.exoPlayer)
      assertTrue(player.isMutedFlow.value)
      assertEquals(1.5f, player.speedFlow.value)
      assertTrue(player.isLoopingFlow.value)
      assertEquals(3_500L, player.getCurrentPosition())
      assertNull(player.getDuration())

      player.play()

      assertEquals(1, createdCount)
      assertNotNull(player.exoPlayer)
      assertEquals(0f, controller.volume)
      assertEquals(1.5f, controller.playbackParameters.speed)
      assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
      assertEquals(3_500L, controller.lastStartPositionMs)
      assertEquals(1, controller.prepareCount)
      assertTrue(controller.playWhenReady)

      // 底层实例已创建后动态修改静音、倍速与循环立即同步到底层播放器
      player.setMute(false)
      assertFalse(player.isMutedFlow.value)
      assertEquals(1f, controller.volume)

      player.setSpeed(2.0f)
      assertEquals(2.0f, player.speedFlow.value)
      assertEquals(2.0f, controller.playbackParameters.speed)

      player.setLooping(false)
      assertFalse(player.isLoopingFlow.value)
      assertEquals(Player.REPEAT_MODE_OFF, controller.repeatMode)
    }
  }

  @Test
  fun setSpeedRejectsNonPositiveOrNonFiniteValues() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = { FakeExoPlayerController().exoPlayer },
    ).usePlayer { player ->
      assertThrows(IllegalArgumentException::class.java) { player.setSpeed(0f) }
      assertThrows(IllegalArgumentException::class.java) { player.setSpeed(-1f) }
      assertThrows(IllegalArgumentException::class.java) { player.setSpeed(Float.NaN) }
      assertThrows(IllegalArgumentException::class.java) { player.setSpeed(Float.POSITIVE_INFINITY) }
      assertEquals(1.0f, player.speedFlow.value)
    }
  }

  @Test
  fun blankDataSourceEmitsDataSourceBlankWithoutCreatingPlayerOrRetrying() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      var createdCount = 0
      VodPlayerImpl(
        context = context,
        retryOnErrorInterval = 1_000L,
        playerProvider = {
          createdCount++
          FakeExoPlayerController().exoPlayer
        },
      ).usePlayer { player ->
        player.setDataSource("   ")
        player.play()

        assertEquals(0, createdCount)
        assertTrue(player.exceptionFlow.value is ComposePlayerException.DataSourceBlank)
        assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(0, createdCount)
      }
    }
  }

  @Test
  fun sameTrimmedDataSourceDoesNotStopActivePlayback() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = FakeExoPlayerController()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = { controller.exoPlayer },
    ).usePlayer { player ->
      player.setDataSource("https://example.com/a.mp4")
      player.play()
      controller.moveToReady(durationMs = 10_000L)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

      player.setDataSource("  https://example.com/a.mp4  ")
      assertEquals(0, controller.stopCount)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)
    }
  }

  @Test
  fun preparePreloadsWithoutAutoPlayingAndIgnoresUnsetPlayingOrEnded() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var createdCount = 0
    val controller = FakeExoPlayerController()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = {
        createdCount++
        controller.exoPlayer
      },
    ).usePlayer { player ->
      // 未设置数据源前调用 prepare 为空操作
      player.prepare()
      player.setDataSource("https://example.com/preload.mp4")
      assertEquals(0, createdCount)

      player.prepare()
      assertEquals(1, createdCount)
      assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)
      assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)

      controller.moveToReady(durationMs = 12_000L, size = VideoSize(1920, 1080))
      assertFalse(controller.playWhenReady)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)
      assertEquals(ComposePlayerBufferState.Ready, player.bufferStateFlow.value)
      assertEquals(12_000L, player.durationFlow.value)
      assertEquals(Pair(1920, 1080), player.videoSizeFlow.value)
      assertTrue(player.videoSizeDp == Size(1920f, 1080f))

      // 验证非正方形像素宽高比（pixelWidthHeightRatio）同步折算为 videoSizeDp
      controller.moveToReady(durationMs = 12_000L, size = VideoSize(1440, 1080, 1.5f))
      assertTrue(player.videoSizeDp == Size(1440f, 720f))
      controller.moveToReady(durationMs = 12_000L, size = VideoSize(1920, 1080, 0.5f))
      assertTrue(player.videoSizeDp == Size(960f, 1080f))

      // 播放中调用 prepare 不打断播放
      player.play()
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)
      player.prepare()
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

      // 播放结束态调用 prepare 不重置状态
      controller.moveToEnded()
      assertEquals(ComposePlayerState.Ended, player.playerStateFlow.value)
      player.prepare()
      assertEquals(ComposePlayerState.Ended, player.playerStateFlow.value)

      // 已发起 play() 处于首次缓冲期（STATE_BUFFERING）时调用 prepare 不打断起播
      player.setDataSource("https://example.com/next.mp4")
      player.play()
      assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)
      assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)
      player.prepare()
      assertTrue(controller.playWhenReady)
      controller.moveToReady(durationMs = 8_000L)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)
    }
  }

  @Test
  fun prepareDuringErrorRetryCancelsRetryAndPreloadsPaused() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      VodPlayerImpl(
        context = context,
        retryOnErrorInterval = 2_000L,
        playerProvider = { controller.exoPlayer },
      ).usePlayer { player ->
        player.setDataSource("https://example.com/video.mp4")
        player.play()
        controller.failWith(newTestPlaybackException())
        assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)

        // 报错等待重试期间调用 prepare，取消旧重试并切换为预加载暂停意图
        player.prepare()
        assertEquals(2, controller.prepareCount)
        controller.moveToReady(durationMs = 10_000L)
        assertFalse(controller.playWhenReady)
        assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertFalse(controller.playWhenReady)
        assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)
      }
    }
  }

  @Test
  fun switchingDataSourceStartsImmediatelyWhenPlayingAndResetsWhenPaused() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = FakeExoPlayerController()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = { controller.exoPlayer },
    ).usePlayer { player ->
      // 播放态下切换数据源立即同步绑定并开始加载新源
      player.setDataSource("https://example.com/1.mp4")
      player.play()
      controller.moveToReady(durationMs = 10_000L)
      assertEquals(1, controller.setMediaItemCount)

      player.setDataSource("https://example.com/2.mp4")
      assertEquals(2, controller.setMediaItemCount)
      assertTrue(controller.playWhenReady)
      controller.moveToReady(durationMs = 20_000L)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

      // 暂停态下切换数据源重置回 Idle，不自动发起网络准备
      player.pause()
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)
      player.setDataSource("https://example.com/3.mp4")
      assertEquals(2, controller.setMediaItemCount)
      assertEquals(ComposePlayerState.Idle, player.playerStateFlow.value)
    }
  }

  @Test
  fun seekToClampsBoundsAndHandlesEndedRewindAndReplay() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = FakeExoPlayerController()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = { controller.exoPlayer },
    ).usePlayer { player ->
      player.setDataSource("https://example.com/video.mp4")
      player.play()
      controller.moveToReady(durationMs = 10_000L)

      player.seekTo(-500L)
      assertEquals(0L, player.getCurrentPosition())

      player.seekDelta(4_000L)
      assertEquals(4_000L, player.getCurrentPosition())

      player.seekTo(25_000L)
      assertEquals(10_000L, player.getCurrentPosition())

      // 自然播放结束进入 Ended
      controller.moveToEnded()
      assertEquals(ComposePlayerState.Ended, player.playerStateFlow.value)
      assertFalse(controller.playWhenReady)

      // 在片尾继续向后跳转保持 Ended
      val seekCountBefore = controller.seekToHistory.size
      player.seekTo(12_000L)
      assertEquals(seekCountBefore, controller.seekToHistory.size)
      assertEquals(ComposePlayerState.Ended, player.playerStateFlow.value)

      // 回退到片尾之前转为 Paused
      player.seekTo(3_000L)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)
      assertEquals(3_000L, player.getCurrentPosition())
      controller.moveToReady(durationMs = 10_000L)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)

      // 暂停态跳转进入缓冲期后立即调用 play()，缓冲完成前直接切为 Playing
      player.seekTo(5_000L)
      assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)
      player.play()
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)
      controller.moveToReady(durationMs = 10_000L)

      // 再次播放到片尾后直接调用 play()，自动回到 0ms 起播且缓冲期间立即进入 Playing
      controller.moveToEnded()
      assertEquals(ComposePlayerState.Ended, player.playerStateFlow.value)
      player.play()
      assertEquals(0L, controller.seekToHistory.last())
      assertTrue(controller.playWhenReady)
      assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)
    }
  }

  @Test
  fun handlesTransientAudioSuppressionAndPermanentAudioFocusLoss() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = FakeExoPlayerController()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = { controller.exoPlayer },
    ).usePlayer { player ->
      player.setDataSource("https://example.com/video.mp4")
      player.play()
      controller.moveToReady(durationMs = 10_000L)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

      // 短暂音频抑制期间对外呈现为 Paused，缓冲完成前解除抑制立即恢复 Playing
      controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)

      controller.moveToBuffering()
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)

      controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

      // 再次抑制至缓冲完成，解除抑制后恢复 Playing
      controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
      controller.moveToReady(durationMs = 10_000L)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)

      controller.updateSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
      assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

      // 永久失去音频焦点后切为 Paused，不再自动恢复播放
      controller.losePlayWhenReady(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)
      controller.moveToReady(durationMs = 10_000L)
      assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)
    }
  }

  @Test
  fun resetsIdleStatesBeforeEmittingExceptionAndHandlesRetryAndBehindLiveWindow() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      VodPlayerImpl(
        context = context,
        retryOnErrorInterval = 2_000L,
        playerProvider = { controller.exoPlayer },
      ).usePlayer { player ->
        player.setDataSource("https://example.com/live_hls.m3u8")
        player.play()
        controller.moveToReady(durationMs = 30_000L, size = VideoSize(1280, 720))
        controller.renderFirstFrame()
        assertTrue(player.isRenderedFirstFrameFlow.value)
        assertTrue(player.isRenderedFirstFrame)

        var observedStateAtError: ComposePlayerState? = null
        var observedBufferAtError: ComposePlayerBufferState? = null
        var observedSizeAtError: Pair<Int, Int>? = Pair(1, 1)
        var observedFirstFrameAtError = true

        val collector = backgroundScope.launch {
          player.exceptionFlow.collect { error ->
            if (error != null) {
              observedStateAtError = player.playerStateFlow.value
              observedBufferAtError = player.bufferStateFlow.value
              observedSizeAtError = player.videoSizeFlow.value
              observedFirstFrameAtError = player.isRenderedFirstFrameFlow.value
            }
          }
        }
        runCurrent()

        // 触发 BEHIND_LIVE_WINDOW 错误：先复位空闲态再发异常，且不在回调内同步调用 seekToDefaultPosition
        controller.failWith(
          newTestPlaybackException(errorCode = PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW),
        )
        runCurrent()

        assertEquals(ComposePlayerState.Idle, observedStateAtError)
        assertEquals(ComposePlayerBufferState.None, observedBufferAtError)
        assertNull(observedSizeAtError)
        assertNull(player.videoSizeDp)
        assertNull(player.durationFlow.value)
        assertNull(player.getDuration())
        assertFalse(observedFirstFrameAtError)
        assertFalse(player.isRenderedFirstFrame)
        assertEquals(0, controller.seekToDefaultPositionCount)

        // 推进重试间隔：点播保留媒体绑定不重复 setMediaItem，但在 prepare 前执行 seekToDefaultPosition
        advanceTimeBy(1_999.milliseconds)
        runCurrent()
        assertEquals(1, controller.prepareCount)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1, controller.setMediaItemCount)
        assertEquals(1, controller.seekToDefaultPositionCount)
        assertEquals(2, controller.prepareCount)

        // 恢复 Ready 后自动清空 exceptionFlow
        controller.moveToReady(durationMs = 30_000L)
        assertNull(player.exceptionFlow.value)
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        // HTTP 401 鉴权错误不自动重试
        controller.failWith(
          newTestPlaybackException(cause = newTestHttpResponseException(401)),
        )
        assertTrue(player.exceptionFlow.value is ComposePlayerException.Auth)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(2, controller.prepareCount)

        collector.cancel()
      }
    }
  }

  @Test
  fun supportsRawResourceDataSourceFactoryCreateAndReleaseReuse() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ComposePlayer.create(context = context).release()

    var createdCount = 0
    var activeController = FakeExoPlayerController()
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = {
        createdCount++
        activeController.exoPlayer
      },
    ).usePlayer { player ->
      player.setDataSource(resId = 98765)
      player.play()
      assertEquals(1, createdCount)
      assertEquals(
        "android.resource:/98765",
        activeController.mediaItems.single().localConfiguration?.uri.toString(),
      )

      val firstController = activeController
      player.release()
      assertTrue(firstController.isReleased)
      assertEquals(1, firstController.clearVideoSurfaceCount)
      assertNull(player.exoPlayer)

      // release 后可重新绑定数据源创建新底层实例复用
      activeController = FakeExoPlayerController()
      player.setDataSource("https://example.com/reborn.mp4")
      player.play()
      assertEquals(2, createdCount)
      assertNotNull(player.exoPlayer)
    }
  }

  @Test
  fun synchronousExceptionCollectorCanImmediatelyRestartPlaybackWithoutBeingOverwritten() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      VodPlayerImpl(
        context = context,
        retryOnErrorInterval = 0L,
        playerProvider = { controller.exoPlayer },
      ).usePlayer { player ->
        var handledAuth = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          player.exceptionFlow.collect { error ->
            if (error is ComposePlayerException.Auth && !handledAuth) {
              handledAuth = true
              player.setDataSource("https://example.com/refreshed_token.mp4")
              player.play()
            }
          }
        }

        player.setDataSource("https://example.com/expired_token.mp4")
        player.play()
        controller.moveToReady(durationMs = 10_000L)

        // 触发鉴权错误时，同步收集器立即切源并调用 play()，新播放意图不应被后续错误处理覆盖
        controller.failWith(newTestPlaybackException(cause = newTestHttpResponseException(401)))
        assertTrue(handledAuth)
        assertEquals(2, controller.setMediaItemCount)
        assertTrue(controller.playWhenReady)

        controller.moveToReady(durationMs = 10_000L)
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        collector.cancel()
      }
    }
  }

  @Test
  fun syncsStatesImmediatelyWhenExoPlayerDefersOnEventsAndClearsDetachedViewSurface() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController().also { it.deferOnEvents = true }
      VodPlayerImpl(
        context = context,
        retryOnErrorInterval = 1_000L,
        playerProvider = { controller.exoPlayer },
      ).usePlayer { player ->
        // 首次 play() 在 onEvents 异步派发前，本帧已同步进入 Buffering
        player.setDataSource("https://example.com/video.mp4")
        player.play()
        assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)
        controller.flushDeferredEvents()

        controller.moveToReady(durationMs = 10_000L)
        controller.flushDeferredEvents()
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)

        // Paused 态调用 play() 在 onEvents 异步派发前，本帧已同步切回 Playing
        player.pause()
        controller.flushDeferredEvents()
        assertEquals(ComposePlayerState.Paused, player.playerStateFlow.value)

        player.play()
        assertEquals(ComposePlayerState.Playing, player.playerStateFlow.value)
        controller.flushDeferredEvents()

        // 预加载报错自动重试在 onEvents 异步派发前，本帧已同步恢复 Buffering
        player.pause()
        controller.flushDeferredEvents()
        controller.failWith(newTestPlaybackException())
        controller.flushDeferredEvents()
        assertEquals(ComposePlayerBufferState.None, player.bufferStateFlow.value)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)
        controller.flushDeferredEvents()

        // 挂载视图同帧切换不触发 clearVideoSurface，上层弹窗卸载通知新栈顶重绑，全部卸载后在下一帧清理表面引用
        val firstToken = player.attachPlayerView()
        player.detachPlayerView(firstToken)
        var baseRebindCount = 0
        val baseToken = player.attachPlayerView(PlayerViewToken { baseRebindCount++ })
        runCurrent()
        assertEquals(0, controller.clearVideoSurfaceCount)
        assertEquals(0, baseRebindCount)

        // 叠加弹窗视图后关闭弹窗：不清空表面，立即通知新栈顶视图重绑接管
        val dialogToken = player.attachPlayerView()
        player.detachPlayerView(dialogToken)
        runCurrent()
        assertEquals(0, controller.clearVideoSurfaceCount)
        assertEquals(1, baseRebindCount)

        player.detachPlayerView(baseToken)
        assertEquals(0, controller.clearVideoSurfaceCount)
        runCurrent()
        assertEquals(1, controller.clearVideoSurfaceCount)
      }
    }
  }

  @Test
  fun switchingDataSourceDoesNotRestoreStaleVideoSizeWhileBuffering() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = FakeExoPlayerController().also { it.keepStaleVideoSizeOnStop = true }
    VodPlayerImpl(
      context = context,
      retryOnErrorInterval = 0L,
      playerProvider = { controller.exoPlayer },
    ).usePlayer { player ->
      player.setDataSource("https://example.com/landscape.mp4")
      player.play()
      controller.moveToReady(durationMs = 10_000L, size = VideoSize(1920, 1080))
      assertEquals(Pair(1920, 1080), player.videoSizeFlow.value)
      assertTrue(player.videoSizeDp == Size(1920f, 1080f))

      // 模拟底层渲染器释放完成前 exoPlayer.videoSize 仍残留旧尺寸：切源缓冲阶段保持 null，不回写旧尺寸
      player.setDataSource("https://example.com/portrait.mp4")
      player.play()
      assertEquals(ComposePlayerBufferState.Buffering, player.bufferStateFlow.value)
      assertNull(player.videoSizeFlow.value)
      assertNull(player.videoSizeDp)

      // 旧渲染器异步回调 VideoSize.UNKNOWN 后仍为 null，新视频派发尺寸事件后更新为新尺寸
      controller.updateVideoSize(VideoSize.UNKNOWN)
      assertNull(player.videoSizeFlow.value)
      controller.moveToReady(durationMs = 15_000L, size = VideoSize(720, 1280))
      assertEquals(Pair(720, 1280), player.videoSizeFlow.value)
      assertTrue(player.videoSizeDp == Size(720f, 1280f))
    }
  }
}
