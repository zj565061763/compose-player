package com.sd.lib.compose.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComposePlayerCoreFlowTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun positionFlowPollsOnlyWhilePlayingAndEmitsImmediatelyOnSeekOrDiscontinuity() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      VodPlayerImpl(
        context = context,
        retryOnErrorInterval = 0L,
        playerProvider = { controller.exoPlayer },
      ).usePlayer { player ->
        val emissions = mutableListOf<Long>()
        val collectJob = backgroundScope.launch {
          player.positionFlow(interval = 100.milliseconds).collect { pos ->
            emissions.add(pos)
          }
        }
        runCurrent()
        assertEquals(listOf(0L), emissions)

        // 空闲态推进时间不会轮询额外发射，预跳转立即发射新起点
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(listOf(0L), emissions)

        player.setDataSource("https://example.com/video.mp4")
        player.seekTo(1_000L)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), emissions)

        // 进入播放态后按间隔轮询采样，位置未变化时自动去重
        player.play()
        controller.moveToReady(durationMs = 20_000L)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), emissions)

        controller.currentPosition = 1_100L
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_100L), emissions)

        // 位置保持不变时不会重复发射
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_100L), emissions)

        controller.currentPosition = 1_250L
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_100L, 1_250L), emissions)

        // 暂停后停止轮询，底层位置变化不会被动触发采样，但显式不连续事件或跳转立即发射
        player.pause()
        runCurrent()
        controller.currentPosition = 2_000L
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_100L, 1_250L), emissions)

        controller.notifyPositionDiscontinuity(3_500L)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 1_100L, 1_250L, 3_500L), emissions)

        collectJob.cancel()
      }
    }
  }

  @Test
  fun positionFlowFallsBackToDefault200msWhenIntervalIsNonPositive() {
    runTest(mainDispatcherRule.testDispatcher) {
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

        val emissions = mutableListOf<Long>()
        val collectJob = backgroundScope.launch {
          player.positionFlow(interval = 0.milliseconds).collect { pos ->
            emissions.add(pos)
          }
        }
        runCurrent()
        assertEquals(listOf(0L), emissions)

        controller.currentPosition = 200L
        advanceTimeBy(199.milliseconds)
        runCurrent()
        assertEquals(listOf(0L), emissions)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf(0L, 200L), emissions)

        collectJob.cancel()
      }
    }
  }

  @Test
  fun suspendAwaitExtensionsResumeWhenTargetConditionsAreMet() {
    runTest(mainDispatcherRule.testDispatcher) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val controller = FakeExoPlayerController()
      VodPlayerImpl(
        context = context,
        retryOnErrorInterval = 0L,
        playerProvider = { controller.exoPlayer },
      ).usePlayer { player ->
        val playingDeferred = async {
          player.awaitPlayerState(ComposePlayerState.Playing)
        }

        val bufferReadyDeferred = async {
          player.awaitBufferReady()
        }

        val firstFrameDeferred = async {
          player.awaitRenderedFirstFrame()
        }

        val durationDeferred = async {
          player.awaitDuration()
        }

        runCurrent()
        assertFalse(playingDeferred.isCompleted)
        assertFalse(bufferReadyDeferred.isCompleted)
        assertFalse(firstFrameDeferred.isCompleted)
        assertFalse(durationDeferred.isCompleted)

        player.setDataSource("https://example.com/video.mp4")
        player.play()
        controller.moveToReady(durationMs = 15_000L)
        runCurrent()

        assertTrue(playingDeferred.isCompleted)
        assertTrue(bufferReadyDeferred.isCompleted)
        assertTrue(durationDeferred.isCompleted)
        assertEquals(15_000L, durationDeferred.await())
        assertFalse(firstFrameDeferred.isCompleted)

        controller.renderFirstFrame()
        runCurrent()
        assertTrue(firstFrameDeferred.isCompleted)
      }
    }
  }
}
