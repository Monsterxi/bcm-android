package com.bcm.messenger.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import com.bcm.messenger.R
import com.bcm.messenger.common.ARouterConstants
import com.bcm.messenger.common.ui.popup.AmePopup
import com.bcm.messenger.common.ui.popup.bottompopup.AmeBottomPopup
import com.bcm.messenger.common.utils.dp2Px
import com.bcm.messenger.common.utils.getScreenWidth
import com.bcm.messenger.common.utils.getString
import com.bcm.messenger.common.utils.isReleaseBuild
import com.bcm.messenger.ui.widget.IProfileView
import com.bcm.messenger.utility.AppContextHolder
import com.bcm.messenger.utility.logger.ALog
import com.bcm.route.api.BcmRouter
import kotlinx.android.synthetic.main.home_profile_add_view.view.*

/**
 * Created by Kin on 2020/1/2
 */
class HomeAddAccountView @JvmOverloads constructor(context: Context,
                                                  attrs: AttributeSet? = null,
                                                  defStyleAttr: Int = 0)
    : ConstraintLayout(context, attrs, defStyleAttr), IProfileView {
    private val TAG = "HomeAddAccountView"

    override var isActive = false
    override var isLogin = false
    override var position = 0f

    private val dp10 = 10.dp2Px()
    private val avatarMargin = (AppContextHolder.APP_CONTEXT.getScreenWidth() - 120.dp2Px()) / 2
    private val badgeMarginEnd = (160.dp2Px() * 0.3f / 2).toInt()
    private val badgeMarginStart = 150.dp2Px() - badgeMarginEnd - 27.dp2Px()

    private fun showAnimation() = AnimatorSet().apply {
        val updateListener = ValueAnimator.AnimatorUpdateListener {
            setViewsAlpha(it.animatedValue as Float)
        }
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f)
        valueAnimator.addUpdateListener(updateListener)

        play(valueAnimator)

        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator?) {
                showAllViews()
            }

            override fun onAnimationEnd(animation: Animator?) {
                valueAnimator.removeAllUpdateListeners()
                this@apply.removeAllListeners()
            }
        })
    }

    private fun hideAnimation() = AnimatorSet().apply {
        val updateListener = ValueAnimator.AnimatorUpdateListener {
            setViewsAlpha(it.animatedValue as Float)
        }
        val valueAnimator = ValueAnimator.ofFloat(1f, 0f)
        valueAnimator.addUpdateListener(updateListener)

        play(valueAnimator)

        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                hideAllViews()
                valueAnimator.removeAllUpdateListeners()
                this@apply.removeAllListeners()
            }
        })
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.home_profile_add_view, this, true)
    }

    override fun initView() {
        home_add_view_add.setOnClickListener {
            showActionSheet()
        }

        showAllViews()
        setViewsAlpha(1f)
    }

    override fun setViewsAlpha(alpha: Float) {
        home_add_view_add.alpha = alpha
        home_add_view_title.alpha = alpha
    }

    override fun showAllViewsWithAnimation() {
        showAnimation().start()
    }

    override fun showAllViews() {
        home_add_view_title.visibility = View.VISIBLE
        showAvatar()
        setViewsAlpha(0f)
    }

    override fun showAvatar() {
        home_add_view_add.visibility = View.VISIBLE
    }

    override fun hideAllViews() {
        home_add_view_add.visibility = View.INVISIBLE
        home_add_view_title.visibility = View.INVISIBLE
    }

    override fun hideAvatar() {
        home_add_view_add.visibility = View.INVISIBLE
    }

    override fun setMargin(topMargin: Int) {
        home_add_view_layout.layoutParams = (home_add_view_layout.layoutParams as LayoutParams).apply {
            setMargins(0, 60.dp2Px() + topMargin / 2, 0, 0)
        }
    }

    override fun resetMargin() {
        home_add_view_layout.layoutParams = (home_add_view_layout.layoutParams as LayoutParams).apply {
            setMargins(0, 60.dp2Px(), 0, 0)
        }
    }

    override fun setPagerChangedAlpha(alpha: Float) {
        home_add_view_title.alpha = alpha
    }

    override fun onViewPositionChanged(position: Float, percent: Float) {
        val scale = 0.7f + 0.3f * percent
        val curAvatarMargin = avatarMargin * (1 - percent)

        home_add_view_add.layoutParams = (home_add_view_add.layoutParams as LayoutParams).apply {
            if (position < 0) {
                marginStart = curAvatarMargin.toInt()
            } else {
                marginEnd = curAvatarMargin.toInt()
            }
        }
        home_add_view_add.scaleX = scale
        home_add_view_add.scaleY = scale

        setPagerChangedAlpha(percent)
    }

    private fun showActionSheet() {
        val builder = AmePopup.bottom.newBuilder()
                .withPopItem(AmeBottomPopup.PopupItem(getString(R.string.me_str_scan_to_login)) {
                    try {
                        BcmRouter.getInstance().get(ARouterConstants.Activity.SCAN_NEW)
                                .putInt(ARouterConstants.PARAM.SCAN.SCAN_TYPE, ARouterConstants.PARAM.SCAN.TYPE_ACCOUNT)
                                .navigation(context as FragmentActivity, HomeActivity.REQ_SCAN_ACCOUNT)
                    } catch (ex: Exception) {
                        ALog.e(TAG, "start ScanActivity error", ex)
                    }
                })
                .withPopItem(AmeBottomPopup.PopupItem(getString(R.string.me_str_create_account)) {
                    BcmRouter.getInstance().get(ARouterConstants.Activity.USER_REGISTER_PATH)
                            .putBoolean("CREATE_ACCOUNT", true)
                            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .navigation()
                })
        if (!isReleaseBuild()) {
            builder.withPopItem(AmeBottomPopup.PopupItem("Import Account") {

            }).withPopItem(AmeBottomPopup.PopupItem("Export Account") {

            })
        }
        builder.withDoneTitle(getString(R.string.common_cancel))
                .show(context as? FragmentActivity)
    }
}