package finance.shilling.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import finance.shilling.shared.ui.AppPaths
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Mirrors in-app navigation into the browser's history (as `#/path` URLs) and maps the
 * browser's back/forward buttons onto the NavController.
 *
 * Browser history is linear, the way people expect on the web: Back returns to the page you
 * were just on, even across tabs. In-app back arrows step the browser history back too, so
 * the two never drift apart. Reloading or opening a `#/path` link lands on that page.
 */
@Composable
fun BrowserHistoryBinding(navController: NavHostController) {
    val sync = remember(navController) { BrowserHistorySync(navController) }

    DisposableEffect(sync) {
        val listener: (Event) -> Unit = { sync.onPopState() }
        window.addEventListener("popstate", listener)
        onDispose { window.removeEventListener("popstate", listener) }
    }

    LaunchedEffect(sync) {
        sync.start()
        navController.currentBackStackEntryFlow.collect { entry ->
            AppPaths.pathOf(entry)?.let(sync::onAppNavigated)
        }
    }
}

private class BrowserHistorySync(private val navController: NavHostController) {
    // Our view of the browser history: the path at each index we've pushed, and where we are.
    private val paths = mutableListOf<String>()
    private var position = 0

    fun start() {
        val initial = currentUrlPath()
        position = stateIndex() ?: 0
        paths.clear()
        repeat(position) { paths.add("") } // entries from before a reload are unknown
        paths.add(initial ?: AppPaths.HOME)
        if (initial == null) {
            window.history.replaceState(position.toJsNumber(), "", "#${AppPaths.HOME}")
        } else if (initial != AppPaths.HOME) {
            AppPaths.navigate(navController, initial)
        }
    }

    /** The app navigated (tab, detail, in-app back); make the browser follow. */
    fun onAppNavigated(path: String) {
        if (path == currentUrlPath()) return // already there (e.g. the browser initiated it)
        if (position > 0 && paths.getOrNull(position - 1) == path) {
            // Going back to the page the browser has just behind us: step back instead of
            // pushing, so Back/Forward stay meaningful. onPopState will see we're already there.
            window.history.back()
            return
        }
        position += 1
        while (paths.size > position) paths.removeAt(paths.lastIndex)
        paths.add(path)
        window.history.pushState(position.toJsNumber(), "", "#$path")
    }

    /** The user pressed browser Back/Forward. */
    fun onPopState() {
        position = stateIndex() ?: 0
        val path = currentUrlPath() ?: AppPaths.HOME
        while (paths.size <= position) paths.add("")
        paths[position] = path
        val current = navController.currentBackStackEntry?.let(AppPaths::pathOf)
        if (path == current) return
        val previous = navController.previousBackStackEntry?.let(AppPaths::pathOf)
        if (path == previous) {
            navController.popBackStack()
        } else if (!AppPaths.navigate(navController, path)) {
            AppPaths.navigate(navController, AppPaths.HOME)
        }
    }

    private fun currentUrlPath(): String? =
        window.location.hash.removePrefix("#").takeIf { it.startsWith("/") }

    private fun stateIndex(): Int? = (window.history.state as? JsNumber)?.toInt()
}
