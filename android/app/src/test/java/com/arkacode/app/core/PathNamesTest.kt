package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PathNamesTest {

    @Test
    fun fileLeafName_stripsTrailingSeparatorOnDirectories() {
        // Server mengirim direktori dengan pemisah akhir -> dulu tampil kosong.
        assertEquals("src", fileLeafName("src/"))
        assertEquals("mysubdir", fileLeafName("mysubdir/"))
        assertEquals("components", fileLeafName("src/components/"))
    }

    @Test
    fun fileLeafName_handlesWindowsSeparator() {
        assertEquals("src", fileLeafName("src\\"))
        assertEquals("nested", fileLeafName("a\\b\\nested\\"))
    }

    @Test
    fun fileLeafName_keepsPlainFilesUnchanged() {
        assertEquals("a.txt", fileLeafName("a.txt"))
        assertEquals("b.txt", fileLeafName("a/b.txt"))
    }

    @Test
    fun fileLeafName_edges() {
        assertEquals("", fileLeafName(""))
        // path yang seluruhnya pemisah jatuh ke nilai aslinya (root).
        assertEquals("/", fileLeafName("/"))
    }

    @Test
    fun normalizeFsPath_stripsTrailingSeparator() {
        assertEquals("src", normalizeFsPath("src/"))
        assertEquals("src/components", normalizeFsPath("src/components/"))
        assertEquals("a.txt", normalizeFsPath("a.txt"))
        assertEquals("", normalizeFsPath(""))
    }

    @Test
    fun normalizeFsPath_handlesWindowsSeparator() {
        assertEquals("src", normalizeFsPath("src\\"))
        assertEquals("a\\b", normalizeFsPath("a\\b\\"))
    }

    @Test
    fun normalizeFsPath_thenLeafName_isNotEmptyForDirs() {
        // Regresi: dulu "src/" -> "" sehingga nama folder tampil kosong.
        assertEquals("src", fileLeafName(normalizeFsPath("src/")))
        assertEquals("components", fileLeafName(normalizeFsPath("a/components/")))
    }
}
