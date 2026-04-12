package chimahon.anki

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named mining configuration that bundles together:
 * - AnkiDroid deck/model/field-map/tag settings
 * - The ordered list of dictionaries to use
 * - Which of those dictionaries are enabled for lookup
 *
 * The list [dictionaryOrder] defines priority (index 0 = highest).
 * [enabledDictionaries] is a subset of [dictionaryOrder]; when empty every
 * dictionary in [dictionaryOrder] is treated as enabled (backwards-compatible
 * default for the "Default" profile created during migration).
 */
data class AnkiProfile(
    val id: String,
    val name: String,
    // Anki settings
    val ankiEnabled: Boolean = false,
    val ankiDeck: String = "",
    val ankiModel: String = "",
    val ankiFieldMap: String = "{}",
    val ankiTags: String = "chimahon",
    val ankiDupCheck: Boolean = true,
    val ankiDupScope: String = "deck",
    val ankiDupAction: String = "prevent",
    val ankiCropMode: String = "full",
    // Dictionary configuration
    val dictionaryOrder: List<String> = emptyList(),
    val enabledDictionaries: Set<String> = emptySet(), // empty = all enabled
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ankiEnabled", ankiEnabled)
        put("ankiDeck", ankiDeck)
        put("ankiModel", ankiModel)
        put("ankiFieldMap", ankiFieldMap)
        put("ankiTags", ankiTags)
        put("ankiDupCheck", ankiDupCheck)
        put("ankiDupScope", ankiDupScope)
        put("ankiDupAction", ankiDupAction)
        put("ankiCropMode", ankiCropMode)
        put("dictionaryOrder", JSONArray(dictionaryOrder))
        put("enabledDictionaries", JSONArray(enabledDictionaries.toList()))
    }

    companion object {

        fun fromJson(json: JSONObject): AnkiProfile = AnkiProfile(
            id = json.getString("id"),
            name = json.getString("name"),
            ankiEnabled = json.optBoolean("ankiEnabled", false),
            ankiDeck = json.optString("ankiDeck", ""),
            ankiModel = json.optString("ankiModel", ""),
            ankiFieldMap = json.optString("ankiFieldMap", "{}"),
            ankiTags = json.optString("ankiTags", "chimahon"),
            ankiDupCheck = json.optBoolean("ankiDupCheck", true),
            ankiDupScope = json.optString("ankiDupScope", "deck"),
            ankiDupAction = json.optString("ankiDupAction", "prevent"),
            ankiCropMode = json.optString("ankiCropMode", "full"),
            dictionaryOrder = json.optJSONArray("dictionaryOrder")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            enabledDictionaries = json.optJSONArray("enabledDictionaries")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                ?: emptySet(),
        )

        /**
         * Migrate legacy flat-key values (passed in from the UI/prefs layer,
         * so this class itself stays free of Android/Prefs dependencies)
         * into a brand-new Default profile.
         */
        fun createDefault(
            defaultName: String = "Default",
            ankiEnabled: Boolean = false,
            ankiDeck: String = "",
            ankiModel: String = "",
            ankiFieldMap: String = "{}",
            ankiTags: String = "chimahon",
            ankiDupCheck: Boolean = true,
            ankiDupScope: String = "deck",
            ankiDupAction: String = "prevent",
            ankiCropMode: String = "full",
            dictionaryOrder: List<String> = emptyList(),
        ): AnkiProfile = AnkiProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = defaultName,
            ankiEnabled = ankiEnabled,
            ankiDeck = ankiDeck,
            ankiModel = ankiModel,
            ankiFieldMap = ankiFieldMap,
            ankiTags = ankiTags,
            ankiDupCheck = ankiDupCheck,
            ankiDupScope = ankiDupScope,
            ankiDupAction = ankiDupAction,
            ankiCropMode = ankiCropMode,
            dictionaryOrder = dictionaryOrder,
            enabledDictionaries = emptySet(),
        )
    }
}
