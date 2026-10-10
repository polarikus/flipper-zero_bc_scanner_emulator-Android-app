package com.polarikus.bcscanner.data.rpc

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.system.System
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RpcCommandDispatcher].
 *
 * Verifies that requests get unique `command_id`s, that responses are correlated by
 * `command_id`, and that a missing response produces a timeout error (FR-015).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RpcCommandDispatcherTest {

    @Test
    fun `execute sends a frame with a non-zero command id`() = runTest {
        val sent = mutableListOf<Flipper.Main>()
        val dispatcher = dispatcher(sent)
        val job = async { dispatcher.execute { setSystemPingRequest(ping()) } }

        runCurrent()

        assertEquals(1, sent.size)
        assertNotEquals(0, sent.first().commandId)
        job.cancel()
    }

    @Test
    fun `execute assigns unique command ids`() = runTest {
        val sent = mutableListOf<Flipper.Main>()
        val dispatcher = dispatcher(sent)
        val first = async { dispatcher.execute { setSystemPingRequest(ping()) } }
        val second = async { dispatcher.execute { setSystemPingRequest(ping()) } }

        runCurrent()

        assertEquals(2, sent.size)
        assertNotEquals(sent[0].commandId, sent[1].commandId)
        first.cancel()
        second.cancel()
    }

    @Test
    fun `response with a matching command id completes the request`() = runTest {
        val sent = mutableListOf<Flipper.Main>()
        val dispatcher = dispatcher(sent)
        val deferred = async { dispatcher.execute { setSystemPingRequest(ping()) } }
        runCurrent()

        val id = sent.first().commandId
        dispatcher.onMessage(
            Flipper.Main.newBuilder()
                .setCommandId(id)
                .setCommandStatus(Flipper.CommandStatus.OK)
                .build(),
        )

        assertEquals(Flipper.CommandStatus.OK, deferred.await().commandStatus)
    }

    @Test
    fun `response with an unknown command id is ignored`() = runTest {
        val sent = mutableListOf<Flipper.Main>()
        val dispatcher = dispatcher(sent)
        val deferred = async { dispatcher.execute { setSystemPingRequest(ping()) } }
        runCurrent()

        // A response for a different command must not complete the pending request.
        dispatcher.onMessage(
            Flipper.Main.newBuilder()
                .setCommandId(9999)
                .setCommandStatus(Flipper.CommandStatus.OK)
                .build(),
        )
        assertTrue(deferred.isActive)

        dispatcher.onMessage(
            Flipper.Main.newBuilder()
                .setCommandId(sent.first().commandId)
                .setCommandStatus(Flipper.CommandStatus.OK)
                .build(),
        )
        deferred.await()
    }

    @Test
    fun `missing response produces a timeout error`() = runTest {
        val sent = mutableListOf<Flipper.Main>()
        val dispatcher = dispatcher(sent, timeoutMillis = 1_000)

        val failure = try {
            dispatcher.execute { setSystemPingRequest(ping()) }
            null
        } catch (e: TimeoutException) {
            e
        }

        assertNotNull("expected TimeoutException", failure)
    }

    @Test
    fun `events without a command id are ignored`() = runTest {
        val sent = mutableListOf<Flipper.Main>()
        val dispatcher = dispatcher(sent)
        val deferred = async { dispatcher.execute { setSystemPingRequest(ping()) } }
        runCurrent()

        // app_state_response has no command_id (0) and must not complete anything.
        dispatcher.onMessage(Flipper.Main.newBuilder().setCommandId(0).build())
        assertTrue(deferred.isActive)

        dispatcher.onMessage(
            Flipper.Main.newBuilder()
                .setCommandId(sent.first().commandId)
                .setCommandStatus(Flipper.CommandStatus.OK)
                .build(),
        )
        deferred.await()
    }

    @Test
    fun `fail all completes pending requests exceptionally`() = runTest {
        val sent = mutableListOf<Flipper.Main>()
        val dispatcher = dispatcher(sent)
        val job = backgroundScope.async {
            runCatching { dispatcher.execute { setSystemPingRequest(ping()) } }.exceptionOrNull()
        }
        runCurrent()

        dispatcher.failAll(IllegalStateException("disconnected"))

        val failure = job.await()
        assertTrue("expected IllegalStateException but was $failure", failure is IllegalStateException)
    }

    private fun dispatcher(
        sent: MutableList<Flipper.Main>,
        timeoutMillis: Long = 4_000,
    ): RpcCommandDispatcher {
        val codec = RpcFrameCodec()
        return RpcCommandDispatcher(
            codec = codec,
            write = { frame -> sent += codec.decode(frame).single() },
            timeoutMillis = timeoutMillis,
        )
    }

    private fun ping(): System.PingRequest = System.PingRequest.getDefaultInstance()
}
