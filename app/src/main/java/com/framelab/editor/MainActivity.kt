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
    var notice by remember { mutableStateOf("Importe vídeos para começar.") }

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
            notice = "${uris.size} mídia(s) adicionada(s) à sequência."
        }
    }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    LaunchedEffect(clips, selected) {
        val uri = clips.getOrNull(selected)
        if (uri == null) {
            exoPlayer.clearMediaItems()
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
        }
    }

    fun moveSelected(delta: Int) {
        val target = selected + delta
        if (target in clips.indices) {
            val reordered = clips.toMutableList()
            val current = reordered[selected]
            reordered[selected] = reordered[target]
            reordered[target] = current
            clips = reordered
            selected = target
            notice = "Ordem dos clipes atualizada."
        }
    }

    fun removeSelected() {
        if (clips.isNotEmpty() && selected in clips.indices) {
            val updated = clips.toMutableList().also { it.removeAt(selected) }
            clips = updated
            selected = if (updated.isEmpty()) 0 else selected.coerceAtMost(updated.lastIndex)
            notice = "Clipe removido da sequência. O arquivo original foi preservado."
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
                Text("0.2.0", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Info("PROJETO", "Edição atual", Modifier.weight(1f))
                Info("FORMATO", "16:9", Modifier.weight(1f))
                Info("MÍDIA", "${clips.size} clipes", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF050609), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
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
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.player = exoPlayer }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TIMELINE", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("＋ Adicionar mídia", color = Violet, modifier = Modifier.clickable {
                    picker.launch(arrayOf("video/*"))
                })
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(clips) { index, uri ->
                    Column(
                        Modifier.width(132.dp)
                            .background(if (selected == index) Color(0xFF30264A) else Panel,
                                RoundedCornerShape(12.dp))
                            .clickable { selected = index; notice = "Clipe ${index + 1} selecionado." }
                            .padding(10.dp)
                    ) {
                        Text(if (selected == index) "● CLIPE ${index + 1}" else "CLIPE ${index + 1}",
                            color = if (selected == index) Color(0xFFC4B5FD) else Color.White,
                            fontSize = 10.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(uri.lastPathSegment?.substringAfterLast('/')?.takeLast(24) ?: "Vídeo",
                            color = Muted, fontSize = 11.sp, maxLines = 2)
                        Spacer(Modifier.height(5.dp))
                        Text("Selecionar", color = Violet, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("EDIÇÃO DA SEQUÊNCIA", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { moveSelected(-1) },
                    enabled = clips.isNotEmpty() && selected > 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("← Mover", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { moveSelected(1) },
                    enabled = clips.isNotEmpty() && selected < clips.lastIndex,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("Mover →", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { removeSelected() },
                    enabled = clips.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A8A))
                ) { Text("Excluir", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tool("Cortar", "✂", Modifier.weight(1f))
                Tool("Áudio", "♫", Modifier.weight(1f))
                Tool("Texto", "T", Modifier.weight(1f))
                Tool("Cor", "◐", Modifier.weight(1f))
                Tool("Exportar", "↑", Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
            Text(notice, color = Muted, fontSize = 9.sp)
            Text("Importação, prévia e organização da sequência disponíveis. Corte e exportação em desenvolvimento.",
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
        Text(label, color = Muted, fontSize = 10.sp)
    }
}
