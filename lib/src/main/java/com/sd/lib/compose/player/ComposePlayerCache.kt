package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** 播放器磁盘缓存封装 */
@SuppressLint("UnsafeOptInUsageError")
class ComposePlayerCache private constructor(
  private val appContext: Context,
  private val directory: File,
  private val maxBytes: Long,
) {
  internal val cache: Cache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val canonicalFile = runCatching { directory.canonicalFile }.getOrElse { directory.absoluteFile }
    synchronized(sLock) {
      val dbProvider = sDatabaseProvider ?: StandaloneDatabaseProvider(appContext).also {
        sDatabaseProvider = it
      }
      sCacheMap.getOrPut(canonicalFile.path) {
        SimpleCache(
          canonicalFile,
          LeastRecentlyUsedCacheEvictor(maxBytes),
          dbProvider,
        )
      }
    }
  }

  companion object {
    private const val DEFAULT_DIR_NAME = "compose_player_cache"
    private const val DEFAULT_MAX_BYTES = 1024L * 1024 * 1024

    private val sLock = Any()
    private val sCacheMap = HashMap<String, Cache>()
    private var sDatabaseProvider: StandaloneDatabaseProvider? = null

    /** 获取默认缓存实例（目录为 `cacheDir/compose_player_cache`，上限 1GB） */
    fun getDefault(context: Context): ComposePlayerCache {
      val appContext = context.applicationContext
      val dir = File(appContext.cacheDir, DEFAULT_DIR_NAME)
      return create(
        context = appContext,
        directory = dir,
        maxBytes = DEFAULT_MAX_BYTES,
      )
    }

    /**
     * 获取或创建指定目录的缓存实例，同一规范路径共享同一底层实例并复用首次创建时的[maxBytes]
     *
     * @param context 上下文
     * @param directory 缓存目录
     * @param maxBytes 最大缓存字节数，默认 1GB
     */
    fun create(
      context: Context,
      directory: File,
      maxBytes: Long = DEFAULT_MAX_BYTES,
    ): ComposePlayerCache {
      require(maxBytes > 0) { "maxBytes must be > 0" }
      return ComposePlayerCache(
        appContext = context.applicationContext,
        directory = directory,
        maxBytes = maxBytes,
      )
    }
  }
}
