package com.ayuemin.ilmar.alpine

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class RootfsArchiveTest {
    private data class Entry(val name: String, val content: String = "", val type: Char = '0', val link: String = "")
    private fun archive(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(512)
            fun text(at: Int, value: String) { value.toByteArray().copyInto(header, at) }
            text(0, entry.name); text(100, "0000755"); text(124, entry.content.length.toString(8).padStart(11, '0'))
            header[156] = entry.type.code.toByte(); text(157, entry.link)
            for (i in 148..155) header[i] = 32
            val sum = header.sumOf { it.toInt() and 255 }
            text(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
            out.write(header); out.write(entry.content.toByteArray()); out.write(ByteArray((512 - entry.content.length % 512) % 512))
        }
        out.write(ByteArray(1024)); return out.toByteArray()
    }
    private fun temp() = Files.createTempDirectory("ilmar-rootfs-test").toFile()

    @Test fun regularFilesHardlinksAndDeferredSymlinksExtract() {
        val root = temp()
        try {
            val bytes = archive(Entry("./bin", type = '5'), Entry("./bin/busybox", "fake-elf"),
                Entry("./bin/sh", type = '2', link = "busybox"), Entry("./bin/cat", type = '1', link = "./bin/busybox"))
            RootfsArchive.extract(bytes.inputStream(), root)
            assertEquals("fake-elf", File(root, "bin/sh").readText())
            assertTrue(Files.isSameFile(File(root, "bin/busybox").toPath(), File(root, "bin/cat").toPath()))
        } finally { RootfsArchive.remove(root) }
    }
    @Test fun refusesTraversalDuplicateAndWritesThroughLinks() {
        for (bytes in listOf(archive(Entry("../escape", "bad")), archive(Entry("/tmp/escape", "bad")),
            archive(Entry("./duplicate", "one"), Entry("./duplicate", "two")),
            archive(Entry("./evil", type = '2', link = "/tmp"), Entry("./evil/escape", "bad")))) {
            val root = temp()
            try { assertTrue(runCatching { RootfsArchive.extract(bytes.inputStream(), root) }.isFailure) }
            finally { RootfsArchive.remove(root) }
        }
    }
    @Test fun checksumAndTruncatedPayloadFailClosed() {
        val valid = archive(Entry("./bin/busybox", "fake-elf"))
        for (bytes in listOf(valid.copyOf().apply { this[0] = 'x'.code.toByte() }, valid.copyOf(514))) {
            val root = temp()
            try { assertTrue(runCatching { RootfsArchive.extract(bytes.inputStream(), root) }.isFailure) }
            finally { RootfsArchive.remove(root) }
        }
    }
    @Test fun deletionNeverFollowsExternalSymlink() {
        val root = temp(); val outside = temp()
        try {
            File(outside, "preserved").writeText("owner data")
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            RootfsArchive.remove(root)
            assertEquals("owner data", File(outside, "preserved").readText())
        } finally { RootfsArchive.remove(root); RootfsArchive.remove(outside) }
    }
    @Test fun officialPinnedArchiveExtractsWhenProvidedByCI() {
        val archive = System.getenv("ILMAR_ROOTFS_ARCHIVE")?.let(::File) ?: return
        assertTrue("CI rootfs artifact missing", archive.isFile)
        val root = temp()
        try {
            GZIPInputStream(archive.inputStream()).use { RootfsArchive.extract(it, root) }
            assertTrue(File(root, "bin/busybox").isFile)
            assertTrue(File(root, "etc/alpine-release").readText().isNotBlank())
        } finally { RootfsArchive.remove(root) }
    }
}
