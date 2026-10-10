package com.polarikus.bcscanner.data.rpc

import com.flipperdevices.protobuf.Flipper
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Sends RPC requests and correlates the responses by `command_id`.
 *
 * Each request gets a unique `command_id`; the matching response completes the suspended
 * [execute] call. When no response arrives within [timeoutMillis] a [TimeoutException] is thrown
 * (FR-015). Events without a `command_id` (for example `app_state_response`) are ignored here and
 * handled by the caller.
 */
class RpcCommandDispatcher(
    private val codec: RpcFrameCodec,
    private val write: suspend (ByteArray) -> Unit,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    private val idGenerator = AtomicInteger(0)
    private val pending = LinkedHashMap<Int, CompletableDeferred<Flipper.Main>>()
    private val lock = Any()

    /**
     * Assigns a `command_id`, sends the request built by [configure] and suspends until the
     * matching response arrives or the timeout elapses.
     *
     * @throws TimeoutException when no response arrives in time.
     */
    suspend fun execute(configure: Flipper.Main.Builder.() -> Unit): Flipper.Main {
        val id = idGenerator.incrementAndGet()
        val deferred = CompletableDeferred<Flipper.Main>()
        synchronized(lock) { pending[id] = deferred }
        try {
            val message = Flipper.Main.newBuilder().setCommandId(id).apply(configure).build()
            write(codec.encode(message))
            return try {
                withTimeout(timeoutMillis) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw TimeoutException("No response for command_id=$id within ${timeoutMillis}ms")
            }
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    /** Routes an incoming [message] to the request waiting for its `command_id`, if any. */
    fun onMessage(message: Flipper.Main) {
        if (message.commandId == 0) return
        val deferred = synchronized(lock) { pending.remove(message.commandId) }
        deferred?.complete(message)
    }

    /** Completes every pending request exceptionally (for example after a disconnect). */
    fun failAll(cause: Throwable) {
        val snapshot = synchronized(lock) {
            val values = pending.values.toList()
            pending.clear()
            values
        }
        snapshot.forEach { it.completeExceptionally(cause) }
    }

    companion object {
        /** Recommended ack timeout (research R8 / contract: 3-5 s). */
        const val DEFAULT_TIMEOUT_MILLIS = 4_000L
    }
}
