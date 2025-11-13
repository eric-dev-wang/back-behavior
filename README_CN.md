# BackBehavior - 理解 Android Compose 中的生命周期事件

[English Documentation](README.md)

## 概述

本项目展示：**`ViewModel.onCleared()` 和 `ON_DESTROY` 事件本身不能可靠代表“用户主动退出页面”**。它们也会在 Activity 被系统回收（例如启用“不要保留活动”或内存压力）时触发。

因此：不能把它们当成“用户退出”的唯一判定依据，但它们仍然非常适合做通用释放与持久化工作。

## 问题说明

如果只在 `onCleared()` 或 `ON_DESTROY` 中做“用户离开页面”相关逻辑（如：埋点、弹确认对话、提交最终数据），当 Activity 因系统策略被销毁时这些逻辑会被误触发。

### 重现方式
在开发者选项启用 **“不保留活动”**。当应用退到后台即销毁当前 Activity，回到前台重新创建。

#### 场景 1：应用进入后台（并非用户明确返回）
```
2025-11-13 10:09:08.848 SecondViewModel  D init
2025-11-13 10:09:08.874 SecondScreen     D ON_CREATE
2025-11-13 10:09:10.232 SecondScreen     D ON_DESTROY  ⚠️
2025-11-13 10:09:10.302 SecondViewModel  D onCleared called  ⚠️
```
#### 场景 2：导航图标返回（明确操作）
```
2025-11-13 10:26:32.158 SecondViewModel  D init
2025-11-13 10:26:32.186 SecondScreen     D ON_CREATE
2025-11-13 10:26:35.220 SecondScreen     D Navigation icon clicked  ✅
2025-11-13 10:26:35.973 SecondScreen     D ON_DESTROY
2025-11-13 10:26:35.984 SecondViewModel  D onCleared called
```
#### 场景 3：系统返回手势（明确操作）
```
2025-11-13 10:27:44.241 SecondViewModel  D init
2025-11-13 10:27:44.269 SecondScreen     D ON_CREATE
2025-11-13 10:27:47.154 SecondScreen     D BackHandler invoked  ✅
2025-11-13 10:27:47.901 SecondScreen     D ON_DESTROY
2025-11-13 10:27:47.911 SecondViewModel  D onCleared called
```

## 正确的做法：同时监听“明确退出”与生命周期销毁
为了精确区分“用户主动返回”与“系统销毁”，应同时：
1. 使用 `BackHandler` 捕获系统返回/手势。
2. 为顶部栏或页面提供明确返回按钮（如果存在导航图标）。
3. 在两个入口集中调用“用户退出”逻辑；仍保留 `onCleared` / `ON_DESTROY` 做通用资源清理。

### 推荐实现（组合式）
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondScreen(onBackClick: () -> Unit) {
    BackHandler {
        handleUserExit() // explicit user exit
        onBackClick()
        Log.d("SecondScreen", "BackHandler invoked")
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Second") },
                navigationIcon = {
                    IconButton(onClick = {
                        handleUserExit() // explicit user exit via button
                        onBackClick()
                        Log.d("SecondScreen", "Navigation icon clicked")
                    }) { Text("<") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("This is the second screen")
        }
    }
}

private fun handleUserExit() {
    // commit final draft, flush page analytics, stop visible timers
}
```

## 何时仍应使用 onCleared / ON_DESTROY

这些生命周期回调仍是“通用清理”的最佳位置：
- 取消协程、关闭 Flow 订阅，防止泄漏。
- 释放 ExoPlayer、Camera、定位、传感器等资源。

### ViewModel 示例：与用户意图无关的清理
```kotlin
class DetailViewModel(
    private val repo: Repo,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private var startTime = System.nanoTime()

    override fun onCleared() {
        repo.releaseTempHandle()
        Log.d("DetailViewModel", "onCleared generic cleanup")
        super.onCleared()
    }
}
```
### Composable 示例：统一在销毁时释放硬件资源
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

## 区分“明确退出”与“通用销毁”策略

- 明确退出：只在 BackHandler / 返回按钮里触发用户意图相关逻辑。
- 通用销毁：在 `onCleared` / `ON_DESTROY` 做必要的资源释放与状态持久化。

## 使用场景（双层处理示例）

1. 页面埋点：进入时开始计时；在明确退出时发送"页面结束"事件；在 `onCleared` 里记录总停留时长（容灾）。
2. 长任务下载：明确退出时更新 UI 状态 / 发出完成提示；在 `onCleared` 中取消协程以免后台继续占用资源。

## 错误示例与改进：误在 onCleared 中清理编辑状态

**场景描述：** 图片编辑器，用户在编辑页面对图片进行裁剪、滤镜等操作，存在一个"撤销栈"（undo stack）保存在内存中用于实现撤销/重做功能。

**错误做法：** 在 `onCleared` 中清空撤销栈，导致应用从后台恢复时用户丢失所有编辑历史。
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

**问题影响：**

- 用户切到其他 App 查看资料，回来后发现无法撤销之前的编辑操作。
- 用户体验极差：花了 10 分钟做的编辑历史突然丢失，无法回退。
- 与用户预期不符：只是切了个应用，编辑状态不应该丢失。

**正确处理：** 只在用户明确退出编辑页面时才清空历史栈。

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

**总结：**
- 撤销栈、临时选中项、表单输入缓存等"用户工作现场"不应在 `onCleared` 中清除。
- 这些状态应在用户明确退出时清理，或在 `onCleared` 中持久化以便恢复。
- 资源释放（数据库连接、监听器）才是 `onCleared` 的核心职责。

## 关键要点
1. 不要把 `onCleared` / `ON_DESTROY` == 用户主动退出。
2. 存在返回 UI 时必须同时使用 BackHandler + 导航按钮。
3. 生命周期回调仍然是通用清理的核心位置。
4. 启用“不要保留活动”验证两层逻辑是否分离正确。

## 测试步骤
1. 打开开发者选项启用“不要保留活动”。
2. 进入 Second 页面。
3. 按 Home 退后台（仅看到销毁日志，无用户退出日志）。
4. 回到应用（重新创建，无明确退出日志）。
5. 使用导航图标或系统返回手势（先记录明确退出日志，再触发销毁）。

---
总结：明确退出用你控制的事件；通用清理用生命周期。双管齐下，行为清晰且安全。
