package com.example.share2link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.example.share2link.data.LinkModel
import com.example.share2link.data.LinkRepository
import com.example.share2link.theme.Share2LinkTheme
import com.example.share2link.ui.main.IntentHandlerScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ShareActivity : ComponentActivity() {
    private lateinit var repository: LinkRepository
    private var hasOpenedBrowser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LinkRepository(this)
        
        var sharedText: String? = null
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        } else if (intent?.action == Intent.ACTION_PROCESS_TEXT && intent.type == "text/plain") {
            sharedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }

        if (sharedText.isNullOrBlank()) {
            Toast.makeText(this, "متنی یافت نشد", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            Share2LinkTheme {
                val links = repository.getLinks()
                val isMultiTab = repository.isMultiTabEnabled()
                var targetUrls by remember { mutableStateOf<List<String>?>(null) }

                LaunchedEffect(sharedText) {
                    if (isMultiTab && targetUrls == null) {
                        if (repository.isPopupEnabled()) {
                            targetUrls = links.map { generateUrl(sharedText!!, it, showToast = false) }
                        } else {
                            // If multi-tab is enabled but popup is disabled, we open external browsers for all.
                            // This might be chaotic but follows the logic.
                            links.forEach { openExternalBrowser(generateUrl(sharedText!!, it, showToast = false)) }
                            finish()
                        }
                    }
                }
                Surface(
                    color = Color.Transparent, 
                    modifier = Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { finish() }
                    )
                ) {
                    if (targetUrls == null) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            // Consume clicks so tapping the dialog doesn't close it immediately
                            Box(modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )) {
                            IntentHandlerScreen(
                                text = sharedText,
                                repository = repository,
                                onLinkSelected = { linkModel ->
                                    val url = generateUrl(sharedText!!, linkModel, showToast = true)
                                    if (repository.isPopupEnabled()) {
                                        targetUrls = listOf(url)
                                    } else {
                                        openExternalBrowser(url)
                                        finish()
                                    }
                                },
                                onCancel = { finish() }
                            )
                            }
                        }
                    } else {
                        // Resizable, Draggable In-App WebView Popup
                        val configuration = LocalConfiguration.current
                        val screenWidth = configuration.screenWidthDp.toFloat()
                        val screenHeight = configuration.screenHeightDp.toFloat()

                        var widthDp by remember { mutableStateOf(repository.getPopupWidth()) }
                        var heightDp by remember { mutableStateOf(repository.getPopupHeight()) }
                        var offsetX by remember { mutableStateOf(repository.getPopupOffsetX()) }
                        var offsetY by remember { mutableStateOf(repository.getPopupOffsetY()) }

                        // Initial centering if offset is 0,0
                        LaunchedEffect(Unit) {
                            if (offsetX == 0f && offsetY == 0f) {
                                offsetX = (screenWidth - widthDp) / 2f
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
                                        onClick = {} // Consume clicks inside the popup
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 8.dp,
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Drag handle area (Top Pill)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(24.dp)
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragEnd = { repository.savePopupOffset(offsetX, offsetY) }
                                                ) { change, dragAmount ->
                                                    change.consume()
                                                    offsetX += dragAmount.x.toDp().value
                                                    offsetY += dragAmount.y.toDp().value
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(40.dp)
                                                .height(5.dp)
                                                .background(Color.Gray.copy(alpha = 0.6f), RoundedCornerShape(2.5.dp))
                                        )
                                    }
                                    
                                    var selectedTabIndex by remember { mutableStateOf(0) }
                                    if (targetUrls!!.size > 1) {
                                        ScrollableTabRow(
                                            selectedTabIndex = selectedTabIndex,
                                            edgePadding = 8.dp,
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            targetUrls!!.forEachIndexed { index, _ ->
                                                val linkName = links.getOrNull(index)?.name ?: "Tab ${index+1}"
                                                Tab(
                                                    selected = selectedTabIndex == index,
                                                    onClick = { selectedTabIndex = index },
                                                    text = { Text(linkName, style = MaterialTheme.typography.labelMedium) }
                                                )
                                            }
                                        }
                                    }
                                    
                                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                        targetUrls!!.forEachIndexed { index, url ->
                                            val isVisible = selectedTabIndex == index
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .alpha(if (isVisible) 1f else 0f)
                                                    .zIndex(if (isVisible) 1f else 0f)
                                            ) {
                                        AndroidView(
                                            modifier = Modifier.fillMaxSize(),
                                            factory = { context ->
                                                WebView(context).apply {
                                                    layoutParams = ViewGroup.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT
                                                    )
                                                    settings.javaScriptEnabled = true
                                                    settings.domStorageEnabled = true
                                                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                                    webViewClient = WebViewClient()
                                                    loadUrl(url)
                                                }
                                            },
                                            update = { /* Do not reload on every recomposition */ }
                                        )
                                            }
                                        }
                                        
                                        // Resize Handle at Bottom Right
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(36.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), 
                                                    RoundedCornerShape(topStart = 16.dp, bottomEnd = 16.dp)
                                                )
                                                .pointerInput(Unit) {
                                                    detectDragGestures(
                                                        onDragEnd = { repository.savePopupSize(widthDp, heightDp) }
                                                    ) { change, dragAmount ->
                                                        change.consume()
                                                        widthDp = (widthDp + dragAmount.x.toDp().value).coerceAtLeast(200f)
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

    private fun generateUrl(text: String, linkModel: LinkModel, showToast: Boolean): String {
        return if (linkModel.urlTemplate.contains("%s")) {
            val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
            linkModel.urlTemplate.replace("%s", encodedText)
        } else {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
            if (showToast) {
                Toast.makeText(this, "متن کپی شد! در صفحه باز شده آن را Paste کنید.", Toast.LENGTH_LONG).show()
            }
            linkModel.urlTemplate
        }
    }

    private fun openExternalBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(browserIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "خطا در باز کردن لینک: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
