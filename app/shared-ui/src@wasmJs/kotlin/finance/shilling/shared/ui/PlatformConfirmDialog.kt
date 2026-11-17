package finance.shilling.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/** A DOM modal keeps Compose's root accessibility owner intact in WebKit. */
@Composable
internal actual fun PlatformConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean
) {
    val confirm = rememberUpdatedState(onConfirm)
    val dismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(title, message, confirmLabel, destructive) {
        val dialog = showConfirmDialog(
            title, message, confirmLabel, destructive,
            { dismiss.value(); confirm.value() },
            { dismiss.value() }
        )
        onDispose { dialog.dispose() }
    }
}

private external interface ConfirmDialogHandle : JsAny {
    fun dispose()
}

private fun showConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    confirm: () -> Unit,
    dismiss: () -> Unit
): ConfirmDialogHandle = js("""(function() {
    const previousFocus = document.activeElement;
    const dialog = document.createElement('dialog');
    dialog.setAttribute('aria-label', title);
    dialog.style.cssText = 'box-sizing:border-box;border:0;border-radius:16px;padding:24px;width:min(440px,90vw);max-width:90vw;color-scheme:light dark;color:CanvasText;background:Canvas;box-shadow:0 16px 48px #0004;';
    const heading = document.createElement('h2');
    heading.textContent = title;
    heading.style.cssText = 'margin:0 0 12px;font:600 20px system-ui;';
    const body = document.createElement('p');
    body.textContent = message;
    body.style.cssText = 'margin:0 0 24px;font:400 14px/1.5 system-ui;';
    const actions = document.createElement('div');
    actions.style.cssText = 'display:flex;justify-content:flex-end;gap:8px;';
    const cancel = document.createElement('button');
    cancel.textContent = 'Cancel';
    const accept = document.createElement('button');
    accept.textContent = confirmLabel;
    for (const button of [cancel, accept]) {
        button.style.cssText = 'font:500 14px system-ui;min-height:40px;padding:8px 16px;border-radius:8px;cursor:pointer;';
    }
    if (destructive) accept.style.color = '#b3261e';
    cancel.onclick = dismiss;
    accept.onclick = confirm;
    actions.append(cancel, accept);
    dialog.append(heading, body, actions);
    dialog.addEventListener('cancel', event => { event.preventDefault(); dismiss(); });
    document.body.appendChild(dialog);
    dialog.showModal();
    cancel.focus();
    return {dispose() {
        cancel.onclick = null;
        accept.onclick = null;
        dialog.close();
        dialog.remove();
        if (previousFocus && previousFocus.isConnected) previousFocus.focus();
    }};
})()""")
