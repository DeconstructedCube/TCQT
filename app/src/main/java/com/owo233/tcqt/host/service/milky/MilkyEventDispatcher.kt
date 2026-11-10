package com.owo233.tcqt.host.service.milky

import com.owo233.tcqt.core.env.launchWithCatch
import com.owo233.tcqt.core.hook.hookAfter
import com.owo233.tcqt.core.hook.hookBefore
import com.owo233.tcqt.core.log.LogUtils
import com.owo233.tcqt.core.sync.ModuleScope
import com.owo233.tcqt.host.QQInterfaces
import com.owo233.tcqt.host.service.ContactHelper
import com.owo233.tcqt.host.service.RecallPacketParser
import com.owo233.tcqt.host.service.milky.model.Event
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernelpublic.nativeinterface.GroupGrayElementType
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

object MilkyEventDispatcher {

    @Volatile
    var enabled: Boolean = false

    private val hookedMsgServices = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private val dispatchedMsgKeys: MutableSet<String> = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val added = super.add(element)
                if (size > 2048) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return added
            }
        }
    )

    fun onKernelServiceFetch(service: IKernelService) {
        if (!enabled) return
        val wrapper = runCatching { service.wrapperSession }.getOrNull() ?: return
        val msgService = runCatching { wrapper.msgService }.getOrNull() ?: return
        installMsgServiceHooks(msgService)
    }

    fun attachToCurrentRuntime() {
        if (!enabled) return
        runCatching {
            if (QQInterfaces.isLogin) {
                val msgService = QQInterfaces.msgService
                installMsgServiceHooks(msgService)
            }
        }
    }

    private fun installMsgServiceHooks(msgService: Any) {
        val serviceClass = msgService.javaClass
        if (!hookedMsgServices.add(serviceClass)) return

        runCatching {
            val listenerClass = Class.forName(
                "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener",
                false,
                serviceClass.classLoader,
            )
            val addListenerMethod = serviceClass.methods.firstOrNull {
                it.name == "addMsgListener" && it.parameterCount == 1
            }

            if (addListenerMethod != null) {
                val proxy = Proxy.newProxyInstance(
                    serviceClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, args ->
                    when (method.name) {
                        "onRecvMsg" -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = args?.getOrNull(0) as? ArrayList<MsgRecord>
                            if (!list.isNullOrEmpty()) {
                                handleIncomingRecords(list)
                            }
                            null
                        }
                        "onAddSendMsg" -> {
                            val record = args?.getOrNull(0) as? MsgRecord
                            if (record != null && MilkyServer.currentConfig.reportSelfMessage) {
                                handleIncomingRecords(arrayListOf(record))
                            }
                            null
                        }
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        "toString" -> "MilkyKernelMsgListener"
                        else -> null
                    }
                }
                addListenerMethod.invoke(msgService, proxy)
                LogUtils.xposedNoFilter.i("MilkyEventDispatcher registered IKernelMsgListener on ${serviceClass.name}")
            }

            // Also hook any registered IKernelMsgListener implementation classes as fallback
            addListenerMethod?.hookBefore { param ->
                val listenerObj = param.args.getOrNull(0) ?: return@hookBefore
                val lClz = listenerObj.javaClass
                if (Proxy.isProxyClass(lClz) || !hookedMsgServices.add(lClz)) return@hookBefore
                lClz.declaredMethods.firstOrNull { it.name == "onRecvMsg" && it.parameterCount == 1 }
                    ?.hookAfter { recvParam ->
                        @Suppress("UNCHECKED_CAST")
                        val list = recvParam.args.getOrNull(0) as? ArrayList<MsgRecord>
                        if (!list.isNullOrEmpty()) {
                            handleIncomingRecords(list)
                        }
                    }
            }
        }.onFailure {
            LogUtils.xposedNoFilter.e("MilkyEventDispatcher failed to hook MsgService", it)
        }
    }

    fun onMsfPush(cmd: String, buffer: ByteArray) {
        if (!enabled) return
        if (cmd == "trpc.msg.olpush.OlPushService.MsgPush" ||
            cmd == "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"
        ) {
            ModuleScope.launchWithCatch {
                val currentUid = runCatching { QQInterfaces.currentUid }.getOrDefault("")
                if (currentUid.isEmpty()) return@launchWithCatch
                val res = if (cmd.endsWith("MsgPush")) {
                    RecallPacketParser.msgPush(buffer, currentUid)
                } else {
                    RecallPacketParser.infoSyncPush(buffer, currentUid)
                }
                for (recall in res.recalls) {
                    dispatchRecallFromPacket(recall)
                }
            }
        }
    }

    private suspend fun dispatchRecallFromPacket(recall: RecallPacketParser.Recall) {
        val selfUin = runCatching { QQInterfaces.currentUin.toLongOrNull() }.getOrNull() ?: return
        val key = "recall_${recall.group}_${recall.peerId}_${recall.msgSeq}"
        if (!dispatchedMsgKeys.add(key)) return

        val peerIdLong = if (recall.group) {
            recall.peerId.toLongOrNull() ?: return
        } else {
            if (recall.peerId.startsWith("u_")) {
                ContactHelper.getUinByUidAsync(recall.peerId).toLongOrNull() ?: return
            } else {
                recall.peerId.toLongOrNull() ?: return
            }
        }
        val senderIdLong = if (recall.senderUid.startsWith("u_")) {
            ContactHelper.getUinByUidAsync(recall.senderUid).toLongOrNull() ?: 0L
        } else {
            recall.senderUid.toLongOrNull() ?: 0L
        }
        val operatorIdLong = if (recall.operatorUid.startsWith("u_")) {
            ContactHelper.getUinByUidAsync(recall.operatorUid).toLongOrNull() ?: senderIdLong
        } else {
            recall.operatorUid.toLongOrNull() ?: senderIdLong
        }

        val event = Event.MessageRecall(
            time = System.currentTimeMillis() / 1000L,
            selfId = selfUin,
            data = Event.MessageRecall.Data(
                messageScene = if (recall.group) "group" else "friend",
                peerId = peerIdLong,
                messageSeq = recall.msgSeq,
                senderId = senderIdLong,
                operatorId = operatorIdLong,
                displaySuffix = "",
            ),
        )
        MilkyServer.broadcastEvent(event)
    }

    private fun handleIncomingRecords(records: List<MsgRecord>) {
        if (!enabled) return
        ModuleScope.launchWithCatch {
            val selfUin = runCatching { QQInterfaces.currentUin.toLongOrNull() }.getOrNull() ?: return@launchWithCatch
            for (record in records) {
                val dedupKey = "msg_${record.chatType}_${record.peerUid}_${record.msgId}_${record.msgSeq}"
                if (!dispatchedMsgKeys.add(dedupKey)) continue

                MilkySegmentConverter.cacheRecord(record)

                if (record.msgType == MsgConstant.KMSGTYPEGRAYTIPS) {
                    handleGrayTipRecord(selfUin, record)
                    continue
                }

                // Check for file upload event
                val fileElem = record.elements?.firstOrNull { it.fileElement != null }?.fileElement
                if (fileElem != null) {
                    dispatchFileUploadEvent(selfUin, record, fileElem)
                }

                val isSelf = record.sendType != MsgConstant.KSENDTYPERECV && record.senderUin == selfUin
                if (isSelf && !MilkyServer.currentConfig.reportSelfMessage) {
                    continue
                }

                val incoming = MilkySegmentConverter.toIncomingMessage(record) ?: continue
                if (incoming.segments.isEmpty()) continue

                val event = Event.MessageReceive(
                    time = if (record.msgTime > 0L) record.msgTime else (System.currentTimeMillis() / 1000L),
                    selfId = selfUin,
                    data = incoming,
                )
                MilkyServer.broadcastEvent(event)
            }
        }
    }

    private suspend fun dispatchFileUploadEvent(
        selfUin: Long,
        record: MsgRecord,
        fileElem: com.tencent.qqnt.kernel.nativeinterface.FileElement,
    ) {
        val now = if (record.msgTime > 0L) record.msgTime else (System.currentTimeMillis() / 1000L)
        val peerId = MilkySegmentConverter.resolvePeerUin(record)
        val senderId = MilkySegmentConverter.resolveSenderUin(record)
        val fileId = fileElem.fileUuid?.takeIf { it.isNotEmpty() } ?: fileElem.fileMd5.orEmpty()
        if (record.chatType == MsgConstant.KCHATTYPEGROUP && peerId > 0L) {
            MilkyServer.broadcastEvent(
                Event.GroupFileUpload(
                    time = now,
                    selfId = selfUin,
                    data = Event.GroupFileUpload.Data(
                        groupId = peerId,
                        userId = senderId,
                        fileId = fileId,
                        fileName = fileElem.fileName.orEmpty(),
                        fileSize = fileElem.fileSize,
                    ),
                )
            )
        } else if (record.chatType == MsgConstant.KCHATTYPEC2C && peerId > 0L) {
            MilkyServer.broadcastEvent(
                Event.FriendFileUpload(
                    time = now,
                    selfId = selfUin,
                    data = Event.FriendFileUpload.Data(
                        userId = peerId,
                        fileId = fileId,
                        fileName = fileElem.fileName.orEmpty(),
                        fileSize = fileElem.fileSize,
                        fileHash = fileElem.fileSha?.takeIf { it.isNotEmpty() } ?: fileElem.fileMd5.orEmpty(),
                        isSelf = senderId == selfUin,
                    ),
                )
            )
        }
    }

    private suspend fun handleGrayTipRecord(selfUin: Long, record: MsgRecord) {
        val now = if (record.msgTime > 0L) record.msgTime else (System.currentTimeMillis() / 1000L)
        val peerId = MilkySegmentConverter.resolvePeerUin(record)
        val elements = record.elements ?: return

        for (elem: MsgElement in elements) {
            val gray = elem.grayTipElement ?: continue

            gray.revokeElement?.let { rev ->
                val opUin = if (rev.operatorUid.startsWith("u_")) {
                    ContactHelper.getUinByUidAsync(rev.operatorUid).toLongOrNull() ?: 0L
                } else rev.operatorUid.toLongOrNull() ?: 0L
                val senderUin = if (rev.origMsgSenderUid.startsWith("u_")) {
                    ContactHelper.getUinByUidAsync(rev.origMsgSenderUid).toLongOrNull() ?: opUin
                } else rev.origMsgSenderUid.toLongOrNull() ?: opUin
                val scene = if (record.chatType == MsgConstant.KCHATTYPEGROUP) "group" else "friend"
                val key = "recall_${record.chatType == MsgConstant.KCHATTYPEGROUP}_${peerId}_${record.msgSeq}"
                if (dispatchedMsgKeys.add(key)) {
                    MilkyServer.broadcastEvent(
                        Event.MessageRecall(
                            time = now,
                            selfId = selfUin,
                            data = Event.MessageRecall.Data(
                                messageScene = scene,
                                peerId = peerId,
                                messageSeq = record.msgSeq,
                                senderId = senderUin,
                                operatorId = opUin,
                                displaySuffix = rev.wording.orEmpty(),
                            ),
                        )
                    )
                }
            }

            gray.emojiReplyElement?.let { emoji ->
                if (peerId > 0L) {
                    MilkyServer.broadcastEvent(
                        Event.GroupMessageReaction(
                            time = now,
                            selfId = selfUin,
                            data = Event.GroupMessageReaction.Data(
                                groupId = peerId,
                                userId = emoji.tinyId,
                                messageSeq = emoji.msgSeq,
                                faceId = emoji.emojiId.toString(),
                                reactionType = if (emoji.emojiType == 2) "emoji" else "face",
                                isAdd = true,
                            ),
                        )
                    )
                }
            }

            gray.groupElement?.let { grp ->
                if (peerId <= 0L) return@let
                grp.shutUp?.let { shut ->
                    val adminUid = shut.admin?.uid.orEmpty()
                    val memberUid = shut.member?.uid.orEmpty()
                    val opUin = if (adminUid.startsWith("u_")) {
                        ContactHelper.getUinByUidAsync(adminUid).toLongOrNull() ?: 0L
                    } else adminUid.toLongOrNull() ?: 0L

                    if (memberUid.isEmpty() || memberUid == "0") {
                        MilkyServer.broadcastEvent(
                            Event.GroupWholeMute(
                                time = now,
                                selfId = selfUin,
                                data = Event.GroupWholeMute.Data(
                                    groupId = peerId,
                                    operatorId = opUin,
                                    isMute = shut.duration > 0L,
                                ),
                            )
                        )
                    } else {
                        val targetUin = if (memberUid.startsWith("u_")) {
                            ContactHelper.getUinByUidAsync(memberUid).toLongOrNull() ?: 0L
                        } else memberUid.toLongOrNull() ?: 0L
                        MilkyServer.broadcastEvent(
                            Event.GroupMute(
                                time = now,
                                selfId = selfUin,
                                data = Event.GroupMute.Data(
                                    groupId = peerId,
                                    userId = targetUin,
                                    operatorId = opUin,
                                    duration = shut.duration.toInt(),
                                ),
                            )
                        )
                    }
                }

                val memberUin = if (grp.memberUid.startsWith("u_")) {
                    ContactHelper.getUinByUidAsync(grp.memberUid).toLongOrNull() ?: 0L
                } else grp.memberUid.toLongOrNull() ?: 0L
                val adminUin = if (grp.adminUid.startsWith("u_")) {
                    ContactHelper.getUinByUidAsync(grp.adminUid).toLongOrNull()
                } else grp.adminUid.toLongOrNull()

                when (grp.type) {
                    GroupGrayElementType.KMEMBERADD -> {
                        if (memberUin > 0L) {
                            MilkyServer.broadcastEvent(
                                Event.GroupMemberIncrease(
                                    time = now,
                                    selfId = selfUin,
                                    data = Event.GroupMemberIncrease.Data(
                                        groupId = peerId,
                                        userId = memberUin,
                                        operatorId = adminUin,
                                        invitorId = null,
                                    ),
                                )
                            )
                        }
                    }
                    GroupGrayElementType.KQUITTED -> {
                        if (memberUin > 0L) {
                            MilkyServer.broadcastEvent(
                                Event.GroupMemberDecrease(
                                    time = now,
                                    selfId = selfUin,
                                    data = Event.GroupMemberDecrease.Data(
                                        groupId = peerId,
                                        userId = memberUin,
                                        operatorId = adminUin,
                                    ),
                                )
                            )
                        }
                    }
                    GroupGrayElementType.KGROUPNAMEMODIFIED -> {
                        if (grp.groupName.isNotEmpty()) {
                            MilkyServer.broadcastEvent(
                                Event.GroupNameChange(
                                    time = now,
                                    selfId = selfUin,
                                    data = Event.GroupNameChange.Data(
                                        groupId = peerId,
                                        newGroupName = grp.groupName,
                                        operatorId = adminUin ?: memberUin,
                                    ),
                                )
                            )
                        }
                    }
                    GroupGrayElementType.KDISBANDED -> {
                        MilkyServer.broadcastEvent(
                            Event.GroupDisband(
                                time = now,
                                selfId = selfUin,
                                data = Event.GroupDisband.Data(
                                    groupId = peerId,
                                    operatorId = adminUin ?: memberUin,
                                ),
                            )
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}
