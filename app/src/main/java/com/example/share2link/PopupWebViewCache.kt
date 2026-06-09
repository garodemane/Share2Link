package com.example.share2link

import android.view.ViewGroup
import android.webkit.WebView
import com.example.share2link.data.LinkModel

/**
 * Process-scoped singleton that keeps WebView instances alive across Activity destructions.
 *
 * Problem being solved:
 *   - finish()            → Chrome unaffected ✓  but WebViews destroyed ✗
 *   - moveTaskToBack(true)→ WebViews survive ✓   but Chrome closes too ✗
 *
 * Solution:
 *   Store WebViews here so they outlive the Activity. ShareActivity can safely call
 *   finish() (Chrome never closes) while WebViews continue living in this object.
 *   On the next share intent, onCreate() is called, we pull the WebViews back out,
 *   detach them from any stale parent, and reattach to the new view hierarchy.
 *   The page content, JS state, scroll position and session are all preserved.
 */
object PopupWebViewCache {

    /** Live WebView instances, keyed by tab index. */
    val webViews: MutableMap<Int, WebView> = mutableMapOf()

    /** The effective URL list used when the WebViews were last created/updated. */
    var targetUrls: List<String>? = null

    /** The LinkModel list that maps 1:1 to each tab / WebView. */
    var openedLinks: List<LinkModel>? = null

    /** Last selected tab index so it is restored across sessions. */
    var selectedTabIndex: Int = 0

    /** Returns true when the cache holds a complete, reusable state. */
    fun hasValidCache(): Boolean =
        webViews.isNotEmpty() && targetUrls != null && openedLinks != null

    /**
     * Remove all cached WebViews from their current parent ViewGroup without destroying them.
     * Must be called before the WebViews are added to a NEW view hierarchy; a View can only
     * belong to one parent at a time.
     */
    fun detachAll() {
        webViews.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
    }

    /**
     * Permanently destroy all cached WebViews and reset state.
     * Call when the user's link configuration changes or the process needs to start fresh.
     */
    fun destroy() {
        webViews.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webViews.clear()
        targetUrls = null
        openedLinks = null
        selectedTabIndex = 0
    }
}
