package com.zachklipp.statedatastore.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.zachklipp.statedatastore.preferences.StateDataStore.DisposableMutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Converts this [DataStore] into a [StateDataStore].
 *
 * Sample:
 * ```
 * class ViewModel(dataStore: DataStore) {
 *   val dataStore: StateDataStore = dataStore.asDataStoreIn(coroutineScope)
 *   …
 * }
 *
 * @Composable fun Screen(viewModel: ViewModel) {
 *   var pref by viewModel.dataStore.collectOrDefaultAsState(prefKey) { "default" }
 *   TextField(pref, onValueChanged = { pref = it })
 * }
 * ```
 *
 * @param scope The [CoroutineScope] that is used to launch the task to sync state changes
 * to and from this [DataStore].
 * @param retryPolicy Determines whether to retry write operations that fail. A function that
 * returns a suspend function that determines whether to retry or not for a given [IOException].
 * Default policy does not retry.
 */
fun DataStore<Preferences>.asStateDataStoreIn(
    scope: CoroutineScope,
    retryPolicy: () -> suspend (IOException) -> Boolean = { { false } }
): StateDataStore {
    // If the store is not created in a snapshot, it will throw an exception if accessed
    // immediately.
    return Snapshot.withMutableSnapshot { PreferencesStateDataStoreImpl(this, retryPolicy) }.apply {
        // Launch undispatched so that if the DataStore is already cached, Preferences will be
        // available to get* calls as soon as this function returns.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runSyncUntilCancelled()
        }
    }
}

/**
 * A wrapper around a [DataStore] that exposes preference keys as [MutableState]s.
 *
 * ## Persisting state updates
 *
 * When a [DisposableMutableState]'s value is changed, the new value will eventually be written to
 * the underlying [DataStore]. This happens asynchronously, and only when the change becomes visible
 * in the global snapshot. All states whose values have changed since the last write will be written
 * together in a batch.
 *
 * Writes are best-effort: If the write batch fails, all the states in that batch will be reverted
 * to their current values in the [DataStore] unless they were changed again since the write was
 * attempted.
 */
@Stable
interface StateDataStore {
    /**
     * Returns true if the [key] is present in the underlying [DataStore].
     */
    @Stable
    operator fun <T> contains(key: Preferences.Key<T>): Boolean

    /**
     * Returns the current value of [key] in the underlying [DataStore]. If this function is called
     * from a restartable, snapshot-observing context (such as in a composable), it will trigger a
     * restart when the value changes.
     */
    @Stable
    operator fun <T> get(key: Preferences.Key<T>): T?

    /**
     * Returns a [DisposableMutableState] that contains the value of [key] in the underlying
     * [DataStore], and will write its value to the [DataStore] whenever it's changed. If [key] is
     * not present in the [DataStore] the returned state will initially return null.
     *
     * The [DisposableMutableState] _must_ be disposed when it's no longer needed, or it will leak.
     * Use [collectAsState] from a Composable to automatically dispose it.
     *
     * @see collectAsState
     */
    // Not @Stable because two calls will return different values if the result from the first one
    // is disposed before the second call.
    fun <T> getAsState(key: Preferences.Key<T>): DisposableMutableState<T?>

    /**
     * Returns a [DisposableMutableState] like [getAsState], but if [key] is not present in the
     * [DataStore] then it will return the value of [orDefault] until it's written.
     *
     * @see collectOrDefaultAsState
     */
    // Not @Stable because two calls will return different values if the result from the first one
    // is disposed before the second call.
    fun <T> getOrDefaultAsState(
        key: Preferences.Key<T>,
        orDefault: () -> T
    ): DisposableMutableState<T>

    /** A [MutableState] that must be [disposed][dispose] when no longer used. */
    @Stable
    interface DisposableMutableState<T> : MutableState<T> {
        /**
         * Disposes the state, allowing resources used to track it to be cleaned up.
         *
         * If the state was changed and hasn't been written to the underlying [DataStore] when
         * [dispose] is called, it will be written to the store asynchronously.
         */
        fun dispose()
    }
}

/**
 * Returns a [MutableState] that holds the value of [key] in the underlying [DataStore] and writes
 * its values to the store. The returned state will initially return null if [key] is not present.
 *
 * @see StateDataStore.getAsState
 */
@Composable
fun <T> StateDataStore.collectAsState(key: Preferences.Key<T>): MutableState<T?> =
    remember(this, key) { getAsState(key) }.also {
        DisposableEffect(it) {
            onDispose(it::dispose)
        }
    }

/**
 * Like [collectAsState] but returns the value of [orDefault] if the key is not present.
 *
 * @see StateDataStore.getOrDefaultAsState
 */
@Composable
fun <T> StateDataStore.collectOrDefaultAsState(
    key: Preferences.Key<T>,
    orDefault: () -> T
): MutableState<T> {
    val updatedOrDefault by rememberUpdatedState(orDefault)
    return remember(this, key) {
        getOrDefaultAsState(key, orDefault = { updatedOrDefault() })
    }.also {
        DisposableEffect(it) {
            onDispose(it::dispose)
        }
    }
}