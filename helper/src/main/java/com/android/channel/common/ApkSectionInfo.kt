package com.android.channel.common

import java.nio.ByteBuffer

/**
 * zip组成：
 *          数据区                                     中央目录                        中央目录结尾记录 固定ID为0x06054b50D
 * Contents of Zip entries          |           Central Directory           |          End of Central Directory(EoCD)
 *  包含zip中所有文件的记录列表            目录信息列表，每条记录包含：文件名、            包含：中央目录条目数、size、起始偏移量、
 *  每条记录包含：                       压缩前后size、本地文件头的起始偏移量               zip文件注释长度、zip文件注释内容
 *  文件名、压缩前后size、                通过本地文件头的起始偏移量
 *  压缩后的数据等                         即可找到压缩后的数据
 *
 *  EoCd结构如下：
 *  Offset	Bytes	Description
 *      0	4	    中央目录目录结尾记录标记ID（0x06054b50）
 *      4	2	    当前磁盘编号
 *      6	2	    中央目录开始位置的磁盘编号
 *      8	2	    该磁盘上所记录的中央目录数量
 *      10	2	    中央目录结构总数
 *      12	4	    中央目录的大小
 *      16	4	    核心目录开始位置相对于archive开始的位移
 *      20	2	    注释长度
 *      22	n	    注释内容
 *
 *
 * V1签名方案：
 * 在APK文件的注释字段，添加渠道信息
 * 添加渠道信息步骤：
 * 1、生成渠道信息：格式：渠道字符串+渠道字符串长度(2个字节存储)+魔数
 * 2、复制APK
 * 3、找到EoCD数据块
 * 4、修改注释长度
 * 5、添加渠道信息
 * 读取渠道信息步骤：
 * 1、找到EoCD数据块
 * 2、定位魔数，获取2字节存储的渠道字符串长度
 * 3、根据渠道字符串长度，继续获取渠道字符串
 *
 *  V2签名方案：
 * apk签名前和zip结构一致，签名后的结构如下
 * Contents of Zip entries      |       Apk Signing Block(Id-Value)       |      Central Directory        |       End of Central Directory(EoCD)
 *
 * Apk V2签名会存储在固定ID为0x7109871a的值中，
 * APK 签名分块的格式如下（所有数字字段均采用小端字节序）
 * 添加渠道信息步骤：
 * 1、找到APK的EoCD块
 * 2、找到APK签名块
 * 3、获取已有的ID-Value Pair
 * 3、添加包含渠道信息的ID-Value
 * 5、基于所有的ID-Value生成新的签名块
 * 6、修改EOCD的中央目录的偏移量（上面已介绍过：修改EOCD的中央目录偏移量，不会导致数据摘要校验失败）
 * 7、用新的签名块替代旧的签名块，生成带有渠道信息的APK
 *
 * ByteBuffer:内容 Long 为起始偏移量
 */
data class ApkSectionInfo(
    val apkSize: Long,
    val contentEntry: Pair<ByteBuffer, Long>,
    val schemeV2Block: Pair<ByteBuffer, Long>,
    val centralDir: Pair<ByteBuffer, Long>,
    val eocd: Pair<ByteBuffer, Long>
) {
    fun checkParameters() {
        val result: Boolean = (contentEntry.second == 0L
                && contentEntry.first.remaining() + contentEntry.second == schemeV2Block.second)
                && (schemeV2Block.first.remaining() + schemeV2Block.second == centralDir.second)
                && (centralDir.first.remaining() + centralDir.second == eocd.second)
                && (eocd.first.remaining() + eocd.second == apkSize)
        if (!result) {
            throw RuntimeException("ApkSectionInfo is invalid" + toString())
        }
        checkEoCDCentralDirOffset()
    }

    fun rewind() {
        contentEntry.first.rewind()
        schemeV2Block.first.rewind()
        centralDir.first.rewind()
        eocd.first.rewind()
    }

    fun checkEoCDCentralDirOffset() {
        val centralDirOffset =
            V2ApkUtils.getCentralDirOffset(eocd.first, eocd.second)
        if (centralDirOffset != centralDir.second) {
            throw RuntimeException(
                "CentralDirOffset mismatch," +
                        " eOcdCentralOffset:$centralDirOffset,centralDirOffset:${centralDir.second}"
            )
        }
    }
}
