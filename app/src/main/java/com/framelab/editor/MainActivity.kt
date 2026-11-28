package com.framelab.editor

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import org.json.JSONArray

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

@OptIn(UnstableApi::class)
@Composable
private fun FrameLabScreen() {
    val context = LocalContext.current
    val projectPrefs = remember { context.getSharedPreferences("framelab_project", android.content.Context.MODE_PRIVATE) }
    val restoredClips = remember {
        runCatching {
            val saved = JSONArray(projectPrefs.getString("clips", "[]"))
            (0 until saved.length()).mapNotNull { index ->
                saved.optString(index).takeIf { it.isNotBlank() }?.let(Uri::parse)
            }
        }.getOrDefault(emptyList())
    }
    val restoredMusic = remember { projectPrefs.getString("music_uri", null)?.let(Uri::parse) }
    val restoredTrimRanges = remember {
        runCatching {
            val saved = JSONArray(projectPrefs.getString("trim_ranges", "[]"))
            (0 until saved.length()).mapNotNull { index ->
                saved.optJSONArray(index)?.let { range ->
                    range.optLong(0) to range.optLong(1)
                }
            }
        }.getOrDefault(emptyList())
    }
    var musicUri by remember { mutableStateOf(restoredMusic) }
    var overlayText by remember { mutableStateOf(projectPrefs.getString("overlay_text", "") ?: "") }
    var textDraft by remember { mutableStateOf(overlayText) }
    var showTextEditor by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf(restoredClips) }
    var trimRanges by remember {
        mutableStateOf(restoredTrimRanges + List((restoredClips.size - restoredTrimRanges.size).coerceAtLeast(0)) { 0L to 0L })
    }
    var selected by remember { mutableIntStateOf(projectPrefs.getInt("selected", 0).coerceIn(0, (restoredClips.size - 1).coerceAtLeast(0))) }
    var notice by remember { mutableStateOf("Importe vídeos para começar.") }
    var durationMs by remember { mutableLongStateOf(0L) }
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }
    var showTrim by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var activeTransformer by remember { mutableStateOf<Transformer?>(null) }

    LaunchedEffect(exporting) {
        while (exporting) {
            val transformer = activeTransformer
            if (transformer != null) {
                val holder = ProgressHolder()
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    exportProgress = holder.progress.toFloat().coerceIn(0f, 100f)
                }
            }
            delay(500)
        }
    }

    LaunchedEffect(clips, selected, musicUri, overlayText, trimRanges) {
        val serialized = JSONArray().apply { clips.forEach { put(it.toString()) } }.toString()
        val serializedRanges = JSONArray().apply {
            trimRanges.forEach { (start, end) ->
                put(JSONArray().put(start).put(end))
            }
        }.toString()
        projectPrefs.edit()
            .putString("clips", serialized)
            .putInt("selected", selected.coerceIn(0, (clips.size - 1).coerceAtLeast(0)))
            .putString("music_uri", musicUri?.toString())
            .putString("overlay_text", overlayText)
            .putString("trim_ranges", serializedRanges)
            .apply()
    }

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
            trimRanges = trimRanges + List(uris.size) { 0L to 0L }
            selected = clips.lastIndex
            notice = "${uris.size} mídia(s) adicionada(s) à sequência."
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            musicUri = uri
            notice = "Faixa de áudio adicionada à exportação."
        }
    }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    LaunchedEffect(clips, selected) {
        val uri = clips.getOrNull(selected)
        if (uri == null) {
            exoPlayer.clearMediaItems()
            durationMs = 0L
            trimStartMs = 0L
            trimEndMs = 0L
        } else {
            durationMs = 0L
            trimStartMs = 0L
            trimEndMs = 0L
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
            val foundDuration = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (_: Exception) {
                    0L
                } finally {
                    retriever.release()
                }
            }
            durationMs = foundDuration
            val savedRange = trimRanges.getOrNull(selected)
            val restoredStart = savedRange?.first?.coerceIn(0L, (foundDuration - 1L).coerceAtLeast(0L)) ?: 0L
            val restoredEnd = savedRange?.second?.takeIf { it > restoredStart }?.coerceAtMost(foundDuration) ?: foundDuration
            trimStartMs = restoredStart
            trimEndMs = restoredEnd
            if (selected in trimRanges.indices) {
                trimRanges = trimRanges.toMutableList().also { it[selected] = restoredStart to restoredEnd }
            } else {
                trimRanges = trimRanges + (restoredStart to restoredEnd)
            }
            if (foundDuration <= 0L) notice = "Não foi possível ler a duração deste vídeo."
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
            if (selected in trimRanges.indices && target in trimRanges.indices) {
                val reorderedRanges = trimRanges.toMutableList()
                val range = reorderedRanges[selected]
                reorderedRanges[selected] = reorderedRanges[target]
                reorderedRanges[target] = range
                trimRanges = reorderedRanges
            }
            selected = target
            notice = "Ordem dos clipes atualizada."
        }
    }

    fun splitSelectedAtPlayhead() {
        if (selected !in clips.indices) return
        val currentUri = clips[selected]
        val currentRange = trimRanges.getOrNull(selected) ?: (0L to durationMs)
        val start = currentRange.first
        val end = currentRange.second.takeIf { it > start } ?: durationMs
        val splitPoint = exoPlayer.currentPosition.coerceIn(0L, durationMs)
        if (splitPoint <= start + 250L || splitPoint >= end - 250L) {
            notice = "Posicione a reprodução dentro do clipe, deixando ao menos 0,25 s de cada lado, para dividir."
            return
        }
        val updatedClips = clips.toMutableList().also { it.add(selected + 1, currentUri) }
        val updatedRanges = trimRanges.toMutableList()
        while (updatedRanges.size < clips.size) updatedRanges.add(0L to 0L)
        updatedRanges[selected] = start to splitPoint
        updatedRanges.add(selected + 1, splitPoint to end)
        clips = updatedClips
        trimRanges = updatedRanges
        notice = "Clipe dividido no ponto ${formatTime(splitPoint)}. Os dois trechos podem ser editados separadamente."
    }
    fun removeSelected() {
        if (clips.isNotEmpty() && selected in clips.indices) {
            val updated = clips.toMutableList().also { it.removeAt(selected) }
            clips = updated
            if (selected in trimRanges.indices) trimRanges = trimRanges.toMutableList().also { it.removeAt(selected) }
            selected = if (updated.isEmpty()) 0 else selected.coerceAtMost(updated.lastIndex)
            notice = "Clipe removido da sequência. O arquivo original foi preservado."
        }
    }

    val saveVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destinationUri ->
        if (destinationUri != null && clips.isNotEmpty() && durationMs > 0L) {
            exporting = true
            exportProgress = 0f
            notice = "Preparando sequência completa para exportação MP4…"
            val outputFile = File(context.cacheDir, "framelab_export_${System.currentTimeMillis()}.mp4")
            try {
                val editedItems = clips.mapIndexed { index, uri ->
                    val builder = MediaItem.Builder().setUri(uri)
                    val clipRange = trimRanges.getOrNull(index)
                    if (clipRange != null && clipRange.second > clipRange.first) {
                        builder.setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clipRange.first)
                                .setEndPositionMs(clipRange.second)
                                .build()
                        )
                    }
                    val editedBuilder = EditedMediaItem.Builder(builder.build())
                    if (overlayText.isNotBlank()) {
                        val styledText = SpannableString(overlayText.trim()).apply {
                            setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
                            setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, length, 0)
                            setSpan(StyleSpan(Typeface.BOLD), 0, length, 0)
                        }
                        editedBuilder.setEffects(
                            Effects(emptyList(), listOf(OverlayEffect(listOf(
                                TextOverlay.createStaticTextOverlay(styledText)
                            ))))
                        )
                    }
                    editedBuilder.build()
                }
                val videoSequence = EditedMediaItemSequence.Builder(editedItems).build()
                val sequences = mutableListOf(videoSequence)
                musicUri?.let { audioUri ->
                    val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(audioUri)).build()
                    val musicSequence = EditedMediaItemSequence.Builder(listOf(musicItem))
                        .setIsLooping(true)
                        .build()
                    sequences.add(musicSequence)
                }
                val composition = Composition.Builder(sequences).build()
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            try {
                                val input = outputFile.inputStream()
                                val output = context.contentResolver.openOutputStream(destinationUri, "w")
                                    ?: error("Não foi possível abrir o destino escolhido.")
                                input.use { source -> output.use { target -> source.copyTo(target) } }
                                exportProgress = 100f
                                notice = "Sequência exportada com sucesso: MP4 salvo no local escolhido."
                            } catch (error: Exception) {
                                notice = "O vídeo foi processado, mas não pôde ser salvo: ${error.message ?: "erro de arquivo"}"
                            } finally {
                                outputFile.delete()
                                exporting = false
                                activeTransformer = null
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            outputFile.delete()
                            exporting = false
                            activeTransformer = null
                            notice = "Falha na exportação: ${exportException.message ?: "formato não suportado"}"
                        }
                    })
                    .build()
                activeTransformer = transformer
                transformer.start(composition, outputFile.absolutePath)
            } catch (error: Exception) {
                outputFile.delete()
                exporting = false
                activeTransformer = null
                notice = "Não foi possível iniciar a exportação: ${error.message ?: "erro inesperado"}"
            }
        } else if (destinationUri != null) {
            notice = "Selecione um vídeo válido antes de exportar."
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
                    Text("VIDEO STUDIO  •  SALVAMENTO AUTOMÁTICO", color = Muted, fontSize = 10.sp)
                }
                Text("0.7.0", color = Muted, fontSize = 11.sp)
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } },
                            modifier = Modifier.fillMaxSize(),
                            update = { it.player = exoPlayer }
                        )
                        if (overlayText.isNotBlank()) {
                            Text(
                                overlayText,
                                color = Color.White,
                                fontSize = 22.sp,
                                lineHeight = 26.sp,
                                modifier = Modifier.padding(28.dp)
                                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
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
                OutlinedButton(onClick = { moveSelected(-1) }, enabled = clips.isNotEmpty() && selected > 0,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("← Mover", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { moveSelected(1) }, enabled = clips.isNotEmpty() && selected < clips.lastIndex,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Mover →", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { splitSelectedAtPlayhead() },
                    enabled = clips.isNotEmpty() && durationMs > 0L,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Dividir", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { removeSelected() }, enabled = clips.isNotEmpty(),
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A8A))) {
                    Text("Excluir", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tool("Cortar", "✂", Modifier.weight(1f), enabled = clips.isNotEmpty() && durationMs > 0L) {
                    showTrim = true
                }
                Tool(if (musicUri == null) "Áudio" else "Remover", "♫", Modifier.weight(1f)) {
                    if (musicUri == null) audioPicker.launch(arrayOf("audio/*"))
                    else {
                        musicUri = null
                        notice = "Faixa de áudio removida do projeto."
                    }
                }
                Tool("Texto", "T", Modifier.weight(1f), enabled = true) { textDraft = overlayText; showTextEditor = true }
                Tool("Cor", "◐", Modifier.weight(1f), enabled = false) {}
                Tool("Exportar", "↑", Modifier.weight(1f), enabled = clips.isNotEmpty() && durationMs > 0L && !exporting) {
                    if (durationMs > 0L) saveVideo.launch("FrameLab_sequencia_${System.currentTimeMillis()}.mp4")
                    else notice = "Aguarde a leitura da duração do vídeo."
                }
            }
            Spacer(Modifier.height(7.dp))
            if (exporting) {
                LinearProgressIndicator(
                    progress = { exportProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Violet
                )
                Text("Exportando sequência: ${exportProgress.toInt()}% • mantenha o aplicativo aberto.",
                    color = Muted, fontSize = 10.sp)
            }
            Text(notice, color = Muted, fontSize = 9.sp)
            Text("Projeto, texto, ordem, cortes e divisões individuais salvos automaticamente. Texto sobreposto e exportação MP4 disponíveis; correção de cor ainda em desenvolvimento.",
                color = Muted, fontSize = 9.sp)
        }

        if (showTextEditor) {
            AlertDialog(
                onDismissRequest = { showTextEditor = false },
                title = { Text("Texto sobre o vídeo") },
                text = {
                    Column {
                        Text("O texto será aplicado sobre todos os clipes durante a exportação.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = textDraft,
                            onValueChange = { textDraft = it.take(160) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Título ou legenda") },
                            placeholder = { Text("Digite seu texto…") },
                            minLines = 2,
                            maxLines = 4
                        )
                        Text("${textDraft.length}/160 caracteres", color = Muted, fontSize = 10.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        overlayText = textDraft.trim()
                        showTextEditor = false
                        notice = if (overlayText.isBlank()) "Texto removido do projeto." else "Texto salvo e aplicado à prévia e à exportação."
                    }) { Text("Aplicar") }
                },
                dismissButton = {
                    TextButton(onClick = { textDraft = overlayText; showTextEditor = false }) { Text("Cancelar") }
                }
            )
        }

        if (showTrim) {
            AlertDialog(
                onDismissRequest = { showTrim = false },
                title = { Text("Cortar clipe") },
                text = {
                    Column {
                        Text("O corte será aplicado ao clipe selecionado; os demais clipes serão mantidos na sequência.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Início: ${formatTime(trimStartMs)}", color = Color.White)
                        Text("Fim: ${formatTime(trimEndMs)}  •  Duração: ${formatTime((trimEndMs - trimStartMs).coerceAtLeast(0L))}",
                            color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        if (durationMs > 1L) {
                            RangeSlider(
                                value = trimStartMs.toFloat().coerceAtLeast(0f)..
                                    trimEndMs.toFloat().coerceAtMost(durationMs.toFloat()),
                                onValueChange = { range ->
                                    val minGap = 500L
                                    var start = range.start.toLong().coerceIn(0L, durationMs - 1L)
                                    var end = range.endInclusive.toLong().coerceIn(1L, durationMs)
                                    if (end - start < minGap) {
                                        if (start + minGap <= durationMs) end = start + minGap
                                        else start = (end - minGap).coerceAtLeast(0L)
                                    }
                                    trimStartMs = start
                                    trimEndMs = end
                                    if (selected in trimRanges.indices) {
                                        trimRanges = trimRanges.toMutableList().also { it[selected] = start to end }
                                    }
                                },
                                valueRange = 0f..durationMs.toFloat()
                            )
                        }
                        Text("Original preservado. O corte gera um novo arquivo MP4.", color = Muted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showTrim = false
                        saveVideo.launch("FrameLab_corte_${System.currentTimeMillis()}.mp4")
                    }, enabled = !exporting && trimEndMs > trimStartMs) { Text("Exportar sequência") }
                },
                dismissButton = {
                    TextButton(onClick = { showTrim = false }) { Text("Cancelar") }
                }
            )
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

@Composable
private fun Info(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(value, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun Tool(
    label: String,
    glyph: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier.background(if (enabled) Panel else Panel.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, color = if (enabled) Violet else Muted, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (enabled) Muted else Muted.copy(alpha = 0.55f), fontSize = 10.sp)
    }
}
