package com.zachklipp.statedatastore.preferences

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesStateDataStoreTest {


    @get:Rule
    val tempDir = TemporaryFolder()

    private val testScope = TestScope()
    private val testJob = Job(parent = testScope.coroutineContext.job)
    private var backingDataStoreWrapper: (DataStore<Preferences>) -> DataStore<Preferences> = { it }
    private var retryPolicy: () -> suspend (IOException) -> Boolean = { { false } }
    private val backingDataStore by lazy {
        backingDataStoreWrapper(PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempDir.newFile("preferences.preferences_pb") }
        ))
    }
    private val dataStore by lazy {
        backingDataStore.asStateDataStoreIn(testScope + testJob, retryPolicy = retryPolicy)
    }

    @Test
    fun `get returns null when unset`() {
        assertThat(dataStore[stringPrefKey]).isNull()
    }

    @Test
    fun `state is initially null when unset`() = runTest {
        val state by dataStore.getAsState(stringPrefKey)
        assertThat(state).isNull()
    }

    @Test
    fun `state with default is initially default when unset`() = runTest {
        val state by dataStore.getOrDefaultAsState(stringPrefKey) { "hello" }
        assertThat(state).isEqualTo("hello")
    }

    @Test
    fun `state is value null when set`() = runTest {
        backingDataStore.edit { it[stringPrefKey] = "hello" }
        val state by dataStore.getAsState(stringPrefKey)
        assertThat(state).isEqualTo("hello")
    }

    @Test
    fun `get returns value when set`() = runTest {
        backingDataStore.edit { it[stringPrefKey] = "hello" }
        advanceUntilIdle()
        assertThat(dataStore[stringPrefKey]).isEqualTo("hello")
    }

    @Test
    fun `get restarts when value changed via backing store`() = runTest {
        val values = Channel<String?>()
        // Force the property to initialize so it doesn't get initialized in the read-only snapshot
        // of snapshotFlow.
        dataStore
        snapshotFlow { dataStore[stringPrefKey] }
            .onEach { values.send(it) }
            .launchIn(this + testJob)

        assertThat(values.receive()).isNull()

        backingDataStore.edit { it[stringPrefKey] = "hello" }
        assertThat(values.receive()).isEqualTo("hello")

        backingDataStore.edit { it[stringPrefKey] = "world" }
        assertThat(values.receive()).isEqualTo("world")
    }

    @Test
    fun `get restarts when value changed via state`() = runTest {
        val values = Channel<String?>()
        // Force the property to initialize so it doesn't get initialized in the read-only snapshot
        // of snapshotFlow.
        dataStore
        snapshotFlow { dataStore[stringPrefKey] }
            .onEach { values.send(it) }
            .launchIn(this + testJob)
        var state by dataStore.getAsState(stringPrefKey)

        assertThat(values.receive()).isNull()

        state = "hello"
        assertThat(values.receive()).isEqualTo("hello")

        state = "world"
        assertThat(values.receive()).isEqualTo("world")
    }

    @Test
    fun `state is changed immediately when written through different state`() = runTest {
        var state1 by dataStore.getAsState(stringPrefKey)
        val state2 by dataStore.getAsState(stringPrefKey)

        assertThat(state1).isNull()
        assertThat(state2).isNull()

        state1 = "hello"
        assertThat(state2).isEqualTo("hello")
    }

    @Test
    fun `value is written to backing store after state changed`() = runTest {
        var state by dataStore.getAsState(stringPrefKey)

        advanceUntilIdle()
        assertThat(backingDataStore.data.first()[stringPrefKey]).isNull()

        state = "hello"

        advanceUntilIdle()
        assertThat(backingDataStore.data.first()[stringPrefKey]).isEqualTo("hello")
    }

    @Test
    fun `state reverted when edit fails with non-IOException`() {
        assertFailsWith<RuntimeException> {
            runTest {
                backingDataStoreWrapper = {
                    object : DataStore<Preferences> by it {
                        override suspend fun updateData(
                            transform: suspend (t: Preferences) -> Preferences
                        ): Preferences {
                            throw RuntimeException("fail")
                        }
                    }
                }

                var state by dataStore.getAsState(stringPrefKey)

                state = "hello"
                assertThat(state).isEqualTo("hello")

                advanceUntilIdle()
                assertThat(state).isNull()
            }
        }
    }

    // TODO test for state write before datastore init
    // TODO test for state initialization without being written first

    @Test
    fun `write retried based on policy`() {
        val succeedOnTry = 3
        var tries = 0
        runTest {
            // Retry forever, but with a delay,
            retryPolicy = { { delay(1000); true } }
            backingDataStoreWrapper = { dataStore ->
                object : DataStore<Preferences> by dataStore {
                    override suspend fun updateData(
                        transform: suspend (t: Preferences) -> Preferences
                    ): Preferences {
                        tries++
                        if (tries == succeedOnTry) {
                            return dataStore.updateData(transform)
                        } else {
                            throw IOException("fail")
                        }
                    }
                }
            }

            var state by dataStore.getAsState(stringPrefKey)

            state = "hello"
            assertThat(state).isEqualTo("hello")

            advanceUntilIdle()
            assertThat(tries).isEqualTo(succeedOnTry)
            assertThat(state).isEqualTo("hello")
        }
    }

    @Test
    fun `state reverted when retries fail`() {
        var triesRemaining = 3
        assertFailsWith<IOException> {
            runTest {
                retryPolicy = { { delay(1000); --triesRemaining > 0 } }
                backingDataStoreWrapper = {
                    object : DataStore<Preferences> by it {
                        override suspend fun updateData(
                            transform: suspend (t: Preferences) -> Preferences
                        ): Preferences {
                            throw IOException("fail")
                        }
                    }
                }

                var state by dataStore.getAsState(stringPrefKey)

                state = "hello"
                assertThat(state).isEqualTo("hello")

                advanceUntilIdle()
                assertThat(state).isNull()
            }
        }
        assertThat(triesRemaining).isEqualTo(0)
    }

    @Test
    fun `non-IOExceptions are never retried`() {
        assertFailsWith<RuntimeException> {
            runTest {
                // Retry forever.
                retryPolicy = { { true } }
                backingDataStoreWrapper = {
                    object : DataStore<Preferences> by it {
                        override suspend fun updateData(
                            transform: suspend (t: Preferences) -> Preferences
                        ): Preferences {
                            throw RuntimeException("fail")
                        }
                    }
                }
                var state by dataStore.getAsState(stringPrefKey)
                state = "hello"
                advanceUntilIdle()
            }
        }
    }

    private fun runTest(testBody: suspend TestScope.() -> Unit) {
        testScope.runTest {
            // Bootstrap the snapshot observer system.
            Snapshot.registerGlobalWriteObserver {
                Snapshot.sendApplyNotifications()
            }

            testBody()
            testJob.cancel()
        }
    }

    private companion object {
        val stringPrefKey = stringPreferencesKey("test string")
    }
}