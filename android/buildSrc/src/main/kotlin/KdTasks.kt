// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Snag
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tareas que generan archivos que van dentro del APK. Viven en buildSrc (y no en app/build.gradle.kts)
// para que Gradle las cree con su ObjectFactory y sean compatibles con el configuration cache.
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Baja por HTTPS y comprueba el SHA-256 fijado en engine-pins.properties. Conexión 30 s, lectura 60 s,
 * 3 intentos (2 s y 4 s de espera), máximo 16 MB: un servidor lento no cuelga el build hasta su timeout.
 * HttpURLConnection no sigue una redirección que cambie de https a http.
 */
fun fetchVerified(url: String, sha256: String, maxBytes: Int = 16 shl 20): ByteArray {
    if (!url.startsWith("https://")) throw GradleException("Solo HTTPS: $url")
    var last: IOException? = null
    for (attempt in 1..3) {
        try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "K-Snag-build (+https://github.com/Kasaco223/K-Snag)")
            }
            val bytes = conn.inputStream.use { it.readNBytes(maxBytes + 1) }
            if (bytes.size > maxBytes) throw GradleException("$url pesa más de $maxBytes bytes")
            val got = sha256(bytes)
            if (got != sha256.lowercase()) {
                throw GradleException("SHA-256 distinto para $url: $got (engine-pins.properties dice $sha256)")
            }
            return bytes
        } catch (e: IOException) {
            last = e
            if (attempt < 3) Thread.sleep(2_000L * attempt)
        }
    }
    throw GradleException("No se pudo bajar $url tras 3 intentos", last)
}

/**
 * assets/licenses/: el texto de android/LICENSE (gpl-3.0.txt), que «Acerca de» muestra sin conexión (los
 * «avisos legales apropiados» de la GPL-3.0, §0 y §5 d), y los avisos de terceros: third_party/components.json
 * y sus textos de licencia (third_party/licenses/), que el APK con el motor tiene que llevar consigo.
 *
 * Antes de copiar la GPL comprueba el SHA-256 del texto oficial de gnu.org: el APK nunca lleva una licencia
 * modificada por error (ci_repo_checks.sh revisa lo mismo en cada PR).
 */
@CacheableTask
abstract class KdLicenseAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gplLicense: RegularFileProperty

    @get:Input
    abstract val gplSha256: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdParty: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val bytes = gplLicense.get().asFile.readBytes()
        val actual = sha256(bytes)
        if (actual != gplSha256.get().lowercase()) {
            throw GradleException(
                "android/LICENSE no es el texto oficial de la GPL-3.0 (SHA-256 $actual, se esperaba " +
                    "${gplSha256.get()}). Restáuralo desde https://www.gnu.org/licenses/gpl-3.0.txt",
            )
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val target = out.resolve("licenses/gpl-3.0.txt")
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
        val tp = thirdParty.get().asFile
        tp.resolve("components.json").copyTo(out.resolve("licenses/third_party/components.json"))
        tp.resolve("licenses").copyRecursively(out.resolve("licenses/third_party/texts"))
    }
}

/**
 * assets/kd/: cacert.pem (los certificados raíz del motor, SSL_CERT_FILE, fijados por SHA-256) y
 * errors_catalog.json (el catálogo de errores de la app de Windows, sin cambios).
 */
@CacheableTask
abstract class KdEngineAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val errorCatalog: RegularFileProperty

    @get:Input
    abstract val cacertUrl: Property<String>

    @get:Input
    abstract val cacertSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        out.resolve("kd").mkdirs()
        out.resolve("kd/cacert.pem").writeBytes(fetchVerified(cacertUrl.get(), cacertSha256.get()))
        errorCatalog.get().asFile.copyTo(out.resolve("kd/errors_catalog.json"))
    }
}

/** res/raw/ytdlp: el yt-dlp (zipapp) que el motor instala en el primer inicio, sin red. */
@CacheableTask
abstract class KdResTask : DefaultTask() {
    @get:Input
    abstract val ytdlpUrl: Property<String>

    @get:Input
    abstract val ytdlpSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        out.resolve("raw").mkdirs()
        out.resolve("raw/ytdlp").writeBytes(fetchVerified(ytdlpUrl.get(), ytdlpSha256.get()))
    }
}

/**
 * jniLibs del motor: copia los .so de jni/<abi>/ de los AAR de youtubedl-android (Python, FFmpeg, QuickJS y sus
 * paquetes lib*.zip.so) para las ABI que publicamos. Nada más del AAR entra al APK: ni su código, ni sus
 * recursos, ni sus dependencias. Gradle ya verificó cada AAR contra gradle/verification-metadata.xml.
 */
@CacheableTask
abstract class KdPayloadTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val aars: ConfigurableFileCollection

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val wanted = abis.get().toSet()
        val copied = mutableMapOf<String, String>()
        for (aar in aars.files.sortedBy { it.name }) {
            ZipFile(aar).use { zip ->
                for (entry in zip.entries()) {
                    val match = Regex("""jni/([^/]+)/([^/]+\.so)""").matchEntire(entry.name) ?: continue
                    val (abi, name) = match.destructured
                    if (abi !in wanted) continue
                    val key = "$abi/$name"
                    copied.put(key, aar.name)?.let { throw GradleException("$key viene de $it y de ${aar.name}") }
                    val target = out.resolve(key)
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        val expected = listOf("libpython.so", "libpython.zip.so", "libqjs.so", "libffmpeg.so", "libffprobe.so", "libffmpeg.zip.so")
        for (abi in wanted) {
            val missing = expected.filter { "$abi/$it" !in copied }
            if (missing.isNotEmpty()) throw GradleException("Los AAR del motor no traen $missing para $abi")
        }
    }
}

/**
 * -Dkd.repoRoot=<raíz del repo> para las pruebas de :core, que leen en su lugar los datos de la app de Windows
 * (tests/fixtures con transcripciones reales de yt-dlp y el catálogo de errores). Los declara como entradas:
 * si cambian, las pruebas vuelven a correr.
 */
abstract class RepoRootArgs : org.gradle.process.CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val errorCatalog: RegularFileProperty

    @get:org.gradle.api.tasks.Internal
    abstract val repoRoot: DirectoryProperty

    override fun asArguments() = listOf("-Dkd.repoRoot=${repoRoot.get().asFile.absolutePath}")
}

/**
 * Las fuentes de libwebp para el reemplazo de 16 KB (M5): baja el .tar.gz fijado por SHA-256 (el mismo de
 * third_party/components.json) y lo descomprime sin la carpeta de arriba. CMake (app/src/main/cpp) lo compila.
 */
@CacheableTask
abstract class KdNativeSourceTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val root = out.canonicalFile
        val tar = java.util.zip.GZIPInputStream(fetchVerified(url.get(), sha256.get()).inputStream()).readBytes()
        var pos = 0
        var longName: String? = null
        while (pos + 512 <= tar.size) {
            val header = tar.copyOfRange(pos, pos + 512)
            if (header.all { it == 0.toByte() }) break
            fun field(off: Int, len: Int) = String(header, off, len, Charsets.UTF_8).substringBefore('\u0000')
            val size = field(124, 12).trim().ifEmpty { "0" }.toLong(8).toInt()
            val type = header[156].toInt().toChar()
            val prefix = field(345, 155)
            var name = longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + field(0, 100)
            longName = null
            val body = tar.copyOfRange(pos + 512, pos + 512 + size)
            pos += 512 + ((size + 511) / 512) * 512
            when (type) {
                'x' -> { // cabecera PAX: puede traer el nombre largo (path=)
                    longName = String(body, Charsets.UTF_8).lineSequence().mapNotNull { line ->
                        line.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=")
                    }.firstOrNull()
                    continue
                }
                'g', 'L' -> {
                    if (type == 'L') longName = String(body, Charsets.UTF_8).substringBefore('\u0000')
                    continue
                }
            }
            name = name.substringAfter('/', "") // sin libwebp-1.6.0/
            if (name.isEmpty()) continue
            val target = root.resolve(name).canonicalFile
            if (!target.path.startsWith(root.path + java.io.File.separator)) throw GradleException("Ruta fuera del destino en el tar: $name")
            when (type) {
                '5' -> target.mkdirs()
                '0', '\u0000' -> {
                    target.parentFile.mkdirs()
                    target.writeBytes(body)
                }
                else -> Unit // enlaces: libwebp no los usa
            }
        }
    }
}
