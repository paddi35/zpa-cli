package br.com.felipezorzo.zpa.cli.plugin

import com.felipebz.zpa.utils.log.Loggers
import me.lucko.jarrelocator.JarRelocator
import me.lucko.jarrelocator.Relocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Supplies the started plugin manager for one analysis run. Everything derived from the plugins (rules definitions,
 * repositories, check instances) is built by the run itself; a provider only decides how long the loaded plugins live.
 */
interface PluginProvider {
    fun <T> withPlugins(block: (PluginManager) -> T): T
}

/** The normal CLI behaviour: the plugins are relocated, loaded and started for this run and removed afterwards. */
class PerRunPlugins(private val pluginRoot: () -> Path = ::defaultPluginRoot) : PluginProvider {
    override fun <T> withPlugins(block: (PluginManager) -> T): T =
        LoadedPlugins.load(pluginRoot()).use { block(it.manager) }
}

/**
 * Keeps the loaded plugins of [pluginRoot] across runs in one JVM (daemon mode), so a warm request does not relocate the
 * plugin JARs and create a new pf4j manager every time.
 *
 * The cache is keyed by the plugin directory and the name, size and modification time of every plugin JAR in it: when
 * a JAR is added, replaced or removed, the next run stops and unloads the old manager, deletes its temporary directory
 * and loads the plugins again. Only the loading is shared. pf4j creates new extension instances on every
 * `getExtensions` call, and the checks are instantiated per run from the check classes, so no stateful visitor is ever
 * shared between runs.
 *
 * Runs are expected to be sequential (the daemon handles one request at a time); the methods are synchronized so that a
 * concurrent caller waits instead of seeing a manager that is being replaced or closed.
 */
class CachedPlugins(private val pluginRoot: Path = defaultPluginRoot()) : PluginProvider, AutoCloseable {
    private var loaded: LoadedPlugins? = null
    private var loadedState: PluginDirectoryState? = null

    /** Number of times the plugins were loaded (test hook). */
    internal var loadCount = 0
        private set

    /** Temporary directory of the currently loaded plugins (test hook). */
    internal val temporaryDirectory: Path?
        @Synchronized get() = loaded?.tempDir

    @Synchronized
    override fun <T> withPlugins(block: (PluginManager) -> T): T {
        val state = PluginDirectoryState.of(pluginRoot)
        var current = loaded
        if (current == null || state != loadedState) {
            if (current != null) {
                LOG.info("Plugin directory changed, reloading the plugins")
                release()
            }
            current = LoadedPlugins.load(pluginRoot)
            loaded = current
            // The state read before loading: if a JAR changes while it is relocated, the next run reloads again.
            loadedState = state
            loadCount++
        }
        return block(current.manager)
    }

    @Synchronized
    override fun close() {
        release()
    }

    private fun release() {
        val current = loaded
        loaded = null
        loadedState = null
        current?.close()
    }

    private companion object {
        val LOG = Loggers.getLogger(CachedPlugins::class.java)
    }
}

/** The name, size and modification time of every plugin JAR in a plugin directory. */
internal data class PluginDirectoryState(val root: Path, val jars: List<Jar>) {
    data class Jar(val name: String, val size: Long, val lastModified: FileTime)

    companion object {
        fun of(pluginRoot: Path): PluginDirectoryState {
            val root = pluginRoot.toAbsolutePath().normalize()
            val jars = pluginJars(root).mapNotNull { jar ->
                try {
                    val attributes = Files.readAttributes(jar, BasicFileAttributes::class.java)
                    Jar(jar.name, attributes.size(), attributes.lastModifiedTime())
                } catch (e: NoSuchFileException) {
                    null // removed while listing; the next run sees the new state
                }
            }.sortedBy { it.name }
            return PluginDirectoryState(root, jars)
        }
    }
}

/**
 * Plugins loaded from one plugin directory: relocated copies of the JARs in [tempDir] and a started pf4j manager over
 * them. [close] stops and unloads the plugins and deletes [tempDir].
 */
class LoadedPlugins private constructor(val manager: PluginManager, internal val tempDir: Path) : AutoCloseable {

    override fun close() {
        try {
            manager.stopPlugins()
        } catch (e: Exception) {
            LOG.warn("Failed to stop plugins: ${e.message}")
        }
        try {
            manager.unloadPlugins()
        } catch (e: Exception) {
            LOG.warn("Failed to unload plugins: ${e.message}")
        }
        deleteTempDir(tempDir)
    }

    companion object {
        private val LOG = Loggers.getLogger(LoadedPlugins::class.java)

        fun load(pluginRoot: Path): LoadedPlugins {
            val tempDir = Files.createTempDirectory("zpa-cli")
            val manager = try {
                relocate(pluginRoot, tempDir)
                PluginManager(tempDir)
            } catch (e: Throwable) {
                deleteTempDir(tempDir)
                throw e
            }
            val plugins = LoadedPlugins(manager, tempDir)
            try {
                manager.loadPlugins()
                manager.startPlugins()
            } catch (e: Throwable) {
                plugins.close()
                throw e
            }
            return plugins
        }

        private fun relocate(pluginRoot: Path, tempDir: Path) {
            for (jar in pluginJars(pluginRoot)) {
                val output = tempDir.resolve(jar.fileName).toFile()

                val rules: MutableList<Relocation> = ArrayList<Relocation>()
                rules.add(Relocation("org.sonar.plugins.plsqlopen.api.sslr", "com.felipebz.flr.api"))
                rules.add(Relocation("org.sonar.plugins.plsqlopen.api", "com.felipebz.zpa.api"))

                val relocator = JarRelocator(jar.toFile(), output, rules)
                try {
                    relocator.run()
                } catch (e: IOException) {
                    throw RuntimeException("Unable to relocate", e)
                }
            }
        }

        /**
         * Deletes the relocated plugin JARs when the plugins are closed instead of relying on deleteOnExit, so repeated
         * runs in one JVM (daemon mode) don't accumulate temporary directories and exit-hook entries.
         */
        private fun deleteTempDir(dir: Path) {
            if (!dir.toFile().deleteRecursively()) {
                LOG.warn("Failed to delete temporary directory: $dir")
                dir.toFile().walkTopDown().forEach { it.deleteOnExit() }
            }
        }
    }
}

private fun pluginJars(pluginRoot: Path): List<Path> =
    if (pluginRoot.exists()) pluginRoot.listDirectoryEntries("*.jar") else emptyList()

/** `<appHome>/plugins` of an installation, or `./plugins` when not running from an installed jar. */
fun defaultPluginRoot(): Path {
    val codePath = Path.of(PluginManager::class.java.protectionDomain.codeSource.location.toURI())
    val appHome = if (codePath.extension == "jar" && (codePath.parent.name == "lib" || codePath.parent.name == "jars")) {
        codePath.parent.parent.absolute()
    } else {
        Path.of(".")
    }
    return appHome.resolve("plugins")
}
