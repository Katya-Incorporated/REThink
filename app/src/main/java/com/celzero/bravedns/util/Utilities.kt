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
package com.celzero.bravedns.util

import Logger
import Logger.LOG_TAG_APP_DB
import Logger.LOG_TAG_DOWNLOAD
import Logger.LOG_TAG_FIREWALL
import Logger.LOG_TAG_VPN
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.text.TextUtils.SimpleStringSplitter
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleCoroutineScope
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository.Companion.NO_PACKAGE
import com.celzero.bravedns.net.doh.CountryMap
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_FDROID
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_HEADLESS
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_PLAY
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_WEBSITE
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.MISSING_UID
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.google.common.base.CharMatcher
import com.google.common.net.InternetDomainName
import com.google.gson.JsonParser
import inet.ipaddr.HostName
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlinx.coroutines.launch
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source

object Utilities {

    // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
    fun getETldPlus1(fqdn: String): String? {
        return try {
            val name: InternetDomainName = InternetDomainName.from(fqdn)
            try {
                name.topPrivateDomain().toString()
            } catch (e: IllegalStateException) {
                // The name doesn't end in a recognized TLD.  This can happen for randomly
                // generated
                // names, or when new TLDs are introduced.
                val parts: List<String> = name.parts()
                val size = parts.count()
                if (size >= 2) {
                    parts[size - 2] + "." + parts[size - 1]
                } else if (size == 1) {
                    parts[0]
                } else {
                    // Empty input?
                    fqdn
                }
            }
        } catch (e: IllegalArgumentException) {
            // If fqdn is not a valid domain name, InternetDomainName.from() will throw an
            // exception.  Since this function is only for aesthetic purposes, we can
            // return the input unmodified in this case.
            fqdn
        }
    }

    fun isAccessibilityServiceEnabled(
        context: Context,
        service: Class<out AccessibilityService?>
    ): Boolean {
        val am = context.getSystemService<AccessibilityManager>() ?: return false
        val enabledServices =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (enabledService in enabledServices) {
            val enabledServiceInfo: ServiceInfo = enabledService.resolveInfo.serviceInfo
            Logger.i(
                LOG_TAG_VPN,
                "Accessibility enabled check for: ${enabledServiceInfo.packageName}"
            )
            if (
                enabledServiceInfo.packageName == context.packageName &&
                    enabledServiceInfo.name == service.name
            ) {
                return true
            }
        }
        Logger.e(
            LOG_TAG_VPN,
            "Accessibility failure, ${context.packageName},  ${service.name}, return size: ${enabledServices.count()}"
        )
        return false
    }

    fun isAccessibilityServiceEnabledViaSettingsSecure(
        context: Context,
        accessibilityService: Class<out AccessibilityService?>
    ): Boolean {
        try {
            val expectedComponentName = ComponentName(context, accessibilityService)
            val enabledServicesSetting: String =
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
            val colonSplitter = SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledService = ComponentName.unflattenFromString(componentNameString)
                if (expectedComponentName == enabledService) {
                    Logger.i(
                        LOG_TAG_VPN,
                        "SettingsSecure accessibility enabled for: ${expectedComponentName.packageName}"
                    )
                    return true
                }
            }
        } catch (e: Settings.SettingNotFoundException) {
            Logger.e(
                LOG_TAG_VPN,
                "isAccessibilityServiceEnabled Exception on isAccessibilityServiceEnabledViaSettingsSecure() ${e.message}",
                e
            )
        }
        Logger.w(LOG_TAG_VPN, "Accessibility service not enabled via Settings Secure")
        return isAccessibilityServiceEnabled(context, accessibilityService)
    }

    private var countryMap: CountryMap? = null

    // Return a two-letter ISO country code, or null if that fails.
    fun getCountryCode(address: InetAddress?, context: Context): String? {
        initCountryMapIfNeeded(context)
        return (if (countryMap == null) {
            null
        } else {
            countryMap?.getCountryCode(address)
        })
    }

    private fun initCountryMapIfNeeded(context: Context) {
        if (countryMap != null) {
            return
        }

        try {
            countryMap = CountryMap(context.assets)
        } catch (e: IOException) {
            Logger.e(LOG_TAG_VPN, "Failure fetching country map ${e.message}", e)
        }
    }

    fun getFlag(countryCode: String?): String {
        if (countryCode == null) {
            return ""
        }
        // Flag emoji consist of two "regional indicator symbol letters", which are
        // Unicode characters that correspond to the English alphabet and are arranged in the
        // same
        // order.  Therefore, to convert from a country code to a flag, we simply need to apply
        // an
        // offset to each character, shifting it from the normal A-Z range into the region
        // indicator
        // symbol letter range.
        val alphaBase = 'A'.code // Start of alphabetic country code characters.
        val flagBase = 0x1F1E6 // Start of regional indicator symbol letters.
        val offset = flagBase - alphaBase
        val firstHalf = Character.codePointAt(countryCode, 0) + offset
        val secondHalf = Character.codePointAt(countryCode, 1) + offset
        return String(Character.toChars(firstHalf)) + String(Character.toChars(secondHalf))
    }

    fun normalizeIp(ipstr: String?): InetAddress? {
        if (ipstr.isNullOrEmpty()) return null

        val ipAddress: IPAddress = HostName(ipstr).asAddress() ?: return null
        val ip = ipAddress.toInetAddress()

        // no need to check if IP is not of type IPv6
        if (!IPUtil.isIpV6(ipAddress)) return ip

        val ipv4 = IPUtil.ip4in6(ipAddress)

        return if (ipv4 != null) {
            ipv4.toInetAddress()
        } else {
            ip
        }
    }

    fun makeAddressPair(countryCode: String?, ipAddress: String?): String {
        return if (ipAddress.isNullOrEmpty()) {
            "--" // to avoid translation set to "--"
        } else if (countryCode == null) {
            ipAddress
        } else {
            String.format("%s (%s)", countryCode, ipAddress)
        }
    }

    fun convertLongToTime(time: Long, template: String): String {
        val date = Date(time)
        return SimpleDateFormat(template, Locale.ENGLISH).format(date)
    }

    fun isLanIpv4(ipAddress: String): Boolean {
        val ip = IPAddressString(ipAddress).address ?: return false

        return ip.isLoopback || ip.isLocal || ip.isAnyLocal || UNSPECIFIED_IP_IPV4.equals(ip)
    }

    fun isValidLocalPort(port: Int?): Boolean {
        if (port == null) return false

        return isValidPort(port)
    }

    fun isValidPort(port: Int?): Boolean {
        if (port == null) return false

        return port in 65535 downTo 0
    }

    fun isMissingOrInvalidUid(uid: Int): Boolean {
        return when (uid) {
            MISSING_UID -> true
            INVALID_UID -> true
            else -> false
        }
    }

    fun isVpnLockdownEnabled(vpnService: BraveVPNService?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        return vpnService?.isLockdownEnabled == true
    }

    fun showToastUiCentered(context: Context, message: String, toastLength: Int) {
        try {
            Toast.makeText(context, message, toastLength).show()
        } catch (e: IllegalStateException) {
            Logger.w(LOG_TAG_VPN, "toast err: ${e.message}")
        } catch (e: IllegalAccessException) {
            Logger.w(LOG_TAG_VPN, "toast err: ${e.message}")
        } catch (e: IOException) {
            Logger.w(LOG_TAG_VPN, "toast err: ${e.message}")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "toast err: ${e.message}")
        }
    }

    fun getPackageMetadata(pm: PackageManager, pi: String): PackageInfo? {
        var metadata: PackageInfo? = null
        try {
            metadata =
                if (isAtleastT()) {
                    pm.getPackageInfo(
                        pi,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    pm.getPackageInfo(pi, PackageManager.GET_META_DATA)
                }
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_APP_DB, "Application not available $pi" + e.message, e)
        }
        return metadata
    }

    fun isFreshInstall(ctx: Context): Boolean {
        try {
            with(
                if (isAtleastT()) {
                    ctx.packageManager.getPackageInfo(
                        ctx.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_META_DATA)
                }
            ) {
                return firstInstallTime == lastUpdateTime
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // assign value as true as the package name not found, should not be the
            // case but some devices seems to return package not found immediately
            // after install
            Logger.w(LOG_TAG_APP_DB, "app not found ${ctx.packageName}" + e.message, e)
            return true
        }
    }

    fun copy(from: String, to: String): Boolean {
        try {
            val src = File(from)
            val dest = File(to)

            if (!src.isFile) return false

            src.copyTo(dest, true)
        } catch (e: Exception) { // Throws NoSuchFileException, IOException
            Logger.e(LOG_TAG_DOWNLOAD, "Error copying file ${e.message}", e)
            return false
        }

        return true
    }

    // ref: https://stackoverflow.com/a/41818556
    fun copyWithStream(readStream: InputStream, writeStream: OutputStream): Boolean {
        val length = 256
        val buffer = ByteArray(length)
        return try {
            var bytesRead: Int = readStream.read(buffer, 0, length)
            // write the required bytes
            while (bytesRead > 0) {
                writeStream.write(buffer, 0, bytesRead)
                bytesRead = readStream.read(buffer, 0, length)
            }
            readStream.close()
            writeStream.close()
            true
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "Issue while copying files using streams: ${e.message}, $e")
            false
        }
    }

    fun isAlwaysOnEnabled(context: Context, vpnService: BraveVPNService?): Boolean {
        // Introduced as part of issue fix #325
        // From android version 12+(R) Settings keys annotated with @hide are restricted to
        // system_server and system apps only. "always_on_vpn_app" is annotated with @hide.

        // For versions above 29(Q), there is vpnService.isAlwaysOn property to check
        // whether always-on is enabled.
        // For versions prior to 29 the check is made with Settings.Secure.
        // In our case, the always-on check is for all the vpn profiles. So using
        // vpnService?.isAlwaysOn will not be much helpful
        if (isAtleastQ()) {
            return vpnService?.isAlwaysOn == true
        }

        return try {
            val alwaysOn = Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
            context.packageName == alwaysOn
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "Failure while retrieving Settings.Secure value ${e.message}", e)
            false
        }
    }

    // This function is not supported from version 12 onwards.
    fun isOtherVpnHasAlwaysOn(context: Context): Boolean {
        return try {
            val alwaysOn = Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
            !TextUtils.isEmpty(alwaysOn) && context.packageName != alwaysOn
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "Failure while retrieving Settings.Secure value ${e.message}", e)
            false
        }
    }

    fun getIcon(ctx: Context, packageName: String, appName: String? = null): Drawable? {
        if (!isValidAppName(appName, packageName)) {
            return getDefaultIcon(ctx)
        }

        return try {
            ctx.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            // Not adding exception details in logs.
            Logger.e(
                LOG_TAG_FIREWALL,
                "Application Icon not available for package: $packageName" + e.message
            )
            getDefaultIcon(ctx)
        }
    }

    private fun isValidAppName(appName: String?, packageName: String): Boolean {
        return !isNonApp(packageName) && Constants.UNKNOWN_APP != appName
    }

    fun getDefaultIcon(context: Context): Drawable? {
        return AppCompatResources.getDrawable(context, R.drawable.default_app_icon)
    }

    fun delay(ms: Long, scope: LifecycleCoroutineScope, updateUi: () -> Unit) {
        scope.launch {
            kotlinx.coroutines.delay(ms)
            try {
                updateUi()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "Failure in delay function ${e.message}", e)
            }
        }
    }

    fun getPackageInfoForUid(ctx: Context, uid: Int): Array<out String>? {
        try {
            return ctx.packageManager.getPackagesForUid(uid)
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_FIREWALL, "Package Not Found: " + e.message)
        } catch (e: SecurityException) {
            Logger.w(LOG_TAG_FIREWALL, "Package Not Found: " + e.message)
        }
        return null
    }

    fun isAtleastN(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    fun isAtleastO(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    fun isAtleastR(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    fun isAtleastP(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    fun isAtleastQ(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun isAtleastS(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    fun isAtleastT(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun isAtleastU(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    fun isFdroidFlavour(): Boolean {
        return BuildConfig.FLAVOR_releaseChannel == FLAVOR_FDROID
    }

    fun isWebsiteFlavour(): Boolean {
        return BuildConfig.FLAVOR_releaseChannel == FLAVOR_WEBSITE
    }

    fun isPlayStoreFlavour(): Boolean {
        return BuildConfig.FLAVOR_releaseChannel == FLAVOR_PLAY
    }

    fun isHeadlessFlavour(): Boolean {
        return BuildConfig.FLAVOR_releaseType == FLAVOR_HEADLESS
    }

    fun getApplicationInfo(ctx: Context, packageName: String): ApplicationInfo? {
        return try {
            if (isAtleastT()) {
                ctx.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                ctx.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_FIREWALL, "no app info for package name: $packageName")
            null
        }
    }

    fun isUnspecifiedIp(serverIp: String): Boolean {
        return UNSPECIFIED_IP_IPV4 == serverIp || UNSPECIFIED_IP_IPV6 == serverIp
    }

    fun calculateTtl(ttl: Long): Long {
        val now = System.currentTimeMillis()

        // on negative ttl, cache dns record for a day
        if (ttl < 0) return now + TimeUnit.DAYS.toMillis(1L)

        return now + TimeUnit.SECONDS.toMillis((ttl + DnsLogTracker.DNS_TTL_GRACE_SEC))
    }

    fun deleteRecursive(fileOrDirectory: File) {
        try {
            if (fileOrDirectory.isDirectory) {
                fileOrDirectory.listFiles()?.forEach { child -> deleteRecursive(child) }
            }
            val isDeleted: Boolean =
                if (isAtleastO()) {
                    fileOrDirectory.deleteRecursively()
                } else {
                    fileOrDirectory.delete()
                }
            Logger.d(LOG_TAG_DOWNLOAD, "deleteRecursive File : ${fileOrDirectory.path}, $isDeleted")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "File delete exception: ${e.message}", e)
        }
    }

    fun localBlocklistFileDownloadPath(ctx: Context, which: String, timestamp: Long): String {
        return blocklistDownloadBasePath(ctx, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp) +
            File.separator +
            which
    }

    fun oldLocalBlocklistDownloadDir(ctx: Context, timestamp: Long): String {
        return ctx.filesDir.canonicalPath + File.separator + timestamp + File.separator
    }

    fun hasLocalBlocklists(ctx: Context, timestamp: Long): Boolean {
        val a =
            Constants.ONDEVICE_BLOCKLISTS_ADM.all {
                localBlocklistFile(ctx, it.filename, timestamp)?.exists() == true
            }
        return a
    }

    fun tempDownloadBasePath(ctx: Context, which: String, timestamp: Long): String {
        // instead of creating folder for actual timestamp, create for its negative value
        return blocklistCanonicalPath(ctx, which) + File.separator + (-1 * timestamp)
    }

    fun blocklistDownloadBasePath(ctx: Context, which: String, timestamp: Long): String {
        return blocklistCanonicalPath(ctx, which) + File.separator + timestamp
    }

    fun blocklistCanonicalPath(ctx: Context, which: String): String {
        return ctx.filesDir.canonicalPath + File.separator + which
    }

    private fun localBlocklistFile(ctx: Context, which: String, timestamp: Long): File? {
        return try {
            val localBlocklist = localBlocklistFileDownloadPath(ctx, which, timestamp)

            return File(localBlocklist)
        } catch (e: IOException) {
            Logger.e(LOG_TAG_VPN, "Could not fetch local blocklist: " + e.message, e)
            null
        }
    }

    fun hasRemoteBlocklists(ctx: Context, timestamp: Long): Boolean {
        val remoteDir =
            blocklistDir(ctx, REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp) ?: return false
        val remoteFile =
            blocklistFile(remoteDir.absolutePath, Constants.ONDEVICE_BLOCKLIST_FILE_TAG)
                ?: return false
        if (remoteFile.exists()) {
            return true
        }

        return false
    }

    fun blocklistDir(ctx: Context?, which: String, timestamp: Long): File? {
        if (ctx == null) return null
        return try {
            File(blocklistDownloadBasePath(ctx, which, timestamp))
        } catch (e: IOException) {
            Logger.e(LOG_TAG_VPN, "Could not fetch remote blocklist: " + e.message, e)
            null
        }
    }

    fun blocklistFile(dirPath: String, fileName: String): File? {
        return try {
            return File(dirPath + fileName)
        } catch (e: IOException) {
            Logger.e(LOG_TAG_VPN, "Could not fetch remote blocklist: " + e.message, e)
            null
        }
    }

    fun isNonApp(p: String): Boolean {
        return p.startsWith(NO_PACKAGE)
    }

    fun removeLeadingAndTrailingDots(str: String?): String {
        if (str.isNullOrBlank()) return ""

        // remove leading and trailing dots(.) from the given string
        // eg., (...adsd.asd.asa... will result in adsd.asd.asa)
        val s = CharMatcher.`is`('.').trimLeadingFrom(str)
        return CharMatcher.`is`('.').trimTrailingFrom(s)
    }

    // https://medium.com/androiddevelopers/all-about-pendingintents-748c8eb8619
    fun getActivityPendingIntent(
        context: Context,
        intent: Intent,
        flag: Int,
        mutable: Boolean
    ): PendingIntent {
        return if (isAtleastS()) {
            val sFlag = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getActivity(context, 0, intent, sFlag)
        } else {
            PendingIntent.getActivity(context, 0, intent, flag)
        }
    }

    fun getBroadcastPendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent,
        flag: Int,
        mutable: Boolean
    ): PendingIntent {
        return if (isAtleastS()) {
            val sFlag = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getBroadcast(context, requestCode, intent, sFlag)
        } else {
            PendingIntent.getBroadcast(context, requestCode, intent, flag)
        }
    }

    fun getRemoteBlocklistStamp(url: String): String {
        return try {
            // extract the path from the url string
            // eg., https://dns.google/dns-query will result in /dns-query
            val path = URI(url).path
            // remove the trailing and leading slashes from the path
            // eg., /dns-query will result in dns-query
            // earlier check of : will not work as now remote stamp can contain sec/rec
            return path.trimStart { it == '/' }.trimEnd { it == '/' }
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_DNS, "failure fetching stamp from Go ${e.message}", e)
            ""
        }
    }

    enum class PrivateDnsMode {
        NONE, // The setting is "Off" or "Opportunistic", and the DNS connection is not using
        // TLS.
        UPGRADED, // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
        STRICT // The setting is "Strict".
    }

    fun getPrivateDnsMode(context: Context): PrivateDnsMode {
        // https://github.com/celzero/rethink-app/issues/408
        if (!isAtleastQ()) {
            // Private DNS was introduced in P.
            return PrivateDnsMode.NONE
        }

        val linkProperties: LinkProperties =
            getLinkProperties(context) ?: return PrivateDnsMode.NONE
        if (linkProperties.privateDnsServerName != null) {
            return PrivateDnsMode.STRICT
        }
        return if (linkProperties.isPrivateDnsActive) {
            PrivateDnsMode.UPGRADED
        } else {
            PrivateDnsMode.NONE
        }
    }

    fun isPrivateDnsActive(context: Context): Boolean {
        return getPrivateDnsMode(context) != PrivateDnsMode.NONE
    }

    private fun getLinkProperties(context: Context): LinkProperties? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getLinkProperties(activeNetwork)
    }

    fun removeBeginningTrailingCommas(value: String): String {
        return value.removePrefix(",").dropLastWhile { it == ',' }
    }

    fun getDnsPort(port: Int): Int {
        if (port > 65535 || port <= 0) return 53
        return port
    }

    // generates a random 12-byte value, converts it to hexadecimal, and then
    // provides the hexadecimal value as a string
    fun getRandomString(length: Int): String {
        val random = ByteArray(length)
        SecureRandom().nextBytes(random)
        // formats each byte as a two-character hexadecimal string
        return random.joinToString("") { "%02x".format(it) }
    }

    fun humanReadableByteCount(bytes: Long, si: Boolean): String {
        val unit = if (si) 1000 else 1024
        if (bytes < unit) return "$bytes B"
        try {
            val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
            val pre = ("KMGTPE")[exp - 1] + if (si) "" else "i"
            val totalBytes = bytes / Math.pow(unit.toDouble(), exp.toDouble())
            return String.format("%.1f %sB", totalBytes, pre)
        } catch (e: NumberFormatException) {
            Logger.e(LOG_TAG_DOWNLOAD, "Number format exception: ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "err in humanReadableByteCount: ${e.message}", e)
        }
        return ""
    }

    fun calculateMd5(filePath: String): String {
        // HashingSink will update the md5sum with every write call and then call down
        // to blackholeSink(), ref: https://stackoverflow.com/a/61217039
        return File(filePath).source().buffer().use { source ->
            HashingSink.md5(blackholeSink()).use { sink ->
                source.readAll(sink)
                sink.hash.hex()
            }
        }
    }

    fun getTagValueFromJson(path: String, tag: String): String {
        var tagValue = ""
        try {
            // Read the JSON file
            val jsonContent = File(path).readText()

            // Parse JSON using JsonParser
            val jsonObject = JsonParser.parseString(jsonContent).asJsonObject

            // Extract the specific tag value
            if (jsonObject.has(tag)) {
                tagValue = jsonObject.get(tag).asString
                Logger.i(LOG_TAG_DOWNLOAD, "get tag value: $tagValue, for tag: $tag")
            } else {
                Logger.i(LOG_TAG_DOWNLOAD, "tag not found: $tag")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "err parsing the json file: ${e.message}", e)
        }
        return tagValue
    }

    fun isNetworkSame(n1: Network?, n2: Network?): Boolean {
        if (n1 == null || n2 == null) return false

        return n1.networkHandle == n2.networkHandle
    }
}
