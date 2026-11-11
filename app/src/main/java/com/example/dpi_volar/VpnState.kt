package com.example.dpi_volar

/**
 * Estado observable del servicio VPN. La UI se suscribe a [MyVpnService.state]
 * para no mostrar nunca un estado distinto al real.
 */
sealed interface VpnState {
    /** El servicio no está activo. */
    data object Stopped : VpnState

    /** Se concedió el permiso y se está creando la interfaz TUN. */
    data object Starting : VpnState

    /** La interfaz TUN está establecida y el tráfico se está procesando. */
    data object Running : VpnState

    /** No se pudo activar o la protección se cortó. [message] es para el usuario. */
    data class Error(val message: String) : VpnState
}
