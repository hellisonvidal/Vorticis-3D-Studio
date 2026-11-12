package com.vorticis.studio3d.core.cloud

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Cliente HTTP do Gradio (API dos "Spaces" do Hugging Face), sem dependências:
 * upload de arquivo -> POST /call/<api> -> acompanha o evento (SSE) -> baixa os arquivos.
 * Funciona com Gradio 4 (prefixo "") e Gradio 5 (prefixo "/gradio_api").
 */
class GradioClient(val space: String, private val token: String? = null) {

    /** Progresso: mensagem amigável e fração 0..1 (ou null quando indeterminado). */
    fun interface Progress { fun update(message: String, fraction: Float?) }

    class SpaceException(message: String) : IOException(message)

    /** Sessão compartilhada entre chamadas (alguns espaços guardam estado por sessão). */
    val sessionHash: String = UUID.randomUUID().toString().replace("-", "").take(11)
    private var config: Map<String, Any?> = emptyMap()

    val src: String = "https://" + space.lowercase().replace('/', '-').replace('.', '-').replace('_', '-') + ".hf.space"
    private var prefix: String? = null

    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        c.setRequestProperty("User-Agent", "Vorticis3DStudio")
        token?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return c
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val body = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        if (code !in 200..299) throw SpaceException("O serviço respondeu $code: ${body.take(300)}")
        return body
    }

    /** Descobre o prefixo da API (e acorda o espaço, se estiver dormindo). */
    fun connect(progress: Progress? = null): String {
        prefix?.let { return it }
        var last: Exception? = null
        repeat(12) { attempt ->
            try {
                val cfg = Json.parse(read(open("$src/config"))).obj()
                config = cfg
                val p = (cfg["api_prefix"] as? String)?.trimEnd('/') ?: ""
                prefix = if (p.isEmpty() || p == "/") "" else (if (p.startsWith("/")) p else "/$p")
                return prefix!!
            } catch (e: Exception) {
                last = e
                progress?.update("Acordando o servidor de IA (${attempt + 1})…", null)
                Thread.sleep(10_000)
            }
        }
        throw SpaceException("Servidor indisponível: ${last?.message}")
    }

    /** Envia um arquivo e devolve o objeto FileData que as funções do Gradio esperam. */
    fun upload(bytes: ByteArray, filename: String, mime: String): Map<String, Any?> {
        val p = connect()
        val boundary = "----vorticis" + UUID.randomUUID().toString().replace("-", "")
        val c = open("$src$p/upload", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { o ->
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            o.write(bytes)
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val path = Json.parse(read(c)).list().firstOrNull() as? String ?: throw SpaceException("Upload sem resposta")
        return mapOf("path" to path, "orig_name" to filename, "mime_type" to mime, "meta" to mapOf("_type" to "gradio.FileData"))
    }

    /** Chama uma função do espaço e espera o resultado (lista de saídas). */
    fun call(api: String, data: List<Any?>, progress: Progress? = null, timeoutMs: Long = 15 * 60_000L): List<Any?> {
        val p = connect(progress)
        val name = api.trimStart('/')
        val c = open("$src$p/call/$name", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(Json.stringify(mapOf("data" to data)).toByteArray()) }
        val eventId = Json.parse(read(c)).obj()["event_id"] as? String ?: throw SpaceException("Sem event_id")

        val s = open("$src$p/call/$name/$eventId")
        s.readTimeout = timeoutMs.toInt()
        val code = s.responseCode
        if (code !in 200..299) throw SpaceException("O serviço respondeu $code ao acompanhar a tarefa")
        val started = System.currentTimeMillis()
        s.inputStream.bufferedReader().use { r ->
            var event = ""
            while (true) {
                val line = r.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.substringAfter(":").trim()
                    line.startsWith("data:") -> {
                        val payload = line.substringAfter(":").trim()
                        when (event) {
                            "complete" -> return Json.parse(payload).list()
                            "error" -> throw SpaceException(friendlyError(payload))
                            "generating", "progress" -> progress?.update("Gerando o modelo 3D na nuvem…", null)
                            "heartbeat" -> progress?.update("Na fila do servidor gratuito… ${(System.currentTimeMillis() - started) / 1000}s", null)
                        }
                    }
                }
            }
        }
        throw SpaceException("A conexão terminou sem resultado")
    }

    /** Índice da função na configuração do espaço (necessário para a fila). */
    private fun fnIndex(api: String): Int {
        val name = api.trimStart('/')
        val deps = config["dependencies"].list()
        deps.forEachIndexed { i, d ->
            val m = d.obj()
            if ((m["api_name"] as? String) == name) return (m["id"] as? Double)?.toInt() ?: i
        }
        throw SpaceException("Função /$name não existe neste espaço")
    }

    /**
     * Chama pela fila do Gradio (mesmo protocolo do cliente oficial), mantendo a sessão.
     * Reporta posição na fila e progresso.
     */
    fun queue(api: String, data: List<Any?>, progress: Progress? = null, timeoutMs: Long = 15 * 60_000L): List<Any?> {
        val p = connect(progress)
        val idx = fnIndex(api)
        val join = open("$src$p/queue/join", "POST")
        join.doOutput = true
        join.setRequestProperty("Content-Type", "application/json")
        join.outputStream.use {
            it.write(Json.stringify(mapOf("data" to data, "event_data" to null, "fn_index" to idx, "trigger_id" to null, "session_hash" to sessionHash)).toByteArray())
        }
        read(join)
        val s = open("$src$p/queue/data?session_hash=$sessionHash")
        s.readTimeout = timeoutMs.toInt()
        s.setRequestProperty("Accept", "text/event-stream")
        if (s.responseCode !in 200..299) throw SpaceException("O serviço respondeu ${s.responseCode} na fila")
        s.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val msg = try { Json.parse(line.substringAfter(":").trim()).obj() } catch (_: Exception) { continue }
                when (msg["msg"]) {
                    "estimation" -> {
                        val rank = (msg["rank"] as? Double)?.toInt()
                        val eta = (msg["rank_eta"] as? Double)?.toInt()
                        progress?.update(
                            if (rank != null && rank > 0) "Na fila do servidor gratuito: posição ${rank + 1}" + (eta?.let { " (~${it}s)" } ?: "")
                            else "Começando na nuvem…", null,
                        )
                    }
                    "process_starts" -> progress?.update("Gerando o modelo 3D na nuvem…", null)
                    "progress" -> {
                        val pd = msg["progress_data"].list().firstOrNull().obj()
                        val idx2 = pd["index"] as? Double; val len = pd["length"] as? Double
                        val desc = pd["desc"] as? String
                        progress?.update(desc ?: "Gerando o modelo 3D na nuvem…", if (idx2 != null && len != null && len > 0) (idx2 / len).toFloat() else null)
                    }
                    "process_completed" -> {
                        val out = msg["output"].obj()
                        if (msg["success"] == false || out["error"] != null) throw SpaceException(friendlyError(Json.stringify(out["error"])))
                        return out["data"].list()
                    }
                    "unexpected_error" -> throw SpaceException(friendlyError(Json.stringify(msg["message"])))
                }
            }
        }
        throw SpaceException("A conexão terminou sem resultado")
    }

    private fun friendlyError(payload: String): String {
        val msg = try { (Json.parse(payload) as? String) ?: payload } catch (_: Exception) { payload }
        return when {
            msg.contains("quota", true) || msg.contains("GPU", false) && msg.contains("exceeded", true) ->
                "A cota gratuita de GPU acabou por hoje. Tente mais tarde ou informe um token grátis do Hugging Face nas configurações."
            msg == "null" || msg.isBlank() -> "O servidor de IA recusou a tarefa (pode estar sobrecarregado). Tente de novo em alguns minutos."
            else -> msg.take(300)
        }
    }

    /** URL para baixar um arquivo devolvido por uma função. */
    fun fileUrl(file: Any?): String? {
        val m = file.obj()
        (m["url"] as? String)?.let { return it }
        val path = (m["path"] as? String) ?: (file as? String) ?: return null
        return "$src${prefix ?: ""}/file=$path"
    }

    fun download(url: String): ByteArray {
        val c = open(url)
        c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) throw SpaceException("Download falhou (${c.responseCode})")
        return c.inputStream.use { i -> ByteArrayOutputStream().also { i.copyTo(it) }.toByteArray() }
    }
}
