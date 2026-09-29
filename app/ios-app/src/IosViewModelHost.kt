package finance.shilling.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.koin.mp.KoinPlatform

/**
 * Base for Swift-facing screen models. Each owns a [ViewModelStore], so the shared view model
 * lives exactly as long as the SwiftUI screen that holds it; Swift calls [close] when done.
 */
abstract class IosViewModelHost {
    @PublishedApi internal val store = ViewModelStore()

    protected inline fun <reified VM : ViewModel> viewModel(): VM =
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { KoinPlatform.getKoin().get<VM>() } }
        )[VM::class]

    fun close() = store.clear()
}
