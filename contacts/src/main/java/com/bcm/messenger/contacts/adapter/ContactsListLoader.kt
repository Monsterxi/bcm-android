package com.bcm.messenger.contacts.adapter

import androidx.loader.content.AsyncTaskLoader
import com.bcm.messenger.common.AccountContext
import com.bcm.messenger.common.provider.AmeModuleCenter
import com.bcm.messenger.common.recipients.Recipient
import com.bcm.messenger.utility.AppContextHolder
import com.bcm.messenger.utility.logger.ALog
import com.orhanobut.logger.Logger
import java.util.*

/**
 * Created by wjh on 2018/4/12
 */
class ContactsListLoader(private val accountContext: AccountContext, private val isGroup: Boolean = false, private val includeMe: Boolean = false) : AsyncTaskLoader<List<Recipient>>(AppContextHolder.APP_CONTEXT) {

    private var mLastResult: List<Recipient>? = null

    override fun loadInBackground(): List<Recipient> {
        val results = ArrayList<Recipient>()
        try {

            ALog.d("ContactsListLoader", "load contacts begin")
            if (isGroup) {
                results.addAll(AmeModuleCenter.group(accountContext)?.getJoinedListBySort()?.map {
                    Recipient.recipientFromNewGroup(accountContext, it)
                } ?: listOf())

            } else {
                if (includeMe) {
                    results.addAll(AmeModuleCenter.contact(accountContext)?.getContactListWithWait() ?: listOf())
                } else {
                    results.addAll(AmeModuleCenter.contact(accountContext)?.getContactListWithWait()?.filter { !it.isLogin } ?: listOf())
                }
            }
            ALog.d("ContactsListLoader", "load contacts end")
            mLastResult = results

        } catch (ex: Exception) {
            ALog.e("ContactsListLoader", "run error", ex)
        }

        return results
    }

    /* Runs on the UI thread */
    override fun deliverResult(data: List<Recipient>?) {
        Logger.d("ContactsListLoader deliverResult")
        if (isReset) {
            return
        }

        if (isStarted) {
            super.deliverResult(data)
        }
    }

    override fun onCanceled(data: List<Recipient>?) {
        Logger.d("ContactsListLoader onCanceled")
        ALog.d("ContactsListLoader", "onCanceled")
    }

    override fun onStartLoading() {
        if (takeContentChanged() || mLastResult == null) {
            forceLoad()
        }
    }

    override fun onStopLoading() {
        cancelLoad()
    }

    override fun onReset() {
        super.onReset()
        ALog.d("ContactsListLoader", "onReset")

        // Ensure the loader is stopped
        onStopLoading()
    }

}