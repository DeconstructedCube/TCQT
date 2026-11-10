package com.owo233.tcqt.host.service.milky

import android.util.Base64
import com.owo233.tcqt.core.env.load
import com.owo233.tcqt.core.reflect.callMethod
import com.owo233.tcqt.host.QQInterfaces
import com.owo233.tcqt.host.service.ContactHelper
import com.owo233.tcqt.host.service.GroupHelper
import com.owo233.tcqt.host.service.milky.model.FriendCategoryEntity
import com.owo233.tcqt.host.service.milky.model.FriendEntity
import com.owo233.tcqt.host.service.milky.model.GroupEntity
import com.owo233.tcqt.host.service.milky.model.GroupMemberEntity
import com.owo233.tcqt.host.service.milky.model.IncomingMessage
import com.owo233.tcqt.host.service.milky.model.IncomingSegment
import com.owo233.tcqt.host.service.milky.model.OutgoingSegment
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.kernel.nativeinterface.ArkElement
import com.tencent.qqnt.kernel.nativeinterface.FaceElement
import com.tencent.qqnt.kernel.nativeinterface.FileElement
import com.tencent.qqnt.kernel.nativeinterface.MarkdownElement
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.qqnt.kernel.nativeinterface.PttElement
import com.tencent.qqnt.kernel.nativeinterface.ReplyElement
import com.tencent.qqnt.kernel.nativeinterface.StructMsgElement
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.kernel.nativeinterface.VideoElement
import com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole
import com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService
import com.tencent.qqnt.troop.ITroopListRepoApi
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

internal object MilkySegmentConverter {

    private const val MAX_CACHE_SIZE = 2048

    private val seqToMsgIdCache: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    private val recordCache: MutableMap<String, MsgRecord> = Collections.synchronizedMap(
        object : LinkedHashMap<String, MsgRecord>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MsgRecord>?): Boolean {
                return size > 512
            }
        }
    )

    private val resourcePathCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 512
            }
        }
    )

    private val msgUtilApi: Any? by lazy {
        runCatching {
            val clz = load("com.tencent.qqnt.msg.api.IMsgUtilApi") ?: return@runCatching null
            @Suppress("UNCHECKED_CAST")
            QRoute.api(clz as Class<com.tencent.mobileqq.qroute.QRouteApi>)
        }.getOrNull()
    }

    fun cacheRecord(record: MsgRecord) {
        val peer = resolvePeerUin(record)
        val seq = if (record.msgSeq > 0L) record.msgSeq else record.clientSeq
        if (peer > 0L && seq > 0L && record.msgId != 0L) {
            val key = "${record.chatType}_${peer}_$seq"
            seqToMsgIdCache[key] = record.msgId
            recordCache[key] = record
        }
    }

    fun findCachedMsgId(chatType: Int, peerId: Long, msgSeq: Long): Long? {
        return seqToMsgIdCache["${chatType}_${peerId}_$msgSeq"]
    }

    fun findCachedRecord(chatType: Int, peerId: Long, msgSeq: Long): MsgRecord? {
        return recordCache["${chatType}_${peerId}_$msgSeq"]
    }

    fun getResourceUrl(resourceId: String): String? {
        return resourcePathCache[resourceId]
    }

    fun resolvePeerUin(record: MsgRecord): Long {
        if (record.peerUin > 0L) return record.peerUin
        val pUid = record.peerUid.orEmpty()
        pUid.toLongOrNull()?.let { if (it > 0L) return it }
        if (pUid.startsWith("u_")) {
            val uinStr = runCatching {
                QRoute.api(IFriendsInfoService::class.java).getUinFromUid(pUid)
            }.getOrNull()
            uinStr?.toLongOrNull()?.let { if (it > 0L) return it }
        }
        return 0L
    }

    fun resolveSenderUin(record: MsgRecord): Long {
        if (record.senderUin > 0L) return record.senderUin
        val sUid = record.senderUid.orEmpty()
        sUid.toLongOrNull()?.let { if (it > 0L) return it }
        if (sUid.startsWith("u_")) {
            val uinStr = runCatching {
                QRoute.api(IFriendsInfoService::class.java).getUinFromUid(sUid)
            }.getOrNull()
            uinStr?.toLongOrNull()?.let { if (it > 0L) return it }
        }
        return 0L
    }

    suspend fun toIncomingMessage(record: MsgRecord): IncomingMessage? {
        cacheRecord(record)
        val peerId = resolvePeerUin(record).let {
            if (it > 0L) it else if (record.peerUid.startsWith("u_")) {
                ContactHelper.getUinByUidAsync(record.peerUid).toLongOrNull() ?: 0L
            } else 0L
        }
        val senderId = resolveSenderUin(record).let {
            if (it > 0L) it else if (record.senderUid.startsWith("u_")) {
                ContactHelper.getUinByUidAsync(record.senderUid).toLongOrNull() ?: 0L
            } else 0L
        }
        if (peerId <= 0L) return null

        val seq = when {
            record.msgSeq > 0L -> record.msgSeq
            record.clientSeq > 0L -> record.clientSeq
            else -> record.msgId
        }
        val time = if (record.msgTime > 0L) record.msgTime else (System.currentTimeMillis() / 1000L)
        val segments = toIncomingSegments(record)

        return when (record.chatType) {
            MsgConstant.KCHATTYPEGROUP -> {
                val groupEntity = buildGroupEntity(peerId, record.peerName)
                val memberEntity = buildGroupMemberEntity(peerId, senderId, record)
                IncomingMessage.Group(
                    peerId = peerId,
                    messageSeq = seq,
                    senderId = senderId,
                    time = time,
                    segments = segments,
                    group = groupEntity,
                    groupMember = memberEntity,
                )
            }
            MsgConstant.KCHATTYPEC2C -> {
                val friendEntity = buildFriendEntity(senderId, record.sendNickName, record.sendRemarkName)
                IncomingMessage.Friend(
                    peerId = peerId,
                    messageSeq = seq,
                    senderId = senderId,
                    time = time,
                    segments = segments,
                    friend = friendEntity,
                )
            }
            else -> {
                IncomingMessage.Temp(
                    peerId = peerId,
                    messageSeq = seq,
                    senderId = senderId,
                    time = time,
                    segments = segments,
                    group = if (record.guildCode > 0L) buildGroupEntity(record.guildCode, record.guildName) else null,
                )
            }
        }
    }

    fun toIncomingSegments(record: MsgRecord): List<IncomingSegment> {
        val out = ArrayList<IncomingSegment>()
        val elements = record.elements ?: return out
        for (elem in elements) {
            convertElement(elem)?.let { out.add(it) }
        }
        return out
    }

    private fun convertElement(elem: MsgElement): IncomingSegment? {
        elem.textElement?.let { txt ->
            return when (txt.atType) {
                MsgConstant.ATTYPEALL -> IncomingSegment.MentionAll(IncomingSegment.MentionAll.Data())
                MsgConstant.ATTYPEONE, MsgConstant.ATTYPEME -> {
                    val targetUin = when {
                        txt.atUid > 0L -> txt.atUid
                        txt.atTinyId > 0L -> txt.atTinyId
                        !txt.atNtUid.isNullOrEmpty() -> runCatching {
                            QRoute.api(IFriendsInfoService::class.java).getUinFromUid(txt.atNtUid)?.toLongOrNull()
                        }.getOrNull() ?: 0L
                        else -> 0L
                    }
                    val displayName = txt.content.orEmpty().removePrefix("@")
                    IncomingSegment.Mention(
                        IncomingSegment.Mention.Data(
                            userId = targetUin,
                            name = displayName,
                        )
                    )
                }
                else -> IncomingSegment.Text(
                    IncomingSegment.Text.Data(text = txt.content.orEmpty())
                )
            }
        }

        elem.faceElement?.let { face ->
            return IncomingSegment.Face(
                IncomingSegment.Face.Data(
                    faceId = face.faceIndex.toString(),
                    isLarge = (face.faceType >= 3 || face.stickerType != null),
                )
            )
        }

        elem.replyElement?.let { rep ->
            val replySeq = rep.replayMsgSeq ?: rep.replyMsgClientSeq ?: rep.replayMsgId
            val replySender = rep.senderUid ?: runCatching {
                val uid = rep.senderUidStr.orEmpty()
                if (uid.startsWith("u_")) {
                    QRoute.api(IFriendsInfoService::class.java).getUinFromUid(uid)?.toLongOrNull()
                } else uid.toLongOrNull()
            }.getOrNull() ?: 0L
            val replyText = rep.sourceMsgText.orEmpty()
            return IncomingSegment.Reply(
                IncomingSegment.Reply.Data(
                    messageSeq = replySeq,
                    senderId = replySender,
                    senderName = rep.anonymousNickName,
                    time = rep.replyMsgTime ?: 0L,
                    segments = if (replyText.isNotEmpty()) {
                        listOf(IncomingSegment.Text(IncomingSegment.Text.Data(replyText)))
                    } else emptyList(),
                )
            )
        }

        elem.picElement?.let { pic ->
            val resId = pic.fileUuid?.takeIf { it.isNotEmpty() } ?: pic.md5HexStr.orEmpty()
            val url = when {
                !pic.originImageUrl.isNullOrEmpty() -> {
                    if (pic.originImageUrl.startsWith("http")) pic.originImageUrl
                    else "https://gchat.qpic.cn${pic.originImageUrl}"
                }
                !pic.sourcePath.isNullOrEmpty() && File(pic.sourcePath).exists() -> "file://${pic.sourcePath}"
                pic.md5HexStr.orEmpty().isNotEmpty() ->
                    "https://gchat.qpic.cn/gchatpic_new/0/0-0-${pic.md5HexStr.uppercase()}/0"
                else -> ""
            }
            if (resId.isNotEmpty() && url.isNotEmpty()) {
                resourcePathCache[resId] = url
            }
            return IncomingSegment.Image(
                IncomingSegment.Image.Data(
                    resourceId = resId,
                    tempUrl = url,
                    width = pic.picWidth,
                    height = pic.picHeight,
                    summary = pic.summary ?: "[图片]",
                    subType = if (pic.picSubType == 1) "sticker" else "normal",
                )
            )
        }

        elem.pttElement?.let { ptt ->
            val resId = ptt.fileUuid?.takeIf { it.isNotEmpty() } ?: ptt.md5HexStr.orEmpty()
            val url = if (!ptt.filePath.isNullOrEmpty()) "file://${ptt.filePath}" else ""
            if (resId.isNotEmpty() && url.isNotEmpty()) {
                resourcePathCache[resId] = url
            }
            return IncomingSegment.Record(
                IncomingSegment.Record.Data(
                    resourceId = resId,
                    tempUrl = url,
                    duration = ptt.duration,
                )
            )
        }

        elem.videoElement?.let { vid ->
            val resId = vid.fileUuid?.takeIf { it.isNotEmpty() } ?: vid.videoMd5.orEmpty()
            val url = if (!vid.filePath.isNullOrEmpty()) "file://${vid.filePath}" else ""
            if (resId.isNotEmpty() && url.isNotEmpty()) {
                resourcePathCache[resId] = url
            }
            return IncomingSegment.Video(
                IncomingSegment.Video.Data(
                    resourceId = resId,
                    tempUrl = url,
                    width = vid.thumbWidth,
                    height = vid.thumbHeight,
                    duration = vid.fileTime,
                )
            )
        }

        elem.fileElement?.let { file ->
            val fId = file.fileUuid?.takeIf { it.isNotEmpty() } ?: file.fileMd5.orEmpty()
            if (fId.isNotEmpty() && !file.filePath.isNullOrEmpty()) {
                resourcePathCache[fId] = "file://${file.filePath}"
            }
            return IncomingSegment.File(
                IncomingSegment.File.Data(
                    fileId = fId,
                    fileName = file.fileName.orEmpty(),
                    fileSize = file.fileSize,
                    fileHash = file.fileSha?.takeIf { it.isNotEmpty() } ?: file.fileMd5,
                )
            )
        }

        elem.multiForwardMsgElement?.let { fwd ->
            return IncomingSegment.Forward(
                IncomingSegment.Forward.Data(
                    forwardId = fwd.resId.orEmpty(),
                    title = "群聊的聊天记录",
                    preview = emptyList(),
                    summary = "[聊天记录]",
                )
            )
        }

        elem.marketFaceElement?.let { mf ->
            val emojiId = mf.emojiId.orEmpty()
            val prefix = if (emojiId.length >= 2) emojiId.substring(0, 2) else "00"
            val url = "https://gxh.vip.qq.com/club/item/parcel/item/$prefix/$emojiId/raw300.gif"
            return IncomingSegment.MarketFace(
                IncomingSegment.MarketFace.Data(
                    emojiPackageId = mf.emojiPackageId,
                    emojiId = emojiId,
                    key = mf.key.orEmpty(),
                    summary = mf.faceName ?: "[商城表情]",
                    url = url,
                )
            )
        }

        elem.arkElement?.let { ark ->
            val payload = ark.bytesData.orEmpty()
            return IncomingSegment.LightApp(
                IncomingSegment.LightApp.Data(
                    appName = "com.tencent.miniapp",
                    jsonPayload = payload,
                )
            )
        }

        elem.structMsgElement?.let { xml ->
            return IncomingSegment.Xml(
                IncomingSegment.Xml.Data(
                    serviceId = 1,
                    xmlPayload = xml.xmlContent.orEmpty(),
                )
            )
        }

        elem.markdownElement?.let { md ->
            return IncomingSegment.Markdown(
                IncomingSegment.Markdown.Data(
                    content = md.content.orEmpty(),
                )
            )
        }

        return null
    }

    suspend fun toNativeElements(
        chatType: Int,
        peerId: Long,
        segments: List<OutgoingSegment>,
    ): ArrayList<MsgElement> {
        val list = ArrayList<MsgElement>(segments.size)
        for (seg in segments) {
            convertOutgoingSegment(chatType, peerId, seg)?.let { list.add(it) }
        }
        return list
    }

    private suspend fun convertOutgoingSegment(
        chatType: Int,
        peerId: Long,
        seg: OutgoingSegment,
    ): MsgElement? {
        return when (seg) {
            is OutgoingSegment.Text -> createTextMsgElement(seg.text)
            is OutgoingSegment.Mention -> {
                val uid = runCatching { ContactHelper.getUidByUinAsync(seg.userId) }.getOrDefault("")
                val nick = if (chatType == MsgConstant.KCHATTYPEGROUP) {
                    runCatching {
                        GroupHelper.getTroopMemberNickByUin(peerId, seg.userId)?.let {
                            it.troopNick.ifEmpty { it.friendNick }
                        }
                    }.getOrNull()
                } else null
                val display = "@${nick?.takeIf { it.isNotEmpty() } ?: seg.userId}"
                createAtMsgElement(display, MsgConstant.ATTYPEONE, seg.userId, uid)
            }
            is OutgoingSegment.MentionAll -> {
                createAtMsgElement("@全体成员", MsgConstant.ATTYPEALL, 0L, "all")
            }
            is OutgoingSegment.Face -> {
                val faceIdx = seg.faceId.toIntOrNull() ?: 0
                createFaceMsgElement(faceIdx, seg.isLarge)
            }
            is OutgoingSegment.Reply -> {
                val replyMsgId = findCachedMsgId(chatType, peerId, seg.messageSeq) ?: seg.messageSeq
                createReplyMsgElement(replyMsgId, seg.messageSeq)
            }
            is OutgoingSegment.Image -> {
                val localFile = resolveUriToLocalFile(seg.uri, "img", ".jpg") ?: return null
                createPicMsgElement(localFile, seg.subType == "sticker", seg.summary)
            }
            is OutgoingSegment.Record -> {
                val localFile = resolveUriToLocalFile(seg.uri, "ptt", ".amr") ?: return null
                createPttMsgElement(localFile)
            }
            is OutgoingSegment.Video -> {
                val localFile = resolveUriToLocalFile(seg.uri, "vid", ".mp4") ?: return null
                createVideoMsgElement(localFile)
            }
            is OutgoingSegment.LightApp -> {
                createArkMsgElement(seg.jsonPayload)
            }
            is OutgoingSegment.Forward -> {
                val summaryText = seg.messages.joinToString("\n") { m ->
                    "${m.senderName}: " + m.segments.mapNotNull {
                        (it as? OutgoingSegment.Text)?.text
                    }.joinToString("")
                }
                createTextMsgElement(summaryText.ifEmpty { "[合并转发]" })
            }
        }
    }

    fun createTextMsgElement(text: String): MsgElement {
        val api = msgUtilApi
        if (api != null) {
            runCatching {
                val textElem = TextElement().apply { content = text }
                return api.callMethod("createTextElement", textElem) as MsgElement
            }
            runCatching {
                return api.callMethod("createTextElement", text) as MsgElement
            }
        }
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPETEXT
            textElement = TextElement().apply {
                content = text
                atType = MsgConstant.ATTYPEUNKNOWN
            }
        }
    }

    private fun createAtMsgElement(contentText: String, atTypeVal: Int, uin: Long, uid: String): MsgElement {
        val api = msgUtilApi
        if (api != null) {
            runCatching {
                return api.callMethod("createAtTextElement", contentText, uid, atTypeVal) as MsgElement
            }
        }
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPETEXT
            textElement = TextElement().apply {
                content = contentText
                atType = atTypeVal
                atUid = uin
                atNtUid = uid
            }
        }
    }

    private fun createFaceMsgElement(faceIndex: Int, isLarge: Boolean): MsgElement {
        val api = msgUtilApi
        if (api != null) {
            runCatching {
                return api.callMethod("createFaceElement", faceIndex, if (isLarge) 3 else 1) as MsgElement
            }
        }
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPEFACE
            faceElement = FaceElement().apply {
                this.faceIndex = faceIndex
                this.faceType = if (isLarge) 3 else 1
            }
        }
    }

    private fun createReplyMsgElement(msgId: Long, msgSeq: Long): MsgElement {
        val api = msgUtilApi
        if (api != null) {
            runCatching {
                return api.callMethod("createReplyElement", msgId) as MsgElement
            }
        }
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPEREPLY
            replyElement = ReplyElement().apply {
                this.replayMsgId = msgId
                this.replayMsgSeq = msgSeq
            }
        }
    }

    private fun createPicMsgElement(file: File, isSticker: Boolean, summary: String?): MsgElement {
        val api = msgUtilApi
        val path = file.absolutePath
        if (api != null) {
            runCatching {
                return api.callMethod("createPicElement", path, true, if (isSticker) 1 else 0) as MsgElement
            }
        }
        val md5 = computeMd5Hex(file)
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPEPIC
            picElement = PicElement().apply {
                this.sourcePath = path
                this.fileName = file.name
                this.fileSize = file.length()
                this.md5HexStr = md5
                this.picSubType = if (isSticker) 1 else 0
                this.original = true
                this.summary = summary ?: if (isSticker) "[动画表情]" else "[图片]"
            }
        }
    }

    private fun createPttMsgElement(file: File): MsgElement {
        val api = msgUtilApi
        val path = file.absolutePath
        if (api != null) {
            runCatching {
                return api.callMethod("createPttElement", path, 5, ArrayList<Byte>()) as MsgElement
            }
        }
        val md5 = computeMd5Hex(file)
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPEPTT
            pttElement = PttElement().apply {
                this.filePath = path
                this.fileName = file.name
                this.fileSize = file.length()
                this.md5HexStr = md5
                this.duration = 5
                this.formatType = MsgConstant.KPTTFORMATTYPESILK
                this.voiceType = MsgConstant.KPTTVOICETYPESOUNDRECORD
            }
        }
    }

    private fun createVideoMsgElement(file: File): MsgElement {
        val api = msgUtilApi
        val path = file.absolutePath
        if (api != null) {
            runCatching {
                return api.callMethod("createVideoElement", path) as MsgElement
            }
        }
        val md5 = computeMd5Hex(file)
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPEVIDEO
            videoElement = VideoElement().apply {
                this.filePath = path
                this.fileName = file.name
                this.fileSize = file.length()
                this.videoMd5 = md5
            }
        }
    }

    fun createFileMsgElement(file: File, fileNameOverride: String? = null): MsgElement {
        val api = msgUtilApi
        val path = file.absolutePath
        if (api != null) {
            runCatching {
                return api.callMethod("createFileElement", path) as MsgElement
            }
        }
        val md5 = computeMd5Hex(file)
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPEFILE
            fileElement = FileElement().apply {
                this.filePath = path
                this.fileName = fileNameOverride?.takeIf { it.isNotEmpty() } ?: file.name
                this.fileSize = file.length()
                this.fileMd5 = md5
            }
        }
    }

    private fun createArkMsgElement(jsonPayload: String): MsgElement {
        val api = msgUtilApi
        if (api != null) {
            runCatching {
                val arkDataClz = load("com.tencent.qqnt.msg.data.ArkMsgModel")
                if (arkDataClz != null) {
                    return api.callMethod("createArkElement", ArkElement(jsonPayload, null, null)) as MsgElement
                }
            }
        }
        return MsgElement().apply {
            elementType = MsgConstant.KELEMTYPEARKSTRUCT
            arkElement = ArkElement(jsonPayload, null, 0)
        }
    }

    fun resolveUriToLocalFile(uri: String, prefix: String, defaultExt: String): File? {
        return runCatching {
            val cacheDir = File(QQInterfaces.context.cacheDir, "milky_media").apply { mkdirs() }
            when {
                uri.startsWith("file://") -> {
                    val path = URI(uri).path ?: uri.removePrefix("file://")
                    File(path).takeIf { it.exists() }
                }
                uri.startsWith("base64://") -> {
                    val b64 = uri.removePrefix("base64://").trim()
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val target = File(cacheDir, "${prefix}_${System.currentTimeMillis()}$defaultExt")
                    FileOutputStream(target).use { it.write(bytes) }
                    target
                }
                uri.startsWith("http://") || uri.startsWith("https://") -> {
                    val target = File(cacheDir, "${prefix}_${System.currentTimeMillis()}$defaultExt")
                    val conn = (URL(uri).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                        instanceFollowRedirects = true
                    }
                    conn.inputStream.use { ins ->
                        FileOutputStream(target).use { outs ->
                            ins.copyTo(outs)
                        }
                    }
                    conn.disconnect()
                    target
                }
                else -> File(uri).takeIf { it.exists() }
            }
        }.getOrNull()
    }

    private fun computeMd5Hex(file: File): String {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val r = ins.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    fun buildGroupEntity(groupId: Long, fallbackName: String? = null): GroupEntity {
        val troopInfo = runCatching {
            QRoute.api(ITroopListRepoApi::class.java).getTroopInfoFromCache(groupId.toString())
        }.getOrNull()
        val name = troopInfo?.troopNameFromNT?.takeIf { it.isNotEmpty() }
            ?: troopInfo?.troopname?.takeIf { it.isNotEmpty() }
            ?: fallbackName.orEmpty()
        return GroupEntity(
            groupId = groupId,
            groupName = name,
            memberCount = troopInfo?.wMemberNum ?: 0,
            maxMemberCount = troopInfo?.wMemberMax ?: 0,
            remark = troopInfo?.troopRemark.orEmpty(),
            createdTime = troopInfo?.troopCreateTime ?: 0L,
            description = troopInfo?.mRichFingerMemo ?: troopInfo?.fingertroopmemo.orEmpty(),
            question = troopInfo?.joinTroopQuestion.orEmpty(),
            announcement = troopInfo?.troopmemo.orEmpty(),
        )
    }

    fun buildGroupMemberEntity(groupId: Long, userId: Long, record: MsgRecord? = null): GroupMemberEntity {
        val roleStr = when (record?.roleType) {
            2 -> "owner"
            1, 3 -> "admin"
            else -> "member"
        }
        return GroupMemberEntity(
            userId = userId,
            nickname = record?.sendNickName.orEmpty(),
            sex = "unknown",
            groupId = groupId,
            card = record?.sendMemberName.orEmpty(),
            title = "",
            level = 0,
            role = roleStr,
            joinTime = 0L,
            lastSentTime = record?.msgTime ?: 0L,
            shutUpEndTime = null,
        )
    }

    fun buildGroupMemberEntityFromMemberInfo(groupId: Long, info: com.tencent.qqnt.kernel.nativeinterface.MemberInfo): GroupMemberEntity {
        val roleStr = when (info.role) {
            MemberRole.OWNER -> "owner"
            MemberRole.ADMIN -> "admin"
            else -> "member"
        }
        return GroupMemberEntity(
            userId = info.uin,
            nickname = info.nick.orEmpty(),
            sex = "unknown",
            groupId = groupId,
            card = info.cardName.orEmpty(),
            title = info.memberSpecialTitle.orEmpty(),
            level = info.memberRealLevel.takeIf { it > 0 } ?: info.memberLevel,
            role = roleStr,
            joinTime = info.joinTime.toLong(),
            lastSentTime = info.lastSpeakTime.toLong(),
            shutUpEndTime = info.shutUpTime.takeIf { it > 0 }?.toLong(),
        )
    }

    fun buildFriendEntity(userId: Long, fallbackNick: String? = null, fallbackRemark: String? = null): FriendEntity {
        val info = runCatching {
            val svc = QRoute.api(IFriendsInfoService::class.java)
            val uid = svc.getUidFromUin(userId.toString()).orEmpty()
            if (uid.isNotEmpty()) svc.getFriendsSimpleInfoWithUid(uid, "MilkyBot") else null
        }.getOrNull()

        val sexStr = when (info?.sex) {
            1 -> "male"
            2 -> "female"
            else -> "unknown"
        }
        return FriendEntity(
            userId = userId,
            nickname = info?.nick ?: fallbackNick.orEmpty(),
            sex = sexStr,
            qid = info?.alias.orEmpty(),
            remark = info?.remark ?: fallbackRemark.orEmpty(),
            category = FriendCategoryEntity(
                categoryId = info?.categoryId ?: 0,
                categoryName = "我的好友",
            ),
        )
    }
}
