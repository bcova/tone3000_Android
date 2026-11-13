package com.bcova.namplayer.t3k

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Data shapes from the TONE3000 API v1 (https://www.tone3000.com/api#types).
// Only the fields the app uses are listed; unknown fields are ignored.

@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class T3kUser(
    val id: Long,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    val url: String? = null,
) {
    /** display_name is only set for verified creators; fall back to username. */
    val shownName: String get() = displayName ?: username
}

@Serializable
data class Tone(
    val id: Long,
    val title: String,
    val description: String? = null,
    val user: T3kUser,
    val gear: String? = null,
    val format: String,
    val images: List<String>? = null,
    val sizes: List<String> = emptyList(),
    @SerialName("models_count") val modelsCount: Int = 0,
    @SerialName("a2_models_count") val a2ModelsCount: Int = 0,
    @SerialName("downloads_count") val downloadsCount: Int = 0,
    @SerialName("favorites_count") val favoritesCount: Int = 0,
    val license: String? = null,
    val url: String? = null,
) {
    val imageUrl: String? get() = images?.firstOrNull()

    /** The app can play NAM captures and IRs; other formats (AIDA-X, Proteus…) aren't supported. */
    val isSupported: Boolean get() = format == FORMAT_NAM || format == FORMAT_IR

    val formatLabel: String
        get() = when (format) {
            FORMAT_NAM -> "NAM"
            FORMAT_IR -> "IR"
            "aida-x" -> "AIDA-X"
            "aa-snapshot" -> "AA Snapshot"
            "proteus" -> "Proteus"
            else -> format.uppercase()
        }

    val gearLabel: String
        get() = when (gear) {
            "amp" -> "Amp"
            "amp-cab", "full-rig" -> "Amp + Cab"
            "pedal" -> "Pedal"
            "outboard" -> "Outboard"
            "cab" -> if (format == FORMAT_IR) "Cabinet" else "Cab"
            "space" -> "Space"
            "experimental" -> "Experimental"
            null -> if (format == FORMAT_IR) "Cabinet" else ""
            else -> gear.replaceFirstChar { it.uppercase() }
        }

    companion object {
        const val FORMAT_NAM = "nam"
        const val FORMAT_IR = "ir"
    }
}

@Serializable
data class ToneModel(
    val id: Long,
    val name: String,
    @SerialName("model_url") val modelUrl: String,
    val size: String? = null,
    @SerialName("tone_id") val toneId: Long,
    @SerialName("architecture_version") val architecture: String? = null,
) {
    val sizeLabel: String
        get() = listOfNotNull(
            size?.replaceFirstChar { it.uppercase() },
            if (architecture == "2") "A2" else null,
        ).joinToString(" · ")
}

@Serializable
data class Paginated<T>(
    val data: List<T>,
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
    val total: Int = 0,
)

@Serializable
data class DataList<T>(val data: List<T>)
