@file:OptIn(ExperimentalMaterial3Api::class)

package com.zachklipp.statedatastore.demo

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zachklipp.statedatastore.preferences.asStateDataStoreIn
import com.zachklipp.statedatastore.preferences.collectOrDefaultAsState

val Context.dataStore by preferencesDataStore(name = "demo")

val StringKey = stringPreferencesKey("string")
val StringKeyDup = stringPreferencesKey(StringKey.name)
val FloatKey = floatPreferencesKey("float")

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember(context, scope) { context.dataStore.asStateDataStoreIn(scope) }

    var stringPref by dataStore.collectOrDefaultAsState(key = StringKey) { "" }
    var stringPrefDup by dataStore.collectOrDefaultAsState(key = StringKeyDup) { "" }
    var floatPref by dataStore.collectOrDefaultAsState(key = FloatKey) { 0f }

    Scaffold(
        topBar = { TopAppBar(title = { Text(text = "StateDataStore Demo") }) }
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            TextField(
                value = stringPref,
                onValueChange = { stringPref = it },
                label = { Text("StringKey") }
            )
            TextField(
                value = stringPref,
                onValueChange = { stringPref = it },
                label = { Text("StringKey") }
            )
            TextField(
                value = stringPrefDup,
                onValueChange = { stringPrefDup = it },
                label = { Text("StringKeyDup") }
            )

            Slider(value = floatPref, onValueChange = { floatPref = it })
            Slider(value = floatPref, onValueChange = { floatPref = it })
        }
    }
}