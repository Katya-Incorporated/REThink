/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import Logger
import Logger.LOG_TAG_PROXY
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.databinding.ListItemWgIncludeAppsBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WgIncludeAppsAdapter(
    private val context: Context,
    private val proxyId: String,
    private val proxyName: String
) :
    PagingDataAdapter<ProxyApplicationMapping, WgIncludeAppsAdapter.IncludedAppInfoViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ProxyApplicationMapping>() {

                // based on the apps package info and excluded status
                override fun areItemsTheSame(
                    oldConnection: ProxyApplicationMapping,
                    newConnection: ProxyApplicationMapping
                ): Boolean {
                    return (oldConnection.proxyId == newConnection.proxyId &&
                        oldConnection.uid == newConnection.uid)
                }

                // return false, when there is difference in excluded status
                override fun areContentsTheSame(
                    oldConnection: ProxyApplicationMapping,
                    newConnection: ProxyApplicationMapping
                ): Boolean {
                    return (oldConnection.proxyId == newConnection.proxyId &&
                        oldConnection.uid == newConnection.uid)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IncludedAppInfoViewHolder {
        val itemBinding =
            ListItemWgIncludeAppsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return IncludedAppInfoViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: IncludedAppInfoViewHolder, position: Int) {
        val apps: ProxyApplicationMapping = getItem(position) ?: return
        holder.update(apps)
    }

    inner class IncludedAppInfoViewHolder(private val b: ListItemWgIncludeAppsBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(mapping: ProxyApplicationMapping) {
            val isProxyExcluded = FirewallManager.isAppExcludedFromProxy(mapping.uid)
            if (isProxyExcluded) {
                b.wgIncludeAppListContainer.isEnabled = false
                b.wgIncludeAppListCheckbox.isChecked = false
                // do not allow to click on the card
                b.wgIncludeCard.isClickable = false
                b.wgIncludeCard.isFocusable = false
                b.wgIncludeAppListCheckbox.isClickable = false
                b.wgIncludeAppListCheckbox.isFocusable = false
            } else {
                b.wgIncludeAppListContainer.isEnabled = true
                b.wgIncludeCard.isClickable = true
                b.wgIncludeCard.isFocusable = true
                b.wgIncludeAppListCheckbox.isClickable = true
                b.wgIncludeAppListCheckbox.isFocusable = true
            }

            b.wgIncludeAppListApkLabelTv.text = mapping.appName

            if (mapping.proxyId == "") {
                b.wgIncludeAppAppDescTv.text = ""
                b.wgIncludeAppAppDescTv.visibility = View.GONE
                b.wgIncludeAppListCheckbox.isChecked = false
                setCardBackground(false)
            } else if (mapping.proxyId != proxyId) {
                if (!isProxyExcluded) {
                    b.wgIncludeAppAppDescTv.text =
                        context.getString(R.string.wireguard_apps_proxy_map_desc, mapping.proxyName)
                } else {
                    b.wgIncludeAppAppDescTv.text = ""
                }
                b.wgIncludeAppAppDescTv.visibility = View.VISIBLE
                b.wgIncludeAppListCheckbox.isChecked = false
                setCardBackground(false)
            } else {
                b.wgIncludeAppAppDescTv.text = ""
                b.wgIncludeAppAppDescTv.visibility = View.GONE
                b.wgIncludeAppListCheckbox.isChecked =
                    mapping.proxyId == proxyId && !isProxyExcluded
                setCardBackground(mapping.proxyId == proxyId && !isProxyExcluded)
            }

            val isIncluded = mapping.proxyId == proxyId && mapping.proxyId != ""
            ui { displayIcon(getIcon(context, mapping.packageName, mapping.appName)) }
            setupClickListeners(mapping, isIncluded)
        }

        private fun setupClickListeners(mapping: ProxyApplicationMapping, isIncluded: Boolean) {
            b.wgIncludeCard.setOnClickListener {
                Logger.i(
                    LOG_TAG_PROXY,
                    "wgIncludeAppListContainer- ${mapping.appName}, $isIncluded"
                )
                updateInterfaceDetails(mapping, !isIncluded)
            }

            b.wgIncludeAppListCheckbox.setOnCheckedChangeListener(null)
            b.wgIncludeAppListCheckbox.setOnClickListener {
                val isAdded = mapping.proxyId == proxyId
                Logger.i(LOG_TAG_PROXY, "wgIncludeAppListCheckbox - ${mapping.appName}, $isAdded")
                updateInterfaceDetails(mapping, !isAdded)
            }
        }

        private fun displayIcon(drawable: Drawable?) {
            Glide.with(context)
                .load(drawable)
                .error(getDefaultIcon(context))
                .into(b.wgIncludeAppListApkIconIv)
        }

        private fun setCardBackground(isSelected: Boolean) {
            if (isSelected) {
                b.wgIncludeCard.setCardBackgroundColor(
                    UIUtils.fetchColor(context, R.attr.selectedCardBg)
                )
            } else {
                b.wgIncludeCard.setCardBackgroundColor(
                    UIUtils.fetchColor(context, R.attr.background)
                )
            }
        }

        private fun updateInterfaceDetails(mapping: ProxyApplicationMapping, include: Boolean) {
            io {
                val appUidList = FirewallManager.getAppNamesByUid(mapping.uid)
                if (FirewallManager.isAppExcludedFromProxy(mapping.uid)) {
                    uiCtx {
                        showToastUiCentered(
                            context,
                            context.getString(R.string.exclude_apps_from_proxy_failure_toast),
                            Toast.LENGTH_LONG
                        )
                    }
                    return@io
                }
                uiCtx {
                    if (appUidList.count() > 1) {
                        showDialog(appUidList, mapping, include)
                    } else {
                        updateProxyIdForApp(mapping, include)
                    }
                }
            }
        }

        private fun updateProxyIdForApp(mapping: ProxyApplicationMapping, include: Boolean) {
            io {
                if (include) {
                    ProxyManager.updateProxyIdForApp(mapping.uid, proxyId, proxyName)
                    Logger.i(LOG_TAG_PROXY, "Included apps: ${mapping.uid}, $proxyId, $proxyName")
                } else {
                    ProxyManager.setNoProxyForApp(mapping.uid)
                    uiCtx { b.wgIncludeAppListCheckbox.isChecked = false }
                    Logger.i(LOG_TAG_PROXY, "Removed apps: ${mapping.uid}, $proxyId, $proxyName")
                }
            }
        }

        private fun showDialog(
            packageList: List<String>,
            mapping: ProxyApplicationMapping,
            included: Boolean
        ) {
            val positiveTxt: String

            val builderSingle = MaterialAlertDialogBuilder(context)

            builderSingle.setIcon(R.drawable.ic_firewall_exclude_on)

            val count = packageList.count()
            val title =
                if (included) {
                    positiveTxt = context.getString(R.string.lbl_include)
                    context.getString(R.string.wg_apps_dialog_title_include, count.toString())
                } else {
                    positiveTxt = context.getString(R.string.lbl_remove)
                    context.getString(R.string.wg_apps_dialog_title_exclude, count.toString())
                }

            builderSingle.setTitle(title)
            val arrayAdapter =
                ArrayAdapter<String>(context, android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle
                .setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                    updateProxyIdForApp(mapping, included)
                }
                .setNeutralButton(context.getString(R.string.ctbs_dialog_negative_btn)) {
                    _: DialogInterface,
                    _: Int ->
                }

            val alertDialog: AlertDialog = builderSingle.show()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.setCancelable(false)
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun ui(f: () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.Main) { f() } }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
