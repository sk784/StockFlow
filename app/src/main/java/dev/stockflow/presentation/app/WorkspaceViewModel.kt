package dev.stockflow.presentation.app

import dev.stockflow.presentation.components.LoadState

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.stockflow.domain.CatalogRepository
import dev.stockflow.domain.ConflictChoice
import dev.stockflow.domain.ConflictRepository
import dev.stockflow.domain.ConnectionMonitor
import dev.stockflow.domain.ConnectionState
import dev.stockflow.domain.InventoryItem
import dev.stockflow.domain.InventoryReadModel
import dev.stockflow.domain.InventoryRepository
import dev.stockflow.domain.SyncQueue
import dev.stockflow.domain.SyncScheduler
import dev.stockflow.domain.UpdateQuantity
import javax.inject.Inject
import dev.stockflow.BuildConfig
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: InventoryRepository,
    private val updateQuantity: UpdateQuantity,
    private val savedState: SavedStateHandle,
    private val syncQueue: SyncQueue,
    private val syncScheduler: SyncScheduler,
    private val catalogRepository: CatalogRepository,
    private val conflictRepository: ConflictRepository,
    private val readModel: InventoryReadModel,
    connectionMonitor: ConnectionMonitor,
) : ViewModel() {
    private val retry = MutableStateFlow(0)
    val tasks = retry.flatMapLatest { readModel.observeTaskProgress().asLoadState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)
    val queue = retry.flatMapLatest { syncQueue.observeStatus().asLoadState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)
    val operations = retry.flatMapLatest { readModel.observeSyncOperations().asLoadState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)
    val conflicts = retry.flatMapLatest { conflictRepository.observeConflicts().asLoadState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)
    val connection = connectionMonitor.observeConnection().catch { emit(ConnectionState.UNKNOWN) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.UNKNOWN)
    val editingItemId = savedState.getStateFlow<Long?>("editingItemId", null)
    val quantityDraft = savedState.getStateFlow("quantityDraft", "")
    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing = mutableRefreshing.asStateFlow()
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableScheduling = MutableStateFlow(false)
    val scheduling = mutableScheduling.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val notices = messages.receiveAsFlow()

    fun observeItems(taskId: Long): Flow<LoadState<List<InventoryItem>>> =
        retry.flatMapLatest { repository.observeItems(taskId).asLoadState() }

    fun retryLoading() { retry.value += 1 }
    fun clearError() { mutableError.value = null }
    fun createDemoTask() = mutate {
        repository.createDemoTask()
        messages.trySend("Учебное задание создано")
    }

    fun openEditor(item: InventoryItem) {
        if (saving.value) return
        clearError()
        savedState["quantityDraft"] = item.countedQuantity?.toString() ?: ""
        savedState["editingItemId"] = item.id
    }

    fun changeDraft(input: String) { if (!saving.value) savedState["quantityDraft"] = input }
    fun closeEditor() { if (!saving.value) savedState["editingItemId"] = null }

    fun saveQuantity() {
        val itemId = editingItemId.value ?: return
        val input = quantityDraft.value
        mutate {
            updateQuantity(itemId, input)
            // Editor state belongs to the ViewModel, so completing a save after rotation closes the current editor.
            savedState["editingItemId"] = null
            scheduleUpload()
        }
    }

    fun refreshTasks() {
        if (mutableRefreshing.value) return
        mutableRefreshing.value = true
        clearError()
        viewModelScope.launch {
            try {
                catalogRepository.refreshTasks()
                messages.trySend("Задания обновлены")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure("refresh catalog", failure)
                mutableError.value = "Не удалось загрузить задания. Сохранённые данные доступны без сети."
            } finally {
                mutableRefreshing.value = false
            }
        }
    }

    fun resolveConflict(operationId: String, choice: ConflictChoice) = mutate(
        errorMessage = "Не удалось разрешить конфликт. Проверьте соединение и повторите.",
    ) {
        conflictRepository.resolveConflict(operationId, choice)
        scheduleUpload()
        messages.trySend("Конфликт разрешён")
    }

    fun discardRejectedChanges(operationId: String) {
        if (scheduling.value) return
        mutate(errorMessage = "Не удалось отменить изменения. Проверьте соединение и повторите. Данные сохранены.") {
            repository.discardRejectedChanges(operationId)
            scheduleUpload()
            messages.trySend("Локальные изменения товара отменены")
        }
    }

    private fun scheduleUpload() {
        try {
            syncScheduler.enqueue()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportFailure("schedule upload", failure)
            mutableError.value = "Данные сохранены на устройстве. Фоновая отправка пока недоступна."
        }
    }

    fun requestSync() {
        if (mutableScheduling.value || saving.value) return
        mutableScheduling.value = true
        clearError()
        viewModelScope.launch {
            try {
                syncQueue.retryBlocked()
                syncScheduler.enqueueNow()
                messages.trySend("Отправка запланирована")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure("request upload", failure)
                mutableError.value = "Не удалось запланировать отправку. Данные остаются на устройстве."
            } finally {
                mutableScheduling.value = false
            }
        }
    }

    private fun mutate(errorMessage: String = "Не удалось сохранить данные. Повторите попытку.", action: suspend () -> Unit) {
        if (saving.value) return
        mutableSaving.value = true
        clearError()
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure("change inventory", failure)
                mutableError.value = errorMessage
            } finally {
                mutableSaving.value = false
            }
        }
    }
}

private fun <T> Flow<T>.asLoadState(): Flow<LoadState<T>> = kotlinx.coroutines.flow.flow<LoadState<T>> {
    emit(LoadState.Loading)
    collect { emit(LoadState.Ready(it)) }
}.catch { exception ->
    if (exception is CancellationException) throw exception
    reportFailure("observe local data", exception)
    emit(LoadState.Failed)
}

private fun reportFailure(action: String, failure: Throwable) {
    if (BuildConfig.DEBUG) Logger.getLogger("StockFlow").log(Level.WARNING, "Failed to $action", failure)
}
