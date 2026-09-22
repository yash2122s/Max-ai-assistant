package com.agentcore.skills.network

/**
 * Abstraction for network connectivity monitoring.
 * 
 * This interface allows the skill to check network status without
 * depending on Android-specific APIs. Platform implementations
 * should provide the actual connectivity information.
 */
interface NetworkMonitor {
    
    /**
     * Returns whether the device is currently connected to a network.
     */
    suspend fun isConnected(): Boolean
    
    /**
     * Returns the current connection type.
     */
    suspend fun getConnectionType(): ConnectionType
    
    /**
     * Returns detailed network information.
     */
    suspend fun getNetworkInfo(): NetworkInfo
}

/**
 * Types of network connections.
 */
enum class ConnectionType {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
    NONE,
}

/**
 * Detailed network information.
 */
data class NetworkInfo(
    /**
     * Whether a network connection is available.
     */
    val isConnected: Boolean,
    
    /**
     * The type of connection.
     */
    val connectionType: ConnectionType,
    
    /**
     * Whether the connection is metered (e.g., mobile data).
     */
    val isMetered: Boolean = false,
    
    /**
     * Whether the connection is roaming.
     */
    val isRoaming: Boolean = false,
    
    /**
     * Signal strength (0-100), or null if not available.
     */
    val signalStrength: Int? = null,
    
    /**
     * Network name (e.g., WiFi SSID), or null if not available.
     */
    val networkName: String? = null,
    
    /**
     * Download speed estimate in Kbps, or null if not available.
     */
    val downstreamBandwidthKbps: Int? = null,
    
    /**
     * Upload speed estimate in Kbps, or null if not available.
     */
    val upstreamBandwidthKbps: Int? = null,
    
    /**
     * Additional platform-specific data.
     */
    val extras: Map<String, Any> = emptyMap(),
) {
    companion object {
        val Disconnected = NetworkInfo(
            isConnected = false,
            connectionType = ConnectionType.NONE,
        )
        
        fun wifi(
            networkName: String? = null,
            signalStrength: Int? = null,
        ) = NetworkInfo(
            isConnected = true,
            connectionType = ConnectionType.WIFI,
            isMetered = false,
            networkName = networkName,
            signalStrength = signalStrength,
        )
        
        fun cellular(
            isMetered: Boolean = true,
            isRoaming: Boolean = false,
        ) = NetworkInfo(
            isConnected = true,
            connectionType = ConnectionType.CELLULAR,
            isMetered = isMetered,
            isRoaming = isRoaming,
        )
    }
}

/**
 * Stub implementation for testing.
 * Always reports as connected.
 */
class StubNetworkMonitor(
    private val networkInfo: NetworkInfo = NetworkInfo.wifi(),
) : NetworkMonitor {
    
    override suspend fun isConnected(): Boolean = networkInfo.isConnected
    
    override suspend fun getConnectionType(): ConnectionType = networkInfo.connectionType
    
    override suspend fun getNetworkInfo(): NetworkInfo = networkInfo
}

/**
 * Configurable test implementation.
 */
class ConfigurableNetworkMonitor : NetworkMonitor {
    
    @Volatile
    private var _networkInfo: NetworkInfo = NetworkInfo.wifi()
    
    override suspend fun isConnected(): Boolean = _networkInfo.isConnected
    
    override suspend fun getConnectionType(): ConnectionType = _networkInfo.connectionType
    
    override suspend fun getNetworkInfo(): NetworkInfo = _networkInfo
    
    /**
     * Sets the current network state for testing.
     */
    fun setNetworkInfo(info: NetworkInfo) {
        _networkInfo = info
    }
    
    /**
     * Simulates disconnection.
     */
    fun disconnect() {
        _networkInfo = NetworkInfo.Disconnected
    }
    
    /**
     * Simulates WiFi connection.
     */
    fun connectWifi(networkName: String? = null) {
        _networkInfo = NetworkInfo.wifi(networkName)
    }
    
    /**
     * Simulates cellular connection.
     */
    fun connectCellular(isRoaming: Boolean = false) {
        _networkInfo = NetworkInfo.cellular(isRoaming = isRoaming)
    }
}
