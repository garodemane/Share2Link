package com.example.share2link

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.share2link.data.LinkRepository
import com.example.share2link.theme.Share2LinkTheme
import com.example.share2link.ui.main.MainScreen

class MainActivity : ComponentActivity() {
    private lateinit var repository: LinkRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LinkRepository(this)

        enableEdgeToEdge()
        setContent {
            Share2LinkTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(repository = repository)
                }
            }
        }
    }
}
