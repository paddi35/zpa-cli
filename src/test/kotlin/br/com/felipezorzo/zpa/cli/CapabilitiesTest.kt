package br.com.felipezorzo.zpa.cli

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CapabilitiesTest {

    @Test
    fun capabilitiesFileListsTheForkFeatures() {
        val stream = Main::class.java.getResourceAsStream("/META-INF/zpa-cli-capabilities.properties")
        assertNotNull(stream, "META-INF/zpa-cli-capabilities.properties must be on the classpath")
        val properties = Properties().apply { stream.use { load(it) } }

        assertEquals(
            mapOf("daemon" to "1", "stdin-project" to "1", "quick-fixes" to "1"),
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        )
    }
}
