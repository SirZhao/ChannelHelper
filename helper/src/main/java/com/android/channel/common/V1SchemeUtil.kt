package com.android.channel.common

import com.android.channel.writer.ChannelWriter
import java.io.DataOutput
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.jar.JarFile

class V1SchemeUtil private constructor() {
    companion object {
        private const val SHORT_BLOCK_LENGTH = 2

        fun readChannel(channelFile: File): String {
            return RandomAccessFile(channelFile, "r").use {
                val buffer = ByteArray(ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.size)
                var index = it.length()
                index -= ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.size
                it.seek(index)
                it.readFully(buffer)
                if (containV1Magic(buffer)) {
                    index -= SHORT_BLOCK_LENGTH
                    it.seek(index)
                    val lengthBytes = ByteArray(SHORT_BLOCK_LENGTH)
                    it.readFully(lengthBytes)
                    val length = ByteBuffer.wrap(lengthBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
                    if (length > 0) {
                        index -= length
                        it.seek(index)
                        val channelBytes = ByteArray(length)
                        it.readFully(channelBytes)
                        channelBytes.toString(Charsets.UTF_8)
                    } else {
                        println("v1 channel not found")
                        ""
                    }
                } else {
                    println("v1 channel not found")
                    ""
                }
            }
        }

        fun writeChannel(apkFile: File, channel: String) {
            if (!apkFile.exists() || !apkFile.isFile || channel.isEmpty()) {
                throw RuntimeException("params error")
            }
            val explanatoryNote = channel.toByteArray()
            RandomAccessFile(apkFile, "rw").use {
                val eOcd = ZipUtils.findZipEndOfCentralDirectoryRecord(it)
                if (eOcd.first.remaining() == ZipUtils.ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION) {
                    println("file: ${apkFile.absolutePath} has no explanatory note")
                    //1.定位到注释长度起始偏移量
                    it.seek(ZipUtils.ZIP_EOCD_NOTE_LENGTH_OFFSET_LOCATION.toLong())
                    //2.写入注释内容长度 步骤3 + 步骤4 + 步骤5 = 注释内容
                    writeShort(
                        explanatoryNote.size + SHORT_BLOCK_LENGTH
                                + ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.size,
                        it
                    )
                    //3.写入渠道内容
                    it.write(explanatoryNote)
                    //4.写入渠道内容长度 2个bytes
                    writeShort(explanatoryNote.size, it)
                    //5.写入魔数
                    it.write(ChannelWriter.CHANNEL_BLOCK_V1_MAGIC)
                } else {
                    println("file: ${apkFile.absolutePath} has explanatory note")
                    val buffer = ByteArray(ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.size)
                    it.seek(it.length() - ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.size)
                    it.readFully(buffer)
                    if (containV1Magic(buffer)) {
                        throw RuntimeException("file: ${apkFile.absolutePath} has a channel")
                    }
                    val existNoteLength = ZipUtils.getUnsignedInt16(
                        eOcd.first,
                        ZipUtils.ZIP_EOCD_NOTE_LENGTH_OFFSET_LOCATION
                    )
                    val newNoteLength =
                        existNoteLength + explanatoryNote.size + SHORT_BLOCK_LENGTH +
                                ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.size
                    //1.定位到注释长度起始偏移量
                    it.seek(eOcd.second + ZipUtils.ZIP_EOCD_NOTE_LENGTH_OFFSET_LOCATION)
                    //2.写入注释内容长度
                    writeShort(newNoteLength, it)
                    //3.定位到注释内容结尾处
                    it.seek(eOcd.second + ZipUtils.ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION + existNoteLength)
                    //4.写入渠道内容
                    it.write(explanatoryNote)
                    //5.写入渠道内容长度 2个bytes
                    writeShort(explanatoryNote.size, it)
                    //6.写入魔数
                    it.write(ChannelWriter.CHANNEL_BLOCK_V1_MAGIC)
                }
            }

        }

        fun removeChannelByV1(apkFile: File) {
            if (!apkFile.exists() || !apkFile.isFile) {
                return
            }
            RandomAccessFile(apkFile, "rw").use {
                val eocd = ZipUtils.findZipEndOfCentralDirectoryRecord(it)
                if (eocd.first.remaining() == ZipUtils.ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION) {
                    println("file: ${apkFile.name} has no note")
                } else {
                    val existNoteLength = ZipUtils.getUnsignedInt16(
                        eocd.first, ZipUtils.ZIP_EOCD_NOTE_LENGTH_OFFSET_LOCATION
                    )
                    it.seek(eocd.second + ZipUtils.ZIP_EOCD_NOTE_LENGTH_OFFSET_LOCATION)
                    writeShort(0, it)
                    it.setLength(apkFile.length() - existNoteLength)
                    println("file: ${apkFile.name} remove note success")
                }
            }
        }

        fun verifyV1Signature(apkFile: File): Boolean {
            try {
                val jarFile = JarFile(apkFile)
                jarFile.getJarEntry("META-INF/MANIFEST.MF") ?: return false
                val entries = jarFile.entries()
                val regex = Regex("META-INF/\\w+\\.SF")
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.matches(regex)) {
                        jarFile.getJarEntry(entry.name) ?: return false
                        return true
                    }
                }
            } catch (ex: Exception) {
                println("error:${ex.stackTrace}")
            }
            return false
        }

        private fun containV1Magic(bytes: ByteArray): Boolean {
            if (bytes.size != ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.size) {
                return false
            }
            ChannelWriter.CHANNEL_BLOCK_V1_MAGIC.forEachIndexed { index, byte ->
                if (byte != bytes[index]) {
                    return false
                }
            }
            return true
        }

        private fun writeShort(num: Int, out: DataOutput) {
            val buffer = ByteBuffer.allocate(SHORT_BLOCK_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(num.toShort())
            out.write(buffer.array())
        }
    }
}