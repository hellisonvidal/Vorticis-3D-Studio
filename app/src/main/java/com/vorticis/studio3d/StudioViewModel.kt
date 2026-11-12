package com.vorticis.studio3d

import android.app.Application
import android.content.Context
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
import com.vorticis.studio3d.core.photo.PhotoOptions
import com.vorticis.studio3d.core.photo.PhotoTo3D
import com.vorticis.studio3d.core.photo.PhotoView
import com.vorticis.studio3d.core.photo.VideoLoop
import com.vorticis.studio3d.export.ExportManager
import com.vorticis.studio3d.photo.AndroidTextRasterizer
import com.vorticis.studio3d.photo.PhotoInput
import com.vorticis.studio3d.photo.PhotoLoader
import com.vorticis.studio3d.update.AppUpdater
import com.vorticis.studio3d.update.UpdateInfo
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
    data class Launch(val intent: Intent) : UiEvent
    data class Snack(val text: String, val share: ExportFile? = null) : UiEvent
    data object OpenCamera : UiEvent
    data object OpenExport : UiEvent
}

/** De onde veio o modelo atual (define as sugestões da conversa). */
enum class Source { NONE, TEXT, PHOTO, MULTI, VIDEO }

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    private val mind = VorticisMind(AndroidTextRasterizer())
    private val prefs = app.getSharedPreferences("vorticis", Context.MODE_PRIVATE)

    var scene by mutableStateOf(Scene())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var photoMode by mutableStateOf(PhotoMode.AUTO)
        private set
    var hasPhoto by mutableStateOf(false)
        private set
    var photoCount by mutableStateOf(0)
        private set
    var captureLayout by mutableStateOf(CaptureLayout.AROUND)
        private set
    var source by mutableStateOf(Source.NONE)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    var segmentationReady by mutableStateOf(false)
        private set

    val messages = mutableStateListOf(
        ChatMessage(false, "Oi! 👋 Eu sou a Vorticis Mind e transformo ideias em 3D, aqui mesmo no seu celular.\n\n" +
            "• Escreva o que quer criar, ex.: \"foguete vermelho com 3 aletas\"\n" +
            "• Ou toque em ＋ para usar foto, várias fotos ou vídeo."),
    )

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val history = ArrayList<Scene>().apply { add(Scene()) }
    private var historyIndex = 0
    private val photos = ArrayList<PhotoInput>()
    private val photo: PhotoInput? get() = photos.firstOrNull()
    private var photoThickness = 1f

    init {
        // baixa a IA de recorte do Google em segundo plano, na primeira abertura
        viewModelScope.launch {
            segmentationReady = PhotoLoader.ensureModel(getApplication())
        }
        // verifica atualização no máximo a cada 6 horas
        val last = prefs.getLong("lastUpdateCheck", 0)
        if (System.currentTimeMillis() - last > 6 * 3600_000L) checkUpdate(silent = true)
    }

    /** Sugestões que mudam conforme o que está na tela. */
    val suggestions: List<String>
        get() = when {
            scene.isEmpty -> listOf(
                "mesa redonda de madeira com 4 cadeiras", "foguete vermelho com 3 aletas", "casa azul com telhado vermelho",
                "texto 'OLÁ' dourado", "castelo com 6 torres", "esfera dourada em cima de um cubo de mármore", "o que você sabe fazer?",
            )
            source == Source.PHOTO -> listOf(
                "foto com mais volume", "foto com menos volume", "transforme a foto em vaso", "foto em relevo",
                "extrusão da foto", "pinte de dourado", "exportar",
            )
            source == Source.MULTI || source == Source.VIDEO -> listOf(
                "fotos no sentido inverso", "suavize", "pinte de dourado", "deixe 2 vezes maior", "exportar",
            )
            else -> listOf(
                "pinte de azul", "deixe 2 vezes maior", "gire 45 graus", "deixe low poly", "adicione uma árvore ao lado",
                "duplique", "desfazer", "exportar",
            )
        }

    private fun say(text: String) { messages += ChatMessage(false, text) }

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

    // ------------------------------------------------------------------ conversa
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy != null) return
        messages += ChatMessage(true, t)
        busy = "Pensando…"
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { mind.interpret(t, scene) }
                when (res.special) {
                    Special.UNDO -> { undo(); say(if (canUndo || canRedo) "Pronto, voltei um passo. ↩️" else "Não há o que desfazer.") }
                    Special.REDO -> { redo(); say("Refeito. ↪️") }
                    Special.EXPORT -> { say("Abrindo a exportação: escolha um formato ou exporte todos de uma vez. 📦"); events.send(UiEvent.OpenExport) }
                    Special.PHOTO_REQUEST -> { say("Abrindo a câmera. 📷"); events.send(UiEvent.OpenCamera) }
                    else -> say(res.message)
                }
                val requestedMode = res.photoMode
                if (requestedMode != null) {
                    if (photo == null) {
                        say("Ainda não tenho uma foto. Toque em ＋ e escolha Câmera, Galeria ou Vídeo.")
                    } else {
                        res.photoThickness?.let { photoThickness = (photoThickness * it).coerceIn(0.3f, 3f) }
                        res.photoLayout?.let { captureLayout = it }
                        rebuildPhoto(if (requestedMode == PhotoMode.MULTIVIEW && photos.size < 2) photoMode else requestedMode)
                    }
                } else if (res.changed && res.special == null) {
                    commit(res.scene)
                    if (source == Source.NONE || res.scene.nodes.none { it.kind == "foto" }) source = if (res.scene.isEmpty) Source.NONE else Source.TEXT
                }
            } catch (e: Throwable) {
                say("Ops, algo deu errado: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    // ------------------------------------------------------------------ fotos
    /** Uma foto: [append] = true acrescenta um ângulo à sessão atual. */
    fun onPhoto(uri: Uri, append: Boolean = false) = onPhotos(listOf(uri), append)

    private suspend fun segmentAll(bitmaps: List<android.graphics.Bitmap>, label: String): List<PhotoInput> {
        if (!segmentationReady) {
            busy = "Baixando a IA de recorte (só na 1ª vez)…"
            segmentationReady = PhotoLoader.ensureModel(getApplication(), waitMs = 25_000)
        }
        val out = ArrayList<PhotoInput>()
        for ((i, b) in bitmaps.withIndex()) {
            busy = if (bitmaps.size > 1) "Recortando $label ${i + 1} de ${bitmaps.size}…" else "Separando o objeto do fundo…"
            out += withContext(Dispatchers.Default) { PhotoLoader.segment(b) }
            b.recycle()
        }
        return out
    }

    /** Uma ou várias fotos (galeria com seleção múltipla, compartilhamento ou "+ Ângulo"). */
    fun onPhotos(uris: List<Uri>, append: Boolean = false) {
        if (busy != null || uris.isEmpty()) return
        val list = uris.take(16)
        busy = "Abrindo ${if (list.size > 1) "${list.size} fotos" else "a foto"}…"
        messages += ChatMessage(true, if (list.size > 1) "📷 ${list.size} fotos" else if (append) "📷 Novo ângulo" else "📷 Foto")
        viewModelScope.launch {
            try {
                val bitmaps = withContext(Dispatchers.IO) { list.map { PhotoLoader.load(getApplication(), it) } }
                val inputs = segmentAll(bitmaps, "foto")
                if (!append) { photos.clear(); photoThickness = 1f }
                photos += inputs
                if (photos.size > 16) photos.subList(0, photos.size - 16).clear()
                hasPhoto = true
                photoCount = photos.size
                say("Recortei ${if (inputs.size > 1) "${inputs.size} fotos" else "o objeto"} usando ${inputs.map { it.segmentedBy }.distinct().joinToString(" e ")}.")
                if (photos.size >= 2) {
                    rebuildPhoto(PhotoMode.MULTIVIEW)
                } else {
                    rebuildPhoto(if (photoMode == PhotoMode.MULTIVIEW) PhotoMode.AUTO else photoMode)
                    say("💡 Para o objeto inteiro, com o verso real: toque em \"+ Ângulo\" e fotografe andando para a SUA DIREITA ao redor dele, na mesma distância e altura. 4 fotos (frente, lado, trás, outro lado) ou 8 em volta. Ou grave um vídeo dando a volta.")
                }
            } catch (e: Throwable) {
                say("Não consegui processar a foto: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    /** Vídeo curto dando uma volta no objeto (ou o objeto girando na frente da câmera). */
    fun onVideo(uri: Uri) {
        if (busy != null) return
        busy = "Lendo o vídeo…"
        messages += ChatMessage(true, "🎬 Vídeo")
        viewModelScope.launch {
            try {
                val frames = withContext(Dispatchers.IO) {
                    PhotoLoader.videoFrames(getApplication(), uri, count = 30) { i, n -> busy = "Extraindo quadro $i de $n…" }
                }
                val inputs = segmentAll(frames, "quadro")
                busy = "Procurando a volta completa…"
                val plan = withContext(Dispatchers.Default) {
                    VideoLoop.plan(inputs.map { PhotoView(it.pixels, it.width, it.height, it.mask) })
                }
                photos.clear(); photoThickness = 1f
                photos += plan.frames.map { inputs[it] }
                hasPhoto = true
                photoCount = photos.size
                captureLayout = if (captureLayout == CaptureLayout.ORTHO) CaptureLayout.AROUND else captureLayout
                say(plan.explanation(inputs.size))
                rebuildPhoto(PhotoMode.MULTIVIEW)
                source = Source.VIDEO
            } catch (e: Throwable) {
                say("Não consegui usar o vídeo: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    fun selectPhotoMode(mode: PhotoMode) {
        photoMode = mode
        if (photo != null && busy == null) {
            viewModelScope.launch { try { rebuildPhoto(mode) } finally { busy = null } }
        }
    }

    fun selectCaptureLayout(layout: CaptureLayout) {
        captureLayout = layout
        if (photos.size >= 2 && busy == null) {
            viewModelScope.launch { try { rebuildPhoto(PhotoMode.MULTIVIEW) } finally { busy = null } }
        }
    }

    fun removeLastPhoto() {
        if (photos.size <= 1 || busy != null) return
        photos.removeAt(photos.size - 1)
        photoCount = photos.size
        say("Tirei a última foto. Agora são ${photos.size}.")
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
        if (source != Source.VIDEO || !multi) source = if (multi) Source.MULTI else Source.PHOTO
        val tip = if (multi) "Se o formato ficou estranho, confira o sentido da volta nos botões abaixo do modelo."
        else "Experimente os modos abaixo do modelo ou diga \"foto com mais volume\"."
        say("✨ ${result.explanation}\n$tip")
    }

    // ------------------------------------------------------------------ exportação
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
        source = Source.NONE
        say("Cena limpa. O que vamos criar agora? 🧊")
    }

    // ------------------------------------------------------------------ atualização
    fun checkUpdate(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { AppUpdater.check() }
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                update = info
                if (info == null && !silent) events.send(UiEvent.Snack("Você já está na versão mais recente (${AppUpdater.currentVersionName})."))
            } catch (e: Throwable) {
                if (!silent) events.send(UiEvent.Snack("Não consegui verificar atualizações: ${e.message ?: "sem internet?"}"))
            }
        }
    }

    fun dismissUpdate() { update = null }

    fun installUpdate() {
        val info = update ?: return
        val ctx = getApplication<Application>()
        if (AppUpdater.needsInstallPermission(ctx)) {
            viewModelScope.launch {
                events.send(UiEvent.Snack("Permita \"instalar apps desconhecidos\" para o Vorticis e toque em Atualizar de novo."))
                events.send(UiEvent.Launch(AppUpdater.permissionIntent(ctx)))
            }
            return
        }
        if (updateProgress != null) return
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { AppUpdater.download(ctx, info) { p -> updateProgress = p } }
                update = null
                events.send(UiEvent.Launch(AppUpdater.installIntent(ctx, apk)))
            } catch (e: Throwable) {
                events.send(UiEvent.Snack("Falha ao baixar a atualização: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                updateProgress = null
            }
        }
    }
}
