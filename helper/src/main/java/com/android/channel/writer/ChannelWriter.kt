package com.android.channel.writer

import com.android.channel.common.ApkSectionInfo
import com.android.channel.common.V1SchemeUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChannelWriter {

    companion object {
        //自定义 channel 在 block 中的值
        const val CHANNEL_BLOCK_ID:Int = 0x71777777
        val CHANNEL_BLOCK_V1_MAGIC = "zdd&wyj".toByteArray()

        /**
         * 向V1签名的apk中添加渠道信息
         */
        fun addChannelByV1(srcApk: File, destApk: File, channel: String) {
            srcApk.copyTo(destApk, true)
            V1SchemeUtil.writeChannel(destApk, channel)
        }

        /**
         * 向V2签名的apk中添加渠道信息
         */
        fun addChannelByV2(srcApk: File, destApk: File, channel: String) {
            val apkSectionInfo = IdValueWriter.getApkSectionInfo(srcApk)
            addChannelByV2(apkSectionInfo, destApk, channel)
        }

        /**
         * 向V2签名的apk中添加渠道信息
         */
        fun addChannelByV2(apkSectionInfo: ApkSectionInfo, destApk: File, channel: String) {
            if (destApk.parentFile != null && !destApk.parentFile!!.exists()) {
                destApk.parentFile!!.mkdirs()
            }
            val channelByteBuffer = ByteBuffer.wrap(channel.toByteArray())
            channelByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            IdValueWriter.addIdValues(
                apkSectionInfo,
                destApk,
                mutableMapOf(CHANNEL_BLOCK_ID to channelByteBuffer)
            )
            apkSectionInfo.checkEoCDCentralDirOffset()
        }

        /**
         * 删除V1签名包中的渠道信息
         */
        fun removeChannelByV1(destApk: File) {
            V1SchemeUtil.removeChannelByV1(destApk)
        }

        /**
         * 删除V2签名包中的渠道信息
         */
        fun removeChannelByV2(destApk: File) {
            val apkSectionInfo = IdValueWriter.getApkSectionInfo(destApk)
            IdValueWriter.removeIdValues(apkSectionInfo, destApk, listOf(CHANNEL_BLOCK_ID))
            apkSectionInfo.checkEoCDCentralDirOffset()
        }

    }
}