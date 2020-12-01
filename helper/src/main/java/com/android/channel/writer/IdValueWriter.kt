package com.android.channel.writer

import com.android.channel.common.ApkSectionInfo
import com.android.channel.common.V2ApkUtils
import com.android.channel.common.V2SchemeUtil
import com.android.channel.common.ZipUtils
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class IdValueWriter {

    companion object {

        fun addIdValues(
            apkSectionInfo: ApkSectionInfo, destApk: File, idValues: MutableMap<Int, ByteBuffer>
        ) {
            if (idValues.isEmpty()) {
                throw RuntimeException("idValues is empty")
            }
            if (idValues.containsKey(V2ApkUtils.APK_SIGNATURE_SCHEME_V2_BLOCK_ID)) {
                idValues.remove(V2ApkUtils.APK_SIGNATURE_SCHEME_V2_BLOCK_ID)
            }
            println("add IdValues = $idValues")
            val existentIdValues = V2SchemeUtil.getAllIdValue(apkSectionInfo.schemeV2Block.first)
            if (!existentIdValues.containsKey(V2ApkUtils.APK_SIGNATURE_SCHEME_V2_BLOCK_ID)) {
                throw RuntimeException("No V2 signature scheme block in apk signing block")
            }
            println("exist IdValues = $existentIdValues")
            existentIdValues.putAll(idValues)
            println("current IdValues = $existentIdValues")
            generateNewApk(apkSectionInfo, destApk, existentIdValues)
        }

        fun removeIdValues(apkSectionInfo: ApkSectionInfo, destApk: File, idList: List<Int>) {
            if (!destApk.exists() || !destApk.isFile || idList.isEmpty()) {
                println("params invalid")
                return
            }
            val existentIdValues = V2SchemeUtil.getAllIdValue(apkSectionInfo.schemeV2Block.first)
            val idValueSize = existentIdValues.size
            if (!existentIdValues.containsKey(V2ApkUtils.APK_SIGNATURE_SCHEME_V2_BLOCK_ID)) {
                throw RuntimeException("No V2 signature scheme block in apk signing block")
            }
            println("exist IdValues = $existentIdValues")
            idList.forEach {
                if (it != V2ApkUtils.APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
                    existentIdValues.remove(it)
                }
            }
            if (idValueSize == existentIdValues.size) {
                println("No id was deleted")
                return
            }
            generateNewApk(apkSectionInfo, destApk, existentIdValues)
        }

        private fun generateNewApk(
            apkSectionInfo: ApkSectionInfo,
            destApk: File,
            idValues: MutableMap<Int, ByteBuffer>
        ) {
            val newApkSignBlock = V2SchemeUtil.generateApkSigningBlock(idValues)
            val contentEntry = apkSectionInfo.contentEntry.first
            val centralDir = apkSectionInfo.centralDir.first
            val eocd = apkSectionInfo.eocd.first
            val apkChangeSize =
                newApkSignBlock.remaining() - apkSectionInfo.schemeV2Block.first.remaining()
            //EoCD中央目录的偏移量
            ZipUtils.setZipEocdCentralDirectoryOffset(
                eocd, apkSectionInfo.centralDir.second + apkChangeSize
            )
            val newApkSize = apkSectionInfo.apkSize + apkChangeSize
            var newApk: RandomAccessFile? = null
            try {
                newApk = RandomAccessFile(destApk, "rw")
                newApk.seek(apkSectionInfo.contentEntry.second)
                newApk.write(
                    contentEntry.array(),
                    contentEntry.arrayOffset() + contentEntry.position(),
                    contentEntry.remaining()
                )
                newApk.write(
                    newApkSignBlock.array(),
                    newApkSignBlock.arrayOffset() + newApkSignBlock.position(),
                    newApkSignBlock.remaining()
                )
                newApk.write(
                    centralDir.array(),
                    centralDir.arrayOffset() + centralDir.position(),
                    centralDir.remaining()
                )
                newApk.write(eocd.array(), eocd.arrayOffset() + eocd.position(), eocd.remaining())
                if (newApk.filePointer != newApkSize) {
                    throw RuntimeException("file size is wrong , FilePointer : ${newApk.filePointer}, newApkSize:$newApkSize")
                }
                newApk.setLength(newApkSize)
                println("apk add channel completed,path = ${destApk.absolutePath},size = ${destApk.length()}")
            } finally {
                ZipUtils.setZipEocdCentralDirectoryOffset(eocd, apkSectionInfo.centralDir.second)
                newApk?.close()
            }
        }

        fun getApkSectionInfo(apkFile: File): ApkSectionInfo {
            return V2SchemeUtil.getApkSectionInfo(apkFile)
        }
    }
}