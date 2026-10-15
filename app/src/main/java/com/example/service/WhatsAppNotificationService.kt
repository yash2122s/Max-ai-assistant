package com.example.service

open class WhatsAppNotificationService : com.example.features.whatsapp.WhatsAppNotificationService() {
    companion object {
        fun getInstance(): com.example.features.whatsapp.WhatsAppNotificationService? =
            com.example.features.whatsapp.WhatsAppNotificationService.getInstance()
    }
}
