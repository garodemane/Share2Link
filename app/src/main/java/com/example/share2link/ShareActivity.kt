package com.example.share2link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
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

    private fun applyWindowBounds(
        isLocked: Boolean,
        xDp: Float,
        yDp: Float,
        wDp: Float,
        hDp: Float,
        density: Float
    ) {
        val lp = window.attributes
        if (isLocked) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (xDp * density).toInt()
            lp.y = (yDp * density).toInt()
            lp.width = (wDp * density).toInt()
            lp.height = (hDp * density).toInt()
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 0
            lp.y = 0
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        }
        window.attributes = lp
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LinkRepository(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

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

                val cacheValid = remember { PopupWebViewCache.hasValidCache() }
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

                var selectedTabIndex by remember {
                    mutableStateOf(if (cacheValid) PopupWebViewCache.selectedTabIndex else 0)
                }

                val configuration = LocalConfiguration.current
                val screenWidth  = configuration.screenWidthDp.toFloat()
                val screenHeight = configuration.screenHeightDp.toFloat()

                var widthDp  by remember {
                    mutableStateOf(repository.getPopupWidth().coerceIn(200f, screenWidth))
                }
                var heightDp by remember {
                    mutableStateOf(repository.getPopupHeight().coerceIn(200f, screenHeight))
                }
                var offsetX  by remember {
                    val savedX = repository.getPopupOffsetX()
                    mutableStateOf(
                        if (savedX <= 0f) ((screenWidth - widthDp) / 2f).coerceAtLeast(0f)
                        else savedX.coerceIn(0f, (screenWidth - widthDp).coerceAtLeast(0f))
                    )
                }
                var offsetY  by remember {
                    val savedY = repository.getPopupOffsetY()
                    mutableStateOf(
                        if (savedY <= 0f) ((screenHeight - heightDp) / 2f).coerceAtLeast(0f)
                        else savedY.coerceIn(0f, (screenHeight - heightDp).coerceAtLeast(0f))
                    )
                }

                var dismissOnTapOutside by remember {
                    mutableStateOf(repository.isDismissOnTapOutsideEnabled())
                }

                val density = LocalDensity.current
                val densityVal = density.density
                val imeBottomPx = WindowInsets.ime.getBottom(density)
                val keyboardDp  = with(density) { imeBottomPx.toDp().value }
                val effectiveHeightDp = if (keyboardDp > 0f) {
                    val popupBottomDp   = offsetY + heightDp
                    val visibleBottomDp = screenHeight - keyboardDp
                    val overlap         = popupBottomDp - visibleBottomDp
                    if (overlap > 0f) (heightDp - overlap).coerceAtLeast(150f) else heightDp
                } else heightDp

                // Window positioning & touch mode synchronization
                LaunchedEffect(dismissOnTapOutside, targetUrls, effectiveHeightDp, widthDp, heightDp, offsetX, offsetY) {
                    if (targetUrls == null) {
                        applyWindowBounds(false, 0f, 0f, 0f, 0f, densityVal)
                    } else {
                        applyWindowBounds(!dismissOnTapOutside, offsetX, offsetY, widthDp, effectiveHeightDp, densityVal)
                    }
                }

                BackHandler {
                    val currentWv = webViewRefs[selectedTabIndex]
                    if (currentWv?.canGoBack() == true) {
                        currentWv.goBack()
                    } else {
                        repository.savePopupSize(widthDp, heightDp)
                        repository.savePopupOffset(offsetX, offsetY)
                        PopupWebViewCache.selectedTabIndex = selectedTabIndex
                        finish()
                    }
                }

                LaunchedEffect(sharedText, isMultiTab) {
                    if (sharedText.isNullOrBlank()) return@LaunchedEffect
                    if (isMultiTab && openedLinks == null) {
                        openedLinks = allLinks
                        PopupWebViewCache.openedLinks = allLinks
                    }
                }

                LaunchedEffect(sharedText, openedLinks) {
                    if (sharedText.isNullOrBlank() || openedLinks == null) return@LaunchedEffect

                    val links = openedLinks!!

                    if (popupAlreadyOpen) {
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
                                "New text copied to clipboard! Paste it into the page.",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        targetUrls = updatedUrls
                        PopupWebViewCache.targetUrls = updatedUrls
                        return@LaunchedEffect
                    }

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
                            "Text copied to clipboard! Paste it into the page.",
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

                if (targetUrls == null) {
                    // Link selection dialog
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    PopupWebViewCache.destroy()
                                    finish()
                                }
                            )
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
                    // Popup Surface Composable
                    @Composable
                    fun PopupContent(containerModifier: Modifier) {
                        Surface(
                            modifier = containerModifier,
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 8.dp,
                            shadowElevation = 12.dp,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {

                                // Modern Header Bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragEnd = {
                                                    repository.savePopupOffset(offsetX, offsetY)
                                                }
                                            ) { change, dragAmount ->
                                                change.consume()
                                                val newX = (offsetX + dragAmount.x.toDp().value).coerceIn(0f, (screenWidth - widthDp).coerceAtLeast(0f))
                                                val newY = (offsetY + dragAmount.y.toDp().value).coerceIn(0f, (screenHeight - effectiveHeightDp).coerceAtLeast(0f))
                                                offsetX = newX
                                                offsetY = newY
                                                if (!dismissOnTapOutside) {
                                                    val lp = window.attributes
                                                    lp.x = (newX * densityVal).toInt()
                                                    lp.y = (newY * densityVal).toInt()
                                                    window.attributes = lp
                                                }
                                            }
                                        }
                                ) {
                                    // Left: Lock toggle & Close buttons
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val newDismiss = !dismissOnTapOutside
                                                dismissOnTapOutside = newDismiss
                                                repository.setDismissOnTapOutside(newDismiss)
                                                val msg = if (newDismiss) {
                                                    "Normal mode: tapping outside closes popup"
                                                } else {
                                                    "Locked mode: popup stays pinned to interact with background apps"
                                                }
                                                Toast.makeText(this@ShareActivity, msg, Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(
                                                    if (!dismissOnTapOutside) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                        ) {
                                            Icon(
                                                imageVector = if (dismissOnTapOutside)
                                                    Icons.Default.LockOpen
                                                else
                                                    Icons.Default.Lock,
                                                contentDescription = if (dismissOnTapOutside)
                                                    "Close on outside tap: enabled"
                                                else
                                                    "Close on outside tap: disabled",
                                                tint = if (dismissOnTapOutside)
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                else
                                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                repository.savePopupSize(widthDp, heightDp)
                                                repository.savePopupOffset(offsetX, offsetY)
                                                PopupWebViewCache.selectedTabIndex = selectedTabIndex
                                                finish()
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // Center drag indicator pill
                                    Box(
                                        modifier = Modifier
                                            .width(46.dp)
                                            .height(5.dp)
                                            .align(Alignment.Center)
                                            .background(
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                                RoundedCornerShape(2.5.dp)
                                            )
                                    )

                                    // Right: Refresh button
                                    IconButton(
                                        onClick = {
                                            val link = openedLinks?.getOrNull(selectedTabIndex)
                                            val wv   = webViewRefs[selectedTabIndex]
                                            if (link != null && wv != null) {
                                                val refreshUrl = if (link.urlTemplate.contains("%s")) {
                                                    targetUrls?.getOrNull(selectedTabIndex)
                                                        ?: link.urlTemplate
                                                } else {
                                                    link.urlTemplate
                                                }
                                                wv.loadUrl(refreshUrl)
                                                wv.tag = refreshUrl
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 8.dp)
                                            .size(32.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                RoundedCornerShape(8.dp)
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Refresh",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Divider(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    thickness = 0.8.dp
                                )

                                // Multi-tab tab bar
                                if (targetUrls!!.size > 1) {
                                    ScrollableTabRow(
                                        selectedTabIndex = selectedTabIndex,
                                        edgePadding = 8.dp,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.height(44.dp)
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

                                // WebView Panes
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
                                                    val cached = PopupWebViewCache.webViews[index]
                                                    if (cached != null) {
                                                        (cached.parent as? ViewGroup)?.removeView(cached)
                                                        webViewRefs[index] = cached
                                                        cached
                                                    } else {
                                                        WebViewHelper.buildWebView(
                                                            ctx, index, url,
                                                            isClipboard, linkId, webViewRefs, repository
                                                        )
                                                    }
                                                },
                                                update = { webView ->
                                                    webViewRefs[index] = webView
                                                    if (!isClipboard && webView.tag != url) {
                                                        webView.loadUrl(url)
                                                        webView.tag = url
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    // Resize handle (bottom-right)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(28.dp)
                                            .zIndex(2f)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                                RoundedCornerShape(topStart = 14.dp, bottomEnd = 18.dp)
                                            )
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragEnd = {
                                                        repository.savePopupSize(widthDp, heightDp)
                                                    }
                                                ) { change, dragAmount ->
                                                    change.consume()
                                                    val newW = (widthDp + dragAmount.x.toDp().value).coerceIn(200f, screenWidth)
                                                    val newH = (heightDp + dragAmount.y.toDp().value).coerceIn(200f, screenHeight)
                                                    widthDp  = newW
                                                    heightDp = newH
                                                    if (!dismissOnTapOutside) {
                                                        val lp = window.attributes
                                                        lp.width = (newW * densityVal).toInt()
                                                        lp.height = (effectiveHeightDp * densityVal).toInt()
                                                        window.attributes = lp
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "⤡",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (dismissOnTapOutside) {
                        // Unlocked mode: full screen window with backdrop click dismiss
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        repository.savePopupSize(widthDp, heightDp)
                                        repository.savePopupOffset(offsetX, offsetY)
                                        PopupWebViewCache.selectedTabIndex = selectedTabIndex
                                        finish()
                                    }
                                )
                        ) {
                            PopupContent(
                                containerModifier = Modifier
                                    .offset { IntOffset(offsetX.dp.roundToPx(), offsetY.dp.roundToPx()) }
                                    .size(widthDp.dp, effectiveHeightDp.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {} // Don't propagate to backdrop
                                    )
                            )
                        }
                    } else {
                        // Locked mode: window bounds match popup, touches outside pass through to other apps
                        Box(modifier = Modifier.fillMaxSize()) {
                            PopupContent(
                                containerModifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

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
            Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Error opening link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
