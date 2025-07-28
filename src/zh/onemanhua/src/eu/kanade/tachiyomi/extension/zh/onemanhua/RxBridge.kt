package eu.kanade.tachiyomi.extension.zh.onemanhua

import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rx.Observable
import kotlin.coroutines.resume

@OptIn(DelicateCoroutinesApi::class)
fun <T> rxObservable(block: suspend () -> T): Observable<T> = Observable.unsafeCreate {
    GlobalScope.launch {
        try {
            it.onNext(block())
            it.onCompleted()
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "exception", e)
            it.onError(e)
        }
    }
}

fun main() = runBlocking {
    val callbacks = arrayOfNulls<(Int) -> Unit>(1)

    val job1 = launch {
        try {
            val v = withTimeout(100) {
                suspendCancellableCoroutine {
                    it.invokeOnCancellation {
                        println("removing callback")
                        callbacks[0] = null
                    }
                    println("installing callback")
                    callbacks[0] = it::resume
                }
            }
            println(v)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    val job2 = launch {
        delay(1200)
        try {
            println("Calling")
            callbacks[0]!!.invoke(123)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        } finally {
            println("job2 done")
        }
    }

    job1.join()
    job2.join()
}
