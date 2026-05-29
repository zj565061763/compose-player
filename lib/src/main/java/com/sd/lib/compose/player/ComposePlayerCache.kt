package com.sd.lib.compose.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object ComposePlayerCache {
  @Volatile
  private var _cache: SimpleCache? = null

  fun getDefault(context: Context): Cache {
    return _cache ?: synchronized(this@ComposePlayerCache) {
      _cache ?: SimpleCache(
        File(context.cacheDir, "compose_player_cache"),
        LeastRecentlyUsedCacheEvictor(1024 * 1024 * 1024),
        StandaloneDatabaseProvider(context)
      ).also { _cache = it }
    }
  }
}