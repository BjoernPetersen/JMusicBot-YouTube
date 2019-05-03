package net.bjoernpetersen.musicbot.youtube.cache

import com.github.benmanes.caffeine.cache.CacheLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

@Suppress("DeferredIsResult")
internal class AsyncLoader<K, V>(
    private val scope: CoroutineScope,
    private val syncLoad: suspend (key: K) -> V
) : CacheLoader<K, Deferred<V>> {
    override fun load(key: K): Deferred<V> {
        return scope.async {
            syncLoad(key)
        }
    }
}
