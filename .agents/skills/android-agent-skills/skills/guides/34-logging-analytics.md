---
name: Logging & Analytics Patterns
description: Structured logging, analytics abstraction, crash reporting, and performance monitoring for Android apps.
compliance_level: RECOMMENDED
tags: [logging, analytics, timber, crashlytics, performance, monitoring]
version: 1.0.0
---

# Logging & Analytics Patterns

## Context

Proper logging and analytics are essential for debugging, monitoring app health, and understanding user behavior. This guide establishes patterns for:

1. **Structured logging** with Timber
2. **Analytics abstraction** (Firebase, Amplitude, etc.)
3. **Crash reporting** (Crashlytics, Sentry)
4. **Performance monitoring**
5. **Privacy-compliant tracking**

**Related Guides:**
- [10-error-handling.md](./10-error-handling.md) - Error handling patterns
- [12-security.md](./12-security.md) - Security considerations
- [23-memory-performance.md](./23-memory-performance.md) - Performance patterns

---

## 🎯 AI Quick Reference

```
LOGGING LEVELS:
• VERBOSE → Development only, detailed traces
• DEBUG   → Development, state changes
• INFO    → Important events, user actions
• WARN    → Recoverable issues
• ERROR   → Failures requiring attention
• WTF     → Should never happen

ANALYTICS EVENTS:
• screen_view    → Screen navigation
• user_action    → Button clicks, interactions
• business_event → Conversions, purchases
• error_event    → Non-fatal errors

CRASH REPORTING:
• Auto-capture unhandled exceptions
• Log breadcrumbs for context
• Attach user/session info
• Redact sensitive data

NEVER LOG:
• Passwords, tokens, API keys
• PII without consent
• Full credit card numbers
• Health/biometric data
```

---

## 1. Timber Setup & Configuration

### 1.1 Base Setup

```kotlin
// Application.kt
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeLogging()
    }
    
    private fun initializeLogging() {
        if (BuildConfig.DEBUG) {
            // Debug builds: Log to Logcat with line numbers
            Timber.plant(DebugTree())
        } else {
            // Release builds: Log to crash reporting
            Timber.plant(CrashReportingTree())
        }
    }
}

/**
 * Custom debug tree with class name and line number.
 */
class DebugTree : Timber.DebugTree() {
    override fun createStackElementTag(element: StackTraceElement): String {
        return "(${element.fileName}:${element.lineNumber}) ${element.methodName}"
    }
}

/**
 * Release tree that sends errors to crash reporting.
 */
class CrashReportingTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return  // Skip VERBOSE and DEBUG in release
        
        // Log to crash reporting service
        when (priority) {
            Log.ERROR -> {
                CrashReporter.logError(tag, message, t)
            }
            Log.WARN -> {
                CrashReporter.logWarning(tag, message)
            }
            else -> {
                CrashReporter.logBreadcrumb(tag, message)
            }
        }
    }
}
```

### 1.2 Scoped Logging

```kotlin
/**
 * Tagged logger for feature-specific logging.
 * Use in ViewModels, UseCases, Repositories.
 */
interface ScopedLogger {
    fun v(message: String, vararg args: Any)
    fun d(message: String, vararg args: Any)
    fun i(message: String, vararg args: Any)
    fun w(message: String, vararg args: Any)
    fun e(message: String, throwable: Throwable? = null, vararg args: Any)
}

class TimberScopedLogger(private val tag: String) : ScopedLogger {
    override fun v(message: String, vararg args: Any) = Timber.tag(tag).v(message, *args)
    override fun d(message: String, vararg args: Any) = Timber.tag(tag).d(message, *args)
    override fun i(message: String, vararg args: Any) = Timber.tag(tag).i(message, *args)
    override fun w(message: String, vararg args: Any) = Timber.tag(tag).w(message, *args)
    override fun e(message: String, throwable: Throwable?, vararg args: Any) {
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message, *args)
        } else {
            Timber.tag(tag).e(message, *args)
        }
    }
}

// Usage in ViewModel
class ProfileViewModel(
    private val logger: ScopedLogger = TimberScopedLogger("ProfileVM"),
) : ViewModel() {
    
    fun loadProfile(userId: String) {
        logger.d("Loading profile for user: %s", userId)
        
        viewModelScope.launch {
            try {
                val profile = getUserUseCase(userId)
                logger.i("Profile loaded successfully")
            } catch (e: Exception) {
                logger.e("Failed to load profile", e)
            }
        }
    }
}
```

### 1.3 Structured Log Messages

```kotlin
/**
 * Structured log entry for consistent formatting.
 */
@Immutable
data class LogEntry(
    val event: String,
    val properties: Map<String, Any?> = emptyMap(),
    val userId: String? = null,
    val sessionId: String? = null,
) {
    fun toLogString(): String = buildString {
        append("[${event}]")
        if (properties.isNotEmpty()) {
            append(" ")
            append(properties.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
    }
}

// Extension for structured logging
fun Timber.Tree.log(priority: Int, entry: LogEntry) {
    log(priority, null, entry.toLogString(), null)
}

// Usage
Timber.i(LogEntry(
    event = "user_login",
    properties = mapOf(
        "method" to "email",
        "success" to true,
        "duration_ms" to 1234,
    ),
).toLogString())
// Output: [user_login] method=email, success=true, duration_ms=1234
```

---

## 2. Analytics Abstraction

### 2.1 Analytics Interface

```kotlin
/**
 * Abstract analytics interface for vendor independence.
 * Allows swapping Firebase, Amplitude, Mixpanel, etc.
 */
interface AnalyticsTracker {
    fun trackScreen(screenName: String, screenClass: String? = null)
    fun trackEvent(event: AnalyticsEvent)
    fun setUserProperty(name: String, value: String?)
    fun setUserId(userId: String?)
    fun resetUser()
}

/**
 * Base analytics event with common properties.
 */
@Immutable
data class AnalyticsEvent(
    val name: String,
    val properties: Map<String, Any?> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    // Builder pattern for fluent API
    class Builder(private val name: String) {
        private val properties = mutableMapOf<String, Any?>()
        
        fun property(key: String, value: Any?): Builder {
            properties[key] = value
            return this
        }
        
        fun build() = AnalyticsEvent(name, properties.toMap())
    }
    
    companion object {
        fun builder(name: String) = Builder(name)
    }
}

// Extension for building events
fun analyticsEvent(name: String, block: AnalyticsEvent.Builder.() -> Unit = {}): AnalyticsEvent {
    return AnalyticsEvent.Builder(name).apply(block).build()
}
```

### 2.2 Firebase Implementation

```kotlin
/**
 * Firebase Analytics implementation.
 */
class FirebaseAnalyticsTracker(
    private val firebaseAnalytics: FirebaseAnalytics,
    private val logger: ScopedLogger = TimberScopedLogger("Analytics"),
) : AnalyticsTracker {
    
    override fun trackScreen(screenName: String, screenClass: String?) {
        logger.d("Screen: %s", screenName)
        
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
            param(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            screenClass?.let { param(FirebaseAnalytics.Param.SCREEN_CLASS, it) }
        }
    }
    
    override fun trackEvent(event: AnalyticsEvent) {
        logger.d("Event: %s %s", event.name, event.properties)
        
        firebaseAnalytics.logEvent(event.name) {
            event.properties.forEach { (key, value) ->
                when (value) {
                    is String -> param(key, value)
                    is Long -> param(key, value)
                    is Double -> param(key, value)
                    is Int -> param(key, value.toLong())
                    is Boolean -> param(key, if (value) "true" else "false")
                    null -> { /* Skip null values */ }
                    else -> param(key, value.toString())
                }
            }
        }
    }
    
    override fun setUserProperty(name: String, value: String?) {
        logger.d("User property: %s = %s", name, value)
        firebaseAnalytics.setUserProperty(name, value)
    }
    
    override fun setUserId(userId: String?) {
        logger.d("User ID: %s", userId?.take(8) ?: "null")  // Log only prefix
        firebaseAnalytics.setUserId(userId)
    }
    
    override fun resetUser() {
        logger.d("Reset user")
        firebaseAnalytics.setUserId(null)
        firebaseAnalytics.resetAnalyticsData()
    }
}
```

### 2.3 Composite Tracker (Multiple Analytics)

```kotlin
/**
 * Sends events to multiple analytics services.
 */
class CompositeAnalyticsTracker(
    private val trackers: List<AnalyticsTracker>,
) : AnalyticsTracker {
    
    override fun trackScreen(screenName: String, screenClass: String?) {
        trackers.forEach { it.trackScreen(screenName, screenClass) }
    }
    
    override fun trackEvent(event: AnalyticsEvent) {
        trackers.forEach { it.trackEvent(event) }
    }
    
    override fun setUserProperty(name: String, value: String?) {
        trackers.forEach { it.setUserProperty(name, value) }
    }
    
    override fun setUserId(userId: String?) {
        trackers.forEach { it.setUserId(userId) }
    }
    
    override fun resetUser() {
        trackers.forEach { it.resetUser() }
    }
}

// DI setup
@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {
    
    @Provides
    @Singleton
    fun provideAnalyticsTracker(
        @ApplicationContext context: Context,
    ): AnalyticsTracker {
        val firebase = FirebaseAnalyticsTracker(FirebaseAnalytics.getInstance(context))
        
        return if (BuildConfig.DEBUG) {
            // In debug, also log to console
            CompositeAnalyticsTracker(listOf(
                firebase,
                DebugAnalyticsTracker(),  // Logs to Timber
            ))
        } else {
            firebase
        }
    }
}
```

### 2.4 Screen Tracking with Compose

```kotlin
/**
 * Composable for automatic screen tracking.
 */
@Composable
fun TrackScreen(
    screenName: String,
    screenClass: String? = null,
    analytics: AnalyticsTracker = LocalAnalytics.current,
) {
    LaunchedEffect(screenName) {
        analytics.trackScreen(screenName, screenClass)
    }
}

// CompositionLocal for analytics
val LocalAnalytics = staticCompositionLocalOf<AnalyticsTracker> {
    error("No AnalyticsTracker provided")
}

// Usage in Route
@Composable
fun ProfileRoute(
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    TrackScreen("Profile", "ProfileRoute")
    
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileScreen(state = state)
}
```

---

## 3. Crash Reporting

### 3.1 Crash Reporter Interface

```kotlin
/**
 * Abstract crash reporter for vendor independence.
 */
interface CrashReporter {
    fun initialize()
    fun setUserId(userId: String?)
    fun setCustomKey(key: String, value: String)
    fun logBreadcrumb(message: String)
    fun logWarning(tag: String?, message: String)
    fun logError(tag: String?, message: String, throwable: Throwable?)
    fun recordException(throwable: Throwable)
    
    companion object : CrashReporter {
        private var instance: CrashReporter = NoOpCrashReporter()
        
        fun setInstance(reporter: CrashReporter) {
            instance = reporter
        }
        
        override fun initialize() = instance.initialize()
        override fun setUserId(userId: String?) = instance.setUserId(userId)
        override fun setCustomKey(key: String, value: String) = instance.setCustomKey(key, value)
        override fun logBreadcrumb(message: String) = instance.logBreadcrumb(message)
        override fun logWarning(tag: String?, message: String) = instance.logWarning(tag, message)
        override fun logError(tag: String?, message: String, throwable: Throwable?) = 
            instance.logError(tag, message, throwable)
        override fun recordException(throwable: Throwable) = instance.recordException(throwable)
    }
}

class NoOpCrashReporter : CrashReporter {
    override fun initialize() {}
    override fun setUserId(userId: String?) {}
    override fun setCustomKey(key: String, value: String) {}
    override fun logBreadcrumb(message: String) {}
    override fun logWarning(tag: String?, message: String) {}
    override fun logError(tag: String?, message: String, throwable: Throwable?) {}
    override fun recordException(throwable: Throwable) {}
}
```

### 3.2 Crashlytics Implementation

```kotlin
/**
 * Firebase Crashlytics implementation.
 */
class CrashlyticsReporter : CrashReporter {
    private val crashlytics: FirebaseCrashlytics by lazy {
        FirebaseCrashlytics.getInstance()
    }
    
    override fun initialize() {
        // Enable collection based on user consent
        crashlytics.setCrashlyticsCollectionEnabled(true)
    }
    
    override fun setUserId(userId: String?) {
        crashlytics.setUserId(userId ?: "")
    }
    
    override fun setCustomKey(key: String, value: String) {
        crashlytics.setCustomKey(key, value)
    }
    
    override fun logBreadcrumb(message: String) {
        crashlytics.log(message)
    }
    
    override fun logWarning(tag: String?, message: String) {
        val formattedMessage = if (tag != null) "[$tag] $message" else message
        crashlytics.log("WARN: $formattedMessage")
    }
    
    override fun logError(tag: String?, message: String, throwable: Throwable?) {
        val formattedMessage = if (tag != null) "[$tag] $message" else message
        crashlytics.log("ERROR: $formattedMessage")
        
        if (throwable != null) {
            crashlytics.recordException(throwable)
        }
    }
    
    override fun recordException(throwable: Throwable) {
        crashlytics.recordException(throwable)
    }
}
```

### 3.3 Breadcrumb Tracking

```kotlin
/**
 * Automatic breadcrumb tracking for debugging crashes.
 */
object BreadcrumbTracker {
    private const val MAX_BREADCRUMBS = 50
    private val breadcrumbs = ArrayDeque<Breadcrumb>(MAX_BREADCRUMBS)
    
    @Immutable
    data class Breadcrumb(
        val timestamp: Long,
        val category: String,
        val message: String,
        val data: Map<String, Any?> = emptyMap(),
    )
    
    fun track(category: String, message: String, data: Map<String, Any?> = emptyMap()) {
        val breadcrumb = Breadcrumb(
            timestamp = System.currentTimeMillis(),
            category = category,
            message = message,
            data = data,
        )
        
        synchronized(breadcrumbs) {
            if (breadcrumbs.size >= MAX_BREADCRUMBS) {
                breadcrumbs.removeFirst()
            }
            breadcrumbs.addLast(breadcrumb)
        }
        
        // Also log to crash reporter
        CrashReporter.logBreadcrumb("[$category] $message")
    }
    
    fun trackNavigation(from: String, to: String) {
        track("navigation", "$from → $to")
    }
    
    fun trackUserAction(action: String, target: String? = null) {
        track("user_action", action, mapOf("target" to target))
    }
    
    fun trackNetworkRequest(url: String, method: String, statusCode: Int?) {
        track("network", "$method $url", mapOf("status" to statusCode))
    }
    
    fun getRecentBreadcrumbs(): List<Breadcrumb> {
        synchronized(breadcrumbs) {
            return breadcrumbs.toList()
        }
    }
}
```

---

## 4. Performance Monitoring

### 4.1 Performance Tracker Interface

```kotlin
/**
 * Performance monitoring abstraction.
 */
interface PerformanceTracker {
    fun startTrace(name: String): Trace
    fun recordMetric(name: String, value: Long)
    fun recordScreenLoadTime(screenName: String, durationMs: Long)
    fun recordNetworkRequest(url: String, method: String, responseCode: Int, durationMs: Long)
}

interface Trace {
    fun putAttribute(name: String, value: String)
    fun incrementMetric(name: String, incrementBy: Long = 1)
    fun stop()
}

/**
 * Firebase Performance implementation.
 */
class FirebasePerformanceTracker : PerformanceTracker {
    private val performance = FirebasePerformance.getInstance()
    
    override fun startTrace(name: String): Trace {
        return FirebaseTraceWrapper(performance.newTrace(name).also { it.start() })
    }
    
    override fun recordMetric(name: String, value: Long) {
        // Custom metric via trace
        performance.newTrace(name).apply {
            putMetric("value", value)
            start()
            stop()
        }
    }
    
    override fun recordScreenLoadTime(screenName: String, durationMs: Long) {
        performance.newTrace("screen_load_$screenName").apply {
            putMetric("duration_ms", durationMs)
            start()
            stop()
        }
    }
    
    override fun recordNetworkRequest(url: String, method: String, responseCode: Int, durationMs: Long) {
        // Firebase auto-tracks network requests, but manual tracking is also possible
        Timber.d("Network: $method $url -> $responseCode (${durationMs}ms)")
    }
}

class FirebaseTraceWrapper(private val trace: com.google.firebase.perf.metrics.Trace) : Trace {
    override fun putAttribute(name: String, value: String) {
        trace.putAttribute(name, value)
    }
    
    override fun incrementMetric(name: String, incrementBy: Long) {
        trace.incrementMetric(name, incrementBy)
    }
    
    override fun stop() {
        trace.stop()
    }
}
```

### 4.2 Screen Load Time Tracking

```kotlin
/**
 * Track screen load time in Compose.
 */
@Composable
fun TrackScreenLoadTime(
    screenName: String,
    isLoaded: Boolean,
    performance: PerformanceTracker = LocalPerformance.current,
) {
    val startTime = remember { System.currentTimeMillis() }
    var tracked by remember { mutableStateOf(false) }
    
    LaunchedEffect(isLoaded) {
        if (isLoaded && !tracked) {
            val duration = System.currentTimeMillis() - startTime
            performance.recordScreenLoadTime(screenName, duration)
            Timber.d("Screen $screenName loaded in ${duration}ms")
            tracked = true
        }
    }
}

// Usage
@Composable
fun ProfileScreen(state: ProfileUiState, modifier: Modifier = Modifier) {
    TrackScreenLoadTime(
        screenName = "Profile",
        isLoaded = !state.isLoading && state.user != null,
    )
    
    // Screen content...
}
```

### 4.3 UseCase Performance Tracking

```kotlin
/**
 * Decorator for tracking UseCase execution time.
 */
class TrackedUseCase<I, O>(
    private val useCase: suspend (I) -> Result<O>,
    private val name: String,
    private val performance: PerformanceTracker,
    private val logger: ScopedLogger,
) {
    suspend operator fun invoke(input: I): Result<O> {
        val trace = performance.startTrace("usecase_$name")
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = useCase(input)
            val duration = System.currentTimeMillis() - startTime
            
            trace.putAttribute("success", result.isSuccess.toString())
            logger.d("$name completed in ${duration}ms, success=${result.isSuccess}")
            
            result
        } catch (e: CancellationException) {
            trace.putAttribute("cancelled", "true")
            throw e
        } catch (e: Exception) {
            trace.putAttribute("error", e.javaClass.simpleName)
            logger.e("$name failed", e)
            Result.failure(e)
        } finally {
            trace.stop()
        }
    }
}
```

---

## 5. Privacy & Compliance

### 5.1 Data Redaction

```kotlin
/**
 * Redact sensitive data before logging.
 */
object DataRedactor {
    
    private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val PHONE_REGEX = Regex("\\+?[0-9]{10,15}")
    private val CREDIT_CARD_REGEX = Regex("\\b[0-9]{13,19}\\b")
    
    fun redact(message: String): String {
        var result = message
        
        // Redact emails
        result = EMAIL_REGEX.replace(result) { "[EMAIL]" }
        
        // Redact phone numbers
        result = PHONE_REGEX.replace(result) { "[PHONE]" }
        
        // Redact credit card numbers
        result = CREDIT_CARD_REGEX.replace(result) { "[CARD]" }
        
        return result
    }
    
    fun redactMap(data: Map<String, Any?>): Map<String, Any?> {
        val sensitiveKeys = setOf(
            "password", "token", "secret", "api_key", "apikey",
            "credit_card", "ssn", "social_security",
        )
        
        return data.mapValues { (key, value) ->
            when {
                sensitiveKeys.any { key.lowercase().contains(it) } -> "[REDACTED]"
                value is String -> redact(value)
                else -> value
            }
        }
    }
}

/**
 * Privacy-aware Timber tree.
 */
class PrivacyAwareTree(private val delegate: Timber.Tree) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val redactedMessage = DataRedactor.redact(message)
        delegate.log(priority, tag, redactedMessage, t)
    }
}
```

### 5.2 Consent Management

```kotlin
/**
 * Analytics consent manager.
 */
interface ConsentManager {
    val hasAnalyticsConsent: StateFlow<Boolean>
    val hasCrashReportingConsent: StateFlow<Boolean>
    
    suspend fun setAnalyticsConsent(granted: Boolean)
    suspend fun setCrashReportingConsent(granted: Boolean)
}

class ConsentManagerImpl(
    private val preferences: DataStore<Preferences>,
) : ConsentManager {
    
    private val analyticsKey = booleanPreferencesKey("analytics_consent")
    private val crashKey = booleanPreferencesKey("crash_consent")
    
    override val hasAnalyticsConsent: StateFlow<Boolean> = preferences.data
        .map { it[analyticsKey] ?: false }
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Eagerly, false)
    
    override val hasCrashReportingConsent: StateFlow<Boolean> = preferences.data
        .map { it[crashKey] ?: false }
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Eagerly, false)
    
    override suspend fun setAnalyticsConsent(granted: Boolean) {
        preferences.edit { it[analyticsKey] = granted }
        
        // Apply immediately
        if (!granted) {
            // Disable tracking
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(false)
        }
    }
    
    override suspend fun setCrashReportingConsent(granted: Boolean) {
        preferences.edit { it[crashKey] = granted }
        
        // Apply immediately
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(granted)
    }
}

/**
 * Consent-aware analytics tracker.
 */
class ConsentAwareAnalyticsTracker(
    private val delegate: AnalyticsTracker,
    private val consentManager: ConsentManager,
) : AnalyticsTracker {
    
    override fun trackScreen(screenName: String, screenClass: String?) {
        if (consentManager.hasAnalyticsConsent.value) {
            delegate.trackScreen(screenName, screenClass)
        }
    }
    
    override fun trackEvent(event: AnalyticsEvent) {
        if (consentManager.hasAnalyticsConsent.value) {
            delegate.trackEvent(event)
        }
    }
    
    override fun setUserProperty(name: String, value: String?) {
        if (consentManager.hasAnalyticsConsent.value) {
            delegate.setUserProperty(name, value)
        }
    }
    
    override fun setUserId(userId: String?) {
        if (consentManager.hasAnalyticsConsent.value) {
            delegate.setUserId(userId)
        }
    }
    
    override fun resetUser() = delegate.resetUser()
}
```

---

## 6. Common Event Definitions

### 6.1 Standard Events

```kotlin
/**
 * Standard analytics events for consistency.
 */
object AnalyticsEvents {
    
    // Authentication
    fun login(method: String, success: Boolean) = analyticsEvent("login") {
        property("method", method)
        property("success", success)
    }
    
    fun signUp(method: String, success: Boolean) = analyticsEvent("sign_up") {
        property("method", method)
        property("success", success)
    }
    
    fun logout() = analyticsEvent("logout")
    
    // Content
    fun viewContent(contentId: String, contentType: String) = analyticsEvent("view_content") {
        property("content_id", contentId)
        property("content_type", contentType)
    }
    
    fun search(query: String, resultsCount: Int) = analyticsEvent("search") {
        property("search_term", query)
        property("results_count", resultsCount)
    }
    
    // E-commerce
    fun addToCart(itemId: String, itemName: String, price: Double, quantity: Int) = 
        analyticsEvent("add_to_cart") {
            property("item_id", itemId)
            property("item_name", itemName)
            property("price", price)
            property("quantity", quantity)
        }
    
    fun purchase(transactionId: String, value: Double, currency: String, items: Int) = 
        analyticsEvent("purchase") {
            property("transaction_id", transactionId)
            property("value", value)
            property("currency", currency)
            property("items", items)
        }
    
    // Errors
    fun error(errorType: String, errorMessage: String, screen: String? = null) = 
        analyticsEvent("app_error") {
            property("error_type", errorType)
            property("error_message", errorMessage)
            screen?.let { property("screen", it) }
        }
    
    // Feature usage
    fun featureUsed(featureName: String, action: String) = analyticsEvent("feature_used") {
        property("feature", featureName)
        property("action", action)
    }
}

// Usage
analytics.trackEvent(AnalyticsEvents.login("email", success = true))
analytics.trackEvent(AnalyticsEvents.purchase("txn_123", 99.99, "USD", items = 3))
```

---

## 7. Integration Examples

### 7.1 ViewModel with Logging & Analytics

```kotlin
@HiltViewModel
class ProductViewModel @Inject constructor(
    private val getProductsUseCase: GetProductsUseCase,
    private val analytics: AnalyticsTracker,
    private val logger: ScopedLogger,
) : ViewModel() {
    
    private val _state = MutableStateFlow(ProductUiState())
    val state: StateFlow<ProductUiState> = _state.asStateFlow()
    
    fun loadProducts(category: String) {
        logger.d("Loading products for category: %s", category)
        
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            getProductsUseCase(category)
                .onSuccess { products ->
                    logger.i("Loaded %d products", products.size)
                    analytics.trackEvent(AnalyticsEvents.viewContent(category, "product_list"))
                    _state.update { it.copy(isLoading = false, products = products) }
                }
                .onFailure { error ->
                    logger.e("Failed to load products", error)
                    analytics.trackEvent(AnalyticsEvents.error("load_products", error.message ?: "Unknown"))
                    CrashReporter.recordException(error)
                    _state.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }
    
    fun onProductClick(product: Product) {
        logger.d("Product clicked: %s", product.id)
        analytics.trackEvent(AnalyticsEvents.viewContent(product.id, "product"))
        BreadcrumbTracker.trackUserAction("product_click", product.id)
    }
}
```

---

## Verification Checklist

- [ ] Timber initialized with appropriate trees for debug/release
- [ ] Analytics abstraction implemented (not vendor-locked)
- [ ] Crash reporting configured with breadcrumbs
- [ ] Sensitive data redacted from logs
- [ ] User consent managed before tracking
- [ ] Screen tracking automated in Compose
- [ ] Performance traces for critical paths
- [ ] Standard events defined for consistency
- [ ] Privacy compliance verified (GDPR, CCPA)

---

## Related Resources

- [10-error-handling.md](./10-error-handling.md) - Error patterns
- [12-security.md](./12-security.md) - Security guidelines
- [23-memory-performance.md](./23-memory-performance.md) - Performance
- [33-agent-feedback-loop.md](./33-agent-feedback-loop.md) - Agent metrics

---

**Created**: January 2026  
**Maintained By**: AI Agent / TrongLB  
**Version**: 1.0.0
