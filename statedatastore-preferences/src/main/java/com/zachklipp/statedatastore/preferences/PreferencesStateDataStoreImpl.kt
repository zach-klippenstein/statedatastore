package com.zachklipp.statedatastore.preferences

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.StateObject
import androidx.compose.runtime.snapshots.StateRecord
import androidx.compose.runtime.snapshots.readable
import androidx.compose.runtime.snapshots.writable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.zachklipp.statedatastore.preferences.StateDataStore.DisposableMutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val DEBUG = false
private const val TAG = "StateDataStore"

internal class PreferencesStateDataStoreImpl(
    private val dataStore: DataStore<Preferences>,
    private val retryPolicy: () -> suspend (IOException) -> Boolean,
) : StateDataStore {
    private var latestPreferences by mutableStateOf<Preferences?>(null, neverEqualPolicy())
    private val statesByKey = mutableMapOf<Preferences.Key<*>, DataStoreState<*>>()
    private val dirtyStates = mutableSetOf<DataStoreState<*>>()
    private val lock = Any()

    override operator fun <T> contains(key: Preferences.Key<T>): Boolean =
        latestPreferences?.contains(key) ?: false

    override operator fun <T> get(key: Preferences.Key<T>): T? =
        latestPreferences?.get(key)

    override fun <T> getOrDefaultAsState(
        key: Preferences.Key<T>,
        orDefault: () -> T
    ): DisposableMutableState<T> = object : DisposableMutableState<T> {
        val baseState = getAsState(key)
        override var value: T
            get() = baseState.value ?: orDefault()
            set(value) {
                baseState.value = value
            }

        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }

        override fun dispose() {
            baseState.dispose()
        }
    }

    override fun <T> getAsState(key: Preferences.Key<T>): DisposableMutableState<T?> {
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val state = statesByKey.getOrPut(key) {
                initializeStateLocked(key)
            } as DataStoreState<T>
            state.refCount.incrementAndGet()
            return state
        }
    }

    suspend fun runSyncUntilCancelled() {
        coroutineScope {
            // Read: Update our copy of the preferences whenever it gets changed.
            launch(start = CoroutineStart.UNDISPATCHED) {
                dataStore.data.collect { prefs ->
                    val isFirstLoad = latestPreferences == null
                    if (prefs !== latestPreferences) {
                        latestPreferences = prefs
                        statesByKey.values.forEach {
                            // Don't overwrite states that were already set before the store was
                            // initialized.
                            if (isFirstLoad) {
                                it.initializeFrom(prefs)
                            } else {
                                it.readFrom(prefs)
                            }
                        }
                    }
                }
            }

            // Write: Whenever some of our snapshot states are changed (in the global snapshot),
            // queue up an asynchronous task to write those changes to the datastore.
            val updateDataStoreChannel = Channel<Unit>(capacity = Channel.CONFLATED)
            launch(start = CoroutineStart.UNDISPATCHED) {
                onSnapshotApplied { changed, _ ->
                    var somethingChanged = false
                    synchronized(lock) {
                        changed.forEach { stateObject ->
                            if (stateObject is DataStoreState<*>) {
                                val lastReadValue = latestPreferences?.get(stateObject.key)
                                if (statesByKey.containsValue(stateObject) &&
                                    stateObject.value != lastReadValue
                                ) {
                                    dirtyStates += stateObject as DataStoreState<*>
                                    somethingChanged = true
                                }
                            }
                        }
                    }
                    if (somethingChanged) {
                        updateDataStoreChannel.trySend(Unit)
                    }
                }
            }

            // DataStore works on the IO dispatcher, so we can just do our work on that dispatcher
            // as well to avoid hopping threads unnecessarily.
            launch {
                updateDataStoreChannel.consumeEach {
                    writeDirtyStates()
                }
            }
        }
    }

    private suspend inline fun onSnapshotApplied(
        noinline block: (Set<Any>, Snapshot) -> Unit
    ): Nothing {
        suspendCancellableCoroutine<Nothing> { continuation ->
            val handle = Snapshot.registerApplyObserver(block)
            continuation.invokeOnCancellation {
                handle.dispose()
            }
        }
    }

    private suspend fun writeDirtyStates() {
        val writtenStates = mutableSetOf<DataStoreState<*>>()
        val retry = retryPolicy()

        while (dirtyStates.isNotEmpty()) {
            try {
                // This may suspend indefinitely before running the internal block, so don't
                // look at the dirty states until we actually are ready to use it.
                dataStore.edit { prefs ->
                    // Take a snapshot of the dirty states list in a lock, so any states that are
                    // added while we're doing the write will be retried on the next attempt.
                    synchronized(lock) {
                        writtenStates += dirtyStates
                        dirtyStates.clear()
                    }

                    writtenStates.forEach { state ->
                        if (DEBUG) Log.d(TAG, "writing $state to prefs…")
                        state.writeTo(prefs)
                    }
                }
            } catch (e: CancellationException) {
                // Don't bother trying to revert if we're being torn down.
                throw e
            } catch (e: Throwable) {
                if ((writtenStates.isNotEmpty() || dirtyStates.isNotEmpty()) &&
                    e is IOException &&
                    retry(e)
                ) {
                    synchronized(lock) {
                        // Retry all the states we just tried in addition to any new states
                        // that were written since the last attempt.
                        dirtyStates += writtenStates
                        writtenStates.clear()
                    }
                    continue
                }

                if (writtenStates.isNotEmpty()) {
                    // Write failed, revert states that haven't been updated since. States that
                    // _have_ been updated since will be tried to write again immediately
                    // anyway so don't bother reverting them.
                    synchronized(lock) {
                        latestPreferences?.let { prefs ->
                            writtenStates.forEach {
                                if (it !in dirtyStates) {
                                    it.readFrom(prefs)
                                }
                            }
                        }
                    }
                }
                throw e
            }
        }
    }

    private fun <T> initializeStateLocked(key: Preferences.Key<T>): DataStoreState<T> {
        // If we already have an up-to-date preferences object, initialize the value from it.
        // Otherwise, the value will be UninitializedRecord, and we'll initialize it as soon as the
        // first Preferences loads.
        // Read without observation because getAsState should never trigger a restart itself, only
        // reading the returned state should.
        val latestPrefs = Snapshot.withoutReadObservation { latestPreferences }
        return if (latestPrefs == null) {
            DataStoreState(key)
        } else {
            DataStoreState(key, latestPrefs[key])
        }
    }

    private fun disposeState(state: DataStoreState<*>) {
        synchronized(lock) {
            // Refcount may have been incremented by getAsState, but only inside a lock.
            if (state.refCount.get() == 0) {
                // If the state is dirty and hasn't been written yet, it will stay in dirtyStates
                // until it gets written.
                statesByKey -= state.key
            }
        }
    }

    private inner class DataStoreState<T> private constructor(
        val key: Preferences.Key<T>,
        initialRecordValue: Any?,
        unused: Unit = Unit
    ) : DisposableMutableState<T?>, StateObject {

        constructor(key: Preferences.Key<T>) :
                this(key, initialRecordValue = UninitializedRecordValue)

        constructor(key: Preferences.Key<T>, initialValue: T?) :
                this(key, initialRecordValue = initialValue)

        private var record = Record(initialRecordValue)
        val refCount = AtomicInteger(0)

        @Suppress("UNCHECKED_CAST")
        override var value: T?
            get() = record.readable(this).value?.let {
                if (it === UninitializedRecordValue) {
                    null
                } else {
                    it as T?
                }
            }
            set(value) {
                record.writable(this) {
                    this.value = value
                }
            }

        fun writeTo(prefs: MutablePreferences) {
            value?.let { prefs[key] = it }
        }

        fun readFrom(prefs: Preferences) {
            value = prefs[key]
        }

        /**
         * Reads this preference value from [prefs] if this value has not already been set.
         * This prevents overwriting states that are set before the store is initialized.
         * The initialization state is checked atomically with the write, so this can safely be
         * called from the global snapshot with a race.
         */
        fun initializeFrom(prefs: Preferences) {
            record.writable(this) {
                if (value === UninitializedRecordValue) {
                    value = prefs[key]
                }
            }
        }

        override fun component1(): T? = value
        override fun component2(): (T?) -> Unit = { value = it }

        override fun dispose() {
            if (refCount.decrementAndGet() == 0) {
                disposeState(this)
            }
        }

        override val firstStateRecord: StateRecord
            get() = record

        override fun prependStateRecord(value: StateRecord) {
            @Suppress("UNCHECKED_CAST")
            record = value as DataStoreState<T>.Record
        }

        override fun toString(): String = "DataStoreState(key=\"$key\", value=$value)"

        private inner class Record(var value: Any?) : StateRecord() {
            override fun create(): StateRecord = Record(UninitializedRecordValue)
            override fun assign(value: StateRecord) {
                @Suppress("UNCHECKED_CAST")
                this.value = (value as DataStoreState<T>.Record).value
            }
        }
    }

    private object UninitializedRecordValue
}