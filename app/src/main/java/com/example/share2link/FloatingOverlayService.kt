package com.example.share2link

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.*
import androidx.savedstate.*
import com.example.share2link.data.LinkModel
import com.example.share2link.data.LinkRepository
import com.example.share2link.theme.Share2LinkTheme
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class FloatingOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var repository: LinkRepository
    private var windowParams: WindowManager.LayoutParams? = null

    // State
    private var sharedTextState = mutableStateOf<String?>(null)
    private var openedLinksState = mutableStateOf<List<LinkModel>?>(null)
    private var targetUrlsState = mutableStateOf<List<String>?>(null)
    private var isLockedState = mutableStateOf(true)

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SHARED_TEXT = "extra_shared_text"

        fun start(context: Context, sharedText: String?) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                putExtra(EXTRA_SHARED_TEXT, sharedText)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = LinkRepository(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        isLockedState.value = !repository.isDismissOnTapOutsideEnabled()

        initOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newText = intent?.getStringExtra(EXTRA_SHARED_TEXT)
        if (!newText.isNullOrBlank()) {
            sharedTextState.value = newText
            updateUrlsForNewText(newText)
        }
        return START_NOT_STICKY
    }

    private fun updateUrlsForNewText(sharedText: String) {
        val allLinks = repository.getLinks()
        val isMultiTab = repository.isMultiTabEnabled()

        val links = if (isMultiTab) {
            allLinks
        } else {
            openedLinksState.value ?: listOf(allLinks.firstOrNull() ?: return)
        }
        openedLinksState.value = links
        PopupWebViewCache.openedLinks = links

        val currentUrls = targetUrlsState.value
        if (currentUrls != null && currentUrls.isNotEmpty()) {
            // Already showing: update search links, copy clipboard text
            val updated = currentUrls.toMutableList()
            var hasClipboard = false
            links.forEachIndexed { i, link ->
                if (link.urlTemplate.contains("%s")) {
                    val enc = URLEncoder.encode(sharedText, StandardCharsets.UTF_8.toString())
                    updated[i] = link.urlTemplate.replace("%s", enc)
                } else {
                    hasClipboard = true
                }
            }
            if (hasClipboard) {
                copyToClipboard(sharedText)
                Toast.makeText(this, "متن جدید کپی شد! در صفحه باز شده Paste کنید.", Toast.LENGTH_SHORT).show()
            }
            targetUrlsState.value = updated
            PopupWebViewCache.targetUrls = updated
        } else {
            // First time load
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
                Toast.makeText(this, "متن کپی شد! در صفحه باز شده Paste کنید.", Toast.LENGTH_SHORT).show()
            }
            targetUrlsState.value = newUrls
            PopupWebViewCache.targetUrls = newUrls
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
    }

    private fun initOverlayView() {
        if (composeView != null) return

        val density = resources.displayMetrics.density
        val widthDp = repository.getPopupWidth()
        val heightDp = repository.getPopupHeight()
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        var offX = repository.getPopupOffsetX().toInt()
        var offY = repository.getPopupOffsetY().toInt()
        if (offX == 0 && offY == 0) {
            offX = (screenW - widthPx) / 2
            offY = (screenH - heightPx) / 2
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = offX
            y = offY
        }
        windowParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)
            setViewTreeViewModelStoreOwner(this@FloatingOverlayService)

            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (!isLockedState.value) {
                        closeOverlay()
                        return@setOnTouchListener true
                    }
                }
                false
            }

            setContent {
                Share2LinkTheme {
                    FloatingOverlayContent()
                }
            }
        }

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    @Composable
    private fun FloatingOverlayContent() {
        val openedLinks = openedLinksState.value
        val targetUrls = targetUrlsState.value
        var selectedTabIndex by remember { mutableStateOf(PopupWebViewCache.selectedTabIndex) }
        val webViewRefs = remember { mutableStateMapOf<Int, WebView>() }
        val density = resources.displayMetrics.density

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Drag handle + buttons ─────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = {
                                    windowParams?.let { lp ->
                                        repository.savePopupOffset(lp.x.toFloat(), lp.y.toFloat())
                                    }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                windowParams?.let { lp ->
                                    lp.x += dragAmount.x.toInt()
                                    lp.y += dragAmount.y.toInt()
                                    windowManager.updateViewLayout(composeView, lp)
                                }
                            }
                        }
                ) {
                    // Left buttons: Lock toggle + Close
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val nextLocked = !isLockedState.value
                                isLockedState.value = nextLocked
                                repository.setDismissOnTapOutside(!nextLocked)
                                Toast.makeText(
                                    this@FloatingOverlayService,
                                    if (nextLocked) "قفل: کار با برنامه‌های زیرین فعال شد" else "باز: با لمس خارج بسته می‌شود",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isLockedState.value) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Lock",
                                tint = if (isLockedState.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { closeOverlay() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Center pill
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(5.dp)
                            .align(Alignment.Center)
                            .background(Color.Gray.copy(alpha = 0.6f), RoundedCornerShape(2.5.dp))
                    )

                    // Right button: Refresh
                    IconButton(
                        onClick = {
                            val link = openedLinks?.getOrNull(selectedTabIndex)
                            val wv = webViewRefs[selectedTabIndex]
                            if (link != null && wv != null) {
                                val refreshUrl = if (link.urlTemplate.contains("%s")) {
                                    targetUrls?.getOrNull(selectedTabIndex) ?: link.urlTemplate
                                } else {
                                    link.urlTemplate
                                }
                                wv.loadUrl(refreshUrl)
                                wv.tag = refreshUrl
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                            .size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── Tab row (multi-tab) ───────────────────────────────────────
                if (targetUrls != null && targetUrls.size > 1) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        edgePadding = 8.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.height(44.dp)
                    ) {
                        targetUrls.forEachIndexed { index, _ ->
                            val label = openedLinks?.getOrNull(index)?.name ?: "Tab ${index + 1}"
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = {
                                    selectedTabIndex = index
                                    PopupWebViewCache.selectedTabIndex = index
                                },
                                text = {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            )
                        }
                    }
                }

                // ── WebView panes ─────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    if (targetUrls != null) {
                        targetUrls.forEachIndexed { index, url ->
                            val isVisible = selectedTabIndex == index
                            val linkModel = openedLinks?.getOrNull(index)
                            val isClipboard = linkModel != null && !linkModel.urlTemplate.contains("%s")
                            val linkId = linkModel?.id ?: ""

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
                    }

                    // ── Resize handle (bottom-right) ──────────────────────────
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(32.dp)
                            .zIndex(2f)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                RoundedCornerShape(topStart = 16.dp, bottomEnd = 16.dp)
                            )
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        windowParams?.let { lp ->
                                            repository.savePopupSize(
                                                lp.width / density,
                                                lp.height / density
                                            )
                                        }
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    windowParams?.let { lp ->
                                        val minW = (180 * density).toInt()
                                        val minH = (150 * density).toInt()
                                        lp.width = (lp.width + dragAmount.x.toInt()).coerceAtLeast(minW)
                                        lp.height = (lp.height + dragAmount.y.toInt()).coerceAtLeast(minH)
                                        windowManager.updateViewLayout(composeView, lp)
                                    }
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

    private fun closeOverlay() {
        windowParams?.let { lp ->
            val density = resources.displayMetrics.density
            repository.savePopupOffset(lp.x.toFloat(), lp.y.toFloat())
            repository.savePopupSize(lp.width / density, lp.height / density)
        }
        if (composeView != null) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        if (composeView != null) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
        }
    }
}
