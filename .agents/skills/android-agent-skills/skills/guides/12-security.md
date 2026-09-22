---
name: Security
description: Security best practices for data protection and network safety.
compliance_level: MANDATORY
tags: [security, encryption, network-security, proguard]
version: 2.2.0
---

# Security

## Context
Securing user data and app integrity is non-negotiable. This guide covers storage encryption, network security, and code protection.

**Related Guides:**
- [24-validation-rules.md](./24-validation-rules.md) - Input validation
- [10-error-handling.md](./10-error-handling.md) - Secure error handling

---

## 🎯 AI Quick Reference

```
STORAGE:
• EncryptedSharedPreferences for sensitive data
• Keystore for encryption keys
• NEVER hardcode secrets

NETWORK:
• HTTPS only (no cleartext)
• Certificate pinning for critical APIs
• NetworkSecurityConfig

CODE:
• R8/ProGuard enabled
• Strip logs in release
• Obfuscate class names
```

---

## 1. Secure Storage

### ✅ DO: EncryptedSharedPreferences
```kotlin
// ════════════════════════════════════════════════════════════════
// Secure token storage
// ════════════════════════════════════════════════════════════════
class SecureTokenStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    
    fun saveToken(token: String) {
        securePrefs.edit().putString(KEY_TOKEN, token).apply()
    }
    
    fun getToken(): String? = securePrefs.getString(KEY_TOKEN, null)
    
    fun clearToken() {
        securePrefs.edit().remove(KEY_TOKEN).apply()
    }
    
    companion object {
        private const val KEY_TOKEN = "auth_token"
    }
}

// ════════════════════════════════════════════════════════════════
// Keystore for encryption keys
// ════════════════════════════════════════════════════════════════
class KeystoreManager {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }
    
    fun getOrCreateSecretKey(alias: String): SecretKey {
        return if (keyStore.containsAlias(alias)) {
            (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            createSecretKey(alias)
        }
    }
    
    private fun createSecretKey(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        
        val keySpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
}
```

### ❌ DON'T: Insecure Storage
```kotlin
// ❌ BAD: Hardcoded secrets
class BadAuth {
    val apiKey = "sk-12345-secret-key" // ❌ Visible in APK!
    val clientSecret = "client-secret-123" // ❌ Exposed
}

// ❌ BAD: Plain SharedPreferences for tokens
class BadStorage(context: Context) {
    private val prefs = context.getSharedPreferences("prefs", MODE_PRIVATE)
    
    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply() // ❌ Not encrypted
    }
}

// ❌ BAD: Logging sensitive data
fun login(email: String, password: String) {
    Timber.d("Login attempt: email=$email, password=$password") // ❌ NEVER!
}
```

---

## 2. Network Security

### ✅ DO: Network Security Config
```xml
<!-- res/xml/network_security_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Base config: No cleartext traffic -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    
    <!-- Certificate pinning for production API -->
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">api.myapp.com</domain>
        <pin-set expiration="2025-12-31">
            <pin digest="SHA-256">AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</pin>
            <pin digest="SHA-256">BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=</pin>
        </pin-set>
    </domain-config>
    
    <!-- Debug config for local development -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>

<!-- AndroidManifest.xml -->
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ... />
```

### ✅ DO: OkHttp Certificate Pinning
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val certificatePinner = CertificatePinner.Builder()
            .add(
                "api.myapp.com",
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            )
            .build()
        
        return OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())
            .addInterceptor(getLoggingInterceptor())
            .build()
    }
    
    private fun getLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE // ✅ No logs in release
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Auth interceptor - Add token to requests
// ════════════════════════════════════════════════════════════════
class AuthInterceptor @Inject constructor(
    private val tokenStorage: SecureTokenStorage,
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        val token = tokenStorage.getToken()
        if (token != null) {
            val authenticatedRequest = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            return chain.proceed(authenticatedRequest)
        }
        
        return chain.proceed(request)
    }
}
```

---

## 3. Code Obfuscation

### ✅ DO: ProGuard/R8 Configuration
```proguard
# proguard-rules.pro

# ════════════════════════════════════════════════════════════════
# Remove logging in release
# ════════════════════════════════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
}

# ════════════════════════════════════════════════════════════════
# Keep model classes (for serialization)
# ════════════════════════════════════════════════════════════════
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

-keep @kotlinx.serialization.Serializable class * {
    static **$Companion Companion;
}

# ════════════════════════════════════════════════════════════════
# Keep navigation routes
# ════════════════════════════════════════════════════════════════
-keep class * extends com.example.navigation.Route { *; }
```

### ✅ DO: Build Configuration
```kotlin
// build.gradle.kts (app)
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            
            // Don't include debug symbols in APK
            isDebuggable = false
        }
    }
}
```

---

## 4. Input Validation

### ✅ DO: Sanitize User Input
```kotlin
object InputSanitizer {
    
    // Prevent SQL injection in search
    fun sanitizeSearchQuery(query: String): String {
        return query
            .replace(Regex("[';\"\\\\]"), "") // Remove dangerous chars
            .trim()
            .take(100) // Limit length
    }
    
    // Sanitize file names
    fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .take(255)
    }
    
    // Validate URLs before loading
    fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            uri.scheme in listOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                !uri.host!!.contains("localhost") // Block local URLs
        } catch (e: Exception) {
            false
        }
    }
}
```

---

## 5. Verification Checklist

### Storage
- [ ] `EncryptedSharedPreferences` for sensitive data
- [ ] No hardcoded secrets in code
- [ ] Secrets in `local.properties` (gitignored) or CI secrets
- [ ] Keystore for encryption keys

### Network
- [ ] `cleartextTrafficPermitted="false"`
- [ ] Certificate pinning for production APIs
- [ ] HTTPS only
- [ ] No logging of auth tokens/passwords

### Code
- [ ] R8/ProGuard enabled for release
- [ ] Logs stripped in release
- [ ] `isDebuggable = false` for release
- [ ] Model classes kept for serialization

### Input
- [ ] User input sanitized
- [ ] URLs validated before loading
- [ ] File names sanitized
