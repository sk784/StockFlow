package dev.stockflow.presentation.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.stockflow.domain.*
import dev.stockflow.presentation.components.*
import dev.stockflow.presentation.conflicts.ConflictDetailsScreen
import dev.stockflow.presentation.conflicts.ConflictsScreen
import dev.stockflow.presentation.scanner.BarcodeScannerDialog
import dev.stockflow.presentation.sync.SynchronizationScreen
import dev.stockflow.presentation.tasks.InventoryScreen
import dev.stockflow.presentation.tasks.QuantityEditor
import dev.stockflow.presentation.tasks.TasksDashboard

private enum class WorkspaceTab(val route: String, val label: String) {
    WORK("work", "Задания"), SYNC("sync", "Отправка"), ATTENTION("attention", "Конфликты")
}

@Composable
fun StockFlowApp(viewModel: WorkspaceViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val scheduling by viewModel.scheduling.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val conflictList = (conflicts as? LoadState.Ready)?.value.orEmpty()
    val operationList = (operations as? LoadState.Ready)?.value.orEmpty()
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(it) } }

    fun openConflict(id: String) { nav.navigate("conflict/$id") { launchSingleTop = true } }
    fun openSync() {
        nav.navigate(if (((queue as? LoadState.Ready)?.value?.conflicts ?: 0) > 0) "attention" else "sync") {
            launchSingleTop = true
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                WorkspaceTab.entries.forEach { tab ->
                    val selected = entry?.destination?.hierarchy?.any { it.route == tab.route } == true
                    val count = when (tab) {
                        WorkspaceTab.WORK -> 0
                        WorkspaceTab.SYNC -> (queue as? LoadState.Ready)?.value?.pending ?: 0
                        WorkspaceTab.ATTENTION -> conflictList.size
                    }
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav-${tab.route}"),
                        selected = selected, enabled = !saving,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString()) } }) {
                                Icon(when (tab) {
                                    WorkspaceTab.WORK -> StockFlowIcons.Tasks
                                    WorkspaceTab.SYNC -> StockFlowIcons.Sync
                                    WorkspaceTab.ATTENTION -> StockFlowIcons.Conflict
                                }, contentDescription = null)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = "work", modifier = Modifier.padding(padding)) {
            navigation(startDestination = "tasks", route = "work") {
                composable("tasks") {
                    TasksDashboard(tasks, queue, connection, refreshing, saving, error,
                        onRefresh = viewModel::refreshTasks, onDemo = viewModel::createDemoTask,
                        onTask = { nav.navigate("inventory/$it") }, onSync = ::openSync,
                        onRetry = viewModel::retryLoading, onDismissError = viewModel::clearError)
                }
                composable("inventory/{taskId}", arguments = listOf(navArgument("taskId") { type = NavType.LongType })) { inventoryEntry ->
                    val taskId = inventoryEntry.arguments?.getLong("taskId") ?: return@composable
                    InventoryRoute(viewModel, taskId,
                        task = (tasks as? LoadState.Ready)?.value?.firstOrNull { it.id == taskId },
                        queue = queue, connection = connection, conflicts = conflicts, operations = operationList,
                        saving = saving, error = error, onBack = { nav.navigateUp() }, onSync = ::openSync,
                        onConflict = ::openConflict)
                }
            }
            composable("sync") {
                SynchronizationScreen(operations, queue, connection, scheduling, error,
                    onSend = viewModel::requestSync, onConflict = ::openConflict,
                    onConflicts = { nav.navigate("attention") { launchSingleTop = true } },
                    onRetry = viewModel::retryLoading, onDismissError = viewModel::clearError,
                    saving = saving, onDiscard = viewModel::discardRejectedChanges)
            }
            navigation(startDestination = "conflicts", route = "attention") {
                composable("conflicts") {
                    ConflictsScreen(conflicts, error, ::openConflict, viewModel::retryLoading, viewModel::clearError)
                }
                composable("conflict/{operationId}", arguments = listOf(navArgument("operationId") { type = NavType.StringType })) { conflictEntry ->
                    val id = conflictEntry.arguments?.getString("operationId") ?: return@composable
                    val conflict = conflictList.firstOrNull { it.operationId == id }
                    var observed by rememberSaveable(id) { mutableStateOf(false) }
                    LaunchedEffect(conflict, saving, conflicts) {
                        if (conflict != null) observed = true
                        else if (observed && conflicts is LoadState.Ready && !saving) nav.navigateUp()
                    }
                    BackHandler(enabled = saving) { }
                    ConflictDetailsScreen(conflict, conflicts is LoadState.Loading, saving, error,
                        onBack = { nav.navigateUp() },
                        onChoice = { viewModel.resolveConflict(id, it) }, onDismissError = viewModel::clearError,
                        loadFailed = conflicts is LoadState.Failed, onRetry = viewModel::retryLoading)
                }
            }
        }
    }
}

@Composable
private fun InventoryRoute(
    viewModel: WorkspaceViewModel, taskId: Long, task: TaskProgress?, queue: LoadState<QueueStatus>,
    connection: ConnectionState, conflicts: LoadState<List<InventoryConflict>>, operations: List<SyncOperation>,
    saving: Boolean, error: String?, onBack: () -> Unit, onSync: () -> Unit, onConflict: (String) -> Unit,
) {
    val itemsFlow = remember(taskId, viewModel) { viewModel.observeItems(taskId) }
    val products by itemsFlow.collectAsStateWithLifecycle(initialValue = LoadState.Loading)
    val editingId by viewModel.editingItemId.collectAsStateWithLifecycle()
    val draft by viewModel.quantityDraft.collectAsStateWithLifecycle()
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    val conflictList = (conflicts as? LoadState.Ready)?.value.orEmpty()
    fun openItem(item: InventoryItem) {
        val conflict = conflictList.firstOrNull { it.itemId == item.id }
        if (conflict != null) onConflict(conflict.operationId) else viewModel.openEditor(item)
    }
    InventoryScreen(task, products, queue, connection,
        conflictedItems = conflictList.map { it.itemId }.toSet(), pendingItems = operations.map { it.itemId }.toSet(),
        editingAllowed = conflicts is LoadState.Ready, saving = saving, error = error,
        onBack = onBack, onScan = { scannerOpen = true }, onItem = ::openItem, onSync = onSync,
        onRetry = viewModel::retryLoading, onDismissError = viewModel::clearError)
    val editing = (products as? LoadState.Ready)?.value?.firstOrNull { it.id == editingId }
    if (editing != null) {
        val conflicted = conflictList.any { it.itemId == editing.id }
        QuantityEditor(editing, draft, saving,
            if (conflicted) "Сначала разрешите конфликт количества этого товара." else error,
            canSave = conflicts is LoadState.Ready && !conflicted,
            onDraft = viewModel::changeDraft, onDismiss = viewModel::closeEditor, onSave = viewModel::saveQuantity)
    }
    if (scannerOpen) {
        BarcodeScannerDialog(onDismiss = { scannerOpen = false }) { code ->
            val items = (products as? LoadState.Ready)?.value
            when {
                !scannerOpen -> "Сканирование завершено"
                items == null || conflicts !is LoadState.Ready || saving -> "Дождитесь загрузки товаров"
                else -> when (val match = findByBarcode(items, taskId, code)) {
                    BarcodeMatch.Empty -> "Введите штрихкод"
                    BarcodeMatch.NotFound -> "Товар с таким штрихкодом отсутствует в этом задании"
                    BarcodeMatch.Ambiguous -> "Код принадлежит нескольким товарам. Выберите товар из списка."
                    is BarcodeMatch.Found -> { scannerOpen = false; openItem(match.item); null }
                }
            }
        }
    }
}
