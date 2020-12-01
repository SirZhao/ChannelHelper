package com.android.channel.reader

import com.android.channel.common.V1SchemeUtil
import com.android.channel.common.V2SchemeUtil
import com.android.channel.writer.ChannelWriter
import java.io.File

class ChannelReader private constructor() {

    companion object {

        /**
         * 获取V1签名的渠道名称
         */
        fun getChannelByV1(channelApk: File): String {
            return V1SchemeUtil.readChannel(channelApk)
        }

        /**
         * 获取V2签名的渠道名称
         */
        fun getChannelByV2(channelApk: File): String {
            return IdValueReader.getStringValueById(channelApk, ChannelWriter.CHANNEL_BLOCK_ID);
        }

        /**
         * 判断是否存在V1签名
         */
        fun verifyV1Signature(apk: File): Boolean {
            if (!apk.exists() || !apk.isFile) {
                return false
            }
            return V1SchemeUtil.verifyV1Signature(apk)
        }

        /**
         * 判断是否存在V2签名
         */
        fun verifyV2Signature(apk: File): Boolean {
            if (!apk.exists() || !apk.isFile) {
                return false
            }
            return V2SchemeUtil.verifyV2Signature(apk)
        }
    }
}