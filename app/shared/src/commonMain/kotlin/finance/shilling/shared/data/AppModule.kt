package finance.shilling.shared.data

import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.store.createAccountStore
import finance.shilling.shared.data.store.createCategoryStore
import finance.shilling.shared.data.store.createPostingStore
import finance.shilling.shared.data.store.createReceiptStore
import finance.shilling.shared.data.store.createScheduleExceptionStore
import finance.shilling.shared.data.store.createScheduleStore
import finance.shilling.shared.data.sync.PeerConnectionStatus
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.presentation.DisplayPreferences
import finance.shilling.shared.presentation.ActivityViewModel
import finance.shilling.shared.presentation.HomeViewModel
import finance.shilling.shared.presentation.AccountEditorViewModel
import finance.shilling.shared.presentation.AccountsViewModel
import finance.shilling.shared.presentation.CategoryEditorViewModel
import finance.shilling.shared.presentation.CategoriesViewModel
import finance.shilling.shared.presentation.OccurrenceActions
import finance.shilling.shared.presentation.ScheduleEditorViewModel
import finance.shilling.shared.presentation.ImportViewModel
import finance.shilling.shared.presentation.OnboardingViewModel
import finance.shilling.shared.presentation.ReceiptEditorViewModel
import finance.shilling.shared.presentation.SchedulesViewModel
import finance.shilling.shared.presentation.TransactionEditorViewModel
import finance.shilling.shared.presentation.PlanOverviewViewModel
import finance.shilling.shared.presentation.PlanRequests
import finance.shilling.shared.presentation.ReceiptsViewModel
import finance.shilling.shared.presentation.SettingsViewModel
import finance.shilling.shared.presentation.HostedDevicesViewModel
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * Starts the app's single Koin graph: the shared [dataModule] plus the app's [modules] (its
 * platform module and, on apps, `sessionModule`). Each platform calls this from its entry point
 * before showing any UI, so Compose and native (Swift) code resolve from the same graph. Later
 * calls return the running instance (e.g. an Android Activity recreated in the same process).
 */
fun initKoin(vararg modules: Module): Koin =
    KoinPlatform.getKoinOrNull() ?: startKoin { modules(dataModule, *modules) }.koin.also {
        DisplayPreferences.load(it.get())
    }

/**
 * Shared data graph: identity, Store5 stores, repositories, use cases, and the sync facade.
 *
 * Every dependency is resolved from Koin, so the platform module must provide:
 * `ShillingDatabase`, `Settings`, `IdGenerator`, `ReceiptFileStore`, and `HttpClient`.
 */
val dataModule: Module = module {
    single { DeviceIdentity(get(), get()) }
    single { ChangeNotifier() }
    // One StoreSyncDeps shared by every store and repository; the sync runtime swaps its
    // peer manager in and out as sync starts and stops.
    single { StoreSyncDeps(get(), null, get<DeviceIdentity>().deviceId, get()) }
    single { PeerConnectionStatus() }

    single { createAccountStore(get(), get()) }
    single { createCategoryStore(get(), get()) }
    single { createScheduleStore(get(), get()) }
    single { createScheduleExceptionStore(get(), get()) }
    single { createPostingStore(get(), get()) }
    single { createReceiptStore(get(), get()) }
    single {
        SyncStoreFacade(
            db = get(),
            accountStore = get(),
            categoryStore = get(),
            scheduleStore = get(),
            scheduleExceptionStore = get(),
            postingStore = get(),
            receiptStore = get()
        )
    }

    single { AccountRepository(get(), get(), get()) }
    single { CategoryRepository(get(), get(), get()) }
    single { ScheduleRepository(get(), get(), get(), get()) }
    single { PostingRepository(get(), get(), get(), get(), get(), get(), get()) }
    single { ReceiptRepository(get(), get(), get(), get(), get()) }

    single { ComputeWindowUseCase(get(), get(), get(), get(), get()) }
    single { ComputeBudgetUseCase(get(), get(), get()) }

    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ActivityViewModel(get()) }
    viewModel { ReceiptsViewModel(get()) }
    single { PlanRequests() }
    factory { OccurrenceActions(get(), get()) }
    viewModel { PlanOverviewViewModel(get(), get()) }
    viewModel { SchedulesViewModel(get(), get(), get()) }
    viewModel { CategoriesViewModel(get()) }
    viewModel { AccountsViewModel(get()) }
    // Editors take the item id (null = new) as a parameter.
    viewModel { params -> CategoryEditorViewModel(params.getOrNull(), get(), get()) }
    viewModel { params -> AccountEditorViewModel(params.getOrNull(), get(), get()) }
    // Parameters: schedule id (null = new), then the preset type for new schedules.
    viewModel { ImportViewModel(get(), get(), get()) }
    // OnboardingActions comes from sessionModule (AppSession).
    viewModel { OnboardingViewModel(get(), get()) }
    viewModel { params -> ReceiptEditorViewModel(params.getOrNull(), get(), get(), get(), get()) }
    viewModel { params -> TransactionEditorViewModel(params.getOrNull(), get(), get(), get(), get(), get(), get()) }
    viewModel { params -> ScheduleEditorViewModel(params.getOrNull(), params.getOrNull(), get(), get(), get(), get()) }
    // Needs SessionState and the Settings callbacks from sessionModule.
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { HostedDevicesViewModel(get(), get(), get(), get(), get()) }

    single {
        LocalDataWiper(
            db = get(),
            settings = get(),
            fileStore = get(),
            accountRepository = get(),
            categoryRepository = get(),
            scheduleRepository = get(),
            postingRepository = get(),
            receiptRepository = get()
        )
    }
}
