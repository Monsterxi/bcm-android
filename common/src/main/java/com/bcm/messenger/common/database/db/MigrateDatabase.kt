package com.bcm.messenger.common.database.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bcm.messenger.common.AccountContext
import com.bcm.messenger.common.attachments.DatabaseAttachment
import com.bcm.messenger.common.database.DatabaseFactory
import com.bcm.messenger.common.database.DraftDatabase
import com.bcm.messenger.common.database.IdentityDatabase
import com.bcm.messenger.common.database.RecipientDatabase
import com.bcm.messenger.common.database.model.*
import com.bcm.messenger.common.database.records.PrivacyProfile
import com.bcm.messenger.common.database.repositories.AttachmentRepo
import com.bcm.messenger.common.grouprepository.room.entity.*
import com.bcm.messenger.utility.AppContextHolder
import com.bcm.messenger.utility.Base64
import com.bcm.messenger.utility.logger.ALog
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.File

/**
 * Database for migration.
 * DO NOT EDIT THIS FILE IF FUNCTIONS WORK NORMALLY!!
 * 
 * Created by Kin on 2019/10/22
 */
class MigrateDatabase(private val accountContext: AccountContext) {
    private val TAG = "MigrateDatabase"

    private val helper = MigrateDatabaseHelper(accountContext)
    
    fun closeDatabase() {
        helper.close()
    }
    
    fun clearTables() {
        val db = helper.writableDatabase

        try {
            db.beginTransaction()
            db.execSQL("PRAGMA defer_foreign_keys = TRUE")
            db.execSQL("DELETE FROM `private_chat`")
            db.execSQL("DELETE FROM `thread`")
            db.execSQL("DELETE FROM `recipient`")
            db.execSQL("DELETE FROM `identities`")
            db.execSQL("DELETE FROM `drafts`")
            db.execSQL("DELETE FROM `push`")
            db.execSQL("DELETE FROM `attachments`")
            db.execSQL("DELETE FROM `ad_hoc_channel_1`")
            db.execSQL("DELETE FROM `adhoc_session_message`")
            db.execSQL("DELETE FROM `ad_hoc_sessions`")
            db.execSQL("DELETE FROM `group_bcm_friend`")
            db.execSQL("DELETE FROM `friend_request`")
            db.execSQL("DELETE FROM `chat_hide_msg`")
            db.execSQL("DELETE FROM `group_avatar_params`")
            db.execSQL("DELETE FROM `group_info`")
            db.execSQL("DELETE FROM `group_join_requests`")
            db.execSQL("DELETE FROM `group_live_info`")
            db.execSQL("DELETE FROM `group_member_table_new`")
            db.execSQL("DELETE FROM `group_message`")
            db.execSQL("DELETE FROM `note_record`")
            db.execSQL("DELETE FROM `group_key_store`")
            db.setTransactionSuccessful()
        } finally {
            if (db.inTransaction()) {
                db.endTransaction()
            }

            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
            if (!db.inTransaction()) {
                db.execSQL("VACUUM")
            }
        }
    }

    fun deleteDatabase() {
        helper.close()
        File("${AppContextHolder.APP_CONTEXT.filesDir.parent}/databases/user_${accountContext.uid}.db").delete()
        File("${AppContextHolder.APP_CONTEXT.filesDir.parent}/databases/user_${accountContext.uid}.db-shm").delete()
        File("${AppContextHolder.APP_CONTEXT.filesDir.parent}/databases/user_${accountContext.uid}.db-wal").delete()
    }

    fun insertThread(record: ThreadRecord): Long {
        val contentValues = ContentValues(16)
        val threadDatabase = DatabaseFactory.getThreadDatabase(AppContextHolder.APP_CONTEXT)

        contentValues.put("timestamp", record.date)
        contentValues.put("message_count", record.count)
        contentValues.put("unread_count", record.unreadCount)
        contentValues.put("uid", record.uid)
        contentValues.put("snippet_content", record.body.body)
        contentValues.put("snippet_type", record.type)
        contentValues.put("snippet_uri", record.snippetUri?.toString())
        contentValues.put("read", if (record.unreadCount == 0) 1 else 0)
        contentValues.put("has_sent", if (threadDatabase.getLastSeenAndHasSent(record.threadId).second()) 1 else 0)
        contentValues.put("distribution_type", record.distributionType)
        contentValues.put("expires_time", record.expiresIn)
        contentValues.put("last_seen", record.lastSeen)
        contentValues.put("pin_time", record.pin)
        contentValues.put("live_state", record.live_state)
        contentValues.put("decrypt_fail_data", threadDatabase.getDecryptFailData(record.threadId).orEmpty())
        contentValues.put("profile_req", if (threadDatabase.hasProfileRequest(record.threadId)) 1 else 0)

        val db = helper.writableDatabase
        return db.insert(ThreadDbModel.TABLE_NAME, null, contentValues)
    }

    fun insertDraft(threadId: Long, draft: DraftDatabase.Draft) {
        val contentValues = ContentValues(3)

        contentValues.put("thread_id", threadId)
        contentValues.put("type", draft.type)
        contentValues.put("value", draft.value)

        helper.writableDatabase.insert(DraftDbModel.TABLE_NAME, null, contentValues)
    }

    fun insertPush(pair: Pair<Long, SignalServiceProtos.Envelope>) {
        val contentValues = ContentValues(8)
        val envelope = pair.second

        contentValues.put("id", pair.first)
        contentValues.put("type", envelope.type.number)
        contentValues.put("source_uid", envelope.source)
        contentValues.put("device_id", envelope.sourceDevice)
        contentValues.put("content", if (envelope.hasContent()) Base64.encodeBytes(envelope.content.toByteArray()) else "")
        contentValues.put("legacy_msg", if (envelope.hasLegacyMessage()) Base64.encodeBytes(envelope.legacyMessage.toByteArray()) else "")
        contentValues.put("source_registration_id", envelope.source)
        contentValues.put("timestamp", envelope.timestamp)

        helper.writableDatabase.insert(PushDbModel.TABLE_NAME, null, contentValues)
    }

    fun insertMessageAndAttachment(threadId: Long, messageRecord: MessageRecord) {
        val db = helper.writableDatabase
        val attachmentList = mutableListOf<ContentValues>()

        val messageValues = ContentValues()
        messageValues.put("thread_id", threadId)
        messageValues.put("uid", messageRecord.uid)
        messageValues.put("address_device", messageRecord.recipientDeviceId)
        messageValues.put("date_receive", messageRecord.dateReceived)
        messageValues.put("date_sent", messageRecord.dateSent)
        messageValues.put("read", messageRecord.read)
        messageValues.put("type", messageRecord.type)
        messageValues.put("body", messageRecord.body.body)
        messageValues.put("expires_time", messageRecord.expiresIn)
        messageValues.put("expires_start", messageRecord.expireStarted)
        messageValues.put("read_recipient_count", messageRecord.readReceiptCount)
        messageValues.put("delivery_receipt_count", messageRecord.deliveryReceiptCount)
        messageValues.put("call_type", messageRecord.communicationType)
        messageValues.put("call_duration", messageRecord.duration)
        messageValues.put("payload_type", messageRecord.payloadType)
        when {
            messageRecord.isLocation -> {
                messageValues.put("message_type", PrivateChatDbModel.MessageType.LOCATION.type)
                messageValues.put("attachment_count", 0)
            }
            messageRecord.isCallLog -> {
                messageValues.put("message_type", PrivateChatDbModel.MessageType.CALL.type)
                messageValues.put("attachment_count", 0)
            }
            messageRecord.isExpirationTimerUpdate -> {
                messageValues.put("message_type", PrivateChatDbModel.MessageType.TEXT.type)
                messageValues.put("attachment_count", 0)
            }
            messageRecord.isMms -> {
                val mmsRecord = messageRecord as MediaMmsMessageRecord
                if (mmsRecord.slideDeck.thumbnailSlide != null) {
                    messageValues.put("message_type", PrivateChatDbModel.MessageType.MEDIA.type)
                } else if (mmsRecord.slideDeck.documentSlide != null) {
                    messageValues.put("message_type", PrivateChatDbModel.MessageType.DOCUMENT.type)
                }
                messageValues.put("attachment_count", messageRecord.partCount)

                messageRecord.slideDeck.slides.forEach { slide ->
                    val attachment = slide.asAttachment() as DatabaseAttachment
                    val attachmentValues = ContentValues(17)
                    attachmentValues.put("content_type", attachment.contentType)
                    attachmentValues.put("name", attachment.relay)
                    attachmentValues.put("file_name", attachment.fileName)
                    attachmentValues.put("key", attachment.key.orEmpty())
                    attachmentValues.put("location", attachment.location.orEmpty())
                    attachmentValues.put("transfer_state", attachment.transferState)
                    attachmentValues.put("uri", attachment.realDataUri?.toString())
                    attachmentValues.put("size", attachment.size)
                    attachmentValues.put("thumbnail_uri", attachment.realThumbnailUri?.toString())
                    attachmentValues.put("thumb_aspect_ratio", attachment.aspectRatio)
                    attachmentValues.put("unique_id", attachment.attachmentId.uniqueId)
                    attachmentValues.put("digest", attachment.digest)
                    attachmentValues.put("fast_preflight_id", attachment.fastPreflightId)
                    attachmentValues.put("duration", attachment.duration)
                    attachmentValues.put("url", attachment.url)
                    attachmentValues.put("type", AttachmentRepo.getMediaType(attachment.contentType).type)
                    attachmentList.add(attachmentValues)
                }
            }
            else -> {
                messageValues.put("message_type", PrivateChatDbModel.MessageType.TEXT.type)
                messageValues.put("attachment_count", 0)
            }
        }

        val messageId = db.insert(PrivateChatDbModel.TABLE_NAME, null, messageValues)
        attachmentList.forEach {
            it.put("mid", messageId)
            db.insert(AttachmentDbModel.TABLE_NAME, null, it)
        }
    }

    fun insertIdentity(identityKey: IdentityDatabase.IdentityRecord) {
        val contentValues = ContentValues(6)

        contentValues.put("uid", identityKey.address.serialize())
        contentValues.put("key", Base64.encodeBytes(identityKey.identityKey.serialize()))
        contentValues.put("first_use", if (identityKey.isFirstUse) 1 else 0)
        contentValues.put("timestamp", identityKey.timestamp)
        contentValues.put("verified", identityKey.verifiedStatus.toInt())
        contentValues.put("non_blocking_approval", if (identityKey.isApprovedNonBlocking) 1 else 0)

        helper.writableDatabase.insert(IdentityDbModel.TABLE_NAME, null, contentValues)
    }

    fun insertRecipient(settings: RecipientDatabase.RecipientSettings) {
        val contentValues = ContentValues(13)

        contentValues.put("uid", settings.uid)
        contentValues.put("block", if (settings.isBlocked) 1 else 0)
        contentValues.put("mute_until", settings.muteUntil)
        contentValues.put("expires_time", settings.expireMessages.toLong())
        contentValues.put("local_name", settings.localName)
        contentValues.put("local_avatar", settings.localAvatar)
        contentValues.put("profile_key", if (settings.profileKey == null) null else Base64.encodeBytes(settings.profileKey))
        contentValues.put("profile_name", settings.profileName)
        contentValues.put("profile_avatar", settings.profileAvatar)
        contentValues.put("profile_sharing_approval", if (settings.isProfileSharing) 0 else 1)
        contentValues.put("relationship", settings.relationship.type)
        contentValues.put("support_feature", settings.featureSupport?.toString().orEmpty())
        contentValues.put("contact_part_key", settings.contactPartKey)
        
        val profile = PrivacyProfile().apply {
            encryptedName = settings.privacyProfile.encryptedName
            name = settings.privacyProfile.name
            encryptedAvatarLD = settings.privacyProfile.encryptedAvatarLD
            avatarLD = settings.privacyProfile.avatarLD
            avatarLDUri = settings.privacyProfile.avatarLDUri
            isAvatarLdOld = settings.privacyProfile.isAvatarLdOld
            encryptedAvatarHD = settings.privacyProfile.encryptedAvatarHD
            avatarHD = settings.privacyProfile.avatarHD
            avatarHDUri = settings.privacyProfile.avatarHDUri
            isAvatarHdOld = settings.privacyProfile.isAvatarHdOld
            namePubKey = settings.privacyProfile.namePubKey
            nameKey = settings.privacyProfile.nameKey
            avatarPubKey = settings.privacyProfile.avatarPubKey
            avatarKey = settings.privacyProfile.avatarKey
            allowStranger = settings.privacyProfile.isAllowStranger
        }
        
        contentValues.put("privacy_profile", profile.toString())

        helper.writableDatabase.insert(RecipientDbModel.TABLE_NAME, null, contentValues)
    }

    fun insertAdHocChannel(info: AdHocChannelInfo) {
        val contentValues = ContentValues(3)

        contentValues.put("cid", info.cid)
        contentValues.put("channel_name", info.channelName)
        contentValues.put("passwd", info.passwd)

        helper.writableDatabase.insert(AdHocChannelInfo.TABLE_NAME, null, contentValues)
    }

    fun insertAdHocMessage(message: AdHocMessageDBEntity) {
        val contentValues = ContentValues(13)

        contentValues.put("session_id", message.sessionId)
        contentValues.put("message_id", message.messageId)
        contentValues.put("from_id", message.fromId)
        contentValues.put("from_nick", message.nickname)
        contentValues.put("text", message.text)
        contentValues.put("state", message.state)
        contentValues.put("is_read", message.read)
        contentValues.put("time", message.time)
        contentValues.put("is_send", message.sentByMe)
        contentValues.put("ext_content", message.extContent)
        contentValues.put("attachment_uri", message.attachmentUri)
        contentValues.put("thumbnail_uri", message.thumbnailUri)
        contentValues.put("attachment_state", message.attachmentState)

        helper.writableDatabase.insert(AdHocMessageDBEntity.TABLE_NAME, null, contentValues)
    }

    fun insertSessionInfo(info: AdHocSessionInfo) {
        val contentValues = ContentValues(11)

        contentValues.put("session_id", info.sessionId)
        contentValues.put("cid", info.cid)
        contentValues.put("uid", info.uid)
        contentValues.put("pin", info.pin)
        contentValues.put("mute", info.mute)
        contentValues.put("at_me", info.atMe)
        contentValues.put("unread_count", info.unreadCount)
        contentValues.put("timestamp", info.timestamp)
        contentValues.put("last_message", info.lastMessage)
        contentValues.put("last_state", info.lastState)
        contentValues.put("draft", info.draft)

        helper.writableDatabase.insert(AdHocSessionInfo.TABLE_NAME, null, contentValues)
    }

    fun insertBcmFriend(friend: BcmFriend) {
        val contentValues = ContentValues(3)

        contentValues.put("uid", friend.uid)
        contentValues.put("tag", friend.tag)
        contentValues.put("state", friend.state)

        helper.writableDatabase.insert(BcmFriend.TABLE_NAME, null, contentValues)
    }

    fun insertBcmFriendRequest(request: BcmFriendRequest) {
        val contentValues = ContentValues(6)

        contentValues.put("proposer", request.proposer)
        contentValues.put("timestamp", request.timestamp)
        contentValues.put("memo", request.memo)
        contentValues.put("signature", request.requestSignature)
        contentValues.put("unread", request.unread)
        contentValues.put("approve", request.approve)

        helper.writableDatabase.insert("friend_request", null, contentValues)
    }

    fun insertHideMessage(message: ChatHideMessage) {
        val contentValues = ContentValues(4)

        contentValues.put("id", message.id)
        contentValues.put("send_time", message.sendTime)
        contentValues.put("body", message.content)
        contentValues.put("dest_addr", message.destinationAddress)

        helper.writableDatabase.insert("chat_hide_msg", null, contentValues)
    }

    fun insertGroupAvatarParams(params: GroupAvatarParams) {
        val contentValues = ContentValues(9)

        contentValues.put("gid", params.gid)
        contentValues.put("uid1", params.uid1)
        contentValues.put("uid2", params.uid2)
        contentValues.put("uid3", params.uid3)
        contentValues.put("uid4", params.uid4)
        contentValues.put("user1Hash", params.user1Hash)
        contentValues.put("user2Hash", params.user2Hash)
        contentValues.put("user3Hash", params.user3Hash)
        contentValues.put("user4Hash", params.user4Hash)

        helper.writableDatabase.insert(GroupAvatarParams.TABLE_NAME, null, contentValues)
    }

    fun insertGroupInfo(info: GroupInfo) {
        val contentValues = ContentValues(39)

        contentValues.put("gid", info.gid)
        contentValues.put("owner", info.owner)
        contentValues.put("name", info.name)
        contentValues.put("key", info.currentKey)
        contentValues.put("key_version", info.currentKeyVersion)
        contentValues.put("channel_key", info.channel_key)
        contentValues.put("permission", info.permission)
        contentValues.put("iconUrl", info.iconUrl)
        contentValues.put("broadcast", info.broadcast)
        contentValues.put("createTime", info.createTime)
        contentValues.put("status", info.status)
        contentValues.put("thread_id", info.thread_id)
        contentValues.put("share_url", info.share_url)
        contentValues.put("share_content", info.share_content)
        contentValues.put("member_count", info.member_count)
        contentValues.put("subscriber_count", info.subscriber_count)
        contentValues.put("role", info.role)
        contentValues.put("illegal", info.illegal)
        contentValues.put("notification_enable", info.notification_enable)
        contentValues.put("notice_content", info.notice_content)
        contentValues.put("notice_update_time", info.notice_update_time)
        contentValues.put("is_show_notice", info.is_show_notice)
        contentValues.put("member_sync_state", info.member_sync_state)
        contentValues.put("share_qr_code_setting", info.shareCodeSetting)
        contentValues.put("owner_confirm", info.needOwnerConfirm)
        contentValues.put("share_sig", info.shareCodeSettingSign)
        contentValues.put("share_and_owner_confirm_sig", info.shareSettingAndConfirmSign)
        contentValues.put("group_info_secret", info.infoSecret)
        contentValues.put("share_enabled", info.shareEnabled)
        contentValues.put("share_code", info.shareCode)
        contentValues.put("share_epoch", info.shareEpoch)
        contentValues.put("group_splice_name", info.spliceName)
        contentValues.put("chn_splice_name", info.chnSpliceName)
        contentValues.put("splice_avatar", info.spliceAvatar)
        contentValues.put("share_link", info.shareLink)
        contentValues.put("pinMid", info.pinMid)
        contentValues.put("hasPin", info.hasPin)
        contentValues.put("ephemeral_key", info.ephemeralKey)
        contentValues.put("version", info.version)

        helper.writableDatabase.insert(GroupInfo.TABLE_NAME, null, contentValues)
    }

    fun insertGroupRequest(info: GroupJoinRequestInfo) {
        val contentValues = ContentValues(11)

        contentValues.put("mid", info.mid)
        contentValues.put("gid", info.gid)
        contentValues.put("uid", info.uid)
        contentValues.put("req_id", info.reqId)
        contentValues.put("identity_key", info.uidIdentityKey)
        contentValues.put("inviter", info.inviter)
        contentValues.put("inviter_identity_key", info.inviterIdentityKey)
        contentValues.put("read", info.read)
        contentValues.put("timestamp", info.timestamp)
        contentValues.put("status", info.status)
        contentValues.put("comment", info.comment)

        helper.writableDatabase.insert(GroupJoinRequestInfo.TABLE_NAME, null, contentValues)
    }

    fun insertGroupLiveInfo(info: GroupLiveInfo) {
        val contentValues = ContentValues(10)

        contentValues.put("gid", info.gid)
        contentValues.put("isLiving", info.isLiving)
        contentValues.put("source_url", info.source_url)
        contentValues.put("start_time", info.start_time)
        contentValues.put("duration", info.duration)
        contentValues.put("liveId", info.liveId)
        contentValues.put("liveStatus", info.liveStatus)
        contentValues.put("currentSeekTime", info.currentSeekTime)
        contentValues.put("currentActionTime", info.currentActionTime)
        contentValues.put("confirmed", info.isConfirmed)

        helper.writableDatabase.insert(GroupLiveInfo.TABLE_NAME, null, contentValues)
    }

    fun insertGroupMember(member: GroupMember) {
        val contentValues = ContentValues(7)

        contentValues.put("uid", member.uid)
        contentValues.put("gid", member.gid)
        contentValues.put("role", member.role)
        contentValues.put("join_time", member.joinTime)
        contentValues.put("nickname", member.nickname)
        contentValues.put("group_nickname", member.customNickname)
        contentValues.put("profile_keys", member.profileKeyConfig)

        helper.writableDatabase.insert(GroupMember.TABLE_NAME, null, contentValues)
    }

    fun insertGroupMessage(message: GroupMessage) {
        val contentValues = ContentValues(16)

        contentValues.put("gid", message.gid)
        contentValues.put("mid", message.mid)
        contentValues.put("from_uid", message.from_uid)
        contentValues.put("type", message.type)
        contentValues.put("text", message.text)
        contentValues.put("send_state", message.send_state)
        contentValues.put("read_state", message.read_state)
        contentValues.put("is_confirm", message.is_confirm)
        contentValues.put("attachment_uri", message.attachment_uri)
        contentValues.put("content_type", message.content_type)
        contentValues.put("create_time", message.create_time)
        contentValues.put("key_version", message.key_version)
        contentValues.put("encrypt_level", message.fileEncrypted)
        contentValues.put("send_or_receive", message.send_or_receive)
        contentValues.put("ext_content", message.extContent)
        contentValues.put("identity_iv", message.identityIvString)

        helper.writableDatabase.insert(GroupMessage.TABLE_NAME, null, contentValues)
    }

    fun insertNoteRecord(record: NoteRecord) {
        val contentValues = ContentValues(10)

        contentValues.put("_id", record.topicId)
        contentValues.put("topic", record.topic)
        contentValues.put("defaultTopic", record.defaultTopic)
        contentValues.put("timestamp", record.timestamp)
        contentValues.put("author", record.author)
        contentValues.put("pin", record.pin)
        contentValues.put("edit_position", record.editPosition)
        contentValues.put("note_url", record.noteUrl)
        contentValues.put("key", record.key)
        contentValues.put("digest", record.digest)

        helper.writableDatabase.insert(NoteRecord.TABLE_NAME, null, contentValues)
    }

    fun insertGroupKey(key: GroupKey) {
        val contentValues = ContentValues(3)

        contentValues.put("gid", key.gid)
        contentValues.put("version", key.version)
        contentValues.put("key", key.key)

        helper.writableDatabase.insert(GroupKey.TABLE_NAME, null, contentValues)
    }

    class MigrateDatabaseHelper(accountContext: AccountContext) : SQLiteOpenHelper(AppContextHolder.APP_CONTEXT, "user_${accountContext.uid}.db", null, 1) {
        private val TAG = "MigrateDatabaseHelper"

        override fun onCreate(db: SQLiteDatabase?) {
            if (db == null) {
                ALog.e(TAG, "db is null")
                return
            }
            // MARK: DO NOT EDIT these codes!!!
            db.execSQL("CREATE TABLE IF NOT EXISTS `private_chat` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `thread_id` INTEGER NOT NULL, `uid` TEXT NOT NULL, `address_device` INTEGER NOT NULL, `date_receive` INTEGER NOT NULL, `date_sent` INTEGER NOT NULL, `read` INTEGER NOT NULL, `type` INTEGER NOT NULL, `message_type` INTEGER NOT NULL, `body` TEXT NOT NULL, `attachment_count` INTEGER NOT NULL, `expires_time` INTEGER NOT NULL, `expires_start` INTEGER NOT NULL, `read_recipient_count` INTEGER NOT NULL, `delivery_receipt_count` INTEGER NOT NULL, `call_type` INTEGER NOT NULL, `call_duration` INTEGER NOT NULL, `payload_type` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `thread` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `message_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, `uid` TEXT NOT NULL, `snippet_content` TEXT NOT NULL, `snippet_type` INTEGER NOT NULL, `snippet_uri` TEXT, `read` INTEGER NOT NULL, `has_sent` INTEGER NOT NULL, `distribution_type` INTEGER NOT NULL, `expires_time` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `pin_time` INTEGER NOT NULL, `live_state` INTEGER NOT NULL, `decrypt_fail_data` TEXT NOT NULL, `profile_req` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `recipient` (`uid` TEXT NOT NULL, `block` INTEGER NOT NULL, `mute_until` INTEGER NOT NULL, `expires_time` INTEGER NOT NULL, `local_name` TEXT, `local_avatar` TEXT, `profile_key` TEXT, `profile_name` TEXT, `profile_avatar` TEXT, `profile_sharing_approval` INTEGER NOT NULL, `privacy_profile` TEXT NOT NULL, `relationship` INTEGER NOT NULL, `support_feature` TEXT NOT NULL, `contact_part_key` TEXT, PRIMARY KEY(`uid`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `identities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, `key` TEXT NOT NULL, `first_use` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `verified` INTEGER NOT NULL, `non_blocking_approval` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `drafts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `thread_id` INTEGER NOT NULL, `type` TEXT NOT NULL, `value` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `push` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `source_uid` TEXT NOT NULL, `device_id` INTEGER NOT NULL, `content` TEXT NOT NULL, `legacy_msg` TEXT NOT NULL, `source_registration_id` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mid` INTEGER NOT NULL, `content_type` TEXT NOT NULL, `type` INTEGER NOT NULL, `name` TEXT, `file_name` TEXT, `key` TEXT NOT NULL, `location` TEXT NOT NULL, `transfer_state` INTEGER NOT NULL, `uri` TEXT, `size` INTEGER NOT NULL, `thumbnail_uri` TEXT, `thumb_aspect_ratio` REAL NOT NULL, `unique_id` INTEGER NOT NULL, `digest` BLOB, `fast_preflight_id` TEXT, `duration` INTEGER NOT NULL, `url` TEXT, FOREIGN KEY(`mid`) REFERENCES `private_chat`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE TABLE IF NOT EXISTS `ad_hoc_channel_1` (`cid` TEXT NOT NULL, `channel_name` TEXT NOT NULL, `passwd` TEXT NOT NULL, PRIMARY KEY(`cid`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `adhoc_session_message` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `from_id` TEXT NOT NULL, `from_nick` TEXT NOT NULL, `text` TEXT NOT NULL, `state` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `time` INTEGER NOT NULL, `is_send` INTEGER NOT NULL, `ext_content` TEXT, `attachment_uri` TEXT, `thumbnail_uri` TEXT, `attachment_state` INTEGER NOT NULL)")
            db.execSQL("CREATE  INDEX `index_adhoc_session_message_session_id` ON `adhoc_session_message` (`session_id`)")
            db.execSQL("CREATE  INDEX `index_adhoc_session_message_message_id` ON `adhoc_session_message` (`message_id`)")
            db.execSQL("CREATE  INDEX `index_adhoc_session_message_from_id` ON `adhoc_session_message` (`from_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `ad_hoc_sessions` (`session_id` TEXT NOT NULL, `cid` TEXT NOT NULL, `uid` TEXT NOT NULL, `pin` INTEGER NOT NULL, `mute` INTEGER NOT NULL, `at_me` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `last_message` TEXT NOT NULL, `last_state` INTEGER NOT NULL, `draft` TEXT NOT NULL, PRIMARY KEY(`session_id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_bcm_friend` (`uid` TEXT NOT NULL, `tag` TEXT NOT NULL, `state` INTEGER NOT NULL, PRIMARY KEY(`uid`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `friend_request` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `proposer` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `memo` TEXT NOT NULL, `signature` TEXT NOT NULL, `unread` INTEGER NOT NULL, `approve` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `chat_hide_msg` (`id` INTEGER, `send_time` INTEGER NOT NULL, `body` TEXT NOT NULL, `dest_addr` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_avatar_params` (`gid` INTEGER NOT NULL, `uid1` TEXT NOT NULL, `uid2` TEXT NOT NULL, `uid3` TEXT NOT NULL, `uid4` TEXT NOT NULL, `user1Hash` TEXT NOT NULL, `user2Hash` TEXT NOT NULL, `user3Hash` TEXT NOT NULL, `user4Hash` TEXT NOT NULL, PRIMARY KEY(`gid`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_info` (`gid` INTEGER NOT NULL, `owner` TEXT, `name` TEXT, `key` TEXT, `key_version` INTEGER NOT NULL, `channel_key` TEXT, `permission` INTEGER NOT NULL, `iconUrl` TEXT, `broadcast` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `status` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, `share_url` TEXT, `share_content` TEXT, `member_count` INTEGER NOT NULL, `subscriber_count` INTEGER NOT NULL, `role` INTEGER NOT NULL, `illegal` INTEGER NOT NULL, `notification_enable` INTEGER NOT NULL, `notice_content` TEXT, `notice_update_time` INTEGER NOT NULL, `is_show_notice` INTEGER NOT NULL, `member_sync_state` TEXT, `share_qr_code_setting` TEXT, `owner_confirm` INTEGER, `share_sig` TEXT, `share_and_owner_confirm_sig` TEXT, `group_info_secret` TEXT, `share_enabled` INTEGER, `share_code` TEXT, `share_epoch` INTEGER, `group_splice_name` TEXT, `chn_splice_name` TEXT, `splice_avatar` TEXT, `ephemeral_key` TEXT, `version` INTEGER NOT NULL, `share_link` TEXT, `pinMid` INTEGER NOT NULL, `hasPin` INTEGER NOT NULL, PRIMARY KEY(`gid`))")
            db.execSQL("CREATE  INDEX `index_group_info_gid` ON `group_info` (`gid`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_join_requests` (`req_id` INTEGER NOT NULL, `identity_key` TEXT NOT NULL, `inviter` TEXT NOT NULL, `inviter_identity_key` TEXT NOT NULL, `read` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `status` INTEGER NOT NULL, `comment` TEXT NOT NULL, `mid` INTEGER NOT NULL, `gid` INTEGER NOT NULL, `uid` TEXT NOT NULL, PRIMARY KEY(`req_id`))")
            db.execSQL("CREATE UNIQUE INDEX `index_group_join_requests_uid_gid_mid` ON `group_join_requests` (`uid`, `gid`, `mid`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_live_info` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `gid` INTEGER NOT NULL, `isLiving` INTEGER NOT NULL, `source_url` TEXT, `source_type` INTEGER NOT NULL, `start_time` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `liveId` INTEGER NOT NULL, `liveStatus` INTEGER NOT NULL, `currentSeekTime` INTEGER NOT NULL, `currentActionTime` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL)")
            db.execSQL("CREATE  INDEX `index_group_live_info__id` ON `group_live_info` (`_id`)")
            db.execSQL("CREATE  INDEX `index_group_live_info_liveId` ON `group_live_info` (`liveId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_member_table_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `uid` TEXT NOT NULL, `gid` INTEGER NOT NULL, `role` INTEGER NOT NULL, `join_time` INTEGER NOT NULL, `nickname` TEXT NOT NULL, `group_nickname` TEXT NOT NULL, `profile_keys` TEXT NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX `index_group_member_table_new_uid_gid` ON `group_member_table_new` (`uid`, `gid`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_message` (`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `gid` INTEGER NOT NULL, `mid` INTEGER NOT NULL, `from_uid` TEXT, `type` INTEGER NOT NULL, `text` TEXT, `send_state` INTEGER NOT NULL, `read_state` INTEGER NOT NULL, `is_confirm` INTEGER NOT NULL, `attachment_uri` TEXT, `content_type` INTEGER NOT NULL, `create_time` INTEGER NOT NULL, `key_version` INTEGER NOT NULL, `encrypt_level` INTEGER NOT NULL, `send_or_receive` INTEGER NOT NULL, `ext_content` TEXT, `identity_iv` TEXT)")
            db.execSQL("CREATE  INDEX `index_group_message_gid` ON `group_message` (`gid`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `note_record` (`_id` TEXT NOT NULL, `topic` TEXT NOT NULL, `defaultTopic` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `author` TEXT NOT NULL, `pin` INTEGER NOT NULL, `edit_position` INTEGER NOT NULL, `note_url` TEXT NOT NULL, `key` TEXT NOT NULL, `digest` TEXT, PRIMARY KEY(`_id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_key_store` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `gid` INTEGER NOT NULL, `version` INTEGER NOT NULL, `key` TEXT NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX `index_group_key_store_version_gid` ON `group_key_store` (`version`, `gid`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '29be03a1194bd8874c4c6a953d6a3ab6')")
        }

        override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
            // MARK: DO NOT do any database upgrade here. Do it in UserDatabase!!!
        }
    }
}