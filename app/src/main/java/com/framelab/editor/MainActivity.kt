package com.framelab.editor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private val Bg = Color(0xFF0B0D12)
private val Panel = Color(0xFF171B24)
private val Violet = Color(0xFF8B5CF6)
private val Muted = Color(0xFF9299AA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrameLabScreen() }
    }
}

@Composable
private fun FrameLabScreen() {
    val context = LocalContext.current
    var clips by remember { mutableStateOf(emptyList<Uri>()) }
    var selected by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            clips = clips + uris
            selected = clips.lastIndex
        }
    }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(clips, selected) {
        val uri = clips.getOrNull(selected)
        if (uri == null) player.clearMediaItems()
        else {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Violet,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(Modifier.fillMaxSize().background(Bg).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FRAMELAB", color = Color.White, fontSize = 23.sp)
                    Text("VIDEO STUDIO  •  PROJETO 01", color = Muted, fontSize = 10.sp)
                }
                Text("0.1.0", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Info("PROJETO", "Novo", Modifier.weight(1f))
                Info("FORMATO", "16:9", Modifier.weight(1f))
                Info("MÍDIA", "${clips.size} clipes", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFF050609), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center) {
                if (clips.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Seu próximo vídeo começa aqui", color = Color.White, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Importe vídeos para iniciar a edição", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { picker.launch(arrayOf("video/*")) }) {
                            Text("＋  Importar vídeos")
                        }
                    }
                } else {
                    AndroidView(factory = { ctx ->
                        PlayerView(ctx).apply { player = player; useController = true }
                    }, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TIMELINE", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("＋ Adicionar", color = Violet, modifier = Modifier.clickable {
                    picker.launch(arrayOf("video/*"))
                })
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(clips) { index, uri ->
                    Column(Modifier.width(124.dp)
                        .background(if (selected == index) Color(0xFF30264A) else Panel,
                            RoundedCornerShape(12.dp))
                        .clickable { selected = index }.padding(10.dp)) {
                        Text("CLIPE ${index + 1}", color = Color.White, fontSize = 10.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(uri.lastPathSegment?.takeLast(20) ?: "Vídeo",
                            color = Muted, fontSize = 11.sp, maxLines = 2)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tool("Cortar", "✂", Modifier.weight(1f))
                Tool("Áudio", "♫", Modifier.weight(1f))
                Tool("Texto", "T", Modifier.weight(1f))
                Tool("Cor", "◐", Modifier.weight(1f))
                Tool("Exportar", "↑", Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
            Text("Base inicial: edição e exportação ainda em desenvolvimento.",
                color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun Info(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(value, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun Tool(label: String, glyph: String, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(12.dp)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(glyph, color = Violet, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}
