package com.bcm.messenger.chats.group.logic

import android.annotation.SuppressLint
import android.util.LongSparseArray
import com.bcm.messenger.chats.group.core.GroupMessageCore
import com.bcm.messenger.common.AccountContext
import com.bcm.messenger.common.utils.log.ACLog
import com.bcm.messenger.utility.dispatcher.AmeDispatcher
import com.bcm.messenger.utility.logger.ALog
import io.reactivex.schedulers.Schedulers

/**
 * Created by bcm.social.01 on 2018/5/23.
 */
class GroupAckReporter(private val accountContext: AccountContext) {
    private val TAG = "GroupAckReporter"

    private val reportStash = LongSparseArray<Long>()
    private var syncingList:ArrayList<Long>? = null

    fun resetSyncState() {
        AmeDispatcher.singleScheduler.scheduleDirect {
            syncingList = null
        }
    }

    fun groupMessageSyncing(gidList:List<Long>) {
        AmeDispatcher.singleScheduler.scheduleDirect {
            syncingList = ArrayList(gidList)
        }
    }

    fun groupMessageSyncReady(gid:Long) {
        AmeDispatcher.singleScheduler.scheduleDirect {
            syncingList?.remove(gid)
            val ack = reportStash.get(gid, 0)
            if (ack > 0) {
                reportAckImpl(gid, ack)
            }
        }
    }

    fun reportAck(gid: Long, ack:Long) {
        AmeDispatcher.singleScheduler.scheduleDirect {
            if (syncingList?.contains(gid) == false) {
                reportAckImpl(gid, ack)
            } else {
                reportStash.put(gid, ack)
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun reportAckImpl(gid: Long, ack: Long) {
        GroupMessageCore.ackMessage(accountContext, gid, ack)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe({
                    ACLog.i(accountContext, TAG, "send ack success gid = $gid mid = $ack")
                }, {
                    ACLog.e(accountContext, TAG, "send ack error gid = $gid mid = $ack", it)
                })
    }
}