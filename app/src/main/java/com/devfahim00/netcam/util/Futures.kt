package com.devfahim00.netcam.util

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Awaits a [ListenableFuture] (e.g. ProcessCameraProvider.getInstance) inside coroutines. */
suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            Executor { it.run() }
        )
        cont.invokeOnCancellation { cancel(false) }
    }
