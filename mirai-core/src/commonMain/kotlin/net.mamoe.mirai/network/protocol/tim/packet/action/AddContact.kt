@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_UNSIGNED_LITERALS")

package net.mamoe.mirai.network.protocol.tim.packet.action

import kotlinx.io.core.ByteReadPacket
import kotlinx.io.core.discardExact
import kotlinx.io.core.readUByte
import kotlinx.io.core.readUInt
import net.mamoe.mirai.contact.QQ
import net.mamoe.mirai.network.BotNetworkHandler
import net.mamoe.mirai.network.protocol.tim.TIMProtocol
import net.mamoe.mirai.network.protocol.tim.packet.*
import net.mamoe.mirai.network.protocol.tim.packet.event.EventPacket
import net.mamoe.mirai.utils.io.*


// 01BC 曾用名查询. 查到的是这个人的
// 发送  00 00
//       3E 03 3F A2 //bot
//       59 17 3E 05 //目标
//
// 接受: 00 00 00 03
//      [00 00 00 0C] E6 A5 BC E4 B8 8A E5 B0 8F E7 99 BD
//      [00 00 00 10] 68 69 6D 31 38 38 E7 9A 84 E5 B0 8F 64 69 63 6B
//      [00 00 00 0F] E4 B8 B6 E6 9A 97 E8 A3 94 E5 89 91 E9 AD 94

/**
 * 查询某人与机器人账号有关的曾用名 (备注).
 *
 * 曾用名可能是:
 * - 昵称
 * - 共同群内的群名片
 */
@PacketVersion(date = "2019.11.02", timVersion = "2.3.2.21173")
object QueryPreviousNamePacket : SessionPacketFactory<QueryPreviousNameResponse>() {
    operator fun invoke(
        bot: UInt,
        sessionKey: SessionKey,
        target: UInt
    ): OutgoingPacket = buildSessionPacket(bot, sessionKey) {
        writeZero(2)
        writeQQ(bot)
        writeQQ(target)
    }

    override suspend fun ByteReadPacket.decode(id: PacketId, sequenceId: UShort, handler: BotNetworkHandler<*>): QueryPreviousNameResponse =
        QueryPreviousNameResponse().apply {
            names = Array(readUInt().toInt()) {
                discardExact(2)
                readUShortLVString()
            }
        }
}

class QueryPreviousNameResponse : Packet {
    lateinit var names: Array<String>
}

// 需要验证消息
// 0065 发送 03 07 57 37 E8
// 0065 接受 03 07 57 37 E8 10 40 00 00 10 14 20 00 00 00 00 00 00 00 01 00 00 00 00 00


/**
 * 添加好友结果
 *
 * @author Him188moe
 */
enum class AddFriendResult {
    /**
     * 等待对方处理
     */
    WAITING_FOR_AGREEMENT,

    /**
     * 和对方已经是好友了
     */
    ALREADY_ADDED,

    /**
     * 对方设置为不添加好友等
     */
    FAILED,
}

/**
 * 向服务器检查是否可添加某人为好友
 *
 * @author Him188moe
 */
@AnnotatedId(KnownPacketId.CAN_ADD_FRIEND)
object CanAddFriendPacket : SessionPacketFactory<CanAddFriendResponse>() {
    operator fun invoke(
        bot: UInt,
        qq: UInt,
        sessionKey: SessionKey
    ): OutgoingPacket = buildSessionPacket(bot, sessionKey) {
        writeQQ(qq)
    }

    override suspend fun ByteReadPacket.decode(id: PacketId, sequenceId: UShort, handler: BotNetworkHandler<*>): CanAddFriendResponse = with(handler.bot) {
        if (remaining > 20) {//todo check
            return CanAddFriendResponse.AlreadyAdded(readUInt().qq())
        }
        val qq: QQ = readUInt().qq()

        return when (val state = readUByte().toUInt()) {
            0x00u -> CanAddFriendResponse.ReadyToAdd(qq)
            0x01u -> CanAddFriendResponse.RequireVerification(qq)
            0x99u -> CanAddFriendResponse.AlreadyAdded(qq)

            0x03u,
            0x04u -> CanAddFriendResponse.Rejected(qq)
            else -> error(state.toString())
        }
    }

}

sealed class CanAddFriendResponse : EventPacket {
    abstract val qq: QQ

    /**
     * 已经添加
     */
    data class AlreadyAdded(
        override val qq: QQ
    ) : CanAddFriendResponse()

    /**
     * 需要验证信息
     */
    data class RequireVerification(
        override val qq: QQ
    ) : CanAddFriendResponse()

    /**
     * 不需要验证信息
     */
    data class ReadyToAdd(
        override val qq: QQ
    ) : CanAddFriendResponse()

    /**
     * 对方拒绝添加
     */
    data class Rejected(
        override val qq: QQ
    ) : CanAddFriendResponse()
}


/**
 * 请求添加好友
 */
@AnnotatedId(KnownPacketId.CAN_ADD_FRIEND)
object AddFriendPacket : SessionPacketFactory<AddFriendPacket.AddFriendResponse>() {
    operator fun invoke(
        bot: UInt,
        qq: UInt,
        sessionKey: SessionKey
    ): OutgoingPacket = buildOutgoingPacket {
        writeQQ(bot)
        writeHex(TIMProtocol.fixVer2)
        encryptAndWrite(sessionKey) {
            writeHex("01 00 01")
            writeQQ(qq)
        }
    }

    override suspend fun BotNetworkHandler<*>.handlePacket(packet: AddFriendResponse) {

    }


    class AddFriendResponse : Packet


    override suspend fun ByteReadPacket.decode(id: PacketId, sequenceId: UShort, handler: BotNetworkHandler<*>): AddFriendResponse {
        TODO()
    }
}
