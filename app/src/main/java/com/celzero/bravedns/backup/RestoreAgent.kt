/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.backup

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_RESTORE_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.METADATA_FILENAME
import com.celzero.bravedns.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.VERSION
import com.celzero.bravedns.backup.BackupHelper.Companion.deleteResidue
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.stopVpn
import com.celzero.bravedns.backup.BackupHelper.Companion.unzip
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.LogDatabase
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream

class RestoreAgent(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val logDatabase by inject<LogDatabase>()
    private val appDatabase by inject<AppDatabase>()
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val TAG = "RestoreAgent"
    }

    override suspend fun doWork(): Result {
        val restoreUri = Uri.parse(inputData.getString(DATA_BUILDER_RESTORE_URI))
        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin restore process with file uri: $restoreUri")
        val result = startRestore(restoreUri)

        Logger.i(LOG_TAG_BACKUP_RESTORE, "completed restore process, is successful? $result")
        return if (result) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private fun startRestore(importUri: Uri): Boolean {
        var inputStream: InputStream? = null
        stopVpn(context)
        try {
            val tempDir = getTempDir(context)
            inputStream = context.contentResolver.openInputStream(importUri)

            Logger.d(LOG_TAG_BACKUP_RESTORE, "restore process, temp file dir: ${tempDir.path}")
            // unzip the backup files to tempDir
            if (!unzip(inputStream, tempDir.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to unzip the uri to temp dir $importUri, ${tempDir.path}, return failure"
                )
                return false
            } else {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "restore process, unzipped the files to temp dir")
                // proceed
            }

            if (!validateMetadata(tempDir.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "invalid meta-data or metadata not found. maybe earlier version backup"
                )
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "metadata file validation complete")
                // no-op; proceed
            }

            // copy SharedPreferences file to its directory,
            // if shared pref copy is succeeds then proceed to database restore else
            // return failed
            if (!restoreSharedPreferencesFromFile(tempDir.path)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore shared pref, return failure")
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "shared pref restored to the temp dir")
                // proceed
            }

            // Copy new database file into its final directory
            if (!restoreDatabaseFile(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore database, return failure")
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "database restored to the temp dir")
                // proceed
            }

            // open log database if its not open
            handleDatabaseInit()

            // update app version after the restore process
            updateLatestVersion()

            // clear WireGuard related entries from database
            wireGuardCleanup()

            return true
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_BACKUP_RESTORE,
                "exception during restore process, reason? ${e.message}",
                e
            )
            return false
        } finally {
            inputStream?.close()
        }
    }

    private fun handleDatabaseInit() {
        // get writable database for logs
        if (!logDatabase.isOpen) {
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "log database is not open, perform writableDatabase operation"
            )
            logDatabase.openHelper.writableDatabase
        } else {
            // no-op
        }

        // get writable database for app
        if (!appDatabase.isOpen) {
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "app database is not open, perform writableDatabase operation"
            )
            appDatabase.openHelper.writableDatabase
        } else {
            // no-op
        }
    }

    // Restore database file stored at tempDir/nameOfFileToRestore.
    private fun restoreDatabaseFile(tempDir: File): Boolean {
        checkPoint()

        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin restore database to temp dir: ${tempDir.path}")

        val files = tempDir.listFiles()
        if (files == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "files to restore is empty, path: ${tempDir.path}")
            return false
        }

        Logger.d(
            LOG_TAG_BACKUP_RESTORE,
            "List of files in backup folder: ${files.size}, path: ${tempDir.path}"
        )
        for (file in files) {
            val currentDbFile = File(context.getDatabasePath(file.name).path)
            Logger.d(
                LOG_TAG_BACKUP_RESTORE,
                "db file: ${file.name} backed up from ${file.path} to ${currentDbFile.path}"
            )

            if (
                !file.name.contains(AppDatabase.DATABASE_NAME) &&
                    !file.name.contains(LogDatabase.LOGS_DATABASE_NAME)
            ) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "restore process, file name is not db, file name: ${file.name}"
                )
                continue
            }
            if (!Utilities.copy(file.path, currentDbFile.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "restore process, failure copying database file: ${file.path} to ${currentDbFile.path}"
                )
                return false
            }
        }

        deleteResidue(tempDir)
        return true
    }

    private fun updateLatestVersion() {
        if (isNewVersion()) {
            persistentState.appVersion = getLatestVersion()
            Logger.i(LOG_TAG_BACKUP_RESTORE, "app version updated to ${persistentState.appVersion}")
        } else {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "no need to update app version")
        }
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(context.packageManager, context.packageName)
        return pInfo?.versionCode ?: 0
    }

    private fun checkPoint() {
        Logger.i(LOG_TAG_BACKUP_RESTORE, "database checkpoint() during restore process")
        appDatabase.checkPoint()
        logDatabase.checkPoint()
        return
    }

    private fun validateMetadata(tempDirectory: String?): Boolean {
        // TODO: revisit this after v055 release
        if (isMetadataCompatible(tempDirectory)) {
            return true
        } else {
            // proceed with META_DATA_FILE validation
        }

        val file = File(tempDirectory, METADATA_FILENAME)
        var stream: InputStream? = null
        return try {
            stream = file.inputStream()
            val metadata = stream.bufferedReader().use { it.readText() }
            isVersionSupported(metadata)
        } catch (ignored: Exception) {
            Logger.e(
                LOG_TAG_BACKUP_RESTORE,
                "error while restoring metadata, reason? ${ignored.message}",
                ignored
            )
            false
        } finally {
            try {
                stream?.close()
            } catch (ignored: IOException) {
                Logger.e(
                    LOG_TAG_BACKUP_RESTORE,
                    "error while restoring metadata, reason? ${ignored.message}",
                    ignored
                )
            }
        }
    }

    private fun wireGuardCleanup() {
        if (appConfig.isWireGuardEnabled()) {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "wireGuard is enabled, reset the wireguard entries")
            appConfig.removeAllProxies()
        }
        // cleaning up the wireguard entries are handled in RefreshDatabase
    }

    private fun isMetadataCompatible(tempDirectory: String?): Boolean {

        val minVersionSupported = 24

        val input: ObjectInputStream?
        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        try {
            input = ObjectInputStream(FileInputStream(prefsBackupFile))

            val pref: Map<String, *> = input.readObject() as Map<String, *>

            for (e in pref.entries) {
                val v: Any? = e.value
                val key: String = e.key

                if (key == PersistentState.APP_VERSION) {
                    val appVersion = v as Int
                    if (appVersion >= minVersionSupported) {
                        Logger.w(
                            LOG_TAG_BACKUP_RESTORE,
                            "app version is less than minAppVersion, proceed with restore"
                        )
                        return true
                    } else {
                        // no-op
                    }
                } else {
                    // no-op
                }
            }
            return false
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_BACKUP_RESTORE,
                "exception while restoring shared pref, reason? ${e.message}",
                e
            )
            return false
        }
    }

    private fun isVersionSupported(metadata: String): Boolean {
        try {
            val minVersionSupported = 24

            if (!metadata.contains(VERSION)) return false

            val versionDetails = metadata.split("|")
            if (versionDetails[0].isEmpty()) return false

            val version = versionDetails[0].split(":")[1].toIntOrNull() ?: 0

            // backup version should be equal to minVersionSupported (prior to that version
            // there is only one database), so do not consider the backups prior to that
            return version >= minVersionSupported && persistentState.appVersion >= version
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_BACKUP_RESTORE,
                "error while reading metadata, reason? ${e.message}",
                e
            )
            return false
        }
    }

    private fun restoreSharedPreferencesFromFile(tempDirectory: String?): Boolean {
        var input: ObjectInputStream? = null
        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        val currentSharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context)

        Logger.d(LOG_TAG_BACKUP_RESTORE, "shared pref file path: ${prefsBackupFile.path}")
        try {
            input = ObjectInputStream(FileInputStream(prefsBackupFile))
            val prefsEditor = currentSharedPreferences.edit()
            prefsEditor.clear()
            val pref: Map<String, *> = input.readObject() as Map<String, *>

            for (e in pref.entries) {
                val v: Any? = e.value
                val key: String = e.key

                if (v is Boolean) prefsEditor.putBoolean(key, (v as Boolean?)!!)
                else if (v is Float) prefsEditor.putFloat(key, (v as Float?)!!)
                else if (v is Int) prefsEditor.putInt(key, (v as Int?)!!)
                else if (v is Long) prefsEditor.putLong(key, (v as Long?)!!)
                else if (v is String) prefsEditor.putString(key, v as String?)
            }
            prefsEditor.apply()
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "completed restore of shared pref values, ${pref.entries}"
            )
            return true
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_BACKUP_RESTORE,
                "exception while restoring shared pref, reason? ${e.message}",
                e
            )
            return false
        } finally {
            deleteResidue(prefsBackupFile)
            try {
                input?.close()
            } catch (e: IOException) {
                // no-op
            }
        }
    }
}
