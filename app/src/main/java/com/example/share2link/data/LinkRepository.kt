package com.example.share2link.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LinkModel(val id: String, val name: String, val urlTemplate: String)

class LinkRepository(context: Context) {
    private val prefs = context.getSharedPreferences("share2link_prefs", Context.MODE_PRIVATE)

    fun getLinks(): List<LinkModel> {
        val jsonString = prefs.getString("links", "[]") ?: "[]"
        val links = mutableListOf<LinkModel>()
        var isCustom = false
        try {
            val jsonArray = JSONArray(jsonString)
            if (jsonArray.length() > 0) {
                isCustom = true
            }
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                links.add(LinkModel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    urlTemplate = obj.getString("urlTemplate")
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (!isCustom && links.isEmpty()) {
            listOf(
                LinkModel(UUID.randomUUID().toString(), "Cambridge Dictionary", "https://dictionary.cambridge.org/dictionary/english/%s"),
                LinkModel(UUID.randomUUID().toString(), "Google Search", "https://www.google.com/search?q=%s")
            )
        } else {
            links
        }
    }

    fun saveLinks(links: List<LinkModel>) {
        val jsonArray = JSONArray()
        for (link in links) {
            val obj = JSONObject()
            obj.put("id", link.id)
            obj.put("name", link.name)
            obj.put("urlTemplate", link.urlTemplate)
            jsonArray.put(obj)
        }
        prefs.edit().putString("links", jsonArray.toString()).apply()
    }
    
    fun addLink(name: String, urlTemplate: String) {
        val current = getLinks().toMutableList()
        current.add(LinkModel(UUID.randomUUID().toString(), name, urlTemplate))
        saveLinks(current)
    }

    fun updateLink(id: String, newName: String, newUrlTemplate: String) {
        val current = getLinks().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            current[index] = LinkModel(id, newName, newUrlTemplate)
            saveLinks(current)
        }
    }
    
    fun deleteLink(id: String) {
        val current = getLinks().toMutableList()
        current.removeAll { it.id == id }
        saveLinks(current)
    }

    fun isPopupEnabled(): Boolean {
        return prefs.getBoolean("use_popup_browser", true)
    }

    fun setPopupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("use_popup_browser", enabled).apply()
    }

    fun isMultiTabEnabled(): Boolean {
        return prefs.getBoolean("multi_tab_mode", false)
    }

    fun setMultiTabEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("multi_tab_mode", enabled).apply()
    }

    fun getPopupWidth(): Float {
        return prefs.getFloat("popup_width", 350f)
    }

    fun getPopupHeight(): Float {
        return prefs.getFloat("popup_height", 500f)
    }

    fun savePopupSize(width: Float, height: Float) {
        prefs.edit().putFloat("popup_width", width).putFloat("popup_height", height).apply()
    }

    fun getPopupOffsetX(): Float {
        return prefs.getFloat("popup_offset_x", 0f)
    }

    fun getPopupOffsetY(): Float {
        return prefs.getFloat("popup_offset_y", 0f)
    }

    fun savePopupOffset(x: Float, y: Float) {
        prefs.edit().putFloat("popup_offset_x", x).putFloat("popup_offset_y", y).apply()
    }
}
