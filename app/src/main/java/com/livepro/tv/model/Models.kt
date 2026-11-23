package com.livepro.tv.model

data class ActivationStatus(
    val isActive: Boolean = false,
    val deviceId: String = "",
    val expiryDate: String = "غير مفعل",
    val serverType: String = "xtream", // "xtream" or "m3u"
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val message: String = ""
)

data class CategoryItem(
    val id: String,
    val name: String
)

data class ChannelItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val categoryId: String,
    val logoUrl: String = "",
    val number: Int = 0
)

enum class AppScreen {
    ACTIVATION,
    HOME,
    LIVE_TV,
    PLAYER,
    SETTINGS
}
