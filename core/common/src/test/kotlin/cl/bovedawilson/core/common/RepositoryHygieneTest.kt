package cl.bovedawilson.core.common

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RepositoryHygieneTest {

    private companion object {
        /**
         * Directorios que no son código del proyecto. Se excluyen del recorrido para que
         * las pruebas no analicen artefactos ni dependencias de terceros: cuando la
         * Fase 4 cree `firebase/node_modules/`, G-70 leería miles de `.json` ajenos y
         * encontraría falsos positivos de `service-account` (hallazgo I-6).
         */
        val EXCLUDED_DIRS = listOf("build", ".gradle", ".git", ".idea", "node_modules")
    }

    private fun isExcluded(file: File): Boolean =
        EXCLUDED_DIRS.any { file.path.contains("$it${File.separator}") }

    private fun getProjectRootDir(): File {
        var currentDir: File? = File(System.getProperty("user.dir")!!)
        while (currentDir != null && !File(currentDir, "settings.gradle.kts").exists()) {
            currentDir = currentDir.parentFile
        }
        return currentDir ?: error("No se encontró la raíz del proyecto")
    }

    private fun getSourceFiles(): Sequence<File> {
        val rootDir = getProjectRootDir()
        val logMain = listOf(
            "core", "common", "src", "main", "kotlin", "cl", "bovedawilson", "core", "common", "log"
        ).joinToString(File.separator)
        val logTest = listOf(
            "core", "common", "src", "test", "kotlin", "cl", "bovedawilson", "core", "common", "log"
        ).joinToString(File.separator)

        return rootDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains(logMain) && !it.path.contains(logTest) }
            .filter { !it.name.contains("RepositoryHygieneTest") }
            .filter { !isExcluded(it) }
    }

    private fun getExpectedProjects(path: String): Set<String> {
        return when {
            path.contains("app${File.separator}build.gradle.kts") ->
                setOf(":core:model", ":core:common", ":data:sync")
            path.contains("core${File.separator}crypto") ->
                setOf(":core:common")
            path.contains("core${File.separator}model") ->
                emptySet()
            path.contains("core${File.separator}common") ->
                emptySet()
            path.contains("data${File.separator}local") ->
                setOf(":core:common", ":core:crypto")
            path.contains("data${File.separator}remote") ->
                setOf(":core:common", ":core:crypto")
            path.contains("data${File.separator}sync") ->
                setOf(":core:model", ":core:common", ":core:crypto", ":data:local", ":data:remote")
            else -> emptySet()
        }
    }

    private fun checkGraph(path: String, projects: Set<String>, expected: Set<String>) {
        if (expected.isNotEmpty() && projects != expected) {
            fail("G-66: Grafo de dependencias incorrecto en $path. Esperado: $expected, Actual: $projects")
        }
        if (expected.isEmpty() && projects.isNotEmpty() && !path.contains("build-logic")) {
            fail("G-66: Grafo de dependencias incorrecto en $path. Esperado: $expected, Actual: $projects")
        }
    }

    @Test
    fun `G-66 module graph hygiene`() {
        val rootDir = getProjectRootDir()
        val buildFiles = rootDir.walkTopDown()
            .filter { it.isFile && it.name == "build.gradle.kts" }
            .filter { !isExcluded(it) }
        var checked = 0
        buildFiles.forEach { file ->
            checked++
            val content = file.readText()
            if (content.contains("api(project(")) {
                fail("G-66: Prohibido usar api(project(...)) para evitar fugas en ${file.path}")
            }
            val projects = "project\\(\"([^\"]+)\"\\)".toRegex()
                .findAll(content)
                .map { it.groupValues[1] }
                .toSet()
            val expected = getExpectedProjects(file.path)
            checkGraph(file.path, projects, expected)
        }
        org.junit.Assert.assertTrue("Debe analizar archivos build.gradle.kts", checked > 0)
    }

    @Test
    fun `G-67 logging hygiene`() {
        val prohibited = listOf(
            "android." + "util." + "Log",
            "print" + "ln",
            "print" + "(",
            "System." + "out",
            "print" + "StackTrace()"
        )
        var checked = 0
        getSourceFiles().forEach { file ->
            checked++
            val content = file.readText()
            prohibited.forEach { p ->
                if (content.contains(p)) {
                    fail("G-67: Uso prohibido de $p en ${file.path}")
                }
            }
        }
        org.junit.Assert.assertTrue("Debe analizar archivos de código", checked > 0)
    }

    @Test
    fun `G-68 cryptographic hygiene`() {
        val prohibited = listOf(
            "AES/" + "ECB",
            "java." + "util." + "Random",
            "Math." + "random()",
        )
        var checked = 0
        getSourceFiles().forEach { file ->
            checked++
            val content = file.readText()
            prohibited.forEach { p ->
                if (content.contains(p)) {
                    fail("G-68: Uso prohibido de $p en ${file.path}")
                }
            }
            val outsideCrypto = !file.path.contains("core${File.separator}crypto")
            // Excepción única y con nombre (ADR-042, mismo patrón que ADR-035 para
            // MessageDigest): el Cipher que envuelve la BiometricKEK con la clave de
            // Android Keystore no es Tink y no puede serlo, porque Android Keystore solo
            // se opera a través de la API estándar de JCA (Cipher.getInstance +
            // Cipher.init con la SecretKey del Keystore); Tink no gestiona claves de
            // Keystore. `BiometricUnlock` es la única clase fuera de `:core:crypto` que
            // puede usar `Cipher.getInstance`, y solo para esa clave.
            val isKeystoreCipherFile = file.path.contains(
                listOf("data", "sync", "biometric", "BiometricUnlock.kt").joinToString(File.separator)
            )
            if (outsideCrypto && !isKeystoreCipherFile && content.contains("Cipher." + "getInstance")) {
                fail("G-68: Cipher.getInstance fuera de crypto en ${file.path}")
            }
            // MessageDigest solo es legítimo dentro de :core:crypto (por ejemplo, el
            // checksum SHA-256 de BIP-39, que no es "hash como cifrado"): fuera de ahí
            // sigue prohibido, igual que Cipher.getInstance.
            if (outsideCrypto && content.contains("Message" + "Digest")) {
                fail("G-68: MessageDigest fuera de crypto en ${file.path}")
            }
        }
        org.junit.Assert.assertTrue("Debe analizar archivos de código", checked > 0)
    }

    @Test
    fun `G-69 persistence and state hygiene`() {
        val prohibited = listOf(
            "fallbackTo" + "DestructiveMigration",
            "remember" + "Saveable",
            "SavedState" + "Handle"
        )
        var checked = 0
        getSourceFiles().forEach { file ->
            checked++
            val content = file.readText()
            prohibited.forEach { p ->
                if (content.contains(p)) {
                    fail("G-69: Uso prohibido de $p en ${file.path}")
                }
            }
            if (!file.name.contains("SecureDialog") && content.contains("Dialog" + "(")) {
                fail("G-69: Uso de Dialog directo en ${file.path}")
            }
        }
        org.junit.Assert.assertTrue("Debe analizar archivos de código", checked > 0)
    }

    /**
     * `google-services.json` real (ADR-041): el propietario autorizó crear un proyecto
     * Firebase real y colocar su configuración de cliente localmente. `SECURITY.md`
     * §7 es explícito: la Web/API key de esa configuración **no es un secreto de
     * autorización** (la seguridad depende de Authentication, Security Rules, App Check y
     * el cifrado local), así que no se trata como fuga solo por coincidir con el patrón
     * `AIza...` de una clave de API de Google. Lo que sí importa es que el archivo nunca
     * llegue a Git; eso se verifica leyendo `.gitignore` en vez de prohibir que el archivo
     * exista en el árbol de trabajo, que rompería el flujo ya autorizado.
     */
    /** WorkManager no recibe datos de la bóveda: la sincronización toma su contexto del repositorio. */
    @Test
    fun `G-90 WorkManager input hygiene`() {
        val root = getProjectRootDir()
        val syncMain = File(root, listOf("data", "sync", "src", "main").joinToString(File.separator))
        val prohibited = listOf("set" + "InputData", "work" + "DataOf", "Data" + ".Builder")
        var checked = 0
        syncMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            checked++
            val content = file.readText()
            prohibited.firstOrNull(content::contains)?.let { pattern ->
                fail("G-90: WorkManager no debe recibir datos de entrada en ${file.path}: $pattern")
            }
        }
        org.junit.Assert.assertTrue("Debe analizar fuentes de sincronización", checked > 0)
    }

    private fun isGoogleServicesFile(file: File): Boolean = file.name == "google" + "-services.json"

    private fun checkContentForSecrets(content: String, path: String, skipApiKeyPattern: Boolean) {
        val realSecrets = listOfNotNull(
            if (!skipApiKeyPattern) "AIza[0-9A-Za-z-_]{35}".toRegex() else null,
            "sk_live_[0-9a-zA-Z]{24}".toRegex(),
            "ghp_[0-9a-zA-Z]{36}".toRegex(),
            ("BEGIN " + "PRIVATE KEY").toRegex(),
            ("BEGIN RSA " + "PRIVATE KEY").toRegex(),
            ("service" + "-account").toRegex(RegexOption.IGNORE_CASE)
        )
        realSecrets.forEach { regex ->
            if (regex.containsMatchIn(content)) {
                fail("G-70: Secreto real detectado en $path")
            }
        }
    }

    /** true si `.gitignore` en la raíz tiene una línea que ignora `google-services.json`
     * globalmente (sin ruta ni negación), que es el patrón real de `.gitignore` §8. */
    private fun googleServicesJsonIsGitignored(): Boolean {
        val gitignore = File(getProjectRootDir(), ".gitignore")
        if (!gitignore.exists()) return false
        return gitignore.readLines().any { it.trim() == "google" + "-services.json" }
    }

    /** `.gitignore` no basta: `git add -f` puede dejar el archivo en el índice. */
    private fun googleServicesJsonIsTracked(file: File): Boolean {
        val root = getProjectRootDir()
        val relativePath = file.relativeTo(root).invariantSeparatorsPath
        val process = ProcessBuilder("git", "ls-files", "--error-unmatch", "--", relativePath)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() == 0
    }

    private fun checkFileForSecrets(file: File) {
        if (file.extension == "jks" || file.extension == "keystore") {
            fail("G-70: Archivo de claves detectado: ${file.path}")
        }
        val content = file.readText()
        if (content.isNotEmpty()) {
            val isGoogleServices = isGoogleServicesFile(file)
            checkContentForSecrets(content, file.path, skipApiKeyPattern = isGoogleServices)
            if (isGoogleServices && content.contains("\"project_id\"") && !googleServicesJsonIsGitignored()) {
                fail(
                    "G-70: google-services.json real detectado y NO está cubierto por " +
                        ".gitignore: ${file.path}"
                )
            }
            if (isGoogleServices && googleServicesJsonIsTracked(file)) {
                fail("G-70: google-services.json está versionado en el índice: ${file.path}")
            }
        }
    }

    @Test
    fun `G-70 secrets hygiene`() {
        val rootDir = getProjectRootDir()
        val extensions = listOf("kt", "kts", "xml", "properties", "toml", "json", "jks", "keystore")
        val filesToCheck = rootDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in extensions }
            .filter { !isExcluded(it) }
            .filter { !it.name.contains("RepositoryHygieneTest") }

        var checked = 0
        filesToCheck.forEach { file ->
            checked++
            checkFileForSecrets(file)
        }
        org.junit.Assert.assertTrue("Debe analizar archivos del repositorio", checked > 0)
    }

    @Test
    fun `G-88 secretos de autenticacion no usan estado String de Compose`() {
        val appSource = File(getProjectRootDir(), "app/src/main")
        var checked = 0
        appSource.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            checked++
            val content = file.readText()
            if (content.contains("PasswordVisualTransformation") && content.contains("mutableStateOf(\"\")")) {
                fail("G-88: campo sensible conservado como String recordado en ${file.path}")
            }
            if (content.contains("var phrase by remember") || content.contains("password.toCharArray()")) {
                fail("G-88: conversión tardía desde String sensible en ${file.path}")
            }
            val recoveryDataClass = Regex(
                "data\\s+class\\s+\\w+\\s*\\([^)]*recoveryPhrase",
                RegexOption.DOT_MATCHES_ALL
            )
            if (recoveryDataClass.containsMatchIn(content)) {
                fail("G-88: data class con frase de recuperación en ${file.path}")
            }
        }
        org.junit.Assert.assertTrue("Debe analizar código de :app", checked > 0)
    }

    /**
     * G-72 custodia lo que `SecureLoggerTest` no puede demostrar en tiempo de ejecución:
     * que ningún llamante interpole un valor variable en el argumento `event`, y que
     * nadie construya un `Redact` directamente saltándose sus fábricas.
     *
     * Alcance real, para no declarar más de lo que hace: la inspección es **por línea**
     * sobre el árbol de fuentes, excluyendo el propio paquete `log/`. Una llamada
     * repartida en varias líneas cuya interpolación caiga en una línea sin el nombre
     * `SecureLogger.` no se detecta. La frontera de tipos de la API es la defensa
     * principal; esta prueba es la red de apoyo.
     */
    @Test
    fun `G-72 secure logging call hygiene`() {
        val callPattern = "SecureLogger\\.[vdiwe]\\s*\\(".toRegex()
        val directRedact = "Redact\\s*\\(".toRegex()
        var checked = 0
        getSourceFiles().forEach { file ->
            checked++
            file.readLines().forEachIndexed { index, line ->
                if (callPattern.containsMatchIn(line) && line.contains('$')) {
                    fail(
                        "G-72: interpolación en una llamada a SecureLogger en " +
                            "${file.path}:${index + 1}. Los datos variables se pasan " +
                            "como campos Redact, no dentro del evento."
                    )
                }
                if (directRedact.containsMatchIn(line)) {
                    fail(
                        "G-72: construcción directa de Redact en ${file.path}:${index + 1}. " +
                            "Debe usarse una fábrica (idPrefix, size, count, type, flag, redacted)."
                    )
                }
            }
        }
        org.junit.Assert.assertTrue("Debe analizar archivos de código", checked > 0)
    }

    /**
     * G-73 custodia `SECURITY.md` §1: ningún tipo con contenido de nota en
     * claro (`title`/`body`) puede ser `data class` sin `toString()` redactado, porque el
     * `toString()`/`equals()` generado expondría ese contenido en cualquier log, aserción
     * fallida o mensaje de excepción accidental (hallazgo real de la revisión de la Fase 2
     * sobre `ItemPayload`/`ItemField`).
     */
    @Test
    fun `G-73 higiene de clases con contenido de nota`() {
        val contentFieldPattern = Regex("val (title|body)\\s*:")
        var checked = 0
        getSourceFiles().forEach { file ->
            checked++
            val content = file.readText()
            if (Regex("data class \\w+").containsMatchIn(content) &&
                contentFieldPattern.containsMatchIn(content) &&
                !content.contains("override fun toString")
            ) {
                fail(
                    "G-73: data class con un campo title/body y sin toString() redactado " +
                        "en ${file.path}",
                )
            }
        }
        org.junit.Assert.assertTrue("Debe analizar archivos de código", checked > 0)
    }

    /**
     * G-74 custodia lo que `docs/architecture.md` §3 y ADR-033 afirmaban que ya existía
     * como "prueba estructural" y no existía (hallazgo ALTO de `android-architect` y
     * `security-architect`, revisión de Fase 4, 2026-07-31): `Ciphertext.fromPersisted`
     * es `public` (necesario para el pull/push con la sesión bloqueada) y solo debe
     * invocarse desde los mapeadores internos de `:data:local`/`:data:remote` o desde
     * pruebas — nunca desde `:data:sync`, `:app` u otro módulo con dominio en claro.
     */
    @Test
    fun `G-74 Ciphertext fromPersisted confinado a mappers internos y a pruebas`() {
        val allowedProdPathFragments = listOf(
            listOf("data", "local", "src", "main").joinToString(File.separator),
            listOf("data", "remote", "src", "main").joinToString(File.separator)
        )
        var checked = 0
        var sawAllowedCaller = false
        getSourceFiles().forEach { file ->
            checked++
            val content = file.readText()
            if (content.contains("fromPersisted(")) {
                val isDefinition = file.name == "Ciphertext.kt"
                val isTestFile = file.path.contains("${File.separator}test${File.separator}") ||
                    file.path.contains("${File.separator}androidTest${File.separator}")
                val isAllowedProdMapper = allowedProdPathFragments.any { file.path.contains(it) }
                if (!isDefinition && !isTestFile && !isAllowedProdMapper) {
                    fail(
                        "G-74: Ciphertext.fromPersisted fuera de los mappers internos de " +
                            "data:local/data:remote o de una prueba, en ${file.path}"
                    )
                }
                if (isAllowedProdMapper) sawAllowedCaller = true
            }
        }
        org.junit.Assert.assertTrue("Debe analizar archivos de código", checked > 0)
        org.junit.Assert.assertTrue(
            "Debe existir al menos un mapper interno real que use fromPersisted",
            sawAllowedCaller
        )
    }

    /**
     * G-75 custodia la otra mitad de la misma afirmación de `docs/architecture.md` §3 /
     * ADR-033: `Aead` y `KeysetHandle` (primitivas de Tink) no deben nombrarse fuera de
     * los archivos internos de `:core:crypto` que los envuelven. `internal` ya lo impide
     * a nivel de compilador para los tipos propios, pero nada impedía hasta ahora que un
     * archivo nuevo, en cualquier módulo, importara estos tipos de Tink directamente;
     * esta prueba es la red de apoyo que detecta ese caso.
     */
    @Test
    fun `G-75 Aead y KeysetHandle confinados a los archivos internos de core crypto`() {
        val allowedFileNames = setOf(
            "KekImporter.kt",
            "UnlockedVault.kt",
            "ItemCryptor.kt",
            "VdekWrapper.kt",
            "VaultWrapping.kt",
            "VdekFactory.kt"
        )
        val cryptoPathFragment = listOf("core", "crypto").joinToString(File.separator)
        val aeadPattern = Regex("\\bAead\\b")
        var checked = 0
        var sawAllowedFile = false
        getSourceFiles().forEach { file ->
            checked++
            val content = file.readText()
            val mentionsPrimitive = content.contains("KeysetHandle") || aeadPattern.containsMatchIn(content)
            if (mentionsPrimitive) {
                val insideCryptoModule = file.path.contains(cryptoPathFragment)
                val isAllowedFile = insideCryptoModule && file.name in allowedFileNames
                if (!isAllowedFile) {
                    fail(
                        "G-75: Aead/KeysetHandle referenciado fuera de los archivos internos " +
                            "permitidos de :core:crypto, en ${file.path}"
                    )
                }
                sawAllowedFile = true
            }
        }
        org.junit.Assert.assertTrue("Debe analizar archivos de código", checked > 0)
        org.junit.Assert.assertTrue(
            "Debe existir al menos un archivo interno que use Aead/KeysetHandle",
            sawAllowedFile
        )
    }
}
