package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxyMode
import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxySettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the per-app proxy settings model (pure Kotlin). */
class PerAppProxySettingsTest {

    @Test
    fun `default ALL mode emits neither list`() {
        val settings = PerAppProxySettings()
        assertEquals(PerAppProxyMode.ALL, settings.mode)
        assertEquals(emptyList<String>(), settings.includePackages())
        assertEquals(emptyList<String>(), settings.excludePackages())
    }

    @Test
    fun `INCLUDE_ONLY emits the selected packages sorted`() {
        val settings = PerAppProxySettings(
            mode = PerAppProxyMode.INCLUDE_ONLY,
            packages = setOf("com.zeta", "com.alpha", "com.mid"),
        )
        assertEquals(
            listOf("com.alpha", "com.mid", "com.zeta"),
            settings.includePackages(),
        )
        assertEquals(emptyList<String>(), settings.excludePackages())
    }

    @Test
    fun `EXCLUDE_SELECTED emits the selected packages sorted`() {
        val settings = PerAppProxySettings(
            mode = PerAppProxyMode.EXCLUDE_SELECTED,
            packages = setOf("com.bank", "com.local"),
        )
        assertEquals(emptyList<String>(), settings.includePackages())
        assertEquals(
            listOf("com.bank", "com.local"),
            settings.excludePackages(),
        )
    }

    @Test
    fun `blank package names are dropped`() {
        val settings = PerAppProxySettings(
            mode = PerAppProxyMode.INCLUDE_ONLY,
            packages = setOf("com.ok", "", "   "),
        )
        assertEquals(listOf("com.ok"), settings.includePackages())
    }

    @Test
    fun `stored packages are kept in ALL mode but emit nothing`() {
        // The list survives a mode switch; the engine just ignores it.
        val settings = PerAppProxySettings(
            mode = PerAppProxyMode.ALL,
            packages = setOf("com.kept"),
        )
        assertEquals(setOf("com.kept"), settings.packages)
        assertEquals(emptyList<String>(), settings.includePackages())
        assertEquals(emptyList<String>(), settings.excludePackages())
    }
}
