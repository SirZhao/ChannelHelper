package com.android.channel.reader

import com.android.channel.common.V2SchemeUtil
import java.io.File
import java.nio.ByteBuffer

class IdValueReader {

    companion object {

        fun getStringValueById(apkFile: File, id: Int): String {
            if (!apkFile.exists() || !apkFile.isFile) {
                println("apkFile is invalid")
                return ""
            }
            val value = getAllIdValueMap(apkFile)[id]
            return if (value == null) {
                println("apkFile has no channel id: $id")
                ""
            } else {
                try {
                    Charsets.UTF_8.decode(value).toString()
                } catch (ex: Exception) {
                    println("channel decode failure $value")
                    ""
                }
            }
        }

        fun getAllIdValueMap(apkFile: File): Map<Int, ByteBuffer> {
            if (!apkFile.exists() || !apkFile.isFile) {
                return mapOf()
            }
            val apkSigningBlock = V2SchemeUtil.getApkSigningBlock(apkFile)
            return V2SchemeUtil.getAllIdValue(apkSigningBlock)

        }
    }
}