package com.sd.lib.compose.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ComposePlayerCacheTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun rejectsNonPositiveMaxBytes() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheDir = tempFolder.newFolder("invalid_cache")

    assertThrows(IllegalArgumentException::class.java) {
      ComposePlayerCache.create(context = context, directory = cacheDir, maxBytes = 0L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      ComposePlayerCache.create(context = context, directory = cacheDir, maxBytes = -1024L)
    }
  }

  @Test
  fun sharesUnderlyingSimpleCacheForEquivalentCanonicalPaths() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val realDir = tempFolder.newFolder("video_cache")
    val siblingDir = tempFolder.newFolder("sibling")
    val nonNormalizedDir = File(siblingDir, "../video_cache")

    val cacheA = ComposePlayerCache.create(context = context, directory = realDir, maxBytes = 10L * 1024 * 1024)
    val cacheB = ComposePlayerCache.create(context = context, directory = nonNormalizedDir, maxBytes = 20L * 1024 * 1024)

    assertSame(cacheA.cache, cacheB.cache)
  }

  @Test
  fun reusesDefaultCacheInstanceAcrossMultipleCalls() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val defaultA = ComposePlayerCache.getDefault(context)
    val defaultB = ComposePlayerCache.getDefault(context)

    assertSame(defaultA.cache, defaultB.cache)
  }
}
