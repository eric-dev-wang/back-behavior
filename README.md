# BackBehavior - Understanding Lifecycle Events in Android Compose

[中文文档](README_CN.md)

## Overview

This project demonstrates an important behavior in Android Compose applications: **`ViewModel.onCleared()` and `ON_DESTROY` lifecycle events cannot by themselves tell you that the user explicitly exited a screen**.

They DO fire when an Activity is destroyed for reasons unrelated to user navigation (e.g. memory pressure, "Don't keep activities" enabled), so treating them as a "user left" signal is unsafe.

## The Problem

You might be tempted to do user-exit work (analytics, draft persistence decisions, confirmation flows) inside `ViewModel.onCleared()` or inside a `LifecycleEventObserver` listening for `ON_DESTROY`. However:

**When the app goes to the background, the Android system may destroy the Activity to reclaim memory. When the user returns, the system recreates the Activity, and those callbacks have already run even though the user never chose to leave.**

### Reproducing the Issue

Enable **"Don't keep activities"** in Developer Options. This forces destruction when the app is backgrounded.

#### Scenario 1: App Goes to Background (NOT an explicit exit)

```
2025-11-13 10:09:08.848 24415-24415 SecondViewModel         D  init
2025-11-13 10:09:08.874 24415-24415 SecondScreen            D  ON_CREATE
2025-11-13 10:09:10.232 24415-24415 SecondScreen            D  ON_DESTROY  ⚠️
2025-11-13 10:09:10.302 24415-24415 SecondViewModel         D  onCleared called  ⚠️
```

`ON_DESTROY` and `onCleared()` fired but the user didn't navigate back.

#### Scenario 2: Explicit Back (Navigation Icon)

```
2025-11-13 10:26:32.158 27146-27146 SecondViewModel         D  init
2025-11-13 10:26:32.186 27146-27146 SecondScreen            D  ON_CREATE
2025-11-13 10:26:35.220 27146-27146 SecondScreen            D  Navigation icon clicked  ✅
2025-11-13 10:26:35.973 27146-27146 SecondScreen            D  ON_DESTROY
2025-11-13 10:26:35.984 27146-27146 SecondViewModel         D  onCleared called
```

An explicit action precedes lifecycle teardown.

#### Scenario 3: Explicit Back (System Gesture via BackHandler)

```
2025-11-13 10:27:44.241 27146-27146 SecondViewModel         D  init
2025-11-13 10:27:44.269 27146-27146 SecondScreen            D  ON_CREATE
2025-11-13 10:27:47.154 27146-27146 SecondScreen            D  BackHandler invoked  ✅
2025-11-13 10:27:47.901 27146-27146 SecondScreen            D  ON_DESTROY
2025-11-13 10:27:47.911 27146-27146 SecondViewModel         D  onCleared called
```

Again an explicit user action is visible.

## The Solution (Always Combine Signals You Control)

To reliably know the user chose to leave the screen, you must hook BOTH:
- System back gesture / hardware back (`BackHandler`) AND
- Any explicit UI navigation affordance (top app bar navigation icon, in-content buttons)

Never choose only one if the other exists; use both so you can centralize "user exit" logic.

### Recommended Implementation

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondScreen(onBackClick: () -> Unit) {
    // System back gesture
    BackHandler {
        // Explicit user exit
        handleUserExit()
        onBackClick()
        Log.d("SecondScreen", "BackHandler invoked")
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Second") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Explicit user exit via UI
                            handleUserExit()
                            onBackClick()
                            Log.d("SecondScreen", "Navigation icon clicked")
                        }
                    ) { Text("<") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("This is the second screen")
        }
    }
}

// Central cleanup you only want for explicit exits.
private fun handleUserExit() {
    // e.g. flush analytics page session, commit draft as final, stop timers shown to user
}
```

## When `onCleared` / `ON_DESTROY` Are STILL Appropriate

You should still use lifecycle teardown for work that must happen any time the screen & its ViewModel go away (explicit OR system-driven):

Good fits:
1. Cancelling coroutines / Rx streams / flows to avoid leaks.
2. Releasing resources that must not survive (camera, location, sensor listeners, ExoPlayer instance) regardless of exit reason.
3. Persisting transient in-memory state to a repository or `SavedStateHandle` so recreation restores context (scroll position, draft text).
4. Flushing caches / closing database cursors.
5. Recording generic lifetime metrics (total session duration) where user intent is not required.

### Example: ViewModel cleanup agnostic of explicit exit

```kotlin
class DetailViewModel(private val repo: Repo, private val savedState: SavedStateHandle) : ViewModel() {
    private val scope = viewModelScope
    private var startTime = System.nanoTime()

    val uiState = repo.observeDetail().stateIn(scope, SharingStarted.WhileSubscribed(), initialValue = null)

    override fun onCleared() {
        // Cancel jobs
        scope.coroutineContext.cancelChildren()
        // Persist transient state
        savedState["lastSessionDurationMs"] = (System.nanoTime() - startTime) / 1_000_000
        // Release resource
        repo.releaseTempHandle()
        Log.d("DetailViewModel", "onCleared generic cleanup")
        super.onCleared()
    }
}
```

### Example: Releasing camera in ON_DESTROY regardless of cause

```kotlin
@Composable
fun CameraScreen(controller: CameraController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                controller.shutdown() // Always release
                Log.d("CameraScreen", "Camera released on destroy")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // ... UI drawing preview
}
```

### Distinguish Explicit Exit vs Generic Teardown
- Use your own callbacks (`handleUserExit`) only for intent-dependent actions.
- Use lifecycle (`onCleared` / `ON_DESTROY`) for safety and leak prevention.

## Updated Use Cases
Below: which layer to use for each concern.

1. Analytics: page session (start/stop) — explicit exit handler; low-level duration — `onCleared`.
2. Long-running background job: cancel in `onCleared`; only show a "completed" toast if explicit exit with completion.

## Wrong Approach & Fix: Clearing Edit State in onCleared

**Scenario:** Image editor where users crop, apply filters, etc. An "undo stack" is maintained in memory to support undo/redo functionality.

**Wrong approach:** Clearing the undo stack in `onCleared` causes users to lose all edit history when returning from background.
```kotlin
class ImageEditViewModel(
    private val repository: ImageRepository
) : ViewModel() {
    val currentImage: StateFlow<Bitmap?> = repository.currentImage

    fun applyFilter(filter: Filter) {
        repository.applyFilter(filter)
    }

    fun undo() {
        repository.undo()
    }

    // WRONG: clearing image and undo history on system-driven destruction
    override fun onCleared() {
        repository.saveImageAndClear() // User loses image and undo capability after backgrounding!
        Analytics.logEvent("image_edit_complete")
        super.onCleared()
    }
}
```

**Impact:**
- User switches to another app to check references, returns to find undo unavailable.
- Terrible UX: 10 minutes of edit history suddenly gone, no way to revert.
- Contradicts user expectations: just switching apps shouldn't lose edit state.

**Correct approach:** Only clear history stack on explicit user exit.

```kotlin
class ImageEditViewModel(
    private val repository: ImageRepository
) : ViewModel() {
    val currentImage: StateFlow<Bitmap?> = repository.currentImage

    fun applyFilter(filter: Filter) {
        repository.applyFilter(filter)
    }

    fun undo() {
        repository.undo()
    }

    fun userExitEdit() {
        repository.saveImageAndClear() // Only clear when user explicitly exits
        Analytics.logEvent("image_edit_complete")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: ImageEditViewModel = viewModel()
) {
    val currentImage by viewModel.currentImage.collectAsStateWithLifecycle()
    BackHandler {
        viewModel.userExitEdit() // explicit exit - clear edit history
        onNavigateBack()
        Log.d("ImageEditScreen", "BackHandler exit edit")
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Edit Image") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.userExitEdit()
                        onNavigateBack()
                        Log.d("ImageEditScreen", "Navigation exit edit")
                    }) { Text("<") }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }) {
                        Text("Undo")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Image(bitmap = currentImage?.asImageBitmap())
            // filter buttons etc.
        }
    }
}
```

**Summary:**
- Undo stacks, temporary selections, form input caches = "user work context" should NOT be cleared in `onCleared`.
- These states should be cleared on explicit exit, or persisted in `onCleared` for restoration.
- Resource release (database connections, listeners) IS what `onCleared` is for.

## Key Takeaways
1. Do NOT equate `onCleared` / `ON_DESTROY` with user intent.
2. ALWAYS wire BackHandler + UI navigation for explicit exit capture.
3. Continue using lifecycle teardown for generic cleanup & resource release.
4. Test with "Don't keep activities" to validate separation.

## Testing Explicit vs Generic Paths
1. Enable "Don't keep activities".
2. Navigate to Second screen.
3. Background app (observe only lifecycle logs — no explicit exit logs).
4. Re-open app (ViewModel recreated; explicit exit not triggered).
5. Navigate back using icon or gesture (observe explicit exit logs preceding lifecycle). 

---
Use explicit user action hooks for intent; use lifecycle for safety. Combine both for robust behavior.
