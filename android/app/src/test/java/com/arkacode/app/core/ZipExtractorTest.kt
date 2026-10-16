package com.arkacode.app.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipExtractorTest {

    private fun tempDir(): File =
        File.createTempFile("ziptest", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    private fun zipWith(dir: File, vararg entries: Pair<String, String>): File {
        val zip = File(dir, "archive.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun extract_nestedDirs_writesFiles() {
        val base = tempDir()
        val src = File(base, "src").apply { mkdirs() }
        val dest = File(base, "dest")
        val zip = zipWith(src, "a.txt" to "hello", "sub/b.txt" to "world")

        val r = ZipExtractor.extract(zip, dest, stripSingleRoot = false)

        assertEquals(2, r.files)
        assertEquals(0, r.skipped)
        assertEquals("hello", File(dest, "a.txt").readText())
        assertEquals("world", File(dest, "sub/b.txt").readText())
    }

    @Test
    fun extract_stripsSingleRootFolder() {
        val base = tempDir()
        val src = File(base, "src").apply { mkdirs() }
        val dest = File(base, "dest")
        val zip = zipWith(
            src,
            "workspace/a.txt" to "hi",
            "workspace/nested/b.txt" to "yo",
        )

        val r = ZipExtractor.extract(zip, dest, stripSingleRoot = true)

        assertEquals(2, r.files)
        assertEquals("hi", File(dest, "a.txt").readText())
        assertEquals("yo", File(dest, "nested/b.txt").readText())
        assertFalse(File(dest, "workspace").exists())
    }

    @Test
    fun extract_keepsRootWhenMultipleTopFolders() {
        val base = tempDir()
        val src = File(base, "src").apply { mkdirs() }
        val dest = File(base, "dest")
        val zip = zipWith(src, "one/a.txt" to "1", "two/b.txt" to "2")

        val r = ZipExtractor.extract(zip, dest, stripSingleRoot = true)

        assertEquals(2, r.files)
        assertEquals("1", File(dest, "one/a.txt").readText())
        assertEquals("2", File(dest, "two/b.txt").readText())
    }

    @Test
    fun extract_blocksZipSlip() {
        val base = tempDir()
        val src = File(base, "src").apply { mkdirs() }
        val dest = File(base, "dest")
        val zip = zipWith(
            src,
            "ok.txt" to "safe",
            "../evil.txt" to "pwned",
            "sub/../../evil2.txt" to "pwned2",
        )

        val r = ZipExtractor.extract(zip, dest, stripSingleRoot = false)

        assertEquals(1, r.files)
        assertEquals(2, r.skipped)
        assertFalse(File(base, "evil.txt").exists())
        assertFalse(File(base, "evil2.txt").exists())
        assertFalse(File(dest, "evil.txt").exists())
        assertTrue(File(dest, "ok.txt").exists())
    }

    @Test
    fun extract_enforcesMaxBytes() {
        val base = tempDir()
        val src = File(base, "src").apply { mkdirs() }
        val dest = File(base, "dest")
        val big = "x".repeat(5000)
        val zip = zipWith(src, "big.bin" to big)

        val r = ZipExtractor.extract(zip, dest, stripSingleRoot = false, maxTotalBytes = 1000)

        assertEquals(0, r.files)
        assertEquals(1, r.skipped)
        assertFalse(File(dest, "big.bin").exists())
    }

    @Test
    fun extract_corruptZip_returnsZeroWithoutThrow() {
        val base = tempDir()
        val dest = File(base, "dest")
        val bad = File(base, "bad.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

        val r = runCatching { ZipExtractor.extract(bad, dest, stripSingleRoot = true) }.getOrThrow()

        assertEquals(0, r.files)
    }
}
