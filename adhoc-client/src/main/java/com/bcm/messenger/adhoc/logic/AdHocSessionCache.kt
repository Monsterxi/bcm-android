package com.bcm.messenger.adhoc.logic

import com.bcm.messenger.adhoc.search.BcmAdHocFinder
import com.bcm.messenger.common.AccountContext
import com.bcm.messenger.common.database.repositories.Repository
import com.bcm.messenger.common.finder.BcmFinderManager
import com.bcm.messenger.common.grouprepository.room.dao.AdHocChannelDao
import com.bcm.messenger.common.grouprepository.room.dao.AdHocMessageDao
import com.bcm.messenger.common.grouprepository.room.dao.AdHocSessionDao
import com.bcm.messenger.common.grouprepository.room.entity.AdHocSessionInfo
import com.bcm.messenger.common.provider.AMELogin
import com.bcm.messenger.common.recipients.Recipient
import com.bcm.messenger.utility.AmeTimeUtil
import com.bcm.messenger.utility.dispatcher.AmeDispatcher
import com.bcm.messenger.utility.logger.ALog
import io.reactivex.Observable

class AdHocSessionCache(private val accountContext: AccountContext, ready:(list:List<AdHocSession>)->Unit) {

    private val sessionList = HashMap<String, AdHocSession>()
    private val sessionFinder = BcmAdHocFinder(accountContext)

    init {
        ALog.i("AdHocSessionCache", "init begin")
        fun initPrivateChatUser(sessionList: List<AdHocSession>) {
            val repo = Repository.getRecipientRepo(accountContext)
            val uidMap = mutableMapOf<String, AdHocSession>()
            sessionList.forEach {
                if (it.isChat()) {
                    uidMap[it.uid] = it
                }
            }
            val settings = repo?.getRecipients(uidMap.keys)
            settings?.forEach {
                ALog.i("AdHocSessionCache", "initPrivateChatUser uid: ${it.uid}")
                uidMap[it.uid]?.updateRecipient(Recipient.fromSnapshot(accountContext, it.uid, it))
            }
        }

        BcmFinderManager.get(accountContext).registerFinder(sessionFinder)
        Observable.create<List<AdHocSession>> { em ->
            val sessionList = mutableListOf<AdHocSession>()
            val messageDao = messageDao()
            getDao().loadAllSession().forEach {
                messageDao.setSendingMessageFail(it.sessionId)
                ALog.i("AdHocSessionCache", "init sessionId: ${it.sessionId}, lastState: ${it.lastState}")
                val lastState = if (it.lastState == AdHocSession.STATE_SENDING) AdHocSession.STATE_FAILURE else it.lastState
                val session = AdHocSession(it.sessionId, it.cid, it.uid, it.pin, it.mute, it.atMe, it.unreadCount, it.timestamp, it.lastMessage, lastState, it.draft)
                sessionList.add(session)
            }
            initPrivateChatUser(sessionList)
            em.onNext(sessionList)
            em.onComplete()
        }.subscribeOn(AmeDispatcher.ioScheduler)
                .observeOn(AmeDispatcher.ioScheduler)
                .doOnError {}
                .subscribe { result ->
                    ALog.i("AdHocSessionCache", "init end")
                    result.forEach {
                        sessionList[it.sessionId] = it
                    }
                    ready(result)
                }
    }

    fun allInitFinish() {
        sessionFinder.updateSource(sessionList.values.toList())
    }

    fun saveChatSession(sessionId: String, uid: String, callback: (changed: Boolean) -> Unit) {
        if (!isExist(sessionId)) {
            val session = AdHocSession(sessionId, "", uid, timestamp = AmeTimeUtil.localTimeMillis())
            sessionList[sessionId] = session
            AmeDispatcher.io.dispatch {
                session.updateRecipient(Recipient.from(accountContext, uid, false))
                sessionFinder.updateSource(sessionList.values.toList())
                val dbSession = AdHocSessionInfo(sessionId, session.cid, session.uid, timestamp = session.timestamp)
                getDao().saveSession(dbSession)
                callback.invoke(true)
            }
        }else {
            callback.invoke(false)
        }
    }

    fun saveChannelSession(sessionId: String, cid:String, callback: (changed: Boolean) -> Unit) {
        if (!isExist(sessionId)) {
            val session = AdHocSession(sessionId, cid, "", timestamp = AmeTimeUtil.localTimeMillis())
            sessionList[sessionId] = session
            sessionFinder.updateSource(sessionList.values.toList())
            AmeDispatcher.io.dispatch {
                val dbSession = AdHocSessionInfo(sessionId, session.cid, session.uid,timestamp = session.timestamp)
                getDao().saveSession(dbSession)
                callback.invoke(true)
            }
        } else {
            callback.invoke(false)
        }
    }

    fun getSession(sessionId: String): AdHocSession? {
        return sessionList[sessionId]
    }

    fun isExist(sessionId: String): Boolean {
        return getSession(sessionId) != null
    }

    fun updateDraft(sessionId: String, draft: String): Boolean {
        val session = getSession(sessionId)
        if (null != session) {
            if (draft == session.draft) {
                return false
            }

            session.draft = draft
            session.timestamp = AmeTimeUtil.localTimeMillis()
            AmeDispatcher.io.dispatch {
                getDao().updateDraft(sessionId, draft)
            }
            return true
        }

        return false
    }

    fun updateLastMessage(sessionId: String, text: String, state: Int): Boolean {
        val session = getSession(sessionId)
        if (null != session) {
            if (text == session.lastMessage && state == session.lastState) {
                return false
            }

            session.lastMessage = text
            session.timestamp = AmeTimeUtil.localTimeMillis()
            session.lastState = state
            AmeDispatcher.io.dispatch {
                getDao().updateLastMessage(sessionId, text, state)
            }
            return true
        }
        return false
    }

    fun getSessionList(): List<AdHocSession> {
        return resortList(sessionList.values.toList())
    }

    private fun resortList(list:List<AdHocSession>):List<AdHocSession> {
        return list.sortedWith(Comparator { o1, o2 ->
            if(o1.pin && !o2.pin){
                return@Comparator -1
            } else if(!o1.pin && o2.pin ){
                return@Comparator 1
            } else {
                return@Comparator when {
                    o1.timestamp > o2.timestamp -> -1
                    o1.timestamp == o2.timestamp -> 0
                    else -> 1
                }
            }
        })
    }

    @Throws(Exception::class)
    private fun messageDao(): AdHocMessageDao {
        return Repository.getAdHocMessageRepo(accountContext) ?: throw Exception("getMessageDao fail")
    }

    @Throws(Exception::class)
    private fun getDao(): AdHocSessionDao {
        return Repository.getAdHocSessionRepo(accountContext) ?: throw Exception("getSessionDao fail")
    }

    @Throws(Exception::class)
    private fun channelDao(): AdHocChannelDao {
        return Repository.getAdHocChannelRepo(accountContext)?: throw Exception("getChannelDao fail")
    }

    fun updatePin(sessionId: String, pin: Boolean): Boolean {
        val session = getSession(sessionId)
        if (null != session) {
            if (session.pin == pin) {
                return false
            }

            session.pin = pin
            session.timestamp = AmeTimeUtil.localTimeMillis()
            AmeDispatcher.io.dispatch {
                getDao().updatePin(sessionId, pin)
            }
            return true
        }
        return false
    }

    fun updateMute(sessionId: String, mute:Boolean): Boolean {
        val session = getSession(sessionId)
        if (null != session) {
            if (session.mute == mute) {
                return false
            }

            session.mute = mute
            session.timestamp = AmeTimeUtil.localTimeMillis()
            AmeDispatcher.io.dispatch {
                getDao().updateMute(sessionId, mute)
            }
            return true
        }
        return false
    }

    fun updateUnreadCount(sessionId: String, count:Int): Boolean {
        val session = getSession(sessionId)
        if (null != session) {
            if (count == session.unreadCount) {
                return false
            }

            session.unreadCount = count
            session.timestamp = AmeTimeUtil.localTimeMillis()
            AmeDispatcher.io.dispatch {
                getDao().updateUnread(sessionId, count)
            }
            return true
        }
        return false
    }

    fun updateAtMeStatus(sessionId: String, hasAtMe:Boolean): Boolean {
        val session = getSession(sessionId)
        if (null != session) {
            if (session.atMe == hasAtMe) {
                return false
            }

            session.atMe = hasAtMe
            session.timestamp = AmeTimeUtil.localTimeMillis()
            AmeDispatcher.io.dispatch {
                getDao().updateAtMe(sessionId, hasAtMe)
            }
            return true
        }
        return false
    }

    fun deleteSession(sessionId: String): Boolean {
        if(null != sessionList.remove(sessionId)) {
            sessionFinder.updateSource(sessionList.values.toList())
            AmeDispatcher.io.dispatch {
                getDao().deleteSession(sessionId)
                messageDao().deleteAllMessage(sessionId)
            }
            return true
        }
        return false
    }
}