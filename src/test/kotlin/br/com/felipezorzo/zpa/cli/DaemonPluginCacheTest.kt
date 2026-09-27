package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.plugin.CachedPlugins
import br.com.felipezorzo.zpa.cli.plugin.PerRunPlugins
import br.com.felipezorzo.zpa.cli.testplugin.TestPlugin
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daemon loads the plugins once and reuses them until the plugin JARs change. */
class DaemonPluginCacheTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var root: File
    private lateinit var sourcesDir: File
    private lateinit var pluginDir: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("zpa-cli-plugin-cache-test").toFile()
        sourcesDir = root.resolve("src").apply { mkdirs() }
        sourcesDir.resolve("test.sql").writeText("BEGIN\n  NULL;\nEND;\n/\n")
        pluginDir = root.resolve("plugins").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun analysis(id: Int): String = mapper.writeValueAsString(
        mapOf(
            "id" to id,
            "args" to listOf("--sources", sourcesDir.absolutePath, "--files", "test.sql", "--output-format", CONSOLE)
        )
    )

    /** What the daemon saw during one request. */
    private class Observed(val response: JsonNode, val loadCount: Int, val tempDir: Path?)

    /**
     * Runs one daemon over [pluginDir]. [beforeRequest] runs before the analysis of each request (numbered from 1), so
     * a test can change the plugin directory between requests.
     */
    private fun runDaemon(
        plugins: CachedPlugins,
        requests: Int,
        beforeRequest: (Int) -> Unit = {},
    ): List<Observed> {
        val loadCounts = mutableListOf<Int>()
        val tempDirs = mutableListOf<Path?>()
        var request = 0
        val executor = { args: Array<String> ->
            beforeRequest(++request)
            try {
                execute(args, plugins)
            } finally {
                loadCounts += plugins.loadCount
                tempDirs += plugins.temporaryDirectory
            }
        }
        val input = ByteArrayInputStream((1..requests).joinToString("\n", postfix = "\n") { analysis(it) }.toByteArray(UTF_8))
        val output = ByteArrayOutputStream()
        val exitCode = Daemon(input, PrintStream(output, true, UTF_8), version = "test", plugins = plugins, executor = executor).run()
        assertEquals(0, exitCode)
        val responses = output.toString(UTF_8).lines().filter { it.isNotEmpty() }.drop(1).map { mapper.readTree(it) }
        assertEquals(requests, responses.size)
        return responses.indices.map { Observed(responses[it], loadCounts[it], tempDirs[it]) }
    }

    private fun Observed.issues(): List<String> {
        assertEquals(0, response.get("exitCode").asInt(), response.toString())
        val stdout = response.get("stdout").asText()
        return Regex("""(\S+:${TestPlugin.RULE_KEY}) (.*)""").findAll(stdout)
            .map { "${it.groupValues[1]} ${it.groupValues[2].trim()}" }
            .toList()
    }

    private fun jar(name: String, repositoryKey: String, message: String) =
        TestPlugin.writeJar(pluginDir.resolve(name), pluginId = name.substringBefore('.'), repositoryKey, message)

    @Test
    fun repeatedRequestsReuseTheLoadedPlugins() {
        jar("a.jar", "plugin-a", "message a")
        val plugins = CachedPlugins(pluginDir.toPath())

        val observed = runDaemon(plugins, 5)

        for (request in observed) {
            // Fresh check instances per run: the test check reports only once per instance.
            assertTrue(request.issues().any { it.contains("plugin-a:${TestPlugin.RULE_KEY}") }, request.response.toString())
            assertTrue(request.issues().single().endsWith("message a"), request.issues().toString())
            assertEquals(1, request.loadCount)
        }
        val tempDir = assertNotNull(observed[0].tempDir)
        assertTrue(observed.all { it.tempDir == tempDir })

        // The end of input stops the daemon, which releases the plugins.
        assertEquals(1, plugins.loadCount)
        assertNull(plugins.temporaryDirectory)
        assertFalse(tempDir.exists(), "the temporary directory is deleted when the daemon stops")
    }

    @Test
    fun changesInThePluginDirectoryReloadThePlugins() {
        jar("a.jar", "plugin-a", "message a")
        val plugins = CachedPlugins(pluginDir.toPath())

        val observed = runDaemon(plugins, 7) { request ->
            when (request) {
                2 -> jar("a.jar", "plugin-a", "message a, replaced") // replaced (other size)
                3 -> jar("b.jar", "plugin-b", "message b") // added
                5 -> pluginDir.resolve("b.jar").delete() // removed
                6 -> pluginDir.resolve("a.jar").let { it.setLastModified(it.lastModified() - 60_000) } // same size, other mtime
            }
        }

        assertEquals(listOf(1, 2, 3, 3, 4, 5, 5), observed.map { it.loadCount })
        assertEquals(listOf("plugin-a:${TestPlugin.RULE_KEY} message a"), observed[0].issues())
        assertEquals(listOf("plugin-a:${TestPlugin.RULE_KEY} message a, replaced"), observed[1].issues())
        for (request in observed.subList(2, 4)) {
            assertEquals(
                setOf("plugin-a:${TestPlugin.RULE_KEY} message a, replaced", "plugin-b:${TestPlugin.RULE_KEY} message b"),
                request.issues().toSet()
            )
        }
        for (request in observed.subList(4, 7)) {
            assertEquals(listOf("plugin-a:${TestPlugin.RULE_KEY} message a, replaced"), request.issues())
        }

        // Every reload deleted the temporary directory of the replaced plugins; the last one is deleted on stop.
        val tempDirs = observed.map { assertNotNull(it.tempDir) }.distinct()
        assertEquals(5, tempDirs.size)
        assertTrue(tempDirs.none { it.exists() }, tempDirs.toString())
    }

    @Test
    fun aPluginThatFailsToLoadIsNotCached() {
        pluginDir.resolve("broken.jar").writeText("not a jar")
        val plugins = CachedPlugins(pluginDir.toPath())
        val tempDirsBefore = pluginTempDirs()

        val observed = runDaemon(plugins, 3) { request ->
            if (request == 3) {
                pluginDir.resolve("broken.jar").delete()
                jar("a.jar", "plugin-a", "message a")
            }
        }

        for (request in observed.take(2)) {
            assertEquals(3, request.response.get("exitCode").asInt(), request.response.toString())
            assertTrue(request.response.get("stderr").asText().contains("Unable to relocate"), request.response.toString())
            assertEquals(0, request.loadCount)
            assertNull(request.tempDir)
        }
        assertEquals(listOf("plugin-a:${TestPlugin.RULE_KEY} message a"), observed[2].issues())
        assertEquals(emptySet(), pluginTempDirs() - tempDirsBefore)
    }

    @Test
    fun aNormalRunLoadsThePluginsForThatRunOnly() {
        jar("a.jar", "plugin-a", "message a")
        val tempDirsBefore = pluginTempDirs()
        val originalOut = System.out
        val out = ByteArrayOutputStream()
        val exitCode = try {
            System.setOut(PrintStream(out, true, UTF_8))
            execute(
                arrayOf("--sources", sourcesDir.absolutePath, "--files", "test.sql"),
                PerRunPlugins { pluginDir.toPath() }
            )
        } finally {
            System.setOut(originalOut)
        }

        assertEquals(0, exitCode)
        assertTrue(out.toString(UTF_8).contains("plugin-a:${TestPlugin.RULE_KEY}"), out.toString(UTF_8))
        assertEquals(emptySet(), pluginTempDirs() - tempDirsBefore, "the temporary directory is deleted after the run")
    }

    @Test
    fun theDefaultDaemonExecutorUsesTheCache() {
        jar("a.jar", "plugin-a", "message a")
        val plugins = CachedPlugins(pluginDir.toPath())
        val input = ByteArrayInputStream((1..3).joinToString("\n", postfix = "\n") { analysis(it) }.toByteArray(UTF_8))
        val output = ByteArrayOutputStream()

        Daemon(input, PrintStream(output, true, UTF_8), version = "test", plugins = plugins).run()

        val responses = output.toString(UTF_8).lines().filter { it.isNotEmpty() }.drop(1).map { mapper.readTree(it) }
        assertEquals(3, responses.size)
        assertTrue(responses.all { it.get("stdout").asText().contains("plugin-a:${TestPlugin.RULE_KEY}") }, responses.toString())
        assertEquals(1, plugins.loadCount)
        assertNull(plugins.temporaryDirectory)
    }

    private fun pluginTempDirs(): Set<String> {
        val pattern = Regex("zpa-cli\\d+")
        return Path(System.getProperty("java.io.tmpdir")).toFile().listFiles().orEmpty()
            .filter { pattern.matches(it.name) }.map { it.name }.toSet()
    }

    @Test
    fun anEmptyOrMissingPluginDirectoryIsCachedToo() {
        pluginDir.deleteRecursively()
        val plugins = CachedPlugins(pluginDir.toPath())

        val observed = runDaemon(plugins, 2)

        assertEquals(listOf(1, 1), observed.map { it.loadCount })
        assertTrue(observed.all { it.issues().isEmpty() })
        assertNotEquals(null, observed[0].tempDir)
    }
}
