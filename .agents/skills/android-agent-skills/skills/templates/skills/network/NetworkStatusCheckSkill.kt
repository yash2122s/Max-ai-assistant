package com.agentcore.skills.network

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.Immutable
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata

/**
 * Input for NetworkStatusCheckSkill.
 */
@Immutable
data class NetworkStatusInput(
    /**
     * Whether to include detailed network information.
     */
    val includeDetails: Boolean = false,
    
    /**
     * Required connection types (empty = any connection).
     */
    val requiredTypes: Set<ConnectionType> = emptySet(),
    
    /**
     * Whether metered connections are acceptable.
     */
    val allowMetered: Boolean = true,
    
    /**
     * Whether roaming connections are acceptable.
     */
    val allowRoaming: Boolean = true,
)

/**
 * Output from NetworkStatusCheckSkill.
 */
@Immutable
data class NetworkStatusOutput(
    /**
     * Whether the network meets the requirements.
     */
    val isAvailable: Boolean,
    
    /**
     * Whether there is any connectivity.
     */
    val isConnected: Boolean,
    
    /**
     * Current connection type.
     */
    val connectionType: ConnectionType,
    
    /**
     * Reason why network is unavailable, if applicable.
     */
    val unavailableReason: NetworkUnavailableReason? = null,
    
    /**
     * Detailed network information.
     */
    val details: NetworkInfo? = null,
)

/**
 * Reasons why network might be unavailable.
 */
enum class NetworkUnavailableReason {
    NO_CONNECTION,
    WRONG_CONNECTION_TYPE,
    METERED_NOT_ALLOWED,
    ROAMING_NOT_ALLOWED,
}

/**
 * Skill for checking network connectivity status.
 * 
 * This skill checks network availability and can validate specific
 * requirements like connection type, metered status, and roaming.
 * 
 * Uses the NetworkMonitor interface so it has no direct Android dependencies.
 */
class NetworkStatusCheckSkill(
    private val networkMonitor: NetworkMonitor,
) : BaseSkill<NetworkStatusInput, NetworkStatusOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "core.network.status"
        name = "Network Status Check"
        description = "Checks network connectivity and validates requirements"
        category = SkillCategory.NETWORK
        tags("network", "connectivity", "core")
        inputType = NetworkStatusInput::class
        outputType = NetworkStatusOutput::class
        requiresNetwork = false  // This skill checks network, doesn't require it
        isRetryable = true
        recommendedTimeoutMs = 5_000L
    }
    
    override suspend fun doExecute(
        input: NetworkStatusInput,
        context: SkillContext,
    ): SkillResult<NetworkStatusOutput> {
        val networkInfo = networkMonitor.getNetworkInfo()
        
        // Check basic connectivity
        if (!networkInfo.isConnected) {
            return SkillResult.success(
                NetworkStatusOutput(
                    isAvailable = false,
                    isConnected = false,
                    connectionType = ConnectionType.NONE,
                    unavailableReason = NetworkUnavailableReason.NO_CONNECTION,
                    details = if (input.includeDetails) networkInfo else null,
                )
            )
        }
        
        // Check connection type requirement
        if (input.requiredTypes.isNotEmpty() && 
            networkInfo.connectionType !in input.requiredTypes
        ) {
            return SkillResult.success(
                NetworkStatusOutput(
                    isAvailable = false,
                    isConnected = true,
                    connectionType = networkInfo.connectionType,
                    unavailableReason = NetworkUnavailableReason.WRONG_CONNECTION_TYPE,
                    details = if (input.includeDetails) networkInfo else null,
                )
            )
        }
        
        // Check metered requirement
        if (!input.allowMetered && networkInfo.isMetered) {
            return SkillResult.success(
                NetworkStatusOutput(
                    isAvailable = false,
                    isConnected = true,
                    connectionType = networkInfo.connectionType,
                    unavailableReason = NetworkUnavailableReason.METERED_NOT_ALLOWED,
                    details = if (input.includeDetails) networkInfo else null,
                )
            )
        }
        
        // Check roaming requirement
        if (!input.allowRoaming && networkInfo.isRoaming) {
            return SkillResult.success(
                NetworkStatusOutput(
                    isAvailable = false,
                    isConnected = true,
                    connectionType = networkInfo.connectionType,
                    unavailableReason = NetworkUnavailableReason.ROAMING_NOT_ALLOWED,
                    details = if (input.includeDetails) networkInfo else null,
                )
            )
        }
        
        // All requirements met
        return SkillResult.success(
            NetworkStatusOutput(
                isAvailable = true,
                isConnected = true,
                connectionType = networkInfo.connectionType,
                unavailableReason = null,
                details = if (input.includeDetails) networkInfo else null,
            )
        )
    }
    
    companion object {
        /**
         * Creates input for simple connectivity check.
         */
        fun simpleCheckInput(): NetworkStatusInput = NetworkStatusInput()
        
        /**
         * Creates input requiring WiFi only.
         */
        fun wifiOnlyInput(): NetworkStatusInput = NetworkStatusInput(
            requiredTypes = setOf(ConnectionType.WIFI),
        )
        
        /**
         * Creates input requiring unmetered connection.
         */
        fun unmeteredInput(): NetworkStatusInput = NetworkStatusInput(
            allowMetered = false,
        )
        
        /**
         * Creates input for detailed status with all restrictions.
         */
        fun detailedInput(
            requiredTypes: Set<ConnectionType> = emptySet(),
            allowMetered: Boolean = true,
            allowRoaming: Boolean = true,
        ): NetworkStatusInput = NetworkStatusInput(
            includeDetails = true,
            requiredTypes = requiredTypes,
            allowMetered = allowMetered,
            allowRoaming = allowRoaming,
        )
    }
}
