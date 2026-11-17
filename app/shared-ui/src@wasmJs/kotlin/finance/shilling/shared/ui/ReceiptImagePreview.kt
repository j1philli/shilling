package finance.shilling.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import org.khronos.webgl.toInt8Array

/** Browser decoding avoids base64/Skia copies and keeps the Compose accessibility root intact. */
@Composable
internal actual fun ReceiptImagePreviewDialog(fileName: String, imageBytes: ByteArray, onDismiss: () -> Unit) {
    val dismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(fileName, imageBytes) {
        val dialog = showReceiptDialog(imageBytes.toInt8Array(), fileName) { dismiss.value() }
        onDispose { dialog.dispose() }
    }
}

private external interface ReceiptDialogHandle : JsAny {
    fun dispose()
}

private fun showReceiptDialog(bytes: JsAny, fileName: String, dismiss: () -> Unit): ReceiptDialogHandle = js("""(function() {
    const ext = fileName.split('.').pop().toLowerCase();
    const type = ({png:'image/png',jpg:'image/jpeg',jpeg:'image/jpeg',gif:'image/gif',webp:'image/webp',bmp:'image/bmp'})[ext];
    const url = URL.createObjectURL(new Blob([bytes], {type: type || 'application/octet-stream'}));
    const previousFocus = document.activeElement;
    const dialog = document.createElement('dialog');
    dialog.setAttribute('aria-label', fileName);
    dialog.style.cssText = 'box-sizing:border-box;border:0;border-radius:16px;padding:16px;width:min(900px,90vw);height:min(900px,90vh);color-scheme:light dark;color:CanvasText;background:Canvas;';
    const heading = document.createElement('div');
    heading.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:16px;height:40px;font:500 16px system-ui;';
    const title = document.createElement('span'); title.textContent = fileName;
    title.style.cssText = 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
    const close = document.createElement('button'); close.textContent = 'Close';
    close.style.cssText = 'font:inherit;min-height:40px;padding:8px 16px;cursor:pointer;';
    close.onclick = dismiss;
    heading.append(title, close);
    const image = document.createElement('img'); image.alt = fileName; image.decoding = 'async';
    image.style.cssText = 'display:block;width:100%;height:calc(100% - 48px);margin-top:8px;object-fit:contain;';
    image.src = url;
    image.onerror = () => { image.alt = 'Could not preview ' + fileName; };
    dialog.append(heading, image);
    dialog.addEventListener('cancel', event => {event.preventDefault();dismiss();});
    dialog.addEventListener('click', event => {
        if(event.target !== dialog) return;
        const r = dialog.getBoundingClientRect();
        if(event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dismiss();
    });
    document.body.appendChild(dialog); dialog.showModal(); close.focus();
    return {dispose() {
        image.onload = null; image.onerror = null; image.removeAttribute('src');
        dialog.close(); dialog.remove(); URL.revokeObjectURL(url);
        if(previousFocus && previousFocus.isConnected) previousFocus.focus();
    }};
})()""")
