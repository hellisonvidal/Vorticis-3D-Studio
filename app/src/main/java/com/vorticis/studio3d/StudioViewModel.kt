package com.vorticis.studio3d

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vorticis.studio3d.core.export.ExportFile
import com.vorticis.studio3d.core.export.ExportFormat
import com.vorticis.studio3d.core.export.Exporters
import com.vorticis.studio3d.core.mind.Special
import com.vorticis.studio3d.core.mind.VorticisMind
import com.vorticis.studio3d.core.model.Node
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.photo.CaptureLayout
import com.vorticis.studio3d.core.photo.MultiView
import com.vorticis.studio3d.core.photo.MultiViewOptions
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.core.photo.PhotoView
import com.vorticis.studio3d.core.photo.PhotoOptions
import com.vorticis.studio3d.core.photo.PhotoTo3D
import com.vorticis.studio3d.export.ExportManager
import com.vorticis.studio3d.photo.AndroidTextRasterizer
import com.vorticis.studio3d.photo.PhotoInput
import com.vorticis.studio3d.photo.PhotoLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(val fromUser: Boolean, val text: String)

sealed interface UiEvent {
    data class Share(val intent: Intent) : UiEvent
    data class Snack(val text: String, val share: ExportFile? = null) : UiEvent
    data object OpenCamera : UiEvent
    data object OpenExport : UiEvent
}

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    private val mind = VorticisMind(AndroidTextRasterizer())

    var scene by mutableStateOf(Scene())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var photoMode by mutableStateOf(PhotoMode.AUTO)
        private set
    var hasPhoto by mutableStateOf(false)
        private set
    /** quantas fotos (ângulos) estão na sessão atual */
    var photoCount by mutableStateOf(0)
        private set
    var captureLayout by mutableStateOf(CaptureLayout.AROUND)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val messages = mutableStateListOf(
        ChatMessage(false, "Olá! Sou a Vorticis Mind. Descreva o que quer criar (\"um foguete vermelho com 4 aletas\") ou tire uma foto de um objeto. Tudo roda no seu celular."),
    )

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val history = ArrayList<Scene>().apply { add(Scene()) }
    private var historyIndex = 0
    private val photos = ArrayList<PhotoInput>()
    private val photo: PhotoInput? get() = photos.firstOrNull()
    private var photoThickness = 1f

    val suggestions = listOf(
        "mesa redonda de madeira com 4 cadeiras",
        "casa azul de 2 andares com telhado vermelho",
        "foguete vermelho com 3 aletas",
        "texto 'VORTICIS' dourado",
        "esfera dourada em cima de um cubo de mármore",
        "árvore low poly",
        "castelo com 6 torres",
        "pinte de verde",
        "deixe 2 vezes maior",
        "gire 45 graus",
        "ajuda",
    )

    private fun commit(s: Scene) {
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        history.add(s)
        if (history.size > 40) history.removeAt(0)
        historyIndex = history.size - 1
        scene = s
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        canUndo = historyIndex > 0
        canRedo = historyIndex < history.size - 1
    }

    fun undo() {
        if (historyIndex > 0) { historyIndex--; scene = history[historyIndex]; updateHistoryFlags() }
    }

    fun redo() {
        if (historyIndex < history.size - 1) { historyIndex++; scene = history[historyIndex]; updateHistoryFlags() }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy != null) return
        messages += ChatMessage(true, t)
        busy = "Pensando…"
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { mind.interpret(t, scene) }
                messages += ChatMessage(false, res.message)
                when (res.special) {
                    Special.UNDO -> undo()
                    Special.REDO -> redo()
                    Special.EXPORT -> events.send(UiEvent.OpenExport)
                    Special.PHOTO_REQUEST -> events.send(UiEvent.OpenCamera)
                    else -> {}
                }
                val requestedMode = res.photoMode
                if (requestedMode != null) {
                    if (photo == null) {
                        messages += ChatMessage(false, "Ainda não há foto. Toque no botão da câmera ou da galeria primeiro.")
                    } else {
                        res.photoThickness?.let { photoThickness = (photoThickness * it).coerceIn(0.3f, 3f) }
                        res.photoLayout?.let { captureLayout = it }
                        rebuildPhoto(if (requestedMode == PhotoMode.MULTIVIEW && photos.size < 2) photoMode else requestedMode)
                    }
                } else if (res.changed) commit(res.scene)
            } catch (e: Throwable) {
                messages += ChatMessage(false, "Ops, algo deu errado: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    /** Uma foto: [append] = true acrescenta um ângulo à sessão atual (várias fotos). */
    fun onPhoto(uri: Uri, append: Boolean = false) = onPhotos(listOf(uri), append)

    /** Uma ou várias fotos (galeria com seleção múltipla ou "+ Ângulo"). */
    fun onPhotos(uris: List<Uri>, append: Boolean = false) {
        if (busy != null || uris.isEmpty()) return
        val list = uris.take(16)
        busy = "Analisando ${if (list.size > 1) "${list.size} fotos" else "a foto"}…"
        messages += ChatMessage(true, if (list.size > 1) "📷 ${list.size} fotos enviadas" else if (append) "📷 Novo ângulo" else "📷 Foto enviada")
        viewModelScope.launch {
            try {
                val inputs = ArrayList<PhotoInput>()
                for ((i, uri) in list.withIndex()) {
                    busy = if (list.size > 1) "Recortando foto ${i + 1} de ${list.size}…" else "Separando o objeto do fundo…"
                    inputs += withContext(Dispatchers.IO) {
                        val bmp = PhotoLoader.load(getApplication(), uri)
                        PhotoLoader.segment(bmp).also { bmp.recycle() }
                    }
                }
                if (!append) { photos.clear(); photoThickness = 1f }
                photos += inputs
                if (photos.size > 16) photos.subList(0, photos.size - 16).clear()
                hasPhoto = true
                photoCount = photos.size
                val how = inputs.map { it.segmentedBy }.distinct().joinToString(" e ")
                messages += ChatMessage(false, "Recortei ${if (inputs.size > 1) "${inputs.size} fotos" else "o objeto"} usando $how.")
                if (photos.size >= 2) rebuildPhoto(PhotoMode.MULTIVIEW)
                else {
                    rebuildPhoto(if (photoMode == PhotoMode.MULTIVIEW) PhotoMode.AUTO else photoMode)
                    messages += ChatMessage(false, "Quer o objeto inteiro, com o verso real? Toque em \"+ Ângulo\" e fotografe andando para a SUA DIREITA ao redor dele (mesma distância e altura): 4 fotos (frente, lado, trás, outro lado) ou 8 em volta.")
                }
            } catch (e: Throwable) {
                messages += ChatMessage(false, "Não consegui processar a foto: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    fun selectPhotoMode(mode: PhotoMode) {
        photoMode = mode
        if (photo != null && busy == null) {
            viewModelScope.launch {
                try { rebuildPhoto(mode) } finally { busy = null }
            }
        }
    }

    fun selectCaptureLayout(layout: CaptureLayout) {
        captureLayout = layout
        if (photos.size >= 2 && busy == null) {
            viewModelScope.launch {
                try { rebuildPhoto(PhotoMode.MULTIVIEW) } finally { busy = null }
            }
        }
    }

    /** Remove o último ângulo adicionado. */
    fun removeLastPhoto() {
        if (photos.size <= 1 || busy != null) return
        photos.removeAt(photos.size - 1)
        photoCount = photos.size
        messages += ChatMessage(false, "Removi a última foto. Agora são ${photos.size}.")
        viewModelScope.launch {
            try { rebuildPhoto(if (photos.size >= 2) PhotoMode.MULTIVIEW else PhotoMode.AUTO) } finally { busy = null }
        }
    }

    private suspend fun rebuildPhoto(mode: PhotoMode) {
        val p = photo ?: return
        busy = "Modelando em 3D…"
        val multi = mode == PhotoMode.MULTIVIEW && photos.size >= 2
        photoMode = if (multi) PhotoMode.MULTIVIEW else mode
        val snapshot = photos.toList()
        val result = withContext(Dispatchers.Default) {
            if (multi) MultiView.build(snapshot.map { PhotoView(it.pixels, it.width, it.height, it.mask) }, MultiViewOptions(layout = captureLayout))
            else PhotoTo3D.build(p.pixels, p.width, p.height, p.mask, PhotoOptions(mode = mode, thickness = photoThickness))
        }
        val node = Node("Foto_${result.mode.name.lowercase()}", result.mesh, result.material, 1, "foto", "corpo")
        commit(Scene(listOf(node), nextGroup = 2))
        val tip = if (multi) "Se o formato ficou estranho, confira o sentido da volta nos botões abaixo, ou adicione mais ângulos."
        else "Diga \"pinte de azul\", \"mais volume\" ou troque o modo abaixo."
        messages += ChatMessage(false, "${result.explanation} (${result.mesh.triangleCount} triângulos). $tip")
    }

    private fun baseName(): String {
        val kind = scene.nodes.firstOrNull()?.kind ?: "modelo"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "vorticis_${kind}_$stamp"
    }

    /** Exporta um formato (ou todos, quando [format] é null) e salva em Downloads. */
    fun export(format: ExportFormat?) {
        if (scene.isEmpty) {
            viewModelScope.launch { events.send(UiEvent.Snack("Crie um modelo antes de exportar.")) }
            return
        }
        if (busy != null) return
        busy = "Gerando ${format?.ext?.uppercase() ?: "todos os formatos"}…"
        val s = scene
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.Default) {
                    if (format == null) Exporters.exportAll(s, baseName()) else Exporters.export(s, format, baseName())
                }
                val where = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(getApplication(), file) }
                events.send(UiEvent.Snack("Salvo em $where (${file.bytes.size / 1024} KB)", share = file))
            } catch (e: Throwable) {
                events.send(UiEvent.Snack("Erro ao exportar: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                busy = null
            }
        }
    }

    fun share(file: ExportFile) {
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) { ExportManager.shareIntent(getApplication(), file) }
            events.send(UiEvent.Share(intent))
        }
    }

    fun clear() {
        commit(Scene(nextGroup = scene.nextGroup))
        messages += ChatMessage(false, "Cena limpa.")
    }
}
