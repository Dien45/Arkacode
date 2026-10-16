package com.arkacode.app.core

import com.arkacode.app.net.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSupportTest {

    private fun m(
        id: String,
        apiType: String?,
        free: Boolean = false,
        status: String? = "active",
        provider: String = "opencode",
    ) = ModelInfo(id = id, name = id, providerID = provider, free = free, apiType = apiType, status = status)

    @Test
    fun isSupported_nativeIsUnsupported() {
        // Regresi: model `native` (mis. big-pickle, *-free) gagal di engine.
        assertFalse(ModelSupport.isApiSupported("native"))
        assertFalse(ModelSupport.isApiSupported("NATIVE"))
    }

    @Test
    fun isSupported_aisdkAndNullAreSupported() {
        assertTrue(ModelSupport.isApiSupported("aisdk"))
        assertTrue(ModelSupport.isApiSupported(null))
    }

    @Test
    fun visibleModels_hidesNativeOnly() {
        val models = listOf(
            m("muse-spark-1.3-contributor-free", "aisdk", free = true),
            m("big-pickle", "native"),
            m("qwen3.8-flash", "aisdk"),
            m("nemotron-3.5-super-vl-free", "native", free = true),
        )
        val visible = ModelSupport.visibleModels(models)
        assertEquals(listOf("muse-spark-1.3-contributor-free", "qwen3.8-flash"), visible.map { it.id })
        assertEquals(2, ModelSupport.unsupportedCount(models))
    }

    @Test
    fun pickDefault_prefersFreeActiveSupported() {
        val models = listOf(
            m("paid", "aisdk", free = false),
            m("free-deprecated", "aisdk", free = true, status = "deprecated"),
            m("free-active", "aisdk", free = true, status = "active"),
            m("native-free", "native", free = true),
        )
        assertEquals("free-active", ModelSupport.pickDefault(models)?.id)
    }

    @Test
    fun pickDefault_fallsBackToAnySupported() {
        val models = listOf(
            m("native", "native", free = true),
            m("paid", "aisdk", free = false),
        )
        assertEquals("paid", ModelSupport.pickDefault(models)?.id)
    }

    @Test
    fun pickDefault_nullWhenAllUnsupported() {
        assertNull(ModelSupport.pickDefault(listOf(m("a", "native"), m("b", "native"))))
        assertNull(ModelSupport.pickDefault(emptyList()))
    }

    @Test
    fun needsDefault_trueWhenMissingOrNative() {
        val models = listOf(m("big-pickle", "native"), m("spark", "aisdk", free = true))
        assertTrue(ModelSupport.needsDefault(null, null, models))
        assertTrue(ModelSupport.needsDefault("opencode", "", models))
        assertTrue(ModelSupport.needsDefault("opencode", "big-pickle", models))
    }

    @Test
    fun needsDefault_falseWhenSupportedOrUnknown() {
        val models = listOf(m("big-pickle", "native"), m("spark", "aisdk", free = true))
        assertFalse(ModelSupport.needsDefault("opencode", "spark", models))
        // Model di luar katalog (provider kustom) tak diutak-atik.
        assertFalse(ModelSupport.needsDefault("custom", "my-model", models))
    }
}
