package com.android.channel.common

import java.io.File
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SignatureException

class V2SchemeUtil private constructor() {

    companion object {

        fun getAllIdValue(signBlock: ByteBuffer): MutableMap<Int, ByteBuffer> {
            val pairs = signBlock.sliceFromTo(
                V2ApkUtils.APK_SIG_BLOCK_SIZE_SPACE,
                signBlock.capacity() - V2ApkUtils.APK_SIG_BLOCK_MAGIC_SPACE -
                        V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_SIZE
            )
            val idValues = LinkedHashMap<Int, ByteBuffer>()
            var entryCount = 0
            while (pairs.hasRemaining()) {
                entryCount++
                if (pairs.remaining() < 0) {
                    throw SignatureException("Insufficient data to read, block entry position: $entryCount ")
                }
                val lengthLong = pairs.long
                if (lengthLong < V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_ID_SIZE || lengthLong > Int.MAX_VALUE) {
                    throw SignatureException("apk signing block entry #$entryCount size out of range $lengthLong")
                }
                val length = lengthLong.toInt()
                val nextEntryPos = pairs.position() + length
                if (length > pairs.remaining()) {
                    throw SignatureException("apk signing block entry #$entryCount size out of range $length,available: ${pairs.remaining()}")
                }
                val id = pairs.int
                idValues[id] =
                    getByteBuffer(pairs, length - V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_ID_SIZE)
                if (id == V2ApkUtils.APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
                    println("find V2 signature block id")
                }
                pairs.position(nextEntryPos)
            }
            if (idValues.isEmpty()) {
                throw SignatureException("have no ID-VALUE in Apk signing block")
            }
            return idValues
        }


        fun getApkSectionInfo(apkFile: File): ApkSectionInfo {
            if (!apkFile.exists() || !apkFile.isFile) {
                throw IllegalArgumentException("apkFile not exists or is dir")
            }
            return RandomAccessFile(apkFile, "r").use {
                //1.找到EOCD
                val eocd = ZipUtils.findZipEndOfCentralDirectoryRecord(it)
                if (ZipUtils.isZip64EndOfCentralDirectoryLocatorPresent(it, eocd.second)) {
                    throw RuntimeException("ZIP64 APK not supported")
                }
                val centralDirOffset =
                    V2ApkUtils.getCentralDirOffset(eocd.first, eocd.second)
                //2.找到签名块
                val schemeV2Block =
                    V2ApkUtils.findApkSigningBlock(it, centralDirOffset)
                //3.找到中央目录
                val centralDir = V2ApkUtils.findCentralDir(
                    it, centralDirOffset, (eocd.second - centralDirOffset).toInt()
                )
                //4.找到数据区
                val contentEntry = V2ApkUtils.finContentEntry(
                    it, schemeV2Block.second.toInt()
                )
                val apkSectionInfo =
                    ApkSectionInfo(apkFile.length(), contentEntry, schemeV2Block, centralDir, eocd)
                apkSectionInfo.checkParameters()
                apkSectionInfo
            }
        }

        fun getApkSigningBlock(channelFile: File): ByteBuffer {
            if (!channelFile.exists() || !channelFile.isFile) {
                throw IllegalArgumentException("apk invalid")
            }
            return RandomAccessFile(channelFile, "r").use {
                //1.找到EOCD
                val eocd = ZipUtils.findZipEndOfCentralDirectoryRecord(it)
                if (ZipUtils.isZip64EndOfCentralDirectoryLocatorPresent(it, eocd.second)) {
                    throw RuntimeException("ZIP64 APK not supported")
                }
                val centralDirOffset =
                    V2ApkUtils.getCentralDirOffset(eocd.first, eocd.second)
                //2.找到签名块
                val schemeV2Block =
                    V2ApkUtils.findApkSigningBlock(it, centralDirOffset)
                schemeV2Block.first
            }
        }

        fun generateApkSigningBlock(idValues: MutableMap<Int, ByteBuffer>): ByteBuffer {
            if (idValues.isEmpty()) {
                throw IllegalArgumentException("params invalid")
            }
            var blockLength =
                V2ApkUtils.APK_SIG_BLOCK_SIZE_SPACE + V2ApkUtils.APK_SIG_BLOCK_MAGIC_SPACE
            idValues.values.forEach {
                blockLength += V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_SIZE +
                        V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_ID_SIZE + it.remaining()
            }
            if (idValues.containsKey(V2ApkUtils.APK_SIG_PADDING_BLOCK_ID)) {
                val paddingValue = idValues[V2ApkUtils.APK_SIG_PADDING_BLOCK_ID]
                val paddingBlockLength = V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_SIZE +
                        V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_ID_SIZE + paddingValue!!.remaining()
                blockLength -= paddingBlockLength
                idValues.remove(V2ApkUtils.APK_SIG_PADDING_BLOCK_ID)
                //schemeBlock total size % 4096
                val remainder =
                    (blockLength + V2ApkUtils.APK_SIG_BLOCK_SIZE_SPACE) % V2ApkUtils.ANDROID_COMMON_PAGE_ALIGNMENT_BYTES
                if (remainder != 0) {//重新计算Padding内容
                    var padding = V2ApkUtils.ANDROID_COMMON_PAGE_ALIGNMENT_BYTES - remainder
                    if (padding < V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_SIZE +
                        V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_ID_SIZE
                    ) {
                        padding += V2ApkUtils.ANDROID_COMMON_PAGE_ALIGNMENT_BYTES
                    }
                    blockLength += padding
                    val bufferSize = padding - V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_SIZE -
                            V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_ID_SIZE
                    val paddingBuffer =
                        ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
                    idValues[V2ApkUtils.APK_SIG_PADDING_BLOCK_ID] = paddingBuffer
                    println("recalculate padding,remainder: $remainder")
                }
            }
            val newApkV2Scheme =
                ByteBuffer.allocate(blockLength + V2ApkUtils.APK_SIG_BLOCK_SIZE_SPACE)
            newApkV2Scheme.order(ByteOrder.LITTLE_ENDIAN)
            //1.write size (excluding this field)
            newApkV2Scheme.putLong(blockLength.toLong())
            //2.write v2SchemeBlock
            idValues.entries.forEach {
                newApkV2Scheme.putLong(//ID-VALUE 的size
                    (it.value.remaining() + V2ApkUtils.APK_SIG_BLOCK_ID_VALUE_ID_SIZE).toLong()
                )
                newApkV2Scheme.putInt(it.key)
                newApkV2Scheme.put(
                    it.value.array(),
                    it.value.arrayOffset() + it.value.position(),
                    it.value.remaining()
                )
            }
            //3.write size (same as the one above)
            newApkV2Scheme.putLong(blockLength.toLong())
            //4. write magic
            newApkV2Scheme.putLong(V2ApkUtils.APK_SIG_BLOCK_MAGIC_LO)
            newApkV2Scheme.putLong(V2ApkUtils.APK_SIG_BLOCK_MAGIC_HI)
            if (newApkV2Scheme.remaining() > 0) {
                throw RuntimeException("generateV2SchemeBlock error")
            }
            newApkV2Scheme.flip()
            return newApkV2Scheme
        }

        fun verifyV2Signature(apkFile: File): Boolean {
            return try {
                val apkSigningBlock = getApkSigningBlock(apkFile)
                getAllIdValue(apkSigningBlock).containsKey(V2ApkUtils.APK_SIGNATURE_SCHEME_V2_BLOCK_ID)
            } catch (ex: Exception) {
                println("APK: ${apkFile.absolutePath} has no signature block")
                false
            }
        }

        private fun ByteBuffer.sliceFromTo(start: Int, end: Int): ByteBuffer {
            if (start < 0 || end < start || end > this.capacity()) {
                throw IllegalArgumentException("param error")
            }
            val originalLimit = this.limit()
            val originalPosition = this.position()
            try {
                this.position(0)
                this.limit(end)
                this.position(start)
                val slice = this.slice()
                slice.order(this.order())
                return slice
            } finally {
                this.position(0)
                this.limit(originalLimit)
                this.position(originalPosition)
            }
        }

        private fun getByteBuffer(source: ByteBuffer, size: Int): ByteBuffer {
            if (size < 0) {
                throw IllegalArgumentException("size: $size")
            }
            val originalLimit = source.limit()
            val limit = source.position() + size
            if (limit > originalLimit) {
                throw BufferUnderflowException()
            }
            source.limit(limit)
            try {
                val slice = source.slice()
                slice.order(source.order())
                source.position(limit)
                return slice
            } finally {
                source.limit(originalLimit)
            }
        }
    }
}