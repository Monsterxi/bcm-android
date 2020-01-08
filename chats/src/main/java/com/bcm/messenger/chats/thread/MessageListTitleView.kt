package com.bcm.messenger.chats.thread

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import android.widget.TextSwitcher
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat.getColor
import com.bcm.messenger.chats.R
import com.bcm.messenger.common.ARouterConstants
import com.bcm.messenger.common.AccountContext
import com.bcm.messenger.common.provider.AMELogin
import com.bcm.messenger.common.provider.AmeModuleCenter
import com.bcm.messenger.common.provider.AmeProvider
import com.bcm.messenger.common.provider.accountmodule.IAdHocModule
import com.bcm.messenger.common.recipients.Recipient
import com.bcm.messenger.common.recipients.RecipientModifiedListener
import com.bcm.messenger.common.server.ConnectState
import com.bcm.messenger.common.server.IServerConnectStateListener
import com.bcm.messenger.common.ui.popup.ToastUtil
import com.bcm.messenger.common.utils.AppUtil.getString
import com.bcm.messenger.common.utils.dp2Px
import com.bcm.messenger.common.utils.sp2Px
import com.bcm.messenger.utility.AppContextHolder
import com.bcm.messenger.utility.StringAppearanceUtil
import com.bcm.messenger.utility.bcmhttp.utils.ServerCodeUtil
import com.bcm.messenger.utility.dispatcher.AmeDispatcher
import com.bcm.messenger.utility.network.INetworkConnectionListener
import com.bcm.messenger.utility.network.NetworkUtil
import com.bcm.netswitchy.proxy.IProxyStateChanged
import com.bcm.netswitchy.proxy.ProxyManager
import com.bcm.route.api.BcmRouter


class MessageListTitleView : TextSwitcher, INetworkConnectionListener, IProxyStateChanged, RecipientModifiedListener, IServerConnectStateListener {

    private val INIT = 0
    private val OFFLINE = 1
    private val CONNECTING = 2
    private val CONNECTED = 3
    private val PROXY_CONNECTING = 4
    private val PROXY_TRY = 5

    private var customProxyConnecting = false

    private var mHasNoticeLowVersionWarning = false
    private var state = INIT

    private var recipient = Recipient.major()
    private var currentName = recipient.name

    constructor(context: Context) : super(context, null)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    fun init() {
        AmeModuleCenter.serverDaemon(AMELogin.majorContext).addConnectionListener(this)
        NetworkUtil.addListener(this)
        ProxyManager.setListener(this)
        recipient.addListener(this)
        currentName = recipient.name

        inAnimation = AnimationUtils.loadAnimation(context, R.anim.common_popup_drop_in)
        outAnimation = AnimationUtils.loadAnimation(context, R.anim.common_popup_drop_out)

        getChildAt(0).setOnClickListener {
            jump()
        }

        getChildAt(1).setOnClickListener {
            jump()
        }

        update()
    }

    fun unInit() {
        AmeModuleCenter.serverDaemon(AMELogin.majorContext).removeConnectionListener(this)
        NetworkUtil.removeListener(this)
        recipient.removeListener(this)
    }

    private fun jump() {
        if (PROXY_TRY == state) {
            BcmRouter.getInstance().get(ARouterConstants.Activity.PROXY_SETTING)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .navigation()
        } else if (OFFLINE == state) {
            val adhocProvider = AmeProvider.get<IAdHocModule>(ARouterConstants.Provider.PROVIDER_AD_HOC)
            adhocProvider?.configHocMode()
        }
    }

    override fun onNetWorkStateChanged() {
        update()
    }

    override fun onServerConnectionChanged(accountContext: AccountContext, newState: ConnectState) {
        AmeDispatcher.mainThread.dispatch {
            update()
        }
    }

    override fun onProxyConnecting(proxyName: String, isOfficial: Boolean) {
        if (!isOfficial) {
            customProxyConnecting = true
            update()
        }
    }

    override fun onProxyConnectFinished() {
        customProxyConnecting = false
        update()
    }

    override fun onProxyListChanged() {
        val isOffline = getState() != CONNECTED
        if (ProxyManager.hasCustomProxy() && !ProxyManager.isProxyRunning() && isOffline) {
            ProxyManager.startProxy()
        }
        update()
    }

    private fun update() {
        val state = getState()
        if (state == this.state) {
            return
        }

        this.state = state

        isEnabled = (state == OFFLINE || state == PROXY_TRY)
        if (isEnabled) {
            getChildAt(0).isEnabled = true
            getChildAt(1).isEnabled = true

            (getChildAt(0) as TextView).maxLines = 2
            (getChildAt(1) as TextView).maxLines = 2
        } else {
            getChildAt(0).isEnabled = false
            getChildAt(1).isEnabled = false

            (getChildAt(0) as TextView).maxLines = 1
            (getChildAt(1) as TextView).maxLines = 1
        }

        val spanText = when (state) {
            OFFLINE -> {
                val builder = SpannableStringBuilder()
                val offlineString = SpannableString(getString(R.string.chats_network_disconnected))
                offlineString.setSpan(StyleSpan(Typeface.BOLD), 0, offlineString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                offlineString.setSpan(AbsoluteSizeSpan(20, true), 0, offlineString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                offlineString.setSpan(ForegroundColorSpan(getColor(context, R.color.common_color_black)), 0, offlineString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                builder.append(offlineString)

                val spanString = SpannableString(getString(R.string.chats_try_airchat))
                spanString.setSpan(StyleSpan(Typeface.BOLD), 0, spanString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(AbsoluteSizeSpan(12, true), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(ForegroundColorSpan(getColor(context, R.color.common_color_379BFF)), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append(spanString)
                builder.append(StringAppearanceUtil.addImage(context, " ", R.drawable.chats_main_status_right_icon, 12.dp2Px(), 0))

                builder
            }
            PROXY_TRY -> {
                val builder = SpannableStringBuilder()
                val offlineString = SpannableString(getString(R.string.chats_server_can_not_reach))
                offlineString.setSpan(StyleSpan(Typeface.BOLD), 0, offlineString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                offlineString.setSpan(AbsoluteSizeSpan(20, true), 0, offlineString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                offlineString.setSpan(ForegroundColorSpan(getColor(context, R.color.common_color_black)), 0, offlineString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                builder.append(offlineString)

                val spanString = SpannableString(getString(R.string.chats_network_try_proxy))
                spanString.setSpan(StyleSpan(Typeface.BOLD), 0, spanString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(AbsoluteSizeSpan(12, true), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(ForegroundColorSpan(getColor(context, R.color.common_color_379BFF)), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append(spanString)
                builder.append(StringAppearanceUtil.addImage(context, " ", R.drawable.chats_main_status_right_icon, 12.dp2Px(), 0))

                builder
            }
            CONNECTING, PROXY_CONNECTING -> {
                if (ServerCodeUtil.pullWebSocketError() == ServerCodeUtil.CODE_LOW_VERSION) {
                    if (!mHasNoticeLowVersionWarning) {
                        mHasNoticeLowVersionWarning = true
                        ToastUtil.show(AppContextHolder.APP_CONTEXT, getString(R.string.common_too_low_version_notice), Toast.LENGTH_LONG)
                    }
                }

                val t = if (state == PROXY_CONNECTING) {
                    getString(R.string.chats_try_proxy_doing)
                } else {
                    getString(R.string.chats_network_connecting)
                }

                val spanString = SpannableString(t)
                spanString.setSpan(StyleSpan(Typeface.BOLD), 0, spanString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(AbsoluteSizeSpan(30, true), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(ForegroundColorSpan(getColor(context, R.color.common_color_black)), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                spanString

            }
            else -> {
                val spanString = SpannableString(currentName)
                spanString.setSpan(StyleSpan(Typeface.BOLD), 0, spanString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(AbsoluteSizeSpan(22.sp2Px()), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spanString.setSpan(ForegroundColorSpan(getColor(context, R.color.common_color_black)), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                spanString
            }
        }

        setText(spanText)
    }

    private fun getState(): Int {
        val serviceConnectState = AmeModuleCenter.serverDaemon(AMELogin.majorContext).state()
        return when {
            !NetworkUtil.isConnected() -> {
                OFFLINE
            }
            serviceConnectState == ConnectState.INIT -> {
                CONNECTING
            }
            serviceConnectState == ConnectState.CONNECTING -> {
                if (ProxyManager.isProxyRunning() && customProxyConnecting) {
                    PROXY_CONNECTING
                } else {
                    CONNECTING
                }
            }
            serviceConnectState == ConnectState.DISCONNECTED -> {
                PROXY_TRY
            }
            else -> {
                CONNECTED
            }
        }
    }

    override fun onModified(recipient: Recipient) {
        if (recipient == this.recipient) {
            if (recipient.name != currentName) {
                currentName = recipient.name

                if (getState() == CONNECTED) {
                    val spanString = SpannableString(currentName)
                    spanString.setSpan(StyleSpan(Typeface.BOLD), 0, spanString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spanString.setSpan(AbsoluteSizeSpan(22.sp2Px()), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spanString.setSpan(ForegroundColorSpan(getColor(context, R.color.common_color_black)), 0, spanString.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                    setText(spanString)
                }
            }
        }
    }
}