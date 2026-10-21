package com.ayuemin.ilmar.alpine

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Bounded ustar extractor for the pinned minirootfs. Links are created only after all regular data. */
internal object RootfsArchive {
    fun remove(root: File) {
        if (!Files.exists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
    fun extract(input: InputStream, root: File, checkActive: () -> Unit = {}) {
        require(root.isDirectory && root.list().orEmpty().isEmpty())
        val base = root.canonicalFile
        fun target(raw: String): File {
            require(!raw.startsWith('/') && '\\' !in raw && raw.split('/').none { it == ".." }) { "Недопустимый путь rootfs" }
            return File(base, raw.removePrefix("./")).canonicalFile.also { require(it == base || it.path.startsWith(base.path + File.separator)) }
        }
        data class Link(val file: File, val value: String, val hard: Boolean)
        val links = mutableListOf<Link>()
        val paths = mutableSetOf<String>()
        val header = ByteArray(512)
        var total = 0L; var entries = 0
        fun readBlock(): Boolean {
            var read = 0
            while (read < 512) {
                val n = input.read(header, read, 512 - read)
                if (n < 0) { require(read == 0) { "Неполный tar header" }; return false }
                read += n
            }
            return true
        }
        fun field(start: Int, length: Int) = String(header, start, length, Charsets.UTF_8).substringBefore('\u0000')
        fun number(start: Int, length: Int): Long = field(start, length).trim().ifEmpty { "0" }.toLong(8)
        while (readBlock()) {
            checkActive()
            if (header.all { it == 0.toByte() }) break
            require(++entries <= 30000) { "Слишком много файлов rootfs" }
            val checksum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }
            require(checksum.toLong() == number(148, 8)) { "Неверная tar checksum" }
            val prefix = field(345, 155)
            val name = (if (prefix.isBlank()) "" else "$prefix/") + field(0, 100)
            val file = target(name)
            val size = number(124, 12)
            require(size in 0..(128L * 1024 * 1024))
            total += size; require(total <= 512L * 1024 * 1024) { "Rootfs превышает лимит" }
            val type = header[156].toInt().toChar()
            require(type in listOf('\u0000', '0', '1', '2', '5')) { "Неподдерживаемый tar entry: $type" }
            require(paths.add(file.path) || type == '5' && file.isDirectory) { "Повторный путь rootfs" }
            file.parentFile?.mkdirs()
            when (type) {
                '5' -> { require(size == 0L); check(file.isDirectory || file.mkdir()) }
                '1', '2' -> { require(size == 0L && file != base); links += Link(file, field(157, 100), type == '1') }
                else -> {
                    require(file != base)
                    file.outputStream().use { out ->
                        var remaining = size; val bytes = ByteArray(8192)
                        while (remaining > 0) {
                            checkActive(); val n = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                            require(n > 0) { "Неполный rootfs" }; out.write(bytes, 0, n); remaining -= n
                        }
                    }
                    if (number(100, 8) and 73L != 0L) check(file.setExecutable(true, true))
                }
            }
            var padding = (512 - size % 512) % 512
            while (padding > 0) { require(input.read() >= 0); padding-- }
        }
        // No following symlinks while writing. Refuse a link that would replace an ancestor of another entry.
        links.forEach { link -> require(paths.none { it.startsWith(link.file.path + File.separator) }) }
        links.filter { it.hard }.forEach { link ->
            val source = target(link.value)
            require(source.isFile && !Files.isSymbolicLink(source.toPath()))
            Files.createLink(link.file.toPath(), source.toPath())
        }
        links.filterNot { it.hard }.forEach { link ->
            require(link.value.isNotEmpty() && '\u0000' !in link.value)
            Files.createSymbolicLink(link.file.toPath(), java.nio.file.Paths.get(link.value))
        }
        require(File(base, "bin/busybox").isFile) { "Это не Alpine minirootfs" }
    }
}
