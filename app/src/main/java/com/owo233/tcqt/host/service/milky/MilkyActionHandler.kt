package com.owo233.tcqt.host.service.milky

import com.owo233.tcqt.core.env.CalculationUtils
import com.owo233.tcqt.core.env.HookEnv
import com.owo233.tcqt.core.env.load
import com.owo233.tcqt.core.proto.ProtoMap
import com.owo233.tcqt.core.reflect.callMethod
import com.owo233.tcqt.core.reflect.getFields
import com.owo233.tcqt.core.reflect.getMethods
import com.owo233.tcqt.host.QQInterfaces
import com.owo233.tcqt.host.service.ContactHelper
import com.owo233.tcqt.host.service.GroupHelper
import com.owo233.tcqt.host.service.TicketManager
import com.owo233.tcqt.host.service.api.GroupService
import com.owo233.tcqt.host.service.api.PacketHelper
import com.owo233.tcqt.host.service.maple.MapleContact
import com.owo233.tcqt.host.service.milky.model.*
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo
import com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole
import com.tencent.qqnt.msg.api.IMsgService
import com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService
import com.tencent.qqnt.troop.ITroopListRepoApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

object MilkyActionHandler {

    private val KNOWN_ACTIONS = setOf(
        "get_login_info",
        "get_impl_info",
        "get_user_profile",
        "get_friend_list",
        "get_friend_info",
        "get_group_list",
        "get_group_info",
        "get_group_member_list",
        "get_group_member_info",
        "get_peer_pins",
        "set_peer_pin",
        "set_avatar",
        "set_nickname",
        "set_bio",
        "get_custom_face_url_list",
        "get_cookies",
        "get_csrf_token",
        "send_private_message",
        "send_group_message",
        "recall_private_message",
        "recall_group_message",
        "get_message",
        "get_history_messages",
        "get_resource_temp_url",
        "get_forwarded_messages",
        "mark_message_as_read",
        "send_friend_nudge",
        "send_profile_like",
        "delete_friend",
        "get_friend_requests",
        "accept_friend_request",
        "reject_friend_request",
        "set_group_name",
        "set_group_avatar",
        "set_group_member_card",
        "set_group_member_special_title",
        "set_group_member_admin",
        "set_group_member_mute",
        "set_group_whole_mute",
        "kick_group_member",
        "get_group_announcements",
        "send_group_announcement",
        "delete_group_announcement",
        "get_group_essence_messages",
        "set_group_essence_message",
        "quit_group",
        "send_group_message_reaction",
        "send_group_nudge",
        "get_group_notifications",
        "accept_group_request",
        "reject_group_request",
        "accept_group_invitation",
        "reject_group_invitation",
        "upload_private_file",
        "upload_group_file",
        "get_private_file_download_url",
        "get_group_file_download_url",
        "get_group_files",
        "move_group_file",
        "rename_group_file",
        "delete_group_file",
        "persist_group_file",
        "create_group_folder",
        "rename_group_folder",
        "delete_group_folder",
    )

    fun isKnownAction(action: String): Boolean = action in KNOWN_ACTIONS

    fun encodeGeneralResponse(resp: ApiGeneralResponse, echo: JsonElement? = null): String {
        val base = milkyJsonModule.encodeToJsonElement(ApiGeneralResponse.serializer(), resp) as JsonObject
        if (echo == null) {
            return milkyJsonModule.encodeToString(JsonObject.serializer(), base)
        }
        val merged = buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("echo", echo)
        }
        return milkyJsonModule.encodeToString(JsonObject.serializer(), merged)
    }

    fun handleHttpAction(action: String, bodyJson: String, echo: JsonElement? = null): String {
        val resp = try {
            runBlocking {
                dispatchAction(action, bodyJson.ifBlank { "{}" })
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            ApiGeneralResponse(
                status = "failed",
                retcode = -400,
                message = "参数解析失败: ${e.message}",
            )
        } catch (e: IllegalArgumentException) {
            ApiGeneralResponse(
                status = "failed",
                retcode = -400,
                message = e.message ?: "参数不合法",
            )
        } catch (t: Throwable) {
            ApiGeneralResponse(
                status = "failed",
                retcode = -500,
                message = t.message ?: t.javaClass.simpleName,
            )
        }
        return encodeGeneralResponse(resp, echo)
    }

    private fun <T> decodeInput(serializer: KSerializer<T>, raw: String): T {
        return milkyJsonModule.decodeFromString(serializer, raw)
    }

    private fun <R> okResponse(serializer: KSerializer<R>, data: R): ApiGeneralResponse {
        val elem = milkyJsonModule.encodeToJsonElement(serializer, data)
        return ApiGeneralResponse(status = "ok", retcode = 0, data = elem)
    }

    private fun okEmpty(): ApiGeneralResponse {
        return ApiGeneralResponse(status = "ok", retcode = 0, data = JsonObject(emptyMap()))
    }

    private suspend fun dispatchAction(action: String, bodyJson: String): ApiGeneralResponse {
        return when (action) {
            // ── 系统 API ──
            "get_login_info" -> {
                val uin = QQInterfaces.currentUin.toLongOrNull() ?: 0L
                val friend = MilkySegmentConverter.buildFriendEntity(uin)
                val nick = friend.nickname.ifEmpty {
                    runCatching { QQInterfaces.appRuntime.currentNickname }.getOrNull().orEmpty()
                }
                okResponse(GetLoginInfoOutput.serializer(), GetLoginInfoOutput(uin = uin, nickname = nick))
            }

            "get_impl_info" -> {
                okResponse(
                    GetImplInfoOutput.serializer(),
                    GetImplInfoOutput(
                        implName = "TCQT-Milky",
                        implVersion = HookEnv.versionName,
                        qqProtocolVersion = HookEnv.versionName,
                        qqProtocolType = if (HookEnv.isTIM()) "android_tim" else "android_phone",
                        milkyVersion = milkyVersion,
                    ),
                )
            }

            "get_user_profile" -> {
                val input = decodeInput(GetUserProfileInput.serializer(), bodyJson)
                val friend = MilkySegmentConverter.buildFriendEntity(input.userId)
                okResponse(
                    GetUserProfileOutput.serializer(),
                    GetUserProfileOutput(
                        nickname = friend.nickname,
                        qid = friend.qid,
                        age = 0,
                        sex = friend.sex,
                        remark = friend.remark,
                        bio = "",
                        level = 0,
                        country = "",
                        city = "",
                        school = "",
                    ),
                )
            }

            "get_friend_list" -> {
                val friends = fetchFriendList()
                okResponse(GetFriendListOutput.serializer(), GetFriendListOutput(friends = friends))
            }

            "get_friend_info" -> {
                val input = decodeInput(GetFriendInfoInput.serializer(), bodyJson)
                val friend = MilkySegmentConverter.buildFriendEntity(input.userId)
                okResponse(GetFriendInfoOutput.serializer(), GetFriendInfoOutput(friend = friend))
            }

            "get_group_list" -> {
                val groups = fetchGroupList()
                okResponse(GetGroupListOutput.serializer(), GetGroupListOutput(groups = groups))
            }

            "get_group_info" -> {
                val input = decodeInput(GetGroupInfoInput.serializer(), bodyJson)
                val group = MilkySegmentConverter.buildGroupEntity(input.groupId)
                okResponse(GetGroupInfoOutput.serializer(), GetGroupInfoOutput(group = group))
            }

            "get_group_member_list" -> {
                val input = decodeInput(GetGroupMemberListInput.serializer(), bodyJson)
                val members = fetchGroupMemberList(input.groupId)
                okResponse(GetGroupMemberListOutput.serializer(), GetGroupMemberListOutput(members = members))
            }

            "get_group_member_info" -> {
                val input = decodeInput(GetGroupMemberInfoInput.serializer(), bodyJson)
                val member = fetchSingleGroupMember(input.groupId, input.userId)
                okResponse(GetGroupMemberInfoOutput.serializer(), GetGroupMemberInfoOutput(member = member))
            }

            "get_peer_pins" -> {
                val groups = fetchGroupList().filter { g ->
                    runCatching {
                        QRoute.api(ITroopListRepoApi::class.java)
                            .getTroopInfoFromCache(g.groupId.toString())?.isTop == true
                    }.getOrDefault(false)
                }
                okResponse(GetPeerPinsOutput.serializer(), GetPeerPinsOutput(friends = emptyList(), groups = groups))
            }

            "set_peer_pin" -> {
                decodeInput(SetPeerPinInput.serializer(), bodyJson)
                okEmpty()
            }

            "set_avatar" -> {
                decodeInput(SetAvatarInput.serializer(), bodyJson)
                okEmpty()
            }

            "set_nickname" -> {
                decodeInput(SetNicknameInput.serializer(), bodyJson)
                okEmpty()
            }

            "set_bio" -> {
                decodeInput(SetBioInput.serializer(), bodyJson)
                okEmpty()
            }

            "get_custom_face_url_list" -> {
                okResponse(GetCustomFaceUrlListOutput.serializer(), GetCustomFaceUrlListOutput(urls = emptyList()))
            }

            "get_cookies" -> {
                val input = decodeInput(GetCookiesInput.serializer(), bodyJson)
                val cookies = TicketManager.getCookie(input.domain)
                okResponse(GetCookiesOutput.serializer(), GetCookiesOutput(cookies = cookies))
            }

            "get_csrf_token" -> {
                val skey = TicketManager.getSkey().orEmpty()
                val bkn = if (skey.isNotEmpty()) CalculationUtils.getBkn(skey).toString() else "0"
                okResponse(GetCsrfTokenOutput.serializer(), GetCsrfTokenOutput(csrfToken = bkn))
            }

            // ── 消息 API ──
            "send_private_message" -> {
                val input = decodeInput(SendPrivateMessageInput.serializer(), bodyJson)
                require(input.userId > 0L) { "user_id (${input.userId}) 不是合法的 QQ 号" }
                val res = sendMessageInternal(MsgConstant.KCHATTYPEC2C, input.userId, input.message)
                okResponse(SendPrivateMessageOutput.serializer(), SendPrivateMessageOutput(res.first, res.second))
            }

            "send_group_message" -> {
                val input = decodeInput(SendGroupMessageInput.serializer(), bodyJson)
                require(input.groupId > 0L) { "group_id (${input.groupId}) 不是合法的群号" }
                val res = sendMessageInternal(MsgConstant.KCHATTYPEGROUP, input.groupId, input.message)
                okResponse(SendGroupMessageOutput.serializer(), SendGroupMessageOutput(res.first, res.second))
            }

            "recall_private_message" -> {
                val input = decodeInput(RecallPrivateMessageInput.serializer(), bodyJson)
                recallMessageInternal(MsgConstant.KCHATTYPEC2C, input.userId, input.messageSeq)
                okEmpty()
            }

            "recall_group_message" -> {
                val input = decodeInput(RecallGroupMessageInput.serializer(), bodyJson)
                recallMessageInternal(MsgConstant.KCHATTYPEGROUP, input.groupId, input.messageSeq)
                okEmpty()
            }

            "get_message" -> {
                val input = decodeInput(GetMessageInput.serializer(), bodyJson)
                val chatType = if (input.messageScene == "group") MsgConstant.KCHATTYPEGROUP else MsgConstant.KCHATTYPEC2C
                val msg = fetchSingleMessage(chatType, input.peerId, input.messageSeq)
                    ?: error("未找到目标消息 (seq=${input.messageSeq})")
                okResponse(GetMessageOutput.serializer(), GetMessageOutput(message = msg))
            }

            "get_history_messages" -> {
                val input = decodeInput(GetHistoryMessagesInput.serializer(), bodyJson)
                val chatType = if (input.messageScene == "group") MsgConstant.KCHATTYPEGROUP else MsgConstant.KCHATTYPEC2C
                val list = fetchHistoryMessages(chatType, input.peerId, input.startMessageSeq ?: 0L, input.limit)
                okResponse(
                    GetHistoryMessagesOutput.serializer(),
                    GetHistoryMessagesOutput(messages = list, nextMessageSeq = list.firstOrNull()?.messageSeq),
                )
            }

            "get_resource_temp_url" -> {
                val input = decodeInput(GetResourceTempUrlInput.serializer(), bodyJson)
                val url = MilkySegmentConverter.getResourceUrl(input.resourceId)
                    ?: "https://gchat.qpic.cn/gchatpic_new/0/0-0-${input.resourceId.uppercase()}/0"
                okResponse(GetResourceTempUrlOutput.serializer(), GetResourceTempUrlOutput(url = url))
            }

            "get_forwarded_messages" -> {
                decodeInput(GetForwardedMessagesInput.serializer(), bodyJson)
                okResponse(GetForwardedMessagesOutput.serializer(), GetForwardedMessagesOutput(messages = emptyList()))
            }

            "mark_message_as_read" -> {
                val input = decodeInput(MarkMessageAsReadInput.serializer(), bodyJson)
                val chatType = if (input.messageScene == "group") MsgConstant.KCHATTYPEGROUP else MsgConstant.KCHATTYPEC2C
                val contact = ContactHelper.generateContact(chatType, input.peerId.toString())
                if (contact is MapleContact.PublicContact) {
                    runCatching {
                        QQInterfaces.msgService.callMethod("setMsgRead", contact.inner, null)
                    }
                }
                okEmpty()
            }

            // ── 好友 API ──
            "send_friend_nudge" -> {
                val input = decodeInput(SendFriendNudgeInput.serializer(), bodyJson)
                val target = if (input.isSelf) (QQInterfaces.currentUin.toLongOrNull() ?: input.userId) else input.userId
                sendPokePacket(targetUin = target, groupId = null)
                okEmpty()
            }

            "send_profile_like" -> {
                val input = decodeInput(SendProfileLikeInput.serializer(), bodyJson)
                sendProfileLikePacket(input.userId, input.count)
                okEmpty()
            }

            "delete_friend" -> {
                val input = decodeInput(DeleteFriendInput.serializer(), bodyJson)
                val uid = ContactHelper.getUidByUinAsync(input.userId)
                runCatching {
                    val buddySvc = QQInterfaces.msgService.let {
                        com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.addBuddyService
                    }
                    buddySvc.callMethod("delBuddy", uid, null)
                }
                okEmpty()
            }

            "get_friend_requests" -> {
                decodeInput(GetFriendRequestsInput.serializer(), bodyJson)
                okResponse(GetFriendRequestsOutput.serializer(), GetFriendRequestsOutput(requests = emptyList()))
            }

            "accept_friend_request" -> {
                decodeInput(AcceptFriendRequestInput.serializer(), bodyJson)
                okEmpty()
            }

            "reject_friend_request" -> {
                decodeInput(RejectFriendRequestInput.serializer(), bodyJson)
                okEmpty()
            }

            // ── 群聊 API ──
            "set_group_name" -> {
                val input = decodeInput(SetGroupNameInput.serializer(), bodyJson)
                runCatching {
                    QQInterfaces.groupService.callMethod(
                        "modifyGroupName",
                        input.groupId,
                        input.newGroupName,
                        false,
                        IOperateCallback { _, _ -> },
                    )
                }
                okEmpty()
            }

            "set_group_avatar" -> {
                decodeInput(SetGroupAvatarInput.serializer(), bodyJson)
                okEmpty()
            }

            "set_group_member_card" -> {
                val input = decodeInput(SetGroupMemberCardInput.serializer(), bodyJson)
                val uid = GroupService.getUidFromUin(input.userId.toString())
                suspendOperate { cb ->
                    QQInterfaces.groupService.modifyMemberCardName(input.groupId, uid, input.card, cb)
                }
                okEmpty()
            }

            "set_group_member_special_title" -> {
                val input = decodeInput(SetGroupMemberSpecialTitleInput.serializer(), bodyJson)
                GroupService.setMemberTitle(input.groupId.toString(), input.userId.toString(), input.specialTitle)
                okEmpty()
            }

            "set_group_member_admin" -> {
                val input = decodeInput(SetGroupMemberAdminInput.serializer(), bodyJson)
                val uid = GroupService.getUidFromUin(input.userId.toString())
                val role = if (input.isSet) MemberRole.ADMIN else MemberRole.MEMBER
                suspendOperate { cb ->
                    QQInterfaces.groupService.modifyMemberRole(input.groupId, uid, role, cb)
                }
                okEmpty()
            }

            "set_group_member_mute" -> {
                val input = decodeInput(SetGroupMemberMuteInput.serializer(), bodyJson)
                val uid = GroupService.getUidFromUin(input.userId.toString())
                val info = arrayListOf(
                    GroupMemberShutUpInfo().apply {
                        this.uid = uid
                        this.timeStamp = input.duration
                    }
                )
                suspendOperate { cb ->
                    QQInterfaces.groupService.setMemberShutUp(input.groupId, info, cb)
                }
                okEmpty()
            }

            "set_group_whole_mute" -> {
                val input = decodeInput(SetGroupWholeMuteInput.serializer(), bodyJson)
                suspendOperate { cb ->
                    QQInterfaces.groupService.setGroupShutUp(input.groupId, input.isMute, cb)
                }
                okEmpty()
            }

            "kick_group_member" -> {
                val input = decodeInput(KickGroupMemberInput.serializer(), bodyJson)
                val uids = arrayListOf(GroupService.getUidFromUin(input.userId.toString()))
                withTimeoutOrNull(8.seconds) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        QQInterfaces.groupService.kickMember(
                            input.groupId,
                            uids,
                            input.rejectAddRequest,
                            "",
                        ) { errCode, errMsg, _ ->
                            if (cont.isActive) {
                                if (errCode == 0) cont.resume(Unit)
                                else cont.resume(Unit)
                            }
                        }
                    }
                }
                okEmpty()
            }

            "get_group_announcements" -> {
                decodeInput(GetGroupAnnouncementsInput.serializer(), bodyJson)
                okResponse(GetGroupAnnouncementsOutput.serializer(), GetGroupAnnouncementsOutput(announcements = emptyList()))
            }

            "send_group_announcement" -> {
                decodeInput(SendGroupAnnouncementInput.serializer(), bodyJson)
                okEmpty()
            }

            "delete_group_announcement" -> {
                decodeInput(DeleteGroupAnnouncementInput.serializer(), bodyJson)
                okEmpty()
            }

            "get_group_essence_messages" -> {
                decodeInput(GetGroupEssenceMessagesInput.serializer(), bodyJson)
                okResponse(
                    GetGroupEssenceMessagesOutput.serializer(),
                    GetGroupEssenceMessagesOutput(messages = emptyList(), isEnd = true),
                )
            }

            "set_group_essence_message" -> {
                val input = decodeInput(SetGroupEssenceMessageInput.serializer(), bodyJson)
                val subcmd = if (input.isSet) 1 else 2
                val buf = ProtoMap().apply {
                    this[1] = 0x8ac
                    this[2] = subcmd
                    this[4, 1] = input.groupId.toULong()
                    this[4, 2] = input.messageSeq.toUInt()
                }.toByteArray()
                PacketHelper.sendRequest("OidbSvc.0x8ac_$subcmd", buf)
                okEmpty()
            }

            "quit_group" -> {
                val input = decodeInput(QuitGroupInput.serializer(), bodyJson)
                runCatching {
                    QQInterfaces.groupService.callMethod(
                        "quitGroup",
                        input.groupId,
                        IOperateCallback { _, _ -> },
                    )
                }
                okEmpty()
            }

            "send_group_message_reaction" -> {
                val input = decodeInput(SendGroupMessageReactionInput.serializer(), bodyJson)
                val contact = ContactHelper.generateContactByUid(MsgConstant.KCHATTYPEGROUP, input.groupId.toString())
                if (contact is MapleContact.PublicContact) {
                    val emojiType = if (input.reactionType == "emoji") 2L else 1L
                    runCatching {
                        QQInterfaces.msgService.callMethod(
                            "setMsgEmojiLikes",
                            contact.inner,
                            input.messageSeq,
                            input.reaction,
                            emojiType,
                            input.isAdd,
                            null,
                        )
                    }
                }
                okEmpty()
            }

            "send_group_nudge" -> {
                val input = decodeInput(SendGroupNudgeInput.serializer(), bodyJson)
                sendPokePacket(targetUin = input.userId, groupId = input.groupId)
                okEmpty()
            }

            "get_group_notifications" -> {
                decodeInput(GetGroupNotificationsInput.serializer(), bodyJson)
                okResponse(
                    GetGroupNotificationsOutput.serializer(),
                    GetGroupNotificationsOutput(notifications = emptyList(), nextNotificationSeq = null),
                )
            }

            "accept_group_request" -> {
                decodeInput(AcceptGroupRequestInput.serializer(), bodyJson)
                okEmpty()
            }

            "reject_group_request" -> {
                decodeInput(RejectGroupRequestInput.serializer(), bodyJson)
                okEmpty()
            }

            "accept_group_invitation" -> {
                decodeInput(AcceptGroupInvitationInput.serializer(), bodyJson)
                okEmpty()
            }

            "reject_group_invitation" -> {
                decodeInput(RejectGroupInvitationInput.serializer(), bodyJson)
                okEmpty()
            }

            // ── 文件 API ──
            "upload_private_file" -> {
                val input = decodeInput(UploadPrivateFileInput.serializer(), bodyJson)
                val file = MilkySegmentConverter.resolveUriToLocalFile(input.fileUri, "ufile", "")
                    ?: error("无法读取文件 URI: ${input.fileUri}")
                val elem = MilkySegmentConverter.createFileMsgElement(file, input.fileName)
                val contact = ContactHelper.generateContact(MsgConstant.KCHATTYPEC2C, input.userId.toString())
                sendRawElements(MsgConstant.KCHATTYPEC2C, contact, arrayListOf(elem))
                okResponse(
                    UploadPrivateFileOutput.serializer(),
                    UploadPrivateFileOutput(fileId = elem.fileElement?.fileUuid ?: elem.fileElement?.fileMd5.orEmpty()),
                )
            }

            "upload_group_file" -> {
                val input = decodeInput(UploadGroupFileInput.serializer(), bodyJson)
                val file = MilkySegmentConverter.resolveUriToLocalFile(input.fileUri, "gfile", "")
                    ?: error("无法读取文件 URI: ${input.fileUri}")
                val elem = MilkySegmentConverter.createFileMsgElement(file, input.fileName)
                val contact = ContactHelper.generateContactByUid(MsgConstant.KCHATTYPEGROUP, input.groupId.toString())
                sendRawElements(MsgConstant.KCHATTYPEGROUP, contact, arrayListOf(elem))
                okResponse(
                    UploadGroupFileOutput.serializer(),
                    UploadGroupFileOutput(fileId = elem.fileElement?.fileUuid ?: elem.fileElement?.fileMd5.orEmpty()),
                )
            }

            "get_private_file_download_url" -> {
                val input = decodeInput(GetPrivateFileDownloadUrlInput.serializer(), bodyJson)
                val url = MilkySegmentConverter.getResourceUrl(input.fileId).orEmpty()
                okResponse(GetPrivateFileDownloadUrlOutput.serializer(), GetPrivateFileDownloadUrlOutput(downloadUrl = url))
            }

            "get_group_file_download_url" -> {
                val input = decodeInput(GetGroupFileDownloadUrlInput.serializer(), bodyJson)
                val url = MilkySegmentConverter.getResourceUrl(input.fileId).orEmpty()
                okResponse(GetGroupFileDownloadUrlOutput.serializer(), GetGroupFileDownloadUrlOutput(downloadUrl = url))
            }

            "get_group_files" -> {
                decodeInput(GetGroupFilesInput.serializer(), bodyJson)
                okResponse(GetGroupFilesOutput.serializer(), GetGroupFilesOutput(files = emptyList(), folders = emptyList()))
            }

            "move_group_file" -> {
                val input = decodeInput(MoveGroupFileInput.serializer(), bodyJson)
                runCatching {
                    val rm = com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.richMediaService
                    rm.callMethod(
                        "moveGroupFile",
                        input.groupId,
                        arrayListOf(102),
                        arrayListOf(input.fileId),
                        input.parentFolderId,
                        input.targetFolderId,
                        null,
                    )
                }
                okEmpty()
            }

            "rename_group_file" -> {
                val input = decodeInput(RenameGroupFileInput.serializer(), bodyJson)
                runCatching {
                    val rm = com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.richMediaService
                    rm.callMethod(
                        "renameGroupFile",
                        input.groupId,
                        102,
                        input.fileId,
                        input.parentFolderId,
                        input.newFileName,
                        null,
                    )
                }
                okEmpty()
            }

            "delete_group_file" -> {
                val input = decodeInput(DeleteGroupFileInput.serializer(), bodyJson)
                runCatching {
                    val rm = com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.richMediaService
                    rm.callMethod(
                        "deleteGroupFile",
                        input.groupId,
                        arrayListOf(102),
                        arrayListOf(input.fileId),
                        null,
                    )
                }
                okEmpty()
            }

            "persist_group_file" -> {
                val input = decodeInput(PersistGroupFileInput.serializer(), bodyJson)
                runCatching {
                    val rm = com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.richMediaService
                    rm.callMethod("transGroupFile", input.groupId, input.fileId, null)
                }
                okEmpty()
            }

            "create_group_folder" -> {
                val input = decodeInput(CreateGroupFolderInput.serializer(), bodyJson)
                runCatching {
                    val rm = com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.richMediaService
                    rm.callMethod("createGroupFolder", input.groupId, input.folderName, null)
                }
                okResponse(CreateGroupFolderOutput.serializer(), CreateGroupFolderOutput(folderId = "/"))
            }

            "rename_group_folder" -> {
                val input = decodeInput(RenameGroupFolderInput.serializer(), bodyJson)
                runCatching {
                    val rm = com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.richMediaService
                    rm.callMethod("renameGroupFolder", input.groupId, input.folderId, input.newFolderName, null)
                }
                okEmpty()
            }

            "delete_group_folder" -> {
                val input = decodeInput(DeleteGroupFolderInput.serializer(), bodyJson)
                runCatching {
                    val rm = com.owo233.tcqt.host.service.NTServiceFetcher.kernelService.wrapperSession.richMediaService
                    rm.callMethod("deleteGroupFolder", input.groupId, input.folderId, null)
                }
                okEmpty()
            }

            else -> ApiGeneralResponse(status = "failed", retcode = -404, message = "Unknown action: $action")
        }
    }

    private suspend fun sendMessageInternal(
        chatType: Int,
        peerId: Long,
        segments: List<OutgoingSegment>,
    ): Pair<Long, Long> {
        val contact = if (chatType == MsgConstant.KCHATTYPEGROUP) {
            ContactHelper.generateContactByUid(MsgConstant.KCHATTYPEGROUP, peerId.toString())
        } else {
            ContactHelper.generateContact(MsgConstant.KCHATTYPEC2C, peerId.toString())
        }
        val elements = MilkySegmentConverter.toNativeElements(chatType, peerId, segments)
        if (elements.isEmpty()) {
            throw IllegalArgumentException("消息内容不能为空")
        }
        val msgId = sendRawElements(chatType, contact, elements)
        val now = System.currentTimeMillis() / 1000L
        return msgId to now
    }

    private suspend fun sendRawElements(
        chatType: Int,
        contact: MapleContact,
        elements: ArrayList<com.tencent.qqnt.kernel.nativeinterface.MsgElement>,
    ): Long {
        if (contact !is MapleContact.PublicContact) {
            error("当前 QQ 版本过低，不支持 PublicContact")
        }
        val kernelMsgSvc = QQInterfaces.msgService
        val msgId = runCatching {
            kernelMsgSvc.generateMsgUniqueId(chatType, QQInterfaces.getServiceTime())
        }.getOrElse {
            runCatching { kernelMsgSvc.getMsgUniqueId(QQInterfaces.getServiceTime()) }
                .getOrDefault(System.currentTimeMillis())
        }

        val codeAndMsg = withTimeoutOrNull(10.seconds) {
            suspendCancellableCoroutine<Pair<Int, String>> { cont ->
                runCatching {
                    val iMsgService = QRoute.api(IMsgService::class.java)
                    iMsgService.sendMsgWithMsgId(contact.inner, msgId, elements) { result, errMsg ->
                        if (cont.isActive) cont.resume(result to errMsg.orEmpty())
                    }
                }.onFailure {
                    kernelMsgSvc.sendMsg(
                        msgId,
                        contact.inner,
                        elements,
                        HashMap<Int, MsgAttributeInfo>(),
                    ) { result, errMsg ->
                        if (cont.isActive) cont.resume(result to errMsg.orEmpty())
                    }
                }
            }
        } ?: (0 to "")

        if (codeAndMsg.first != 0) {
            error("发送消息失败 (code=${codeAndMsg.first}, msg=${codeAndMsg.second})")
        }
        return msgId
    }

    private suspend fun recallMessageInternal(chatType: Int, peerId: Long, messageSeq: Long) {
        val contact = if (chatType == MsgConstant.KCHATTYPEGROUP) {
            ContactHelper.generateContactByUid(MsgConstant.KCHATTYPEGROUP, peerId.toString())
        } else {
            ContactHelper.generateContact(MsgConstant.KCHATTYPEC2C, peerId.toString())
        }
        if (contact !is MapleContact.PublicContact) return

        val msgId = MilkySegmentConverter.findCachedMsgId(chatType, peerId, messageSeq)
            ?: resolveMsgIdFromSeq(contact, messageSeq)
            ?: messageSeq

        suspendOperate { cb ->
            QQInterfaces.msgService.recallMsg(contact.inner, arrayListOf(msgId), cb)
        }
    }

    private suspend fun resolveMsgIdFromSeq(contact: MapleContact.PublicContact, msgSeq: Long): Long? {
        return withTimeoutOrNull(5.seconds) {
            suspendCancellableCoroutine<Long?> { cont ->
                QQInterfaces.msgService.getSingleMsg(contact.inner, msgSeq) { _, _, list ->
                    if (cont.isActive) {
                        val rec = list?.firstOrNull()
                        if (rec != null) {
                            MilkySegmentConverter.cacheRecord(rec)
                            cont.resume(rec.msgId)
                        } else {
                            cont.resume(null)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchSingleMessage(
        chatType: Int,
        peerId: Long,
        msgSeq: Long,
    ): IncomingMessage? {
        MilkySegmentConverter.findCachedRecord(chatType, peerId, msgSeq)?.let {
            return MilkySegmentConverter.toIncomingMessage(it)
        }
        val contact = if (chatType == MsgConstant.KCHATTYPEGROUP) {
            ContactHelper.generateContactByUid(MsgConstant.KCHATTYPEGROUP, peerId.toString())
        } else {
            ContactHelper.generateContact(MsgConstant.KCHATTYPEC2C, peerId.toString())
        }
        if (contact !is MapleContact.PublicContact) return null

        val record = withTimeoutOrNull(5.seconds) {
            suspendCancellableCoroutine<MsgRecord?> { cont ->
                QQInterfaces.msgService.getSingleMsg(contact.inner, msgSeq) { _, _, list ->
                    if (cont.isActive) {
                        val first = list?.firstOrNull()
                        if (first != null) {
                            cont.resume(first)
                        } else {
                            // Try by msgId if caller passed msgId as message_seq
                            QQInterfaces.msgService.getMsgsByMsgId(contact.inner, arrayListOf(msgSeq)) { _, _, byId ->
                                if (cont.isActive) cont.resume(byId?.firstOrNull())
                            }
                        }
                    }
                }
            }
        } ?: return null

        return MilkySegmentConverter.toIncomingMessage(record)
    }

    private suspend fun fetchHistoryMessages(
        chatType: Int,
        peerId: Long,
        startSeq: Long,
        limit: Int,
    ): List<IncomingMessage> {
        val contact = if (chatType == MsgConstant.KCHATTYPEGROUP) {
            ContactHelper.generateContactByUid(MsgConstant.KCHATTYPEGROUP, peerId.toString())
        } else {
            ContactHelper.generateContact(MsgConstant.KCHATTYPEC2C, peerId.toString())
        }
        if (contact !is MapleContact.PublicContact) return emptyList()

        val count = limit.coerceIn(1, 30)
        val records = withTimeoutOrNull(8.seconds) {
            suspendCancellableCoroutine<List<MsgRecord>> { cont ->
                val svc = QQInterfaces.msgService
                val getMsgsBySeq = svc.javaClass.methods.firstOrNull {
                    it.name == "getMsgsBySeqAndCount" && it.parameterCount == 5
                }
                if (getMsgsBySeq != null) {
                    val cb = IMsgOperateCallback { _, _, list ->
                        if (cont.isActive) cont.resume(list ?: emptyList())
                    }
                    runCatching {
                        getMsgsBySeq.invoke(svc, contact.inner, startSeq, count, true, cb)
                    }.onFailure {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                } else {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        }.orEmpty()

        val out = ArrayList<IncomingMessage>(records.size)
        for (r in records.sortedBy { it.msgSeq }) {
            MilkySegmentConverter.toIncomingMessage(r)?.let { out.add(it) }
        }
        return out
    }

    private fun fetchGroupList(): List<GroupEntity> {
        val result = ArrayList<GroupEntity>()
        runCatching {
            val repo = QRoute.api(ITroopListRepoApi::class.java)
            val listMethod = repo.javaClass.methods.firstOrNull {
                (it.name == "getTroopListFromCache" || it.name == "getSortedJoinedTroopInfoFromCache") &&
                    it.parameterCount == 0
            }
            val troopList = listMethod?.invoke(repo) as? List<*> ?: return@runCatching
            for (item in troopList) {
                if (item == null) continue
                runCatching {
                    val uinStr = (item as? com.tencent.mobileqq.data.troop.TroopInfo)?.troopuin
                        ?: item.javaClass.getField("troopuin").get(item) as? String
                    val groupId = uinStr?.toLongOrNull() ?: return@runCatching
                    result.add(MilkySegmentConverter.buildGroupEntity(groupId))
                }
            }
        }
        return result
    }

    private fun fetchFriendList(): List<FriendEntity> {
        val out = ArrayList<FriendEntity>()
        runCatching {
            val svc = QRoute.api(IFriendsInfoService::class.java)
            val allMethod = svc.javaClass.methods.firstOrNull {
                it.name == "getAllFriend" && it.parameterCount <= 1
            }
            if (allMethod != null) {
                val list = if (allMethod.parameterCount == 0) {
                    allMethod.invoke(svc) as? List<*>
                } else {
                    allMethod.invoke(svc, "MilkyBot") as? List<*>
                }
                if (list != null) {
                    for (item in list) {
                        val info = item as? com.tencent.qqnt.ntrelation.friendsinfo.bean.d ?: continue
                        val uin = info.uin.toLongOrNull() ?: continue
                        val sexStr = when (info.sex) {
                            1 -> "male"
                            2 -> "female"
                            else -> "unknown"
                        }
                        out.add(
                            FriendEntity(
                                userId = uin,
                                nickname = info.nick.orEmpty(),
                                sex = sexStr,
                                qid = info.alias.orEmpty(),
                                remark = info.remark.orEmpty(),
                                category = FriendCategoryEntity(
                                    categoryId = info.categoryId.coerceAtLeast(0),
                                    categoryName = "我的好友",
                                ),
                            )
                        )
                    }
                    return out
                }
            }

            // Fallback: borrow XAutoDaily's FriendsManager reflection
            val mgr = QQInterfaces.appRuntime.getManager(50)
            val mapMethod = mgr.javaClass.getMethods(false).firstOrNull {
                Modifier.isPrivate(it.modifiers) && it.returnType == ConcurrentHashMap::class.java
            }?.apply { isAccessible = true }
            val map = mapMethod?.invoke(mgr, true) as? ConcurrentHashMap<*, *>
            val rawFriends = map?.values?.flatMap { (it as? List<*>).orEmpty() }.orEmpty()
            for (f in rawFriends) {
                if (f == null) continue
                val fields = f.javaClass.getFields(false)
                val uinStr = fields.firstOrNull { it.name == "uin" }?.apply { isAccessible = true }?.get(f) as? String
                val uin = uinStr?.toLongOrNull() ?: continue
                val name = fields.firstOrNull { it.name == "name" || it.name == "nick" }
                    ?.apply { isAccessible = true }?.get(f) as? String
                val remark = fields.firstOrNull { it.name == "remark" }
                    ?.apply { isAccessible = true }?.get(f) as? String
                out.add(MilkySegmentConverter.buildFriendEntity(uin, name, remark))
            }
        }
        return out
    }

    private suspend fun fetchGroupMemberList(groupId: Long): List<GroupMemberEntity> {
        val out = ArrayList<GroupMemberEntity>()
        val admins = runCatching { GroupService.getGroupAdminList(groupId.toString()) }.getOrDefault(emptyList())
        val ownerUin = runCatching {
            GroupService.getGroupInfo(groupId.toString()).troopowneruin?.toLongOrNull()
        }.getOrNull()

        for ((idx, uinStr) in admins.withIndex()) {
            val uin = uinStr.toLongOrNull() ?: continue
            val nickInfo = runCatching { GroupHelper.getTroopMemberNickByUin(groupId, uin) }.getOrNull()
            val role = if (uin == ownerUin || idx == 0) "owner" else "admin"
            out.add(
                GroupMemberEntity(
                    userId = uin,
                    nickname = nickInfo?.friendNick.orEmpty(),
                    sex = "unknown",
                    groupId = groupId,
                    card = nickInfo?.troopNick.orEmpty(),
                    title = "",
                    level = 0,
                    role = role,
                    joinTime = 0L,
                    lastSentTime = 0L,
                    shutUpEndTime = null,
                )
            )
        }
        return out
    }

    private suspend fun fetchSingleGroupMember(groupId: Long, userId: Long): GroupMemberEntity {
        val uid = runCatching { GroupService.getUidFromUin(userId.toString()) }.getOrDefault("")
        if (uid.isNotEmpty()) {
            val info = withTimeoutOrNull(5.seconds) {
                suspendCancellableCoroutine<com.tencent.qqnt.kernel.nativeinterface.MemberInfo?> { cont ->
                    QQInterfaces.groupService.getMemberInfoForMqq(
                        groupId,
                        arrayListOf(uid),
                        false,
                    ) { _, _, result ->
                        if (cont.isActive) {
                            val map = runCatching {
                                result?.javaClass?.getField("infos")?.get(result) as? Map<*, *>
                            }.getOrNull()
                            val memberInfo = map?.values?.firstOrNull() as? com.tencent.qqnt.kernel.nativeinterface.MemberInfo
                            cont.resume(memberInfo)
                        }
                    }
                }
            }
            if (info != null) {
                return MilkySegmentConverter.buildGroupMemberEntityFromMemberInfo(groupId, info)
            }
        }

        val nickInfo = runCatching { GroupHelper.getTroopMemberNickByUin(groupId, userId) }.getOrNull()
        val title = runCatching { GroupService.getMemberTitle(groupId.toString(), userId.toString()) }.getOrDefault("")
        return GroupMemberEntity(
            userId = userId,
            nickname = nickInfo?.friendNick.orEmpty(),
            sex = "unknown",
            groupId = groupId,
            card = nickInfo?.troopNick.orEmpty(),
            title = title,
            level = 0,
            role = "member",
            joinTime = 0L,
            lastSentTime = 0L,
            shutUpEndTime = null,
        )
    }

    private fun sendPokePacket(targetUin: Long, groupId: Long?) {
        val buf = ProtoMap().apply {
            this[1] = 0xed3
            this[2] = 1
            this[4, 1] = targetUin.toULong()
            if (groupId != null && groupId > 0L) {
                this[4, 2] = groupId.toULong()
            } else {
                this[4, 5] = targetUin.toULong()
            }
        }.toByteArray()
        PacketHelper.sendRequest("OidbSvc.0xed3_1", buf)
    }

    private fun sendProfileLikePacket(targetUin: Long, count: Int) {
        val uid = runCatching { GroupService.getUidFromUin(targetUin.toString()) }.getOrDefault("")
        val buf = ProtoMap().apply {
            this[1] = 0x7e5
            this[2] = 104
            if (uid.isNotEmpty()) {
                this[4, 11] = uid
            }
            this[4, 12] = 71
            this[4, 13] = count.coerceIn(1, 20)
        }.toByteArray()
        PacketHelper.sendRequest("OidbSvcTrpcTcp.0x7e5_104", buf)
    }

    private suspend fun suspendOperate(block: (IOperateCallback) -> Unit) {
        val res = withTimeoutOrNull(8.seconds) {
            suspendCancellableCoroutine<Pair<Int, String>> { cont ->
                block(IOperateCallback { code, msg ->
                    if (cont.isActive) cont.resume(code to msg.orEmpty())
                })
            }
        } ?: (0 to "")
        if (res.first != 0) {
            error("操作失败 (code=${res.first}, msg=${res.second})")
        }
    }
}
