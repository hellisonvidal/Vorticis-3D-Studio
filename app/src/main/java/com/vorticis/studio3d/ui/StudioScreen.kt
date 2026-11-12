package com.vorticis.studio3d.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.vorticis.studio3d.ChatMessage
import com.vorticis.studio3d.R
import com.vorticis.studio3d.Source
import com.vorticis.studio3d.StudioViewModel
import com.vorticis.studio3d.UiEvent
import com.vorticis.studio3d.core.export.ExportFormat
import com.vorticis.studio3d.core.photo.CaptureLayout
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.gl.ModelSurfaceView
import com.vorticis.studio3d.update.AppUpdater
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(vm: StudioViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    var input by rememberSaveable { mutableStateOf("") }
    var showExport by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showVideoTips by remember { mutableStateOf(false) }
    var showCloud by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var chatExpanded by rememberSaveable { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var appendNext by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    // ------------------------------------------------ lançadores
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.onPhoto(uri, append = appendNext)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(16)) { uris ->
        if (uris.isNotEmpty()) vm.onPhotos(uris, append = appendNext)
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = videoUri
        if (ok && uri != null) vm.onVideo(uri)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onVideo(uri)
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.send(text)
    }

    fun captureUri(name: String): Uri {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return FileProvider.getUriForFile(context, context.packageName + ".files", File(dir, name))
    }

    fun snack(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    fun openCamera(append: Boolean) {
        appendNext = append
        val uri = captureUri("foto_${System.currentTimeMillis()}.jpg")
        cameraUri = uri
        try { takePicture.launch(uri) } catch (_: ActivityNotFoundException) { snack("Nenhum app de câmera encontrado. Use a galeria.") }
    }

    fun openGallery(append: Boolean) {
        appendNext = append
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun openVideoCamera() {
        val uri = captureUri("video_${System.currentTimeMillis()}.mp4")
        videoUri = uri
        try { recordVideo.launch(uri) } catch (_: ActivityNotFoundException) { snack("Nenhum app de câmera de vídeo encontrado.") }
    }

    fun openVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o que criar ou alterar")
        }
        try { voice.launch(i) } catch (_: ActivityNotFoundException) { snack("Reconhecimento de voz indisponível neste aparelho.") }
    }

    fun submit() {
        if (input.isBlank()) return
        vm.send(input); input = ""
        keyboard?.hide()
    }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is UiEvent.Share -> context.startActivity(e.intent)
                is UiEvent.Launch -> try { context.startActivity(e.intent) } catch (_: Exception) { snack("Não foi possível abrir.") }
                is UiEvent.OpenCamera -> openCamera(false)
                is UiEvent.OpenExport -> showExport = true
                is UiEvent.AskCloud -> if (vm.hasPhotoForCloud) showCloud = true else snack("Envie uma foto primeiro (toque em ＋).")
                is UiEvent.Snack -> scope.launch {
                    val r = snackbar.showSnackbar(e.text, actionLabel = if (e.share != null) "Compartilhar" else null, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed && e.share != null) vm.share(e.share)
                }
            }
        }
    }

    // Surface define a cor padrão de textos/ícones (antes ficavam escuros sobre o fundo escuro)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize()) {
        // safeDrawing = barras do sistema + recorte da tela + TECLADO: o conteúdo sobe junto com o teclado
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // ------------------------------------------------ cabeçalho
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.logo_vorticis), null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vorticis 3D Studio", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        vm.busy ?: "IA no aparelho • funciona offline",
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (vm.busy != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showExport = true }) { Icon(painterResource(R.drawable.ic_download), "Exportar") }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Mais opções") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Como usar") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { showMenu = false; showHelp = true })
                        DropdownMenuItem(text = { Text("Nuvem grátis: token (opcional)") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { showMenu = false; showToken = true })
                        DropdownMenuItem(text = { Text("Verificar atualização") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { showMenu = false; vm.checkUpdate() })
                        DropdownMenuItem(text = { Text("Limpar cena") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, enabled = !vm.scene.isEmpty, onClick = { showMenu = false; vm.clear() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Versão ${AppUpdater.currentVersionName}", fontSize = 12.sp) }, enabled = false, onClick = {})
                    }
                }
            }

            // ------------------------------------------------ visualizador 3D
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0B0D12)),
            ) {
                AndroidView(
                    factory = { ModelSurfaceView(it) },
                    update = { it.setScene(vm.scene); it.setCloud(vm.cloud) },
                    modifier = Modifier.fillMaxSize(),
                )
                // desfazer/refazer sobre o visualizador (libera espaço no cabeçalho)
                if (vm.canUndo || vm.canRedo) Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(painterResource(R.drawable.ic_undo), "Desfazer", tint = if (vm.canUndo) Color.White else Color(0x55FFFFFF)) }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(painterResource(R.drawable.ic_redo), "Refazer", tint = if (vm.canRedo) Color.White else Color(0x55FFFFFF)) }
                }
                if (!vm.scene.isEmpty && vm.cloud == null) {
                    Surface(
                        Modifier.padding(10.dp).align(Alignment.TopStart),
                        color = Color(0x99000000), shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "${"%,d".format(vm.scene.triangleCount)} triângulos • arraste para girar",
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, color = Color(0xDDFFFFFF),
                        )
                    }
                } else if (vm.scene.isEmpty && vm.cloud == null && vm.busy == null && !imeVisible) {
                    Text(
                        "Toque em ＋ para enviar fotos ou vídeo da galeria,\nou descreva o que quer criar.",
                        Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center, fontSize = 13.sp, color = Color(0x99FFFFFF),
                    )
                }
                vm.busy?.let { msg ->
                    // durante a animação de pontos, o aviso fica discreto no rodapé do visualizador
                    Surface(
                        Modifier.align(if (vm.cloud != null) Alignment.BottomCenter else Alignment.Center).padding(bottom = 14.dp),
                        color = Color(0xCC10131A), shape = RoundedCornerShape(50),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(msg, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                if (vm.hasPhoto && !imeVisible && vm.cloud == null && vm.busy == null) PhotoControls(vm, Modifier.align(Alignment.BottomCenter), onAddCamera = { openCamera(true) }, onAddGallery = { openGallery(true) }, onCloud = { showCloud = true })
            }

            // ------------------------------------------------ conversa
            ChatPanel(
                vm = vm,
                expanded = chatExpanded || imeVisible,
                onToggle = { chatExpanded = !chatExpanded },
                input = input,
                onInput = { input = it },
                onSubmit = ::submit,
                onVoice = ::openVoice,
                onAttach = { showAttach = true },
                inputFocus = inputFocus,
                showSuggestions = !imeVisible || input.isBlank(),
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(12.dp))
        }
    }
    }

    // ------------------------------------------------ menu ＋
    if (showAttach) {
        ModalBottomSheet(onDismissRequest = { showAttach = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                Text("Criar a partir de…", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                AttachRow(painterResource(R.drawable.ic_gallery), "Fotos da galeria", "1 foto, ou várias do mesmo objeto tiradas ao redor dele.") { showAttach = false; openGallery(false) }
                AttachRow(painterResource(R.drawable.ic_video), "Vídeo da galeria", "Um vídeo curto dando a volta no objeto (10 a 20 s).") {
                    showAttach = false
                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Usar a câmera agora", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                AttachRow(painterResource(R.drawable.ic_camera), "Tirar foto", "Fundo liso e boa luz ajudam.") { showAttach = false; openCamera(false) }
                AttachRow(painterResource(R.drawable.ic_video), "Gravar vídeo dando a volta", "Ande ao redor do objeto, ou gire o objeto.") { showAttach = false; showVideoTips = true }
            }
        }
    }

    if (showVideoTips) {
        AlertDialog(
            onDismissRequest = { showVideoTips = false },
            title = { Text("Vídeo → 3D") },
            text = {
                Text(
                    "Para um bom resultado:\n\n" +
                        "1. Objeto no centro, inteiro no quadro.\n" +
                        "2. Ande devagar para a SUA DIREITA dando UMA volta completa (ou gire o objeto sobre uma mesa).\n" +
                        "3. Mantenha a mesma distância e altura.\n" +
                        "4. 10 a 20 segundos bastam. Fundo liso ajuda.\n\n" +
                        "Eu descubro sozinho onde a volta termina e escolho os melhores quadros.",
                    fontSize = 14.sp,
                )
            },
            confirmButton = { Button(onClick = { showVideoTips = false; openVideoCamera() }) { Text("Gravar") } },
            dismissButton = {
                TextButton(onClick = { showVideoTips = false; pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                    Text("Escolher da galeria")
                }
            },
        )
    }

    // ------------------------------------------------ nuvem grátis
    if (showCloud) {
        AlertDialog(
            onDismissRequest = { showCloud = false },
            icon = { Icon(Icons.Default.Star, null) },
            title = { Text("Qualidade máxima na nuvem") },
            text = {
                Text(
                    "Gera o modelo com o TRELLIS (IA de código aberto da Microsoft), que \"imagina\" os lados e as costas, como o Meshy e o Tripo.\n\n" +
                        "• É grátis: roda no Hugging Face, numa GPU pública.\n" +
                        "• Só o recorte do objeto é enviado (sem o fundo), e o envio é para um serviço público de terceiros. Não use fotos que você não queira compartilhar.\n" +
                        "• Leva de 30 s a 3 min (pode haver fila) e há um limite diário gratuito.\n" +
                        "• Precisa de internet. O modo do aparelho continua funcionando offline.",
                    fontSize = 14.sp,
                )
            },
            confirmButton = { Button(onClick = { showCloud = false; vm.cloudGenerate() }) { Text("Gerar na nuvem") } },
            dismissButton = { TextButton(onClick = { showCloud = false }) { Text("Agora não") } },
        )
    }

    if (showToken) {
        var t by remember { mutableStateOf(vm.hfToken) }
        AlertDialog(
            onDismissRequest = { showToken = false },
            title = { Text("Token do Hugging Face (opcional)") },
            text = {
                Column {
                    Text(
                        "Sem token, a cota diária gratuita é menor e compartilhada. Com uma conta grátis em huggingface.co → Settings → Access Tokens (tipo \"Read\"), a cota fica sua. O token fica salvo só neste aparelho.",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, placeholder = { Text("hf_…") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { vm.saveHfToken(t); showToken = false; snack(if (t.isBlank()) "Token removido." else "Token salvo.") }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { showToken = false }) { Text("Cancelar") } },
        )
    }

    // ------------------------------------------------ exportação
    if (showExport) {
        ModalBottomSheet(onDismissRequest = { showExport = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Exportar modelo 3D", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Vai para Downloads/Vorticis3D. Depois, toque em \"Compartilhar\" para enviar.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { showExport = false; vm.export(null) },
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("📦  Todos os formatos (ZIP)", fontWeight = FontWeight.Bold)
                        Text(ExportFormat.entries.joinToString(" • ") { it.ext.uppercase() }, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(ExportFormat.entries) { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showExport = false; vm.export(f) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                                Text(f.ext.uppercase(), Modifier.width(56.dp).padding(vertical = 6.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(f.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(f.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Entendi") } },
            title = { Text("Como usar") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item { Text(com.vorticis.studio3d.core.mind.VorticisMind().help(), fontSize = 13.sp) }
                }
            },
        )
    }

    // ------------------------------------------------ atualização
    vm.update?.let { info ->
        AlertDialog(
            onDismissRequest = { if (vm.updateProgress == null) vm.dismissUpdate() },
            title = { Text("Nova versão ${info.versionName}") },
            text = {
                Column {
                    Text("Você está na ${AppUpdater.currentVersionName}. Quer atualizar agora? O download tem ${info.sizeBytes / (1024 * 1024)} MB.", fontSize = 14.sp)
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    vm.updateProgress?.let { p ->
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Baixando… ${(p * 100).toInt()}%", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { Button(onClick = vm::installUpdate, enabled = vm.updateProgress == null) { Text("Atualizar") } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate, enabled = vm.updateProgress == null) { Text("Depois") } },
        )
    }
}

@Composable
private fun AttachRow(icon: Painter, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoControls(vm: StudioViewModel, modifier: Modifier, onAddCamera: () -> Unit, onAddGallery: () -> Unit, onCloud: () -> Unit) {
    Column(modifier.padding(bottom = 8.dp)) {
        if (vm.source != Source.CLOUD) {
            Button(
                onClick = onCloud,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Default.Star, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Qualidade máxima (nuvem grátis)", fontWeight = FontWeight.SemiBold)
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                Surface(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        when { vm.source == Source.VIDEO -> "🎬 ${vm.photoCount} quadros"; vm.photoCount > 1 -> "📷 ${vm.photoCount} fotos"; else -> "📷 1 foto" },
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 12.sp, color = Color.White,
                    )
                }
            }
            item {
                AssistChip(
                    onClick = onAddGallery,
                    label = { Text("+ Ângulos da galeria", fontSize = 12.sp, color = Color.White) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_gallery), null, Modifier.size(16.dp), tint = Color.White) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            }
            item {
                AssistChip(
                    onClick = onAddCamera,
                    label = { Text("Câmera", fontSize = 12.sp, color = Color.White) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(16.dp), tint = Color.White) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xCC242833)),
                )
            }
            if (vm.photoCount > 1) item {
                AssistChip(onClick = vm::removeLastPhoto, label = { Text("Remover última", fontSize = 12.sp) }, colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xCC242833)))
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            if (vm.photoCount > 1) {
                items(CaptureLayout.entries) { l ->
                    FilterChip(
                        selected = vm.captureLayout == l && vm.photoMode == PhotoMode.MULTIVIEW,
                        onClick = { vm.selectCaptureLayout(l) },
                        label = { Text(l.label, fontSize = 12.sp) },
                    )
                }
                item {
                    FilterChip(selected = vm.photoMode != PhotoMode.MULTIVIEW, onClick = { vm.selectPhotoMode(PhotoMode.AUTO) }, label = { Text("Só a 1ª", fontSize = 12.sp) })
                }
            } else {
                items(PhotoMode.entries.filter { it != PhotoMode.MULTIVIEW }) { m ->
                    FilterChip(selected = vm.photoMode == m, onClick = { vm.selectPhotoMode(m) }, label = { Text(m.label, fontSize = 12.sp) })
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(
    vm: StudioViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    input: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoice: () -> Unit,
    onAttach: () -> Unit,
    inputFocus: FocusRequester,
    showSuggestions: Boolean,
) {
    val listState = rememberLazyListState()
    val count = vm.messages.size + if (vm.busy != null) 1 else 0
    LaunchedEffect(count, expanded) { if (count > 0) listState.animateScrollToItem(count - 1) }

    Surface(
        Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column {
            // alça + título
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.logo_vorticis), null, Modifier.size(22.dp).clip(CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Vorticis Mind", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (vm.busy != null) Color(0xFFFFB74D) else Color(0xFF4ADE80)))
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "recolher" else "ver conversa", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = if (expanded) 300.dp else 112.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val shown = if (expanded) vm.messages.toList() else vm.messages.takeLast(1)
                items(shown) { m -> Bubble(m, compact = !expanded) }
                if (vm.busy != null) item { Bubble(ChatMessage(false, "…  ${vm.busy}"), compact = true, typing = true) }
            }
            AnimatedVisibility(showSuggestions) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(vm.suggestions) { s ->
                        AssistChip(
                            onClick = { vm.send(s) },
                            label = { Text(s, fontSize = 12.sp) },
                            shape = RoundedCornerShape(50),
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, labelColor = MaterialTheme.colorScheme.onSurface),
                            border = null,
                        )
                    }
                }
            }
            // entrada
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 8.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(onClick = onAttach) { Icon(Icons.Default.Add, "Foto, várias fotos ou vídeo") }
                Spacer(Modifier.width(6.dp))
                TextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f).focusRequester(inputFocus),
                    placeholder = { Text("Descreva ou peça uma alteração", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                )
                Spacer(Modifier.width(6.dp))
                if (input.isBlank()) {
                    IconButton(onClick = onVoice) { Icon(painterResource(R.drawable.ic_mic), "Falar") }
                } else {
                    FilledIconButton(onClick = onSubmit, enabled = vm.busy == null) { Icon(Icons.Default.Send, "Enviar") }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, compact: Boolean, typing: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        Surface(
            color = when {
                m.fromUser -> MaterialTheme.colorScheme.primary
                typing -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (m.fromUser) 16.dp else 4.dp, bottomEnd = if (m.fromUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            Text(
                m.text,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 14.sp, lineHeight = 19.sp,
                color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = if (compact) 4 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
