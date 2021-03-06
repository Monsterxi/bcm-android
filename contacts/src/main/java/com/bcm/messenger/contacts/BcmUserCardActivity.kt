package com.bcm.messenger.contacts

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.bcm.messenger.common.ARouterConstants
import com.bcm.messenger.common.AccountSwipeBaseActivity
import com.bcm.messenger.common.core.Address
import com.bcm.messenger.common.core.corebean.AmeGroupMemberInfo
import com.bcm.messenger.common.database.model.ProfileKeyModel
import com.bcm.messenger.common.event.HomeTopEvent
import com.bcm.messenger.common.provider.AmeModuleCenter
import com.bcm.messenger.common.provider.AmeProvider
import com.bcm.messenger.common.provider.IAmeAppModule
import com.bcm.messenger.common.recipients.Recipient
import com.bcm.messenger.common.recipients.RecipientModifiedListener
import com.bcm.messenger.common.ui.CommonTitleBar2
import com.bcm.messenger.common.ui.IndividualAvatarView
import com.bcm.messenger.common.ui.popup.AmePopup
import com.bcm.messenger.common.ui.popup.bottompopup.AmeBottomPopup
import com.bcm.messenger.common.utils.BcmGroupNameUtil
import com.bcm.messenger.common.utils.getAttrColor
import com.bcm.messenger.common.utils.startBcmActivity
import com.bcm.messenger.utility.QuickOpCheck
import com.bcm.route.api.BcmRouter
import io.reactivex.disposables.Disposable
import kotlinx.android.synthetic.main.contacts_activity_contact_card.*

class BcmUserCardActivity : AccountSwipeBaseActivity(), RecipientModifiedListener {

    private var mRecipient: Recipient? = null
    private var groupMemberInfo: AmeGroupMemberInfo? = null
    private var groupId: Long = 0

    private var isWaitToChat = false
    private var isProfileUpdated = false

    private var mProfileDispose: Disposable? = null

    private var mOtherNick: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.contacts_activity_contact_card)

        val address = intent.getParcelableExtra<Address>(ARouterConstants.PARAM.PARAM_ADDRESS)
        groupId = intent.getLongExtra(ARouterConstants.PARAM.PARAM_GROUP_ID, 0)
        initView(address, intent.getStringExtra(ARouterConstants.PARAM.PARAM_NICK))
    }

    override fun onDestroy() {
        super.onDestroy()
        mRecipient?.removeListener(this)
        mProfileDispose?.dispose()
    }

    private fun initView(address: Address, nick: String?) {
        val recipient = Recipient.from(address, true)
        mRecipient = recipient
        mRecipient?.addListener(this)

        mOtherNick = nick
        if (!nick.isNullOrEmpty()) {
            AmeModuleCenter.contact(accountContext)?.updateNickFromOtherWay(recipient, nick)
        }

        friend_chat.setOnClickListener {
            if (QuickOpCheck.getDefault().isQuick) {
                return@setOnClickListener
            }
            if (isProfileUpdated || AmeModuleCenter.adhoc().isAdHocMode()) {
                goChat()
            } else {
                AmePopup.loading.show(this)
                isWaitToChat = true
            }
        }

        friend_add.setOnClickListener {
            if (QuickOpCheck.getDefault().isQuick) {
                return@setOnClickListener
            }
            addFriend()
        }

        title_bar.setListener(object : CommonTitleBar2.TitleBarClickListener() {
            override fun onClickLeft() {
                finish()
            }

            override fun onClickRight() {
                if (AmeModuleCenter.adhoc().isAdHocMode()) {
                    return
                }
                val builder = AmePopup.bottom.newBuilder()
                builder.withPopItem(AmeBottomPopup.PopupItem(getString(R.string.contacts_user_card_forward_title)) {
                    BcmRouter.getInstance().get(ARouterConstants.Activity.CONTACT_SHARE_FORWARD)
                            .putParcelable(ARouterConstants.PARAM.PARAM_ADDRESS, mRecipient?.address)
                            .startBcmActivity(accountContext, this@BcmUserCardActivity)

                })
                if (!recipient.isLogin) {
                    builder.withPopItem(AmeBottomPopup.PopupItem(if (recipient.isBlocked) getString(R.string.contacts_user_card_unblock_title) else getString(R.string.contacts_user_card_block_title), AmeBottomPopup.PopupItem.CLR_RED) {
                        val provider = AmeModuleCenter.contact(accountContext)
                        provider?.blockContact(recipient.address, !recipient.isBlocked) { success ->
                            if (success) {
                                if (recipient.isBlocked) {
                                    AmePopup.result.succeed(this@BcmUserCardActivity, getString(R.string.contacts_user_block_success))
                                } else {
                                    AmePopup.result.succeed(this@BcmUserCardActivity, getString(R.string.contacts_user_unblock_success))
                                }
                            } else {
                                if (!recipient.isBlocked) {
                                    AmePopup.result.failure(this@BcmUserCardActivity, getString(R.string.contacts_user_block_fail))
                                } else {
                                    AmePopup.result.failure(this@BcmUserCardActivity, getString(R.string.contacts_user_unblock_fail))
                                }
                            }
                        }

                    })
                }
                builder.withDoneTitle(getString(R.string.common_cancel))
                        .withCancelable(true)
                        .show(this@BcmUserCardActivity)
            }
        })

        if (AmeModuleCenter.adhoc().isAdHocMode()) {
            title_bar.hideRightViews()
        }

        updateUserInfo(recipient, null)
        if (groupId > 0 && recipient.address.isIndividual) {
            AmeModuleCenter.group(accountContext)?.queryMember(groupId, recipient.address.serialize()) {
                groupMemberInfo = it
                updateUserInfo(recipient, it)
            }

        }

        if (!AmeModuleCenter.adhoc().isAdHocMode()) {
            mProfileDispose = AmeModuleCenter.contact(accountContext)?.fetchProfile(recipient) {
                isProfileUpdated = true
                if (isWaitToChat) {
                    isWaitToChat = false
                    AmePopup.loading.dismiss()

                    if (recipient.isAllowStranger || recipient.isFriend) {
                        goChat()
                    }
                }
            }
        }
    }

    private fun updateUserInfo(recipient: Recipient, member: AmeGroupMemberInfo?) {
        val nickname = BcmGroupNameUtil.getGroupMemberName(recipient, member)
        mOtherNick = nickname
        val profileKey = ProfileKeyModel.fromKeyConfig(member?.keyConfig)
        if (null != profileKey) {
            AmeModuleCenter.contact(accountContext)?.updateProfileKey(this, recipient, profileKey)
        }
        anchor_img.setPhoto(recipient, nickname, IndividualAvatarView.DEFAULT_PHOTO_TYPE)
        anchor_name.text = nickname
        updateActionState(recipient)

        val groupMemberNick = member?.nickname // maybe stranger, so should use group member nick name
        if (!groupMemberNick.isNullOrEmpty()) {
            AmeModuleCenter.contact(accountContext)?.updateNickFromOtherWay(recipient, groupMemberNick)
        }
    }

    private fun updateActionState(recipient: Recipient) {
        val allowChat = AmeModuleCenter.adhoc().isAdHocMode() || recipient.isFriend || recipient.isAllowStranger
        val allowAdd = !AmeModuleCenter.adhoc().isAdHocMode() && !recipient.isFriend

        if (allowChat) {
            friend_chat.isEnabled = true
            friend_chat.setTextColor(getAttrColor(R.attr.common_text_blue_color))
        } else {
            friend_chat.isEnabled = false
            friend_chat.setTextColor(getAttrColor(R.attr.common_text_secondary_color))
        }

        if (!allowAdd) {
            friend_add.visibility = View.GONE
            anchor_friend_type.visibility = View.GONE
        } else {
            friend_add.isEnabled = true
            friend_add.visibility = View.VISIBLE

            anchor_friend_type.visibility = View.VISIBLE
        }
    }

    private fun addFriend() {
        val address = mRecipient?.address ?: return
        val intent = Intent(this, RequestAddFriendActivity::class.java).apply {
            putExtra(ARouterConstants.PARAM.PARAM_ADDRESS, address)
            putExtra(ARouterConstants.PARAM.PARAM_NICK, mOtherNick)
        }
        startBcmActivity(intent)
    }

    private fun goChat() {
        val recipient = mRecipient ?: return
        if (AmeModuleCenter.adhoc().isAdHocMode()) {
            AmeModuleCenter.adhoc().gotoPrivateChat(this, recipient.address.serialize())
        } else {
            AmeProvider.get<IAmeAppModule>(ARouterConstants.Provider.PROVIDER_APPLICATION_BASE)?.gotoHome(accountContext, HomeTopEvent(true,
                    HomeTopEvent.ConversationEvent.fromPrivateConversation(recipient.address.serialize(), false)))
        }
    }

    override fun onModified(recipient: Recipient) {
        anchor_img.post {
            if (mRecipient == recipient) {
                updateUserInfo(recipient, groupMemberInfo)
            }
        }
    }

}