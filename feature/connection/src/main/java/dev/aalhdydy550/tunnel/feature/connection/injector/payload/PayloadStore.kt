package dev.aalhdydy550.tunnel.feature.connection.injector.payload

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray

/**
 * Which payload templates the user saved, and which template each
 * network uses.
 *
 * Built-in templates ([PayloadTemplate.BUILT_INS]) are code, not
 * storage — only user-written custom templates are persisted, plus
 * the selected template id per network (null = the built-in
 * default, `connect-host`).
 *
 * **Storage choice (documented deviation from PLAN-AR.md §3):** the
 * plan says DataStore, but `:feature:connection` has no DataStore
 * dependency and adding one would mean touching
 * `feature/connection/build.gradle.kts` — outside this item's file
 * scope. This store therefore follows the injector package's own
 * precedent ([dev.aalhdydy550.tunnel.feature.connection.injector.SharedPreferencesVerifiedNetworkStore]):
 * one private SharedPreferences file holding small JSON blobs, built
 * with the manual `kotlinx.serialization.json` builders (no
 * `@Serializable` plugin, same style as `InjectorConfig` /
 * `ProfileBackup`). Behaviour is identical for these blobs; a
 * DataStore migration, if ever wanted, is a mechanical swap behind
 * this same interface.
 */
interface PayloadStore {

    /** User-saved custom templates (built-ins are not stored). */
    fun customTemplates(): List<PayloadTemplate>

    /** Saves (or replaces, by id) one custom template. */
    fun saveCustomTemplate(template: PayloadTemplate)

    /** Deletes a custom template by id (no-op when absent). */
    fun deleteCustomTemplate(id: String)

    /** Selected template id for a network, or null = built-in default. */
    fun selectedTemplateId(networkId: Long): String?

    /** Selects a template for a network (null clears to the default). */
    fun setSelectedTemplateId(networkId: Long, templateId: String?)
}

/** [PayloadStore] backed by its own SharedPreferences file. */
class SharedPreferencesPayloadStore(
    context: Context,
) : PayloadStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun customTemplates(): List<PayloadTemplate> {
        val raw = prefs.getString(CUSTOM_TEMPLATES_KEY, null) ?: return emptyList()
        return runCatching {
            PayloadTemplate.listFromJsonArray(json.parseToJsonElement(raw).jsonArray)
        }.getOrDefault(emptyList())
    }

    override fun saveCustomTemplate(template: PayloadTemplate) {
        require(template.isCustom) { "Only custom templates are stored" }
        val updated = customTemplates()
            .filter { it.id != template.id }
            .plus(template)
            .takeLast(MAX_CUSTOM_TEMPLATES)
        prefs.edit()
            .putString(CUSTOM_TEMPLATES_KEY, templatesJson(updated))
            .apply()
    }

    override fun deleteCustomTemplate(id: String) {
        val updated = customTemplates().filter { it.id != id }
        prefs.edit()
            .putString(CUSTOM_TEMPLATES_KEY, templatesJson(updated))
            .apply()
    }

    override fun selectedTemplateId(networkId: Long): String? =
        prefs.getString(selectedKey(networkId), null)

    override fun setSelectedTemplateId(networkId: Long, templateId: String?) {
        val editor = prefs.edit()
        if (templateId == null) {
            editor.remove(selectedKey(networkId))
        } else {
            editor.putString(selectedKey(networkId), templateId)
        }
        editor.apply()
    }

    private fun templatesJson(templates: List<PayloadTemplate>): String =
        buildJsonArray {
            for (template in templates) add(template.toJson())
        }.toString()

    private fun selectedKey(networkId: Long) = "$SELECTED_KEY_PREFIX$networkId"

    companion object {
        const val PREFS_NAME = "injector_payloads"
        const val MAX_CUSTOM_TEMPLATES = 20
        private const val CUSTOM_TEMPLATES_KEY = "custom_templates"
        private const val SELECTED_KEY_PREFIX = "selected_template_"
    }
}
