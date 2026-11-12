package com.vorticis.studio3d.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.vorticis.studio3d.R
import com.vorticis.studio3d.StudioViewModel
import com.vorticis.studio3d.UiEvent
import com.vorticis.studio3d.core.export.ExportFormat
import com.vorticis.studio3d.core.photo.CaptureLayout
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.gl.ModelSurfaceView
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(vm: StudioViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var showExport by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var appendNext by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.onPhoto(uri, append = appendNext)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(16)) { uris ->
        if (uris.isNotEmpty()) vm.onPhotos(uris, append = appendNext)
    }
    fun openGallery(append: Boolean) {
        appendNext = append
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.send(text)
    }

    fun openCamera(append: Boolean = false) {
        appendNext = append
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        cameraUri = uri
        try { takePicture.launch(uri) } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Nenhum app de câmera encontrado. Use a galeria.") }
        }
    }

    fun openVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o que criar ou alterar")
        }
        try { voice.launch(i) } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Reconhecimento de voz indisponível neste aparelho.") }
        }
    }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is UiEvent.Share -> context.startActivity(e.intent)
                is UiEvent.OpenCamera -> openCamera(false)
                is UiEvent.OpenExport -> showExport = true
                is UiEvent.Snack -> scope.launch {
                    val r = snackbar.showSnackbar(e.text, actionLabel = if (e.share != null) "Compartilhar" else null, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed && e.share != null) vm.share(e.share)
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_cube), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Vorticis 3D Studio", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("IA no aparelho • offline", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(painterResource(R.drawable.ic_undo), "Desfazer") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(painterResource(R.drawable.ic_redo), "Refazer") }
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Default.Info, "Ajuda") }
                    IconButton(onClick = { showExport = true }) { Icon(painterResource(R.drawable.ic_download), "Exportar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            // ------------------------------------------------ visualizador 3D
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { ModelSurfaceView(it) },
                    update = { it.setScene(vm.scene) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!vm.scene.isEmpty) {
                    Surface(
                        Modifier.padding(10.dp).align(Alignment.TopStart),
                        color = Color(0xAA000000), shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "${vm.scene.groups().size} objeto(s) • ${vm.scene.nodes.size} peças • ${"%,d".format(vm.scene.triangleCount)} triângulos",
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, color = Color.White,
                        )
                    }
                    IconButton(onClick = vm::clear, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Default.Delete, "Limpar cena", tint = Color(0xCCFFFFFF))
                    }
                } else {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(painterResource(R.drawable.ic_cube), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Descreva um objeto ou tire uma foto.\nArraste para girar • pinça para zoom • toque duplo para centralizar",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        )
                    }
                }
                vm.busy?.let { msg ->
                    Surface(
                        Modifier.align(Alignment.Center), color = Color(0xDD10131A), shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(msg, color = Color.White)
                        }
                    }
                }
                if (vm.hasPhoto) {
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                        // barra de ângulos: várias fotos do mesmo objeto
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item {
                                Surface(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        if (vm.photoCount > 1) "${vm.photoCount} fotos" else "1 foto",
                                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 12.sp, color = Color.White,
                                    )
                                }
                            }
                            item {
                                AssistChip(
                                    onClick = { openCamera(true) },
                                    label = { Text("+ Ângulo (câmera)", fontSize = 12.sp) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(16.dp)) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { openGallery(true) },
                                    label = { Text("+ Da galeria", fontSize = 12.sp) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xCC242833)),
                                )
                            }
                            if (vm.photoCount > 1) item {
                                AssistChip(
                                    onClick = vm::removeLastPhoto,
                                    label = { Text("Remover última", fontSize = 12.sp) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xCC242833)),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // modos: com uma foto escolhe a técnica; com várias, como foram tiradas
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            if (vm.photoCount > 1) {
                                items(CaptureLayout.entries) { l ->
                                    FilterChip(
                                        selected = vm.captureLayout == l && vm.photoMode == PhotoMode.MULTIVIEW,
                                        onClick = { vm.selectCaptureLayout(l) },
                                        label = { Text(l.label, fontSize = 12.sp) },
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = vm.photoMode != PhotoMode.MULTIVIEW,
                                        onClick = { vm.selectPhotoMode(PhotoMode.AUTO) },
                                        label = { Text("Só a 1ª foto", fontSize = 12.sp) },
                                    )
                                }
                            } else {
                                items(PhotoMode.entries.filter { it != PhotoMode.MULTIVIEW }) { m ->
                                    FilterChip(
                                        selected = vm.photoMode == m,
                                        onClick = { vm.selectPhotoMode(m) },
                                        label = { Text(m.label, fontSize = 12.sp) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ conversa
            val listState = rememberLazyListState()
            LaunchedEffect(vm.messages.size) { if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 170.dp)
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(vm.messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.widthIn(max = 320.dp),
                        ) {
                            Text(m.text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
                        }
                    }
                }
            }
            HorizontalDivider()
            LazyRow(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(vm.suggestions) { s ->
                    AssistChip(
                        onClick = { vm.send(s) },
                        label = { Text(s, fontSize = 12.sp) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
            // ------------------------------------------------ entrada
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { openCamera(false) }) { Icon(painterResource(R.drawable.ic_camera), "Tirar foto (nova)") }
                IconButton(onClick = { openGallery(false) }) {
                    Icon(painterResource(R.drawable.ic_gallery), "Escolher uma ou várias fotos")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Descreva ou peça uma alteração…", fontSize = 13.sp) },
                    maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" }),
                )
                if (input.isBlank()) {
                    IconButton(onClick = ::openVoice) { Icon(painterResource(R.drawable.ic_mic), "Falar") }
                } else {
                    FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = vm.busy == null) {
                        Icon(Icons.Default.Send, "Enviar")
                    }
                }
            }
        }
    }

    if (showExport) {
        ModalBottomSheet(onDismissRequest = { showExport = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Exportar modelo 3D", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Os arquivos vão para Downloads/Vorticis3D. Depois toque em \"Compartilhar\" para enviar.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showExport = false; vm.export(null) },
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Exportar TODOS os formatos (ZIP)", fontWeight = FontWeight.Bold)
                        Text(ExportFormat.entries.joinToString(" • ") { it.ext.uppercase() }, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(ExportFormat.entries) { f ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showExport = false; vm.export(f) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(".${f.ext}  —  ${f.label}", fontWeight = FontWeight.SemiBold)
                            Text(f.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
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
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item { Text(com.vorticis.studio3d.core.mind.VorticisMind().help(), fontSize = 13.sp) }
                }
            },
        )
    }
}
