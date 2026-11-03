package dev.unifiedcall.flutter_unified_call

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture

/** Engine-independent entry points for a host push SDK. No push vendor dependency. */
object UnifiedCall {
    @JvmStatic
    fun showIncomingCall(context: Context, config: Map<String, Any?>): CompletableFuture<Void> =
        dispatch { CallRuntime.get(context).incoming(config) }

    @JvmStatic
    fun cancelCall(context: Context, callId: String): CompletableFuture<Void> = dispatch {
        require(callId.isNotBlank()) { "Missing callId" }
        CallRuntime.get(context).act(callId, "ended", "cancelled")
    }

    private fun dispatch(operation: () -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        Handler(Looper.getMainLooper()).post {
            try { operation(); future.complete(null) }
            catch (error: Exception) { future.completeExceptionally(error) }
        }
        return future
    }
}
