package com.example.core.registry

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object ServiceRegistry {
    private val services = ConcurrentHashMap<ServiceType, WeakReference<Any>>()

    @Synchronized
    fun register(type: ServiceType, service: Any) {
        services[type] = WeakReference(service)
    }

    @Synchronized
    fun unregister(type: ServiceType) {
        services.remove(type)
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(type: ServiceType): T? {
        val ref = services[type] ?: return null
        val service = ref.get()
        if (service == null) {
            services.remove(type)
            return null
        }
        return service as? T
    }
}
