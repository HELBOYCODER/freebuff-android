package com.freebuff.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freebuff.android.model.ClientToolName
import com.freebuff.android.workspace.StrReplace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SlicePreview()
                }
            }
        }
    }
}

// Vertical-slice smoke screen: proves the Compose app is wired to the tested JVM
// core (protocol model + workspace edit engine). Real behavior, not a mockup.
@androidx.compose.runtime.Composable
fun SlicePreview() {
    var text by remember { mutableStateOf("val answer = 41") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Freebuff Android slice", style = MaterialTheme.typography.headlineSmall)
        Text("host tools bound: ${ClientToolName.entries.size}")
        Text("editor buffer: $text")
        Button(onClick = {
            val r = StrReplace.replace(text, "41", "42")
            if (r is StrReplace.Outcome.Applied) text = r.content
        }) { Text("apply str_replace 41->42") }
    }
}
