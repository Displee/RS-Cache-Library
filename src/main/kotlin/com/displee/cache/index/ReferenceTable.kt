package com.displee.cache.index

import com.displee.cache.CacheLibrary
import com.displee.cache.index.Index.Companion.WHIRLPOOL_SIZE
import com.displee.cache.index.archive.Archive
import com.displee.cache.index.archive.Archive317
import com.displee.cache.index.archive.file.File
import com.displee.compress.CompressionType
import com.displee.compress.decompress
import com.displee.io.impl.InputBuffer
import com.displee.io.impl.OutputBuffer
import java.util.*
import kotlin.collections.ArrayList

open class ReferenceTable(protected val origin: CacheLibrary, val id: Int) {

    var revision = 0
    private var mask = 0x0
    private var needUpdate = false
    protected var archives: SortedMap<Int, Archive> = TreeMap()

    var version = 0

    open fun read(buffer: InputBuffer) {
        version = buffer.readUnsigned()
        if (version < 5 || version > 7) {
            throw RuntimeException("Unknown version: $version")
        }
        revision = if (version >= 6) buffer.readInt() else 0
        mask = buffer.read().toInt()
        val named = mask and FLAG_NAME != 0
        val whirlpool = mask and FLAG_WHIRLPOOL != 0
        val flag4 = mask and FLAG_4 != 0
        val flag8 = mask and FLAG_8 != 0

        val readFun: () -> (Int) = if (version >= 7) {
            {
                buffer.readBigSmart()
            }
        } else {
            {
                buffer.readUnsignedShort()
            }
        }

        val archiveIds = IntArray(readFun())
        for (i in archiveIds.indices) {
            val archiveId = readFun() + if (i == 0) 0 else archiveIds[i - 1]
            archiveIds[i] = archiveId
            archives[archiveId] = Archive(archiveId)
        }
        val archives = archives()
        if (named) {
            archives.forEach { it.hashName = buffer.readInt() }
        }
        if (origin.isRS3()) {
            archives.forEach { it.crc = buffer.readInt() }
            if (flag8) {
                archives.forEach { it.flag8Value = buffer.readInt() }
            }
            if (whirlpool) {
                archives.forEach {
                    var archiveWhirlpool = it.whirlpool
                    if (archiveWhirlpool == null) {
                        archiveWhirlpool = ByteArray(WHIRLPOOL_SIZE)
                        it.whirlpool = archiveWhirlpool
                    }
                    buffer.read(archiveWhirlpool)
                }
            }
            if (flag4) {
                archives.forEach {
                    it.flag4Value1 = buffer.readInt()
                    it.flag4Value2 = buffer.readInt()
                }
            }
        } else {
            if (whirlpool) {
                archives.forEach {
                    var archiveWhirlpool2 = it.whirlpool
                    if (archiveWhirlpool2 == null) {
                        archiveWhirlpool2 = ByteArray(WHIRLPOOL_SIZE)
                        it.whirlpool = archiveWhirlpool2
                    }
                    buffer.read(archiveWhirlpool2)
                }
            }
            archives.forEach { it.crc = buffer.readInt() }
        }
        archives.forEach { it.revision = buffer.readInt() }
        val archiveFileSizes = IntArray(archives.size)
        for (i in archives.indices) {
            archiveFileSizes[i] = readFun()
        }
        for (i in archives.indices) {
            val archive = archives[i]
            var fileId = 0
            for (fileIndex in 0 until archiveFileSizes[i]) {
                fileId += readFun()
                archive.files[fileId] = File(fileId)
            }
        }
        if (named) {
            for (i in archives.indices) {
                val archive = archives[i]
                val fileIds = archive.fileIds()
                for (fileIndex in 0 until archiveFileSizes[i]) {
                    archive.file(fileIds[fileIndex])?.hashName = buffer.readInt()
                }
            }
        }
    }

    open fun write(): ByteArray {
        val buffer = OutputBuffer(1000)
        buffer.write(version)
        if (version >= 6) {
            buffer.writeInt(revision)
        }
        buffer.write(mask)

        val writeFun: (Int) -> Unit = if (version >= 7) {
            {
                buffer.writeBigSmart(it)
            }
        } else {
            {
                buffer.writeShort(it)
            }
        }

        writeFun(archives.size)
        val archiveIds = archiveIds()
        val archives = archives()
        for (i in archives.indices) {
            writeFun(archiveIds[i] - if (i == 0) 0 else archiveIds[i - 1])
        }
        if (isNamed()) {
            archives.forEach { buffer.writeInt(it.hashName) }
        }
        if (origin.isRS3()) {
            archives.forEach { buffer.writeInt(it.crc) }
            if (hasFlag8()) {
                archives.forEach { buffer.writeInt(it.flag8Value) }
            }
            if (hasWhirlpool()) {
                val empty = ByteArray(WHIRLPOOL_SIZE)
                archives.forEach { buffer.write(it.whirlpool ?: empty) }
            }
            if (hasFlag4()) {
                archives.forEach {
                    buffer.writeInt(it.flag4Value1)
                    buffer.writeInt(it.flag4Value2)
                }
            }
        } else {
            if (hasWhirlpool()) {
                val empty = ByteArray(WHIRLPOOL_SIZE)
                archives.forEach { buffer.write(it.whirlpool ?: empty) }
            }
            archives.forEach { buffer.writeInt(it.crc) }
        }
        archives.forEach { buffer.writeInt(it.revision) }
        archives.forEach {
            writeFun(it.files.size)
        }
        archives.forEach {
            val fileIds = it.fileIds()
            for (fileIndex in fileIds.indices) {
                writeFun(fileIds[fileIndex] - if (fileIndex == 0) 0 else fileIds[fileIndex - 1])
            }
        }
        if (isNamed()) {
            archives.forEach { archive ->
                archive.files().forEach { file ->
                    buffer.writeInt(file.hashName)
                }
            }
        }
        return buffer.array()
    }

    fun add(vararg archives: Archive): Array<Archive> {
        val newArchives = ArrayList<Archive>(archives.size)
        newArchives.forEach { newArchives.add(add(it)) }
        return newArchives.toTypedArray()
    }

    fun add(archive: Archive): Archive {
        return add(archive.id, archive.hashName)
    }

    fun add(id: Int, hashName: Int = 0): Archive {
        var existing = archive(id, direct = true)
        if (existing != null && !existing.read && !existing.new && !existing.flagged()) {
            existing = archive(id)
        }
        if (existing == null) {
            if (this is Index317) {
                existing = Archive317(id, hashName)
                if (this.id != 0) {
                    existing.compressionType = CompressionType.GZIP
                }
            } else {
                existing = Archive(id, hashName)
            }
            existing.new = true
            existing.flag()
            flag()
        } else {
            var flag = false
            if (existing.hashName != hashName) {
                existing.hashName = hashName
                flag = true
            }
            if (flag) {
                existing.flag()
                flag()
            }
        }
        return existing
    }

    fun archive(name: String, xtea: IntArray? = null, direct: Boolean = false): Archive? {
        return archive(archiveId(name), xtea, direct)
    }

    @JvmOverloads
    open fun archive(id: Int, xtea: IntArray? = null, direct: Boolean = false): Archive? {
        check(!origin.closed) { "Cache is closed." }
        val archive = archives[id] ?: return null
        if (direct || archive.read || archive.new) {
            return archive
        }
        val sector = origin.index(this.id)?.readArchiveSector(id)
        if (sector == null) {
            archive.read = true
            archive.new = true
            archive.clear()
        } else {
            val is317 = this is Index317
            if (is317) {
                (archive as Archive317).compressionType = if (this.id == 0) CompressionType.BZIP2 else CompressionType.GZIP
            }
            archive.read(InputBuffer(decompress(sector, xtea)))
            archive.compressionType = sector.compressionType
            val mapsIndex = if (is317) 4 else 5
            if (this.id == mapsIndex && !archive.containsData()) {
                archive.read = false
            }
            if (!is317) {
                val sectorBuffer = InputBuffer(sector.data)
                sectorBuffer.offset = 1
                val remaining: Int = sector.data.size - (sectorBuffer.readInt() + sectorBuffer.offset)
                if (remaining >= 2) {
                    sectorBuffer.offset = sector.data.size - 2
                    archive.revision = sectorBuffer.readUnsignedShort()
                }
            }
        }
        return archive
    }

    fun data(archive: Int, file: Int, xtea: IntArray?): ByteArray? {
        return archive(archive, xtea)?.file(file)?.data
    }

    fun remove(id: Int): Archive? {
        val archive = archives.remove(id)
        flag()
        return archive
    }

    fun remove(name: String): Archive? {
        return remove(archiveId(name))
    }

    fun first(): Archive? {
        if (archives.isEmpty()) {
            return null
        }
        return archive(archives.firstKey())
    }

    fun last(): Archive? {
        if (archives.isEmpty()) {
            return null
        }
        return archive(archives.lastKey())
    }

    fun archiveId(name: String): Int {
        val hashName = toHash(name)
        archives.values.forEach {
            if (it.hashName == hashName) {
                return it.id
            }
        }
        return 0
    }

    fun nextId(): Int {
        val last = last()
        return if (last == null) 0 else last.id + 1
    }

    fun copyArchives(): Array<Archive> {
        val archives = archives()
        val copy = ArrayList<Archive>(archives.size)
        for (i in archives.indices) {
            copy.add(i, Archive(archives[i]))
        }
        return copy.toTypedArray()
    }

    fun flag() {
        revision++
        needUpdate = true
    }

    fun flagged(): Boolean {
        return needUpdate
    }

    fun unFlag() {
        if (!flagged()) {
            return
        }
        needUpdate = false
    }

    fun flagMask(flag: Int) {
        mask = mask or flag
    }

    fun unFlagMask(flag: Int) {
        mask = mask and flag.inv()
    }

    fun isNamed(): Boolean {
        return mask and FLAG_NAME != 0
    }

    fun hasWhirlpool(): Boolean {
        return mask and FLAG_WHIRLPOOL != 0
    }

    fun hasFlag4(): Boolean {
        return mask and FLAG_4 != 0
    }

    fun hasFlag8(): Boolean {
        return mask and FLAG_8 != 0
    }

    fun archiveIds(): IntArray {
        return archives.keys.toIntArray()
    }

    fun archives(): Array<Archive> {
        return archives.values.toTypedArray()
    }

    open fun toHash(name: String): Int {
        return name.hashCode()
    }

    companion object {
        const val FLAG_NAME = 0x1
        const val FLAG_WHIRLPOOL = 0x2
        const val FLAG_4 = 0x4
        const val FLAG_8 = 0x8
    }

}