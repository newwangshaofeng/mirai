@file:Suppress("EXPERIMENTAL_API_USAGE", "unused")

package net.mamoe.mirai

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mamoe.mirai.Bot.ContactSystem
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.network.BotNetworkHandler
import net.mamoe.mirai.network.protocol.tim.TIMBotNetworkHandler
import net.mamoe.mirai.network.protocol.tim.packet.action.AddFriendPacket
import net.mamoe.mirai.network.protocol.tim.packet.action.CanAddFriendPacket
import net.mamoe.mirai.network.protocol.tim.packet.action.CanAddFriendResponse
import net.mamoe.mirai.network.protocol.tim.packet.login.LoginResult
import net.mamoe.mirai.network.sessionKey
import net.mamoe.mirai.utils.BotConfiguration
import net.mamoe.mirai.utils.DefaultLogger
import net.mamoe.mirai.utils.MiraiLogger
import net.mamoe.mirai.utils.internal.coerceAtLeastOrFail
import kotlin.jvm.JvmOverloads

data class BotAccount(
    val id: UInt,
    val password: String//todo 不保存 password?
)

/**
 * Mirai 的机器人. 一个机器人实例登录一个 QQ 账号.
 * Mirai 为多账号设计, 可同时维护多个机器人.
 *
 * [Bot] 由 3 个模块组成.
 * [联系人管理][ContactSystem]: 可通过 [Bot.contacts] 访问
 * [网络处理器][TIMBotNetworkHandler]: 可通过 [Bot.network] 访问
 * [机器人账号信息][BotAccount]: 可通过 [Bot.qqAccount] 访问
 *
 * 若需要得到机器人的 QQ 账号, 请访问 [Bot.qqAccount]
 * 若需要得到服务器上所有机器人列表, 请访问 [Bot.instances]
 *
 * 在 BotHelper.kt 中有一些访问的捷径. 如 [Bot.getGroup]
 *
 *
 *
 * Bot that is the base of the whole program.
 * It consists of
 * a [ContactSystem], which manage contacts such as [QQ] and [Group];
 * a [TIMBotNetworkHandler], which manages the connection to the server;
 * a [BotAccount], which stores the account information(e.g. qq number the bot)
 *
 * To of all the QQ contacts, access [Bot.qqAccount]
 * To of all the Robot instance, access [Bot.instances]
 *
 *
 * @author Him188moe
 * @author NaturalHG
 * @see Contact
 */
class Bot(val account: BotAccount, val logger: MiraiLogger) {
    constructor(qq: UInt, password: String) : this(BotAccount(qq, password))
    constructor(account: BotAccount) : this(account, DefaultLogger("Bot(" + account.id + ")"))

    val contacts = ContactSystem()

    var network: BotNetworkHandler<*> = TIMBotNetworkHandler(this)

    init {
        instances.add(this)
    }

    override fun toString(): String = "Bot(${account.id})"

    /**
     * [关闭][BotNetworkHandler.close]网络处理器, 取消所有运行在 [BotNetworkHandler] 下的协程.
     * 然后重新启动并尝试登录
     */
    @JvmOverloads
    suspend fun reinitializeNetworkHandler(
        configuration: BotConfiguration,
        cause: Throwable? = null
    ): LoginResult {
        logger.info("Initializing BotNetworkHandler")
        try {
            network.close(cause)
        } catch (e: Exception) {
            logger.error(e)
        }
        network = TIMBotNetworkHandler(this)
        return network.login(configuration)
    }

    /**
     * Bot 联系人管理.
     *
     * @see Bot.contacts
     */
    inner class ContactSystem internal constructor() {
        inline val bot: Bot get() = this@Bot

        private val _groups = ContactList<Group>()
        private lateinit var groupsUpdater: Job
        val groups = ContactList<Group>()
        private val groupsLock = Mutex()

        private val _qqs = ContactList<QQ>() //todo 实现群列表和好友列表获取
        private lateinit var qqUpdaterJob: Job
        val qqs: ContactList<QQ> = _qqs
        private val qqsLock = Mutex()

        /**
         * 获取缓存的 QQ 对象. 若没有对应的缓存, 则会创建一个.
         *
         * 注: 这个方法是线程安全的
         */
        suspend fun getQQ(id: UInt): QQ =
            if (qqs.containsKey(id)) qqs[id]!!
            else qqsLock.withLock {
                qqs.getOrPut(id) { QQ(bot, id) }
            }

        /**
         * 获取缓存的群对象. 若没有对应的缓存, 则会创建一个.
         *
         * 注: 这个方法是线程安全的
         */
        suspend fun getGroup(internalId: GroupInternalId): Group = getGroup(internalId.toId())

        /**
         * 获取缓存的群对象. 若没有对应的缓存, 则会创建一个.
         *
         * 注: 这个方法是线程安全的
         */
        suspend fun getGroup(id: GroupId): Group = id.value.let {
            if (groups.containsKey(it)) groups[it]!!
            else groupsLock.withLock {
                groups.getOrPut(it) { Group(bot, id) }
            }
        }

    }

    suspend inline fun Int.qq(): QQ = getQQ(this.coerceAtLeastOrFail(0).toUInt())
    suspend inline fun Long.qq(): QQ = getQQ(this.coerceAtLeastOrFail(0))
    suspend inline fun UInt.qq(): QQ = getQQ(this)

    suspend inline fun Int.group(): Group = getGroup(this.coerceAtLeastOrFail(0).toUInt())
    suspend inline fun Long.group(): Group = getGroup(this.coerceAtLeastOrFail(0))
    suspend inline fun UInt.group(): Group = getGroup(GroupId(this))
    suspend inline fun GroupId.group(): Group = getGroup(this)
    suspend inline fun GroupInternalId.group(): Group = getGroup(this)

    suspend fun close() {
        this.network.close()
        this.contacts.groups.clear()
        this.contacts.qqs.clear()
    }

    companion object {
        val instances: MutableList<Bot> = mutableListOf()
    }
}

@Suppress("ClassName")
sealed class AddFriendResult {

    open class SUCCESS internal constructor() : AddFriendResult() {
        companion object : SUCCESS()

        override fun toString(): String = "AddFriendResult(Success)"
    }

    /**
     * 对方拒绝添加好友
     */
    object REJECTED : AddFriendResult() {
        override fun toString(): String = "AddFriendResult(Rejected)"
    }

    /**
     * 这个人已经是好友
     */
    object ALREADY_ADDED : SUCCESS() {
        override fun toString(): String = "AddFriendResult(AlreadyAdded)"
    }
}

/**
 * 添加一个好友
 *
 * @param lazyMessage 若需要验证请求时的验证消息.
 */
suspend fun ContactSystem.addFriend(id: UInt, lazyMessage: () -> String = { "" }): AddFriendResult = bot.withSession {
    when (CanAddFriendPacket(bot.qqAccount, id, bot.sessionKey).sendAndExpect<CanAddFriendResponse>().await()) {
        is CanAddFriendResponse.AlreadyAdded -> AddFriendResult.ALREADY_ADDED
        is CanAddFriendResponse.Rejected -> AddFriendResult.REJECTED
        is CanAddFriendResponse.RequireVerification -> {

            TODO()
        }
        is CanAddFriendResponse.ReadyToAdd -> {
            AddFriendPacket(bot.qqAccount, id, bot.sessionKey).sendAndExpect<AddFriendPacket.AddFriendResponse>().await()
            TODO()
        }
    }
}
