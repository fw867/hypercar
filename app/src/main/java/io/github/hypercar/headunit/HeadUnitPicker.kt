package io.github.hypercar.headunit

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

internal object HeadUnitPicker {
    private val specialGroups = linkedMapOf(
        "motorcycle" to "摩托车",
        "general" to "通用",
        "receiver" to "桌面接收端",
        "uncategorized" to "未分类",
    )
    // Volkswagen ID.7 and BMW ID7 are different models.
    private val punctuation = Regex("[^\\p{L}\\p{N}.]+")
    private val wordSeparator = Regex("\\s+")

    data class Group(val id: String, val label: String, val profiles: List<HeadUnitProfile>)

    fun groups(profiles: List<HeadUnitProfile>): List<Group> =
        profiles.groupBy { profile ->
            val listing = profile.listing
            if (listing.category != HeadUnitCategory.CAR) listing.category.key
            else if (listing.brandId.isNotBlank() && listing.brandName.isNotBlank()) "brand:${listing.brandId}"
            else "uncategorized"
        }.map { (id, members) ->
            Group(id, specialGroups[id] ?: members.first().listing.brandName, members)
        }.sortedBy { specialGroups.keys.indexOf(it.id) }

    fun search(profiles: List<HeadUnitProfile>, query: String): List<HeadUnitProfile> {
        val words = query.trim().split(wordSeparator).map(::normalize).filter { it.isNotEmpty() }
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

    private fun normalize(text: String): String =
        punctuation.replace(text.lowercase(Locale.ROOT), "")
}
