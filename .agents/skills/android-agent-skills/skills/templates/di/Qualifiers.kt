package com.agentcore.di

import javax.inject.Qualifier

/**
 * Qualifier annotations for dependency injection.
 * 
 * These qualifiers help distinguish between different implementations
 * of the same type (e.g., different CoroutineDispatchers).
 * 
 * Compatible with both Hilt and Koin.
 */

/**
 * Qualifier for IO dispatcher - use for network, file, database operations.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for Default dispatcher - use for CPU-intensive work.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Qualifier for Main dispatcher - use for UI updates.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Qualifier for Main.immediate dispatcher - immediate execution if already on main.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainImmediateDispatcher

/**
 * Qualifier for application-scoped CoroutineScope.
 * Use for work that should survive configuration changes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Qualifier for process-scoped CoroutineScope.
 * Use for work that should survive the entire app lifecycle.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProcessScope
