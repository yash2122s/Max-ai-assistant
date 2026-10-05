package com.example.automation.state

sealed class ExpectedPredicate {
    data class FocusEquals(val targetQuery: String) : ExpectedPredicate()
    data class ElementPresent(val query: String) : ExpectedPredicate()
    data class ScreenEquals(val screenNameOrActivity: String) : ExpectedPredicate()
    data class PackageForeground(val packageName: String) : ExpectedPredicate()
    data class TextAppeared(val text: String) : ExpectedPredicate()

    fun evaluate(snapshot: UiScreenSnapshot): Boolean {
        return when (this) {
            is FocusEquals -> {
                snapshot.focusedNode?.let {
                    it.id.contains(targetQuery, ignoreCase = true) ||
                    it.resourceId?.contains(targetQuery, ignoreCase = true) == true ||
                    it.text?.contains(targetQuery, ignoreCase = true) == true
                } ?: false
            }

            is ElementPresent -> {
                snapshot.nodes.any {
                    it.text?.contains(query, ignoreCase = true) == true ||
                    it.contentDescription?.contains(query, ignoreCase = true) == true ||
                    it.resourceId?.contains(query, ignoreCase = true) == true
                }
            }

            is ScreenEquals -> {
                snapshot.activityName?.contains(screenNameOrActivity, ignoreCase = true) == true
            }

            is PackageForeground -> {
                snapshot.packageName.equals(packageName, ignoreCase = true) ||
                snapshot.packageName.contains(packageName, ignoreCase = true)
            }

            is TextAppeared -> {
                snapshot.allVisibleTexts.any { it.contains(text, ignoreCase = true) } ||
                snapshot.nodes.any { it.text?.contains(text, ignoreCase = true) == true }
            }
        }
    }
}
