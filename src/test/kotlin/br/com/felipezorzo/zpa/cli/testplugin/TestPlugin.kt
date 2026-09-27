package br.com.felipezorzo.zpa.cli.testplugin

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.CustomPlSqlRulesDefinition
import com.felipebz.zpa.api.annotations.ActivatedByDefault
import com.felipebz.zpa.api.annotations.Priority
import com.felipebz.zpa.api.annotations.Rule
import com.felipebz.zpa.api.checks.PlSqlCheck
import java.io.File
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A custom rules plugin for the tests, packaged at test time from the classes below. The repository key and the issue
 * message come from a resource inside the JAR, so JARs built with different values are distinguishable in the output
 * and prove which JAR the loaded classes came from (pf4j loads plugin classes and resources from the plugin first).
 */
object TestPlugin {
    const val RULE_KEY = "TestPluginRule"
    internal const val RESOURCE = "zpa-cli-test-plugin.properties"

    fun writeJar(target: File, pluginId: String, repositoryKey: String, message: String) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Plugin-Key", pluginId)
            mainAttributes.putValue("Plugin-Version", "1.0.0")
        }
        target.parentFile.mkdirs()
        JarOutputStream(target.outputStream(), manifest).use { jar ->
            for (type in listOf(TestPluginRulesDefinition::class.java, TestPluginCheck::class.java)) {
                val name = type.name.replace('.', '/') + ".class"
                jar.putNextEntry(JarEntry(name))
                type.classLoader.getResourceAsStream(name)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
            jar.putNextEntry(JarEntry("META-INF/extensions.idx"))
            jar.write((TestPluginRulesDefinition::class.java.name + "\n").toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry(RESOURCE))
            Properties().apply {
                setProperty("repositoryKey", repositoryKey)
                setProperty("message", message)
            }.store(jar, null)
            jar.closeEntry()
        }
    }

    internal fun property(owner: Class<*>, key: String): String {
        val properties = Properties()
        owner.classLoader.getResourceAsStream(RESOURCE)!!.use { properties.load(it) }
        return properties.getProperty(key)
    }
}

class TestPluginRulesDefinition : CustomPlSqlRulesDefinition() {
    override fun repositoryName(): String = "Test plugin"

    override fun repositoryKey(): String = TestPlugin.property(javaClass, "repositoryKey")

    override fun checkClasses(): Array<Class<*>> = arrayOf(TestPluginCheck::class.java)
}

/**
 * Reports one issue on the first line of a file, but only once per instance: an instance shared between runs would
 * stay silent from the second run on.
 */
@Rule(key = TestPlugin.RULE_KEY, name = "Test plugin rule", description = "Reports every file.", priority = Priority.MAJOR)
@ActivatedByDefault
class TestPluginCheck : PlSqlCheck() {
    private var reported = false

    override fun visitFile(node: AstNode) {
        if (!reported) {
            reported = true
            addLineIssue(TestPlugin.property(javaClass, "message"), 1)
        }
    }
}
