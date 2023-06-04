# statedatastore

A snapshot state wrapper around the Jetpack Datastore library.

**This is a highly experimental, proof-of-concept library.** It's meant to be a rough example sketch
of how you could use Compose's snapshot state system to implement automatic persistence. For more
information, see my talk [_Opening the shutter on snapshots_](https://www.droidcon.com/2022/09/29/opening-the-shutter-on-snapshots/)
([slides](https://speakerdeck.com/zachklipp/opening-the-shutter-on-snapshots)).

## Usage

Easily consume and update `Preferences` state via `MutableState`s:
```kotlin
val prefKey = stringPreferencesKey("pref") 

@Composable fun Screen(dataStore: StateDataStore) {
  var pref by dataStore.collectAsState(prefKey)
  TextField(pref, onValueChanged = { pref = it })
}
```
The `pref` state will automatically update whenever the backing `DataStore` is changed, and changes
made to the `pref` value will automatically be written to the `DataStore`.

If you want to provide a default, you do that too:
```kotlin
var pref by dataStore.collectOrDefaultAsState(prefKey) { "default" }
```

There are also versions of both of these methods that you can use outside of composition:
```kotlin
dataStore.getAsState(prefKey)
dataStore.getOrDefaultAsState(prefKey) { "default" }
```

To create a `StateDataStore`, take a `DataStore` instance and call `asDataStoreIn` on it: 
```kotlin
class ViewModel(dataStore: DataStore) {
  val dataStore: StateDataStore = dataStore.asDataStoreIn(coroutineScope)
}
```
`asDataStoreIn` needs a `CoroutineScope` to run in to observe changes and write state changes
back to the `DataStore`.