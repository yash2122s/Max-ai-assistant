package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.SearchContactAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.PendingCallManager
import com.example.automation.verification.RetryPolicy
import org.json.JSONArray
import org.json.JSONObject

class SearchContactTool(private val searchContactAction: SearchContactAction) : Tool {
    override val name: String = "search_contact"
    override val supportedActions: Set<String> = setOf("SEARCH_CONTACT")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        return request.arguments.has("query")
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val query = request.arguments.get("query").asString
            var matches = searchContactAction.searchContacts(context, query)

            val cleanQuery = query.trim().replace(Regex("[\\s\\-()]"), "")
            val isDigitOnly = cleanQuery.matches(Regex("""\+?\d{7,15}"""))

            if (matches.isEmpty() && isDigitOnly) {
                val normalizedNumber = android.telephony.PhoneNumberUtils.normalizeNumber(query) ?: query
                val syntheticMatch = com.example.automation.engine.ContactMatch(
                    contactId = -999L,
                    lookupKey = "raw_number:$normalizedNumber",
                    displayName = "Raw Number",
                    phoneNumbers = listOf(
                        com.example.automation.engine.PhoneNumber(
                            id = -999L,
                            label = "Number",
                            normalizedNumber = normalizedNumber
                        )
                    )
                )
                matches = listOf(syntheticMatch)
            }

            if (matches.isEmpty()) {
                PendingCallManager.clear()
                return ToolResult(
                    success = true,
                    toolName = name,
                    message = JSONObject().apply {
                        put("success", true)
                        put("found", false)
                        put("matches", JSONArray())
                        put("instruction", "Tell the user you couldn't find the contact and ask what they want to do.")
                    }.toString()
                )
            }

            // Save the matches into the pending call state machine
            PendingCallManager.setAwaitingContactSelection(matches)

            // Convert to JSON
            val matchesJson = JSONArray()
            matches.forEach { match ->
                val matchObj = JSONObject()
                matchObj.put("contactId", match.contactId)
                matchObj.put("displayName", match.displayName)
                
                val phonesJson = JSONArray()
                match.phoneNumbers.forEach { phone ->
                    val phoneObj = JSONObject()
                    phoneObj.put("phoneId", phone.id)
                    phoneObj.put("label", phone.label)
                    phonesJson.put(phoneObj)
                }
                matchObj.put("phoneNumbers", phonesJson)
                matchesJson.put(matchObj)
            }

            val resultJson = JSONObject().apply {
                put("success", true)
                put("found", true)
                put("matches", matchesJson)
                put("instruction", "Read out the matching names and labels. Ask the user to confirm WHICH one to call. DO NOT invoke call_contact until the user explicitly says YES or chooses one.")
            }

            ToolResult(
                success = true,
                toolName = name,
                message = resultJson.toString()
            )
        } catch (e: SecurityException) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "PERMISSION_DENIED",
                message = "I need permission to access your contacts before I can search them."
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "SEARCH_ERROR",
                message = e.message ?: "Failed to search contacts"
            )
        }
    }
}
