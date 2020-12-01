package com.android.channel.common

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SignatureException
import kotlin.math.min

/**
 *  EoCd结构如下：
 *  Offset	Bytes	Description
 *      0	4	    中央目录目录结尾记录标记ID（0x06054b50）
 *      4	2	    当前磁盘编号
 *      6	2	    中央目录开始位置的磁盘编号
 *      8	2	    该磁盘上所记录的中央目录数量
 *      10	2	    中央目录结构总数
 *      12	4	    中央目录的大小
 *      16	4	    中央目录开始位置相对于archive开始的位移
 *      20	2	    注释长度
 *      22	n	    注释内容
 *
 *  zip64核心目录定位结束符(4G以上zip文件会用到)，在EOCD之前
 *   Offset	Bytes	description
 *   0	4	zip64核心目录定位结束符标志位，固定值（0x07064b50）
 *   4	4	核心目录开始的磁盘编号
 *   8	8	核心目录末端的相对偏移
 *   16	4	磁盘总数
 */
class ZipUtils {
    companion object {

        const val ZIP_EOCD_CENTRAL_DIR_SIZE_LOCATION = 12
        const val ZIP_EOCD_CENTRAL_DIR_OFFSET_LOCATION = 16
        const val ZIP_EOCD_NOTE_LENGTH_OFFSET_LOCATION = 20
        const val ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION = 22

        private const val ZIP64_EOCD_LOCATOR_SIZE = 20//16+4
        private const val ZIP64_EOCD_LOCATOR_ID = 0x504b0607//大端的写法
        private const val UNSIGNED_INT16_MAX_VALUE = 0xFFFF
        private const val ZIP_EOCD_REC_SIG_ID = 0x06054b50//小端的写法

        fun findZipEndOfCentralDirectoryRecord(zip: RandomAccessFile): Pair<ByteBuffer, Long> {
            if (zip.length() < ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION) {
                throw SignatureException("eocd format error")
            }
            var result = findZipEndOfCentralDirectoryRecord(zip, 0)
            if (result == null) {
                result = findZipEndOfCentralDirectoryRecord(zip, UNSIGNED_INT16_MAX_VALUE)
            }
            return result ?: throw RuntimeException("eocd not found")
        }

        fun setZipEocdCentralDirectoryOffset(eocd: ByteBuffer, offset: Long) {
            setUnsignedInt32(
                eocd, eocd.position() + ZIP_EOCD_CENTRAL_DIR_OFFSET_LOCATION,
                offset
            )
        }

        fun isZip64EndOfCentralDirectoryLocatorPresent(
            apkFile: RandomAccessFile, eocdOffset: Long
        ): Boolean {
            val locatorPosition = eocdOffset - ZIP64_EOCD_LOCATOR_SIZE
            if (locatorPosition < 0) {
                return false
            }
            apkFile.seek(locatorPosition)
            return apkFile.readInt() == ZIP64_EOCD_LOCATOR_ID
        }

        fun getUnsignedInt16(buffer: ByteBuffer, offset: Int): Int {
            return buffer.getShort(offset).toInt() and 0xFFFF
        }

        fun getUnsignedInt32(buffer: ByteBuffer, offset: Int): Long {
            return buffer.getInt(offset).toLong() and 0xFFFF_FFFF
        }

        private fun setUnsignedInt32(buffer: ByteBuffer, offset: Int, value: Long) {
            if (value < 0 || value > 0xFFFF_FFFF) {
                throw IllegalArgumentException("UInt32 value of range:$value")
            }
            buffer.putInt(offset, value.toInt())
        }

        private fun findZipEndOfCentralDirectoryRecord(
            zip: RandomAccessFile, maxNoteSize: Int
        ): Pair<ByteBuffer, Long>? {
            if (maxNoteSize < 0 || maxNoteSize > UNSIGNED_INT16_MAX_VALUE) {
                throw IllegalArgumentException("eocd note size $maxNoteSize")
            }
            val fileSize = zip.length()
            if (fileSize < ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION) {
                return null
            }
            val noteLength =
                min(maxNoteSize, (fileSize - ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION).toInt())
            val buffer = ByteBuffer.allocate(ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION + noteLength)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val bufferOffsetInFile = fileSize - buffer.capacity()
            zip.seek(bufferOffsetInFile)
            zip.readFully(buffer.array(), buffer.arrayOffset(), buffer.capacity())
            val eocdOffsetInBuff = findZipEndOfCentralDirectoryRecord(buffer)
            if (eocdOffsetInBuff == -1) {
                println("eocdOffsetInBuff = -1")
                return null
            }
            buffer.position(eocdOffsetInBuff)
            val eocd = buffer.slice()
            eocd.order(ByteOrder.LITTLE_ENDIAN)
            return Pair(eocd, bufferOffsetInFile + eocdOffsetInBuff)
        }

        private fun findZipEndOfCentralDirectoryRecord(note: ByteBuffer): Int {
            val archiveSize = note.capacity()
            if (archiveSize < ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION) {
                return -1
            }
            val noteLength = min(
                archiveSize - ZIP_EOCD_NOTE_CONTENT_OFFSET_LOCATION,
                UNSIGNED_INT16_MAX_VALUE
            )
            for (expectedCommentLength in 0 until noteLength) {
                val eocdStartPos = noteLength - expectedCommentLength
                if (note.getInt(eocdStartPos) == ZIP_EOCD_REC_SIG_ID) {
                    println("find sign id 0x06054b50")
                    val actualNoteLength =
                        getUnsignedInt16(note, eocdStartPos + ZIP_EOCD_NOTE_LENGTH_OFFSET_LOCATION)
                    if (actualNoteLength == expectedCommentLength) {
                        return eocdStartPos
                    }
                    println("actualNoteLength: $actualNoteLength,expectedCommentLength: $expectedCommentLength,noteLength: $noteLength")
                    break
                }
            }
            return -1
        }
    }
}