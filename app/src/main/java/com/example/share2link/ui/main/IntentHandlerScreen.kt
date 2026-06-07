package com.example.share2link.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.share2link.data.LinkModel
import com.example.share2link.data.LinkRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentHandlerScreen(
    text: String,
    repository: LinkRepository,
    onLinkSelected: (LinkModel) -> Unit,
    onCancel: () -> Unit
) {
    val links = repository.getLinks()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Select Target",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Text to send: \"$text\"",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(links) { link ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLinkSelected(link) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = link.name, style = MaterialTheme.typography.titleMedium)
                            Text(text = link.urlTemplate, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
