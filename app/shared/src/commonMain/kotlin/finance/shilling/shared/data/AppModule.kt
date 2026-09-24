package finance.shilling.shared.data

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import finance.shilling.shared.data.auth.HostedBootstrapState
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.AccountStore
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.CategoryStore
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.PostingStore
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ReceiptStore
import finance.shilling.shared.data.store.ScheduleExceptionStore
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.store.ScheduleStore
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.store.createAccountStore
import finance.shilling.shared.data.store.createCategoryStore
import finance.shilling.shared.data.store.createPostingStore
import finance.shilling.shared.data.store.createReceiptStore
import finance.shilling.shared.data.store.createScheduleExceptionStore
import finance.shilling.shared.data.store.createScheduleStore
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.db.ShillingDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

fun createAppModule(
    db: ShillingDatabase,
    idGenerator: IdGenerator,
    fileStore: ReceiptFileStore,
    settings: Settings,
    deviceIdentity: DeviceIdentity,
    notifier: ChangeNotifier,
    syncDeps: StoreSyncDeps,
    hostedBootstrapState: HostedBootstrapState,
    onRetryHostedBootstrap: HostedBootstrapRetryCallback,
    onResetOnboardingUi: suspend () -> Unit,
    onRestartHostedLoginUi: suspend () -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onHouseholdIdChanged: (String) -> Unit
): Module = module {
    single<IdGenerator> { idGenerator }
    single<ReceiptFileStore> { fileStore }
    single { settings }
    single { deviceIdentity }
    single { hostedBootstrapState }
    single { onRetryHostedBootstrap }
    single {
        // Compose the full reset: wipe local data through Store5 repositories, then
        // run the caller-supplied UI-state reset. Constructed inside Koin so the
        // repositories needed for the wipe are resolvable.
        ResetOnboardingCallback {
            wipeLocalAppState(
                db = db,
                settings = settings,
                fileStore = fileStore,
                accountRepository = get<AccountRepository>(),
                categoryRepository = get<CategoryRepository>(),
                scheduleRepository = get<ScheduleRepository>(),
                postingRepository = get<PostingRepository>(),
                receiptRepository = get<ReceiptRepository>()
            )
            onResetOnboardingUi()
        }
    }
    single {
        // Soft-return to Welcome while keeping local data for a matching re-login.
        RestartHostedLoginCallback {
            onRestartHostedLoginUi()
        }
    }
    single { ServerUrlCallback(onServerUrlChanged) }
    single { HouseholdIdCallback(onHouseholdIdChanged) }
    single { db }
    single { notifier }
    single { syncDeps }
    single { createAccountStore(db, syncDeps) }
    single { createCategoryStore(db, syncDeps) }
    single { createScheduleStore(db, syncDeps) }
    single { createScheduleExceptionStore(db, syncDeps) }
    single { createPostingStore(db, syncDeps) }
    single { createReceiptStore(db, syncDeps) }
    single {
        SyncStoreFacade(
            db = db,
            accountStore = get(),
            categoryStore = get(),
            scheduleStore = get(),
            scheduleExceptionStore = get(),
            postingStore = get(),
            receiptStore = get()
        )
    }
    single { AccountRepository(notifier, get<AccountStore>(), syncDeps) }
    single { CategoryRepository(notifier, get<CategoryStore>(), syncDeps) }
    single { ScheduleRepository(notifier, get<ScheduleStore>(), get<ScheduleExceptionStore>(), syncDeps) }
    single {
        PostingRepository(
            idGenerator,
            notifier,
            get<PostingStore>(),
            get<AccountStore>(),
            get<CategoryStore>(),
            get<ScheduleStore>(),
            syncDeps
        )
    }
    single { ReceiptRepository(notifier, get<ReceiptStore>(), get<PostingStore>(), get<ScheduleStore>(), syncDeps) }
    single {
        ComputeWindowUseCase(
            notifier,
            get<AccountRepository>(),
            get<CategoryRepository>(),
            get<ScheduleRepository>(),
            get<PostingRepository>()
        )
    }
    single {
        ComputeBudgetUseCase(
            notifier,
            get<CategoryRepository>(),
            get<ScheduleRepository>()
        )
    }
}
