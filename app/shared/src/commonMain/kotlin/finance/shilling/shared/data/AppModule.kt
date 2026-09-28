package finance.shilling.shared.data

import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import finance.shilling.shared.data.auth.HostedBootstrapState
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
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

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

/**
 * Bindings owned by the app bootstrap: hosted-bootstrap status plus the callbacks Settings
 * uses to drive bootstrap state (reset, sign-out, server/household changes).
 */
fun bootstrapSessionModule(
    hostedBootstrapState: HostedBootstrapState,
    onRetryHostedBootstrap: HostedBootstrapRetryCallback,
    onResetOnboardingUi: suspend () -> Unit,
    onRestartHostedLoginUi: suspend () -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onHouseholdIdChanged: (String) -> Unit
): Module = module {
    single { hostedBootstrapState }
    single { onRetryHostedBootstrap }
    single {
        // Full reset: wipe local data through Store5 repositories, then reset the UI state.
        val wiper = get<LocalDataWiper>()
        ResetOnboardingCallback {
            wiper.wipe()
            onResetOnboardingUi()
        }
    }
    // Soft-return to Welcome while keeping local data for a matching re-login.
    single { RestartHostedLoginCallback(onRestartHostedLoginUi) }
    single { ServerUrlCallback(onServerUrlChanged) }
    single { HouseholdIdCallback(onHouseholdIdChanged) }
}
