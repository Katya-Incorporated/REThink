/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_FIREWALL
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseIpsAdapter
import com.celzero.bravedns.adapter.DomainRulesBtmSheetAdapter
import com.celzero.bravedns.databinding.BottomSheetAppConnectionsBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.UIUtils.updateHtmlEncodedText
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class AppIpRulesBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAppConnectionsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()

    // listener to inform dataset change to the adapter
    private var dismissListener: OnBottomSheetDialogFragmentDismiss? = null
    private var adapter: AppWiseIpsAdapter? = null
    private var position: Int = -1

    override fun getTheme(): Int =
        getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private var uid: Int = -1
    private var ipAddress: String = ""
    private var ipRule: IpRulesManager.IpRuleStatus = IpRulesManager.IpRuleStatus.NONE
    private var domains: String = ""

    companion object {
        const val UID = "UID"
        const val IP_ADDRESS = "IP_ADDRESS"
        const val DOMAINS = "DOMAINS"
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    interface OnBottomSheetDialogFragmentDismiss {
        fun notifyDataset(position: Int)
    }

    fun dismissListener(aca: AppWiseIpsAdapter?, pos: Int) {
        adapter = aca
        position = pos
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAppConnectionsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        uid = arguments?.getInt(UID) ?: INVALID_UID
        ipAddress = arguments?.getString(IP_ADDRESS) ?: ""
        domains = arguments?.getString(DOMAINS) ?: ""

        dismissListener = adapter

        init()
        initializeClickListeners()
        setRulesUi()
    }

    private fun init() {
        if (uid == INVALID_UID) {
            this.dismiss()
            return
        }

        updateAppDetails()
        b.bsacIpAddressTv.text = ipAddress

        b.bsacIpRuleTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_ip))
        b.bsacDomainRuleTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_domain))

        setupRecycler()
    }

    private fun updateAppDetails() {
        if (uid == -1) return

        io {
            val appNames = FirewallManager.getAppNamesByUid(uid)
            if (appNames.isEmpty()) {
                uiCtx { handleNonApp() }
                return@io
            }
            val pkgName = FirewallManager.getPackageNameByAppName(appNames[0])

            val appCount = appNames.count()
            uiCtx {
                if (appCount >= 1) {
                    b.bsacAppName.text =
                        if (appCount >= 2) {
                            getString(
                                R.string.ctbs_app_other_apps,
                                appNames[0],
                                appCount.minus(1).toString()
                            )
                        } else {
                            appNames[0]
                        }
                    if (pkgName == null) return@uiCtx
                    b.bsacAppIcon.setImageDrawable(Utilities.getIcon(requireContext(), pkgName))
                } else {
                    // apps which are not available in cache are treated as non app.
                    // TODO: check packageManager#getApplicationInfo() for appInfo
                    handleNonApp()
                }
            }
        }
    }

    private fun handleNonApp() {
        b.bsacAppName.visibility = View.GONE
        b.bsacAppIcon.visibility = View.GONE
    }

    private fun setupRecycler() {
        if (domains.isEmpty()) {
            b.bsacDomainLl.visibility = View.GONE
            return
        }

        val list = domains.split(",").toTypedArray()

        b.bsacDomainList.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.bsacDomainList.layoutManager = layoutManager

        val recyclerAdapter = DomainRulesBtmSheetAdapter(requireContext(), uid, list)
        b.bsacDomainList.adapter = recyclerAdapter
    }

    private fun setRulesUi() {
        io {
            // no need to send port number for the app info screen
            ipRule = IpRulesManager.getMostSpecificRuleMatch(uid, ipAddress)
            Logger.d(LOG_TAG_FIREWALL, "Set selection of ip: $ipAddress, ${ipRule.id}")
            uiCtx {
                when (ipRule) {
                    IpRulesManager.IpRuleStatus.TRUST -> {
                        enableTrustUi()
                    }
                    IpRulesManager.IpRuleStatus.BLOCK -> {
                        enableBlockUi()
                    }
                    IpRulesManager.IpRuleStatus.NONE -> {
                        noRuleUi()
                    }
                    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                        noRuleUi()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (uid == INVALID_UID) {
            this.dismiss()
            return
        }
    }

    private fun initializeClickListeners() {

        b.blockIcon.setOnClickListener {
            if (ipRule == IpRulesManager.IpRuleStatus.BLOCK) {
                applyIpRule(IpRulesManager.IpRuleStatus.NONE)
                noRuleUi()
            } else {
                applyIpRule(IpRulesManager.IpRuleStatus.BLOCK)
                enableBlockUi()
            }
        }

        b.trustIcon.setOnClickListener {
            if (ipRule == IpRulesManager.IpRuleStatus.TRUST) {
                applyIpRule(IpRulesManager.IpRuleStatus.NONE)
                noRuleUi()
            } else {
                applyIpRule(IpRulesManager.IpRuleStatus.TRUST)
                enableTrustUi()
            }
        }
    }

    private fun applyIpRule(status: IpRulesManager.IpRuleStatus) {
        Logger.i(LOG_TAG_FIREWALL, "ip rule for uid: $uid, ip: $ipAddress (${status.name})")
        ipRule = status
        val ipPair = IpRulesManager.getIpNetPort(ipAddress)
        val ip = ipPair.first ?: return

        // set port number as null for all the rules applied from this screen
        io { IpRulesManager.addIpRule(uid, ip, null, status) }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissListener?.notifyDataset(position)
    }

    private fun enableTrustUi() {
        b.trustIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_trust_accent)
        )
        b.blockIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_block)
        )
    }

    private fun enableBlockUi() {
        b.trustIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_trust)
        )
        b.blockIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_block_accent)
        )
    }

    private fun noRuleUi() {
        b.trustIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_trust)
        )
        b.blockIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_block)
        )
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
