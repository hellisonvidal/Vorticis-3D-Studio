package com.vorticis.studio3d.core.cloud

/**
 * "Qualidade máxima (nuvem grátis)": envia a foto ao TRELLIS (Microsoft, código aberto)
 * publicado gratuitamente no Hugging Face e recebe o GLB com textura.
 * Testado de verdade pelo GitHub Actions (CloudTest).
 */
object CloudGenerator {
    const val DEFAULT_SPACE = "trellis-community/TRELLIS"

    fun generate(image: ByteArray, mime: String, token: String?, progress: GradioClient.Progress, space: String = DEFAULT_SPACE): ByteArray {
        val c = GradioClient(space, token)
        progress.update("Conectando ao servidor de IA gratuito…", null)
        c.connect(progress)
        c.queue("/start_session", emptyList(), progress)
        progress.update("Enviando a foto…", null)
        val file = c.upload(image, if (mime.endsWith("png")) "foto.png" else "foto.jpg", mime)
        val out = c.queue(
            "/generate_and_extract_glb",
            listOf(file, emptyList<Any>(), 0, 7.5, 12, 3.0, 12, "stochastic", 0.95, 1024),
            progress,
        )
        // saídas: [vídeo, GLB extraído, botão de download do GLB]
        val glb = out.drop(1).firstNotNullOfOrNull { c.fileUrl(it) } ?: throw GradioClient.SpaceException("O servidor não devolveu o modelo")
        progress.update("Baixando o modelo 3D…", null)
        return c.download(glb)
    }
}
