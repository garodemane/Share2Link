package com.example.share2link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.share2link.data.LinkModel
import com.example.share2link.data.LinkRepository
import com.example.share2link.theme.Share2LinkTheme
import com.example.share2link.ui.main.IntentHandlerScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ShareActivity : ComponentActivity() {

    private lateinit var repository: LinkRepository
    private var sharedTextState = mutableStateOf<String?>(null)
    private var lastHandledText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use applicationContext so the repository (and any closures capturing it)
        // do not hold a reference to this Activity instance.
        repository = LinkRepository(applicationContext)

        // If the user has changed their link list while multi-tab was active,
        // the cached WebViews belong to a stale set — discard them.
        if (repository.isMultiTabEnabled() && PopupWebViewCache.hasValidCache()) {
            val currentIds = repository.getLinks().map { it.id }.toSet()
            val cachedIds  = PopupWebViewCache.openedLinks?.map { it.id }?.toSet()
            if (currentIds != cachedIds) PopupWebViewCache.destroy()
        }

        handleIntent(intent)

        setContent {
            Share2LinkTheme {
                val allLinks    = repository.getLinks()
                val isMultiTab  = repository.isMultiTabEnabled()
                val sharedText  = sharedTextState.value

                // ── Decide whether to restore from the WebView cache ──────────────
                // The cache survives Activity.finish() because WebViews live in the
                // PopupWebViewCache singleton (process-scoped).  When onCreate() is
                // called again (next share), we pull the WebViews back, detach them
                // from any stale parent, and reattach to the new view hierarchy —
                // DOM / JS / scroll / session are all preserved.
                val cacheValid = remember { PopupWebViewCache.hasValidCache() }

                // Stable WebView reference map — kept at the top level so
                // BackHandler (defined here) can trigger reload/goBack.
                val webViewRefs = remember { mutableStateMapOf<Int, WebView>() }

                var openedLinks by remember {
                    mutableStateOf<List<LinkModel>?>(
                        if (cacheValid) PopupWebViewCache.openedLinks else null
                    )
                }
                var targetUrls by remember {
                    mutableStateOf<List<String>?>(
                        if (cacheValid) PopupWebViewCache.targetUrls else null
                    )
                }
                var popupAlreadyOpen by remember { mutableStateOf(cacheValid) }

                // Restore last selected tab when returning from cache.
                var selectedTabIndex by remember {
                    mutableStateOf(if (cacheValid) PopupWebViewCache.selectedTabIndex else 0)
                }

                val configuration = LocalConfiguration.current
                val screenWidth  = configuration.screenWidthDp.toFloat()
                val screenHeight = configuration.screenHeightDp.toFloat()
                var widthDp  by remember { mutableStateOf(repository.getPopupWidth()) }
                var heightDp by remember { mutableStateOf(repository.getPopupHeight()) }
                var offsetX  by remember { mutableStateOf(repository.getPopupOffsetX()) }
                var offsetY  by remember { mutableStateOf(repository.getPopupOffsetY()) }

                // ── Back button: navigate within WebView first, then close ────────
                BackHandler {
                    val currentWv = webViewRefs[selectedTabIndex]
                    if (currentWv?.canGoBack() == true) {
                        currentWv.goBack()
                    } else {
                        repository.savePopupSize(widthDp, heightDp)
                        repository.savePopupOffset(offsetX, offsetY)
                        PopupWebViewCache.selectedTabIndex = selectedTabIndex
                        finish()   // Chrome / caller is unaffected; cache holds our WebViews
                    }
                }

                // ── Auto-open all links in multi-tab mode ────────────────────────
                LaunchedEffect(sharedText, isMultiTab) {
                    if (sharedText.isNullOrBlank()) return@LaunchedEffect
                    if (isMultiTab && openedLinks == null) {
                        openedLinks = allLinks
                        PopupWebViewCache.openedLinks = allLinks
                    }
                }

                // ── Core URL logic ───────────────────────────────────────────────
                LaunchedEffect(sharedText, openedLinks) {
                    if (sharedText.isNullOrBlank() || openedLinks == null) return@LaunchedEffect

                    val links = openedLinks!!

                    if (popupAlreadyOpen) {
                        // ── Popup already visible (live session or cache-restored) ──────
                        // Clipboard links → copy new text; do NOT reload WebView
                        // Search links   → update URL so WebView reloads with new query
                        val updatedUrls = targetUrls?.toMutableList() ?: return@LaunchedEffect
                        var hasClipboard = false

                        links.forEachIndexed { index, link ->
                            if (link.urlTemplate.contains("%s")) {
                                val enc = URLEncoder.encode(sharedText, StandardCharsets.UTF_8.toString())
                                updatedUrls[index] = link.urlTemplate.replace("%s", enc)
                            } else {
                                hasClipboard = true
                            }
                        }

                        if (hasClipboard) {
                            copyToClipboard(sharedText)
                            Toast.makeText(
                                this@ShareActivity,
                                "متن جدید کپی شد! در صفحه باز شده آن را Paste کنید.",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        targetUrls = updatedUrls
                        PopupWebViewCache.targetUrls = updatedUrls
                        return@LaunchedEffect
                    }

                    // ── First-time opening ───────────────────────────────────────────────
                    // Clipboard links resume from the last page the user visited (saved in
                    // prefs by onPageFinished).  Search links encode the shared text.
                    var hasClipboard = false
                    val newUrls = links.map { link ->
                        if (link.urlTemplate.contains("%s")) {
                            val enc = URLEncoder.encode(sharedText, StandardCharsets.UTF_8.toString())
                            link.urlTemplate.replace("%s", enc)
                        } else {
                            hasClipboard = true
                            repository.getLastWebViewUrl(link.id) ?: link.urlTemplate
                        }
                    }

                    if (hasClipboard) {
                        copyToClipboard(sharedText)
                        Toast.makeText(
                            this@ShareActivity,
                            "متن کپی شد! در صفحه باز شده آن را Paste کنید.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    if (repository.isPopupEnabled()) {
                        targetUrls               = newUrls
                        popupAlreadyOpen         = true
                        PopupWebViewCache.targetUrls   = newUrls
                        PopupWebViewCache.openedLinks  = links
                    } else {
                        newUrls.forEach { openExternalBrowser(it) }
                        finish()
                    }
                }

                // ── Transparent full-screen backdrop — tap outside to dismiss ────
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                repository.savePopupSize(widthDp, heightDp)
                                repository.savePopupOffset(offsetX, offsetY)
                                PopupWebViewCache.selectedTabIndex = selectedTabIndex
                                finish()   // safe — WebViews stay alive in PopupWebViewCache
                            }
                        )
                ) {
                    if (targetUrls == null) {
                        // ── Link selection dialog ─────────────────────────────────────────
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {}
                                )
                            ) {
                                IntentHandlerScreen(
                                    text = sharedText ?: "",
                                    repository = repository,
                                    onLinkSelected = { linkModel ->
                                        openedLinks = listOf(linkModel)
                                        PopupWebViewCache.openedLinks = listOf(linkModel)
                                    },
                                    onCancel = {
                                        PopupWebViewCache.destroy()
                                        finish()
                                    }
                                )
                            }
                        }
                    } else {
                        // ── Resizable, Draggable WebView Popup ───────────────────────────
                        LaunchedEffect(Unit) {
                            if (offsetX == 0f && offsetY == 0f) {
                                offsetX = (screenWidth  - widthDp)  / 2f
                                offsetY = (screenHeight - heightDp) / 2f
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                modifier = Modifier
                                    .offset { IntOffset(offsetX.dp.roundToPx(), offsetY.dp.roundToPx()) }
                                    .size(widthDp.dp, heightDp.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {} // consume — don't propagate to backdrop
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 8.dp,
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {

                                    // ── Drag handle + refresh button ──────────────────────
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragEnd = {
                                                        repository.savePopupOffset(offsetX, offsetY)
                                                    }
                                                ) { change, dragAmount ->
                                                    change.consume()
                                                    offsetX += dragAmount.x.toDp().value
                                                    offsetY += dragAmount.y.toDp().value
                                                }
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(40.dp)
                                                .height(5.dp)
                                                .align(Alignment.Center)
                                                .background(
                                                    Color.Gray.copy(alpha = 0.6f),
                                                    RoundedCornerShape(2.5.dp)
                                                )
                                        )
                                        IconButton(
                                            onClick = { webViewRefs[selectedTabIndex]?.reload() },
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .padding(end = 8.dp)
                                                .size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Refresh",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // ── Tab row (multi-tab only) ──────────────────────────
                                    if (targetUrls!!.size > 1) {
                                        ScrollableTabRow(
                                            selectedTabIndex = selectedTabIndex,
                                            edgePadding = 8.dp,
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            targetUrls!!.forEachIndexed { index, _ ->
                                                val label = openedLinks?.getOrNull(index)?.name
                                                    ?: "Tab ${index + 1}"
                                                Tab(
                                                    selected = selectedTabIndex == index,
                                                    onClick = {
                                                        selectedTabIndex = index
                                                        PopupWebViewCache.selectedTabIndex = index
                                                    },
                                                    text = {
                                                        Text(
                                                            label,
                                                            style = MaterialTheme.typography.labelMedium
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // ── WebView panes ─────────────────────────────────────
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxSize()
                                    ) {
                                        targetUrls!!.forEachIndexed { index, url ->
                                            val isVisible      = selectedTabIndex == index
                                            val linkModel      = openedLinks?.getOrNull(index)
                                            val isClipboard    = linkModel != null &&
                                                    !linkModel.urlTemplate.contains("%s")
                                            val linkId         = linkModel?.id ?: ""

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .alpha(if (isVisible) 1f else 0f)
                                                    .zIndex(if (isVisible) 1f else 0f)
                                            ) {
                                                AndroidView(
                                                    modifier = Modifier.fillMaxSize(),
                                                    factory = { ctx ->
                                                        // ── Reuse cached WebView when available ──────
                                                        // The cached WebView was detached from the old
                                                        // Activity's hierarchy when finish() was called.
                                                        // Removing it from any residual parent is a
                                                        // safety net; then Compose can attach it here.
                                                        val cached = PopupWebViewCache.webViews[index]
                                                        if (cached != null) {
                                                            (cached.parent as? ViewGroup)?.removeView(cached)
                                                            webViewRefs[index] = cached
                                                            cached  // full page state is preserved ✓
                                                        } else {
                                                            buildWebView(
                                                                ctx, index, url,
                                                                isClipboard, linkId, webViewRefs
                                                            )
                                                        }
                                                    },
                                                    update = { webView ->
                                                        webViewRefs[index] = webView
                                                        // Clipboard → never auto-reload
                                                        // Search    → reload when URL (text) changes
                                                        if (!isClipboard && webView.tag != url) {
                                                            webView.loadUrl(url)
                                                            webView.tag = url
                                                        }
                                                    }
                                                )
                                            }
                                        }

                                        // ── Resize handle (bottom-right) ──────────────────
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(36.dp)
                                                .zIndex(2f)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                                    RoundedCornerShape(topStart = 16.dp, bottomEnd = 16.dp)
                                                )
                                                .pointerInput(Unit) {
                                                    detectDragGestures(
                                                        onDragEnd = {
                                                            repository.savePopupSize(widthDp, heightDp)
                                                        }
                                                    ) { change, dragAmount ->
                                                        change.consume()
                                                        widthDp  = (widthDp  + dragAmount.x.toDp().value).coerceAtLeast(200f)
                                                        heightDp = (heightDp + dragAmount.y.toDp().value).coerceAtLeast(200f)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("⤡", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build a brand-new WebView and register it in the cache
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildWebView(
        context: Context,
        index: Int,
        url: String,
        isClipboard: Boolean,
        linkId: String,
        webViewRefs: MutableMap<Int, WebView>
    ): WebView {
        // Use applicationContext so these closures do not prevent the current
        // Activity from being garbage-collected after finish() is called.
        val repo = repository

        return WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Remove the WebView marker so sites treat this like a normal browser.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            // Required for Google Sign-In and other OAuth popup windows.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false   // follow all redirects (OAuth, etc.)

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    // Persist the current URL for clipboard links.
                    // On the next session the popup will open directly to this page.
                    if (isClipboard && !pageUrl.isNullOrBlank() && pageUrl != "about:blank") {
                        repo.saveLastWebViewUrl(linkId, pageUrl)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                // Handle popup windows opened by JS (Google Sign-In, OAuth, etc.)
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (view == null || resultMsg == null) return false
                    val popup = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        settings.setSupportMultipleWindows(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                view.removeView(window)
                            }
                        }
                    }
                    view.addView(
                        popup,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    transport?.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }

            loadUrl(url)
            tag = url
            webViewRefs[index] = this
            PopupWebViewCache.webViews[index] = this  // keep alive across Activity recreation
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent handling
    // ─────────────────────────────────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newText = extractTextFromIntent(intent)
        if (!newText.isNullOrBlank() && newText != lastHandledText) {
            sharedTextState.value = newText
            lastHandledText = newText
        }
    }

    private fun handleIntent(intent: Intent?) {
        val text = extractTextFromIntent(intent)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "متنی یافت نشد", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sharedTextState.value = text
        lastHandledText = text
    }

    private fun extractTextFromIntent(intent: Intent?): String? = when {
        intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
            intent.getStringExtra(Intent.EXTRA_TEXT)
        intent?.action == Intent.ACTION_PROCESS_TEXT && intent.type == "text/plain" ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
    }

    private fun openExternalBrowser(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در باز کردن لینک: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
