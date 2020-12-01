package com.android.channel.common

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SignatureException

class V2ApkUtils private constructor() {
    companion object {
        /**
         * The padding in APK SIG BLOCK (V3 scheme introduced)
         * See https://android.googlesource.com/platform/tools/apksig/+/master/src/main/java/com/android/apksig/internal/apk/ApkSigningBlockUtils.java
         */
        const val APK_SIG_PADDING_BLOCK_ID = 0x42726577
        const val ANDROID_COMMON_PAGE_ALIGNMENT_BYTES = 4096
        const val APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a

        //大端：高位数据存储于低位内存中               内存增长方向： 低    - >     高位
        //APK Sig Block 42，hex大端表示的话就是：41 50 4b 20 53 69 67 20      42 6c 6f 63 6b 20 34 32
        const val APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L
        const val APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L
        private const val APK_SIG_BLOCK_MIN_SIZE = 32 //8 + 8 + 16 初始分块长度8 在分块长度的值中不计此长度
        const val APK_SIG_BLOCK_SIZE_SPACE = 8
        const val APK_SIG_BLOCK_ID_VALUE_SIZE=8
        const val APK_SIG_BLOCK_ID_VALUE_ID_SIZE=4
        const val APK_SIG_BLOCK_MAGIC_SPACE = 16

        fun finContentEntry(apkFile: RandomAccessFile, length: Int): Pair<ByteBuffer, Long> {
            return Pair(getByteBuffer(apkFile, 0, length), 0)
        }

        fun findApkSigningBlock(
            apkFile: RandomAccessFile,
            centralDirOffset: Long
        ): Pair<ByteBuffer, Long> {
            //0x12345678 内存 低->高
            //大端存储 12  34  56  78
            //小端存储 78  56  34  12

            //签名分块格式
            //1、分块长度  8 bytes UInt64 (不计长度)
            //2、分块键值对数组：
            //            size长度：UInt64  8字节 ID-VALUE的长度
            //            ID ：(uint32) 4字节
            //            值：（可变长度：“size - 4 个字节）
            //3、分块大小  8 bytes UInt64
            //4、魔数     16 bytes UInt128 固定值为： APK Sig Block 42，hex大端表示的话就是：41 50 4b 20 3 69 67 20 42 6c 6f 63 6b 20 34 32
            if (centralDirOffset < APK_SIG_BLOCK_MIN_SIZE) {
                throw SignatureException("APK too small sig block size")
            }
            //分块大小  8 bytes UInt64
            // 魔数     16 bytes UInt128
            val footer = ByteBuffer.allocate(APK_SIG_BLOCK_SIZE_SPACE + APK_SIG_BLOCK_MAGIC_SPACE)
            footer.order(ByteOrder.LITTLE_ENDIAN)
            apkFile.seek(centralDirOffset - footer.capacity())
            apkFile.readFully(footer.array(), footer.arrayOffset(), footer.capacity())
            if ((footer.getLong(8) != APK_SIG_BLOCK_MAGIC_LO) || footer.getLong(16) != APK_SIG_BLOCK_MAGIC_HI) {
                throw SignatureException("no sig block before centralDir")
            }
            //这里的blockSize指的是在内存占用的位数 UInt64 最大长度为Int32位
            val apkSigBlockSizeInFooter = footer.getLong(0)
            if (apkSigBlockSizeInFooter < footer.capacity() || (apkSigBlockSizeInFooter > Int.MAX_VALUE - APK_SIG_BLOCK_SIZE_SPACE)) {
                throw SignatureException("apk signing block size out of range: $apkSigBlockSizeInFooter")
            }
            //虽然第一个分块长度不计整个block的size，但是在字节中会占8个字节位置
            val blockLength = (apkSigBlockSizeInFooter + APK_SIG_BLOCK_SIZE_SPACE).toInt()
            val apkSignBlockOffset = centralDirOffset - blockLength
            if (apkSignBlockOffset < 0) {
                throw SignatureException("apk sign block offset out of range:$apkSignBlockOffset")
            }
            return Pair(getByteBuffer(apkFile, apkSignBlockOffset, blockLength), apkSignBlockOffset)
        }

        fun findCentralDir(
            apkFile: RandomAccessFile, centralDirOffset: Long, centralLength: Int
        ): Pair<ByteBuffer, Long> {
            return Pair(getByteBuffer(apkFile, centralDirOffset, centralLength), centralDirOffset)
        }

        fun findEndOfCentralDirectoryRecord(apkFile: RandomAccessFile): Pair<ByteBuffer, Long> {
            return ZipUtils.findZipEndOfCentralDirectoryRecord(apkFile)
        }

        fun getCentralDirOffset(eocd: ByteBuffer, eocdOffset: Long): Long {
            if (eocd.order() != ByteOrder.LITTLE_ENDIAN) {
                throw IllegalArgumentException("ByteBuffer must be little endian")
            }
            val centralDirOffset = ZipUtils.getUnsignedInt32(
                eocd, eocd.position() + ZipUtils.ZIP_EOCD_CENTRAL_DIR_OFFSET_LOCATION
            )
            if (centralDirOffset >= eocdOffset) {
                throw SignatureException("centralDir offset: $centralDirOffset,eocd offset :$eocdOffset")
            }
            val centralDirSize = ZipUtils.getUnsignedInt32(
                eocd, eocd.position() + ZipUtils.ZIP_EOCD_CENTRAL_DIR_SIZE_LOCATION
            )
            if (centralDirOffset + centralDirSize != eocdOffset) {
                throw SignatureException("CentralDir is not immediately followed by EOCD")
            }
            return centralDirOffset
        }

        private fun getByteBuffer(
            apkFile: RandomAccessFile, offset: Long, length: Int
        ): ByteBuffer {
            val buffer = ByteBuffer.allocate(length)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            apkFile.seek(offset)
            apkFile.readFully(buffer.array(), buffer.arrayOffset(), buffer.capacity())
            return buffer
        }
    }
}