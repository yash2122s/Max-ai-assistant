package com.agentcore.skill.base

/**
 * Marker annotation indicating that a class is immutable.
 * 
 * When using Jetpack Compose, this should be replaced with:
 * `import androidx.compose.runtime.Immutable`
 * 
 * This annotation indicates that:
 * - All public properties are vals (immutable)
 * - All properties use immutable types
 * - The class represents a stable snapshot of data
 * 
 * For Compose stability, classes marked with this annotation
 * will skip recomposition when equals() returns true.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Immutable

/**
 * Marker annotation indicating that a class is stable.
 * 
 * When using Jetpack Compose, this should be replaced with:
 * `import androidx.compose.runtime.Stable`
 * 
 * This annotation indicates that:
 * - The class may have mutable state
 * - Changes to state will notify Compose (e.g., via MutableState)
 * - The class can be used as a stable key for recomposition
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Stable
