package io.github.hypercar.headunit

import io.github.hypercar.settings.ProjectionTarget
import java.util.Locale

enum class HeadUnitCategory(val key: String) {
    CAR("car"),
    MOTORCYCLE("motorcycle"),
    GENERAL("general"),
    RECEIVER("receiver"),
}

/** Search and grouping metadata. Connection decisions use HeadUnitProfile's existing policy fields. */
data class HeadUnitListing(
    val category: HeadUnitCategory = HeadUnitCategory.CAR,
    val brandId: String = "",
    val brandName: String = "",
    val vehicleModels: List<String> = emptyList(),
    val platformName: String = "",
    val aliases: List<String> = emptyList(),
)

/** These entries open their own settings and never become a CarPlay protocol profile. */
internal enum class ElectricVehicleProfile(
    val id: String, val label: String, val model: String, val target: ProjectionTarget,
) {
    Yadea("yadea_instrument", "雅迪仪表", "Wi-Fi 仪表投屏", ProjectionTarget.Yadea),
}

internal object HeadUnitPicker {
    const val ELECTRIC_VEHICLE_GROUP = "electric_vehicle"
    const val ELECTRIC_VEHICLE_LABEL = "电动车"
    private val specialGroups = linkedMapOf(
        "motorcycle" to "摩托车",
        ELECTRIC_VEHICLE_GROUP to ELECTRIC_VEHICLE_LABEL,
        "general" to "通用",
        "receiver" to "桌面接收端",
        "uncategorized" to "未分类",
    )
    // Volkswagen ID.7 and BMW ID7 are different models.
    private val punctuation = Regex("[^\\p{L}\\p{N}.]+")
    private val wordSeparator = Regex("\\s+")

    data class Group(
        val id: String,
        val label: String,
        val profiles: List<HeadUnitProfile> = emptyList(),
        val electricVehicles: List<ElectricVehicleProfile> = emptyList(),
    )

    fun groups(profiles: List<HeadUnitProfile>): List<Group> =
        (profiles.groupBy { profile ->
            val listing = profile.listing
            if (listing.category != HeadUnitCategory.CAR) listing.category.key
            else if (listing.brandId.isNotBlank() && listing.brandName.isNotBlank()) "brand:${listing.brandId}"
            else "uncategorized"
        }.map { (id, members) ->
            Group(id, specialGroups[id] ?: members.first().listing.brandName, members)
        } + Group(ELECTRIC_VEHICLE_GROUP, ELECTRIC_VEHICLE_LABEL, electricVehicles = electricVehicles()))
            .sortedBy { specialGroups.keys.indexOf(it.id) }

    fun search(profiles: List<HeadUnitProfile>, query: String): List<HeadUnitProfile> {
        val words = searchWords(query)
        if (words.isEmpty()) return profiles
        return profiles.filter { profile ->
            val listing = profile.listing
            val fields = listOf(profile.id, profile.label, profile.model, listing.brandId,
                listing.brandName, listing.platformName, specialGroups[listing.category.key].orEmpty()) +
                listing.vehicleModels + listing.aliases
            val searchable = fields.map(::normalize)
            words.all { word -> searchable.any { word in it } }
        }
    }

    fun electricVehicles(query: String = ""): List<ElectricVehicleProfile> {
        val words = searchWords(query)
        return ElectricVehicleProfile.entries.filter { profile ->
            val fields = listOf(profile.id, profile.label, profile.model, ELECTRIC_VEHICLE_LABEL).map(::normalize)
            words.all { word -> fields.any { word in it } }
        }
    }

    private fun searchWords(query: String) =
        query.trim().split(wordSeparator).map(::normalize).filter { it.isNotEmpty() }

    private fun normalize(text: String): String =
        punctuation.replace(text.lowercase(Locale.ROOT), "")
}
