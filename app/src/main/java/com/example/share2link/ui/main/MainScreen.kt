package com.example.share2link.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.share2link.data.LinkModel
import com.example.share2link.data.LinkRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: LinkRepository, modifier: Modifier = Modifier) {
    var links by remember { mutableStateOf(repository.getLinks()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var linkToEdit by remember { mutableStateOf<LinkModel?>(null) }
    var isPopupEnabled by remember { mutableStateOf(repository.isPopupEnabled()) }
    var isMultiTabEnabled by remember { mutableStateOf(repository.isMultiTabEnabled()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share2Link") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Multi-Tab", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
                        Switch(
                            checked = isMultiTabEnabled,
                            onCheckedChange = { 
                                isMultiTabEnabled = it
                                repository.setMultiTabEnabled(it)
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        
                        Text("Pop-up", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
                        Switch(
                            checked = isPopupEnabled,
                            onCheckedChange = { 
                                isPopupEnabled = it
                                repository.setPopupEnabled(it)
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("➕", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        val listState = rememberLazyListState()
        var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(0f) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

        LazyColumn(
            state = listState,
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                offset.y.toInt() in it.offset..(it.offset + it.size)
                            }
                            if (itemInfo != null) {
                                draggedItemIndex = itemInfo.index
                                dragOffset = 0f
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount.y
                            
                            val draggedIndex = draggedItemIndex ?: return@detectDragGesturesAfterLongPress
                            val draggedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIndex }
                            if (draggedItem != null) {
                                val currentY = draggedItem.offset + dragOffset
                                
                                val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    it.index != draggedIndex &&
                                    currentY > it.offset && currentY < it.offset + it.size
                                }
                                
                                if (targetItem != null) {
                                    val targetIndex = targetItem.index
                                    val newList = links.toMutableList()
                                    val item = newList.removeAt(draggedIndex)
                                    newList.add(targetIndex, item)
                                    links = newList
                                    draggedItemIndex = targetIndex
                                    dragOffset += (draggedItem.offset - targetItem.offset)
                                }
                            }
                        },
                        onDragEnd = {
                            draggedItemIndex = null
                            dragOffset = 0f
                            repository.saveLinks(links)
                        },
                        onDragCancel = {
                            draggedItemIndex = null
                            dragOffset = 0f
                        }
                    )
                },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(links, key = { _, link -> link.id }) { index, link ->
                val isDragged = index == draggedItemIndex
                val offsetDp = if (isDragged) with(LocalDensity.current) { dragOffset.toDp() } else 0.dp
                val elevation = if (isDragged) 8.dp else 0.dp
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = offsetDp)
                        .zIndex(if (isDragged) 1f else 0f),
                    elevation = CardDefaults.cardElevation(defaultElevation = elevation)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(text = link.name, style = MaterialTheme.typography.titleMedium)
                            Text(text = link.urlTemplate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            IconButton(onClick = { linkToEdit = link }) {
                                Text("✏️", style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = {
                                repository.deleteLink(link.id)
                                links = repository.getLinks()
                            }) {
                                Text("🗑️", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog || linkToEdit != null) {
        val isEditing = linkToEdit != null
        var name by remember { mutableStateOf(linkToEdit?.name ?: "") }
        var url by remember { mutableStateOf(linkToEdit?.urlTemplate ?: "") }
        
        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false
                linkToEdit = null
            },
            title = { Text(if (isEditing) "Edit Link" else "Add New Link") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (e.g. Dictionary)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL Template (use %s for text)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank() && url.isNotBlank()) {
                            if (isEditing) {
                                repository.updateLink(linkToEdit!!.id, name, url)
                            } else {
                                repository.addLink(name, url)
                            }
                            links = repository.getLinks()
                            showAddDialog = false
                            linkToEdit = null
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    linkToEdit = null
                }) { Text("Cancel") }
            }
        )
    }
}
