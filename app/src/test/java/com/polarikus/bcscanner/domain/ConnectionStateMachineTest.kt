package com.polarikus.bcscanner.domain

import com.polarikus.bcscanner.domain.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ConnectionStateMachine] describing the documented transitions
 * (data-model.md):
 *
 * Disconnected -> Connecting -> Connected -> Disconnected
 *                              \-> Error -> Connecting / Disconnected
 */
class ConnectionStateMachineTest {

    @Test
    fun `initial state is Disconnected`() {
        val machine = ConnectionStateMachine()

        assertEquals(ConnectionState.Disconnected, machine.state.value)
    }

    @Test
    fun `start connecting from Disconnected moves to Connecting`() {
        val machine = ConnectionStateMachine()

        machine.onConnecting()

        assertEquals(ConnectionState.Connecting, machine.state.value)
    }

    @Test
    fun `successful connection moves Connecting to Connected`() {
        val machine = ConnectionStateMachine()
        machine.onConnecting()

        machine.onConnected()

        assertEquals(ConnectionState.Connected, machine.state.value)
    }

    @Test
    fun `connection failure moves Connecting to Error`() {
        val machine = ConnectionStateMachine()
        machine.onConnecting()

        machine.onError("gatt 133")

        val state = machine.state.value
        assertTrue(state is ConnectionState.Error)
        assertEquals("gatt 133", (state as ConnectionState.Error).reason)
    }

    @Test
    fun `cancel while Connecting moves to Disconnected`() {
        val machine = ConnectionStateMachine()
        machine.onConnecting()

        machine.onDisconnected()

        assertEquals(ConnectionState.Disconnected, machine.state.value)
    }

    @Test
    fun `drop while Connected moves to Disconnected`() {
        val machine = ConnectionStateMachine()
        machine.onConnecting()
        machine.onConnected()

        machine.onDisconnected()

        assertEquals(ConnectionState.Disconnected, machine.state.value)
    }

    @Test
    fun `retry from Error moves to Connecting`() {
        val machine = ConnectionStateMachine()
        machine.onConnecting()
        machine.onError("timeout")

        machine.onConnecting()

        assertEquals(ConnectionState.Connecting, machine.state.value)
    }

    @Test
    fun `dismissing Error moves to Disconnected`() {
        val machine = ConnectionStateMachine()
        machine.onConnecting()
        machine.onError("timeout")

        machine.onDisconnected()

        assertEquals(ConnectionState.Disconnected, machine.state.value)
    }

    @Test
    fun `connected is ignored when not Connecting`() {
        val machine = ConnectionStateMachine()

        machine.onConnected()

        assertEquals(ConnectionState.Disconnected, machine.state.value)
    }

    @Test
    fun `connecting is ignored while already Connected`() {
        val machine = ConnectionStateMachine()
        machine.onConnecting()
        machine.onConnected()

        machine.onConnecting()

        assertEquals(ConnectionState.Connected, machine.state.value)
    }

    @Test
    fun `error is ignored when Disconnected`() {
        val machine = ConnectionStateMachine()

        machine.onError("spurious")

        assertEquals(ConnectionState.Disconnected, machine.state.value)
    }

    @Test
    fun `state flow emits every transition`() {
        val machine = ConnectionStateMachine()
        val observed = mutableListOf<ConnectionState>()

        observed += machine.state.value
        machine.onConnecting()
        observed += machine.state.value
        machine.onConnected()
        observed += machine.state.value
        machine.onDisconnected()
        observed += machine.state.value

        assertEquals(
            listOf(
                ConnectionState.Disconnected,
                ConnectionState.Connecting,
                ConnectionState.Connected,
                ConnectionState.Disconnected,
            ),
            observed,
        )
    }
}
