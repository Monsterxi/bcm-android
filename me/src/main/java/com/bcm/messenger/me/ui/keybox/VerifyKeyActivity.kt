package com.bcm.messenger.me.ui.keybox

import android.content.Intent
import android.os.Bundle
import com.bcm.messenger.common.ARouterConstants
import com.bcm.messenger.common.AccountSwipeBaseActivity
import com.bcm.messenger.common.provider.AMELogin
import com.bcm.messenger.common.utils.hideKeyboard
import com.bcm.messenger.common.utils.startBcmActivity
import com.bcm.messenger.me.R
import com.bcm.messenger.me.ui.login.LoginVerifyPinFragment
import com.bcm.messenger.me.ui.login.RegistrationActivity
import com.bcm.route.annotation.Route

@Route(routePath = ARouterConstants.Activity.VERIFY_PASSWORD)
class VerifyKeyActivity : AccountSwipeBaseActivity() {
    companion object {
        const val BACKUP_JUMP_ACTION = "BACKUP_JUMP_ACTION"
        const val NEED_FINISH = "NEED_FINISH"
        const val LOGIN_PROFILE = 3
    }

    private var controlType: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        controlType = intent.getIntExtra(BACKUP_JUMP_ACTION, 0)
        if (controlType == LOGIN_PROFILE) {
            disableDefaultTransitionAnimation()
            overridePendingTransition(R.anim.common_popup_alpha_in, R.anim.common_popup_alpha_out)
        } else {
            disableStatusBarLightMode()
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.me_activity_verify)
        initView()
    }

    fun initView() {
        val arg = intent.extras
        arg?.putBoolean(NEED_FINISH, true)

        val f = LoginVerifyPinFragment()
        arg?.putString(RegistrationActivity.RE_LOGIN_ID, intent.getStringExtra(RegistrationActivity.RE_LOGIN_ID))
        initFragment(R.id.register_container, f, arg)

    }

    override fun onBackPressed() {
        if (!AMELogin.isLogin) {
            hideKeyboard()
            val intent = Intent(this, RegistrationActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
            this.startBcmActivity(intent)
            this.finish()
        } else {
            super.onBackPressed()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, R.anim.common_popup_alpha_out)
    }
}