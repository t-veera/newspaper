package android.print

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives a [PrintDocumentAdapter] directly, without the system print dialog.
 *
 * LayoutResultCallback and WriteResultCallback have constructors hidden from the SDK
 * (public at runtime, `@hide` in android.jar), so subclasses must be declared in the
 * `android.print` package to compile. This is the standard workaround for headless
 * WebView-to-PDF export.
 */
object PrintAdapterDriver {

    class PrintFailed(message: String) : Exception(message)

    suspend fun layout(adapter: PrintDocumentAdapter, attributes: PrintAttributes): PrintDocumentInfo =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            adapter.onLayout(null, attributes, cancel, object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                    if (cont.isActive) cont.resume(info)
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    if (cont.isActive) cont.resumeWithException(PrintFailed("Layout failed: $error"))
                }

                override fun onLayoutCancelled() {
                    if (cont.isActive) cont.resumeWithException(PrintFailed("Layout cancelled"))
                }
            }, Bundle())
        }

    suspend fun write(adapter: PrintDocumentAdapter, destination: ParcelFileDescriptor): List<PageRange> =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), destination, cancel,
                object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>) {
                        if (cont.isActive) cont.resume(pages.toList())
                    }

                    override fun onWriteFailed(error: CharSequence?) {
                        if (cont.isActive) cont.resumeWithException(PrintFailed("Write failed: $error"))
                    }

                    override fun onWriteCancelled() {
                        if (cont.isActive) cont.resumeWithException(PrintFailed("Write cancelled"))
                    }
                })
        }
}
