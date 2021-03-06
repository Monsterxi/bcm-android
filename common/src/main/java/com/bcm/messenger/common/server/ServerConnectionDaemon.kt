package com.bcm.messenger.common.server

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.bcm.messenger.common.AccountContext
import com.bcm.messenger.common.BuildConfig
import com.bcm.messenger.common.bcmhttp.interceptor.RedirectInterceptorHelper
import com.bcm.messenger.common.bcmhttp.configure.lbs.LBSFetcher
import com.bcm.messenger.common.bcmhttp.configure.lbs.LBSManager
import com.bcm.messenger.common.metrics.COUNTER_TOPIC_BCM_SERVER
import com.bcm.messenger.common.metrics.COUNTER_WEBSOCKET_FAIL
import com.bcm.messenger.common.metrics.COUNTER_WEBSOCKET_SUCCESS
import com.bcm.messenger.common.provider.AmeModuleCenter
import com.bcm.messenger.common.utils.log.ACLog
import com.bcm.messenger.utility.AppContextHolder
import com.bcm.messenger.utility.GsonUtils
import com.bcm.messenger.utility.listener.SafeWeakListeners
import com.bcm.messenger.utility.logger.ALog
import com.bcm.messenger.utility.network.NetworkUtil
import com.google.protobuf.ByteString
import com.orhanobut.logger.Logger
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.whispersystems.libsignal.util.Pair
import org.whispersystems.signalservice.internal.push.OutgoingPushMessageList
import org.whispersystems.signalservice.internal.push.SendMessageResponse
import org.whispersystems.signalservice.internal.util.Util
import org.whispersystems.signalservice.internal.util.concurrent.SettableFuture
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos
import java.io.IOException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.concurrent.*

class ServerConnectionDaemon(private val accountContext: AccountContext
                             , private val daemonScheduler: DaemonScheduler
                             , private val serverDataDispatcher: ServerDataDispatcher)
    : IServerConnectionDaemon
        , ServerConnection.IServerProtoDataEvent
        , LBSFetcher.ILBSFetchResult
        , DaemonScheduler.IDaemonTicker {

    companion object {
        private const val KEEPALIVE_TIMEOUT_MILLI = 60_000L
        private const val TAG = "ServerConnectionDaemon"
        private const val CONN_METRICS_TOKEN = 1 //
        private const val CONN_DEFAULT_TOKEN = 0 //
    }

    private val singleScheduler = daemonScheduler.scheduler
    private val messageScheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    private var serverConn: ServerConnection? = null
    private var lastKeepTime: Long = 0L

    private var status: ConnectState = ConnectState.INIT

    private var hostUri: String = ""

    private var retryTimer: Disposable? = null

    private val metrics = AmeModuleCenter.metric(accountContext)

    private var lbsFetchIndex = 0

    private val connectionListener = SafeWeakListeners<IServerConnectStateListener>()

    private var forceLogoutListener: IServerConnectForceLogoutListener? = null

    private var tickListening = false

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (tickListening && NetworkUtil.isConnected()) {
                checkConnection(false)
            }
        }
    }

    init {
        LBSManager.addListener(this)
    }

    override fun addConnectionListener(listener: IServerConnectStateListener) {
        this.connectionListener.addListener(listener)
    }

    override fun removeConnectionListener(listener: IServerConnectStateListener) {
        this.connectionListener.removeListener(listener)
    }

    override fun setForceLogoutListener(listener: IServerConnectForceLogoutListener?) {
        this.forceLogoutListener = listener
    }

    override fun startDaemon() {
        ACLog.w(accountContext, TAG, "startDaemon")
        singleScheduler.scheduleDirect {
            stop()
            start()
        }
    }

    override fun stopDaemon() {
        ACLog.w(accountContext, TAG, "stopDaemon")
        singleScheduler.scheduleDirect {
            stop()
        }
    }

    private fun isDaemonRunning(): Boolean {
        return tickListening
    }

    override fun startConnection() {
        ACLog.w(accountContext, TAG, "startConnection")
        singleScheduler.scheduleDirect {
            doConnection(CONN_METRICS_TOKEN)
        }
    }

    override fun stopConnection() {
        ACLog.w(accountContext, TAG, "stopConnection")
        singleScheduler.scheduleDirect {
            serverConn?.setConnectionListener(null)
            serverConn?.disconnect()
            serverConn = null
        }
    }

    override fun state(): ConnectState {
        return status
    }

    override fun checkConnection(manual: Boolean) {
        ACLog.w(accountContext, TAG, "checkConnection network connected:${NetworkUtil.isConnected()}")
        singleScheduler.scheduleDirect {
            if (!isDaemonRunning()) {
                return@scheduleDirect
            }

            val tmp = retryTimer
            if (tmp != null && !tmp.isDisposed) {
                tmp.dispose()
            }

            if (serverConn?.isConnected() == true) {
                ACLog.w(accountContext, TAG, "service connected")
                return@scheduleDirect
            }


            val connectToken = if (manual) {
                CONN_METRICS_TOKEN
            } else {
                CONN_DEFAULT_TOKEN
            }

            var retryCalled = false
            retryTimer = Observable.timer(600, TimeUnit.MILLISECONDS, singleScheduler)
                    .repeat(4)
                    .subscribeOn(singleScheduler)
                    .observeOn(singleScheduler)
                    .doOnComplete {
                        retryTimer = null

                        val conn = serverConn
                        if (null == conn || conn.isDisconnect()) {
                            onServiceConnected(accountContext, connectToken, ConnectState.DISCONNECTED)
                            ACLog.w(accountContext, TAG, "checkConnection disconnected")
                        } else if (conn.isConnected()) {
                            onServiceConnected(accountContext, connectToken, ConnectState.CONNECTED)
                            ACLog.w(accountContext, TAG, "checkConnection connected")
                        } else {
                            Logger.i("$TAG checkConnection ${conn.state()}")
                        }

                    }
                    .subscribe({
                        val conn = serverConn

                        if ((null == conn || conn.isDisconnect() || conn.isTimeout())
                                && !retryCalled
                                && NetworkUtil.isConnected()) {
                            retryCalled = true
                            doConnection(CONN_DEFAULT_TOKEN)
                        } else if (conn?.isConnected() == true) {
                            retryTimer?.dispose()
                            retryTimer = null
                        }
                    }, {
                        Logger.e(it, "checkConnection")
                        retryTimer = null
                    })
        }
    }

    private fun doConnection(connectToken: Int): Boolean {
        val conn = serverConn
        if (conn != null && !conn.isDisconnect() && !conn.isTimeout()) {
            return true
        }

        conn?.disconnect()

        val serverConn = ServerConnection(accountContext, daemonScheduler.scheduler, BuildConfig.USER_AGENT)
        serverConn.setConnectionListener(this)
        this.serverConn = serverConn

        return serverConn.connect(connectToken)
    }

    private fun sendRequest(request: WebSocketProtos.WebSocketRequestMessage): Future<Pair<Int, String>> {
        val callback = SettableFuture<Pair<Int, String>>()
        singleScheduler.scheduleDirect {
            val conn = serverConn
            if (null != conn) {
                conn.sendRequest(request, callback)
            } else {
                callback.setException(IOException("connection not ready"))
            }
        }
        return callback
    }

    @Throws(IOException::class)
    override fun sendMessage(list: OutgoingPushMessageList): SendMessageResponse {
        try {
            val requestMessage = WebSocketProtos.WebSocketRequestMessage.newBuilder()
                    .setId(SecureRandom.getInstance("SHA1PRNG").nextLong())
                    .setVerb("PUT")
                    .setPath(String.format("/v1/messages/%s", list.destination))
                    .addHeaders("content-type:application/json")
                    .setBody(ByteString.copyFrom(GsonUtils.toJson(list).toByteArray()))
                    .build()

            val startTime = System.currentTimeMillis()

            val response = sendRequest(requestMessage).get(10, TimeUnit.SECONDS)

            val endTime = System.currentTimeMillis()

            val serverUrl = RedirectInterceptorHelper.imServerInterceptor.getCurrentServer()
            metrics?.addNetworkReportData(serverUrl.ip, serverUrl.port, requestMessage.verb, requestMessage.path, response.first().toString(), endTime - startTime)

            if (response.first() < 200 || response.first() >= 300) {
                throw IOException("Non-successful response: " + response.first())
            }

            return if (Util.isEmpty(response.second()))
                SendMessageResponse(false)
            else
                GsonUtils.fromJson(response.second(), SendMessageResponse::class.java)
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        } catch (e: InterruptedException) {
            throw IOException(e)
        } catch (e: ExecutionException) {
            throw IOException(e)
        } catch (e: TimeoutException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    fun sendResponse(response: WebSocketProtos.WebSocketResponseMessage): Future<Boolean> {
        val callback = SettableFuture<Boolean>()
        singleScheduler.scheduleDirect {
            val conn = serverConn
            if (null != conn) {
                conn.sendResponse(response, callback)
            } else {
                callback.setException(IOException("connection not ready"))
            }
        }
        return callback
    }

    private fun start() {
        daemonScheduler.startTicker()
        AppContextHolder.APP_CONTEXT.registerReceiver(networkReceiver, IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"))
        tickListening = true
        daemonScheduler.tickListener.addListener(this)
    }

    private fun daemonRun() {
        val conn = serverConn

        if (!NetworkUtil.isConnected()) {
            onServiceConnected(accountContext, CONN_DEFAULT_TOKEN, conn?.state()
                    ?: ConnectState.DISCONNECTED)
            return
        }

        if (null == conn || conn.isDisconnect() || conn.isTimeout()) {
            if (!doConnection(CONN_DEFAULT_TOKEN)) {
                ACLog.w(accountContext, TAG, "daemonRun params error ${hostUri.length}")
            } else {
                ACLog.w(accountContext, TAG, "daemonRun try reconnecting")
            }
            onServiceConnected(accountContext, CONN_DEFAULT_TOKEN, conn?.state()
                    ?: ConnectState.DISCONNECTED)
        } else if (conn.isConnected()) {
            if (tickTime() - lastKeepTime >= KEEPALIVE_TIMEOUT_MILLI) {
                if (conn.sendKeepAlive()) {
                    lastKeepTime = System.currentTimeMillis()
                } else {
                    ACLog.w(accountContext, TAG, "keep alive failed")
                }
            }

        } else {
            ACLog.w(accountContext, TAG, "daemonRun connecting")
        }
    }


    private fun stop() {
        if (tickListening) {
            try {
                AppContextHolder.APP_CONTEXT.unregisterReceiver(networkReceiver)
            } catch (e: Throwable) {
            }
            daemonScheduler.tickListener.removeListener(this)

            val loginList = AmeModuleCenter.login().getLoginAccountContextList()
            if (loginList.isEmpty()
                    || loginList.takeIf { it.size == 1 }?.first()?.uid == accountContext.uid) {
                daemonScheduler.stopTicker()
            }
        }
    }

    override fun onDaemonTick() {
        if (accountContext.isLogin) {
            daemonRun()
        }
    }

    private fun tickTime(): Long {
        return System.currentTimeMillis()
    }

    override fun onServiceConnected(accountContext: AccountContext, connectToken: Int, state: ConnectState) {
        ACLog.w(accountContext, TAG, "onServiceConnected $state token:$connectToken")
        if (this.status != state) {
            this.status = state

            if (state != ConnectState.CONNECTING && NetworkUtil.isConnected()) {
                if (connectToken == CONN_METRICS_TOKEN) {
                    checkMetrics(state == ConnectState.CONNECTED)
                }
            }
            this.connectionListener.forEach {
                it.onServerConnectionChanged(accountContext, state)
            }
        }
    }

    override fun onMessageArrive(accountContext: AccountContext, message: WebSocketProtos.WebSocketRequestMessage): Boolean {
        messageScheduler.scheduleDirect {
            try {
                val result = serverDataDispatcher.onMessageArrive(accountContext, message)

                val response = if (result) {
                    WebSocketProtos.WebSocketResponseMessage.newBuilder()
                            .setId(message.id)
                            .setStatus(200)
                            .setMessage("OK")
                            .build()
                } else {
                    WebSocketProtos.WebSocketResponseMessage.newBuilder()
                            .setId(message.id)
                            .setStatus(400)
                            .setMessage("Unknown")
                            .build()
                }
                sendResponse(response)
            } catch (e: Throwable) {
                ACLog.e(accountContext, TAG, "onMessageArrive", e)
            }
        }
        return true
    }

    override fun onClientForceLogout(accountContext: AccountContext, info: String?, type: KickEvent) {
        ACLog.w(accountContext, TAG, "onClientForceLogout $type $info")
        stopDaemon()
        this.forceLogoutListener?.onClientForceLogout(accountContext, info, type)
        onServiceConnected(accountContext, CONN_DEFAULT_TOKEN, ConnectState.DISCONNECTED)
    }

    override fun onLBSFetchResult(succeed: Boolean, fetchIndex: Int) {
        if (fetchIndex != this.lbsFetchIndex) {
            lbsFetchIndex = fetchIndex

            if (succeed && serverConn?.isConnected() == true) {
                stopConnection()
                startConnection()
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun checkMetrics(connected: Boolean) {
        if (!NetworkUtil.isConnected()) {
            return
        }

        metrics?.addCustomCounterReportData(COUNTER_TOPIC_BCM_SERVER, COUNTER_WEBSOCKET_SUCCESS, connected)
        metrics?.addCustomCounterReportData(COUNTER_TOPIC_BCM_SERVER, COUNTER_WEBSOCKET_FAIL, !connected)
    }

}