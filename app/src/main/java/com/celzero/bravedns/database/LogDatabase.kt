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
package com.celzero.bravedns.database

import Logger
import android.content.Context
import android.content.pm.PackageInfo
import android.database.Cursor
import android.database.sqlite.SQLiteException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.celzero.bravedns.util.Utilities

@Database(
    entities = [ConnectionTracker::class, DnsLog::class, RethinkLog::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LogDatabase : RoomDatabase() {

    companion object {
        const val LOGS_DATABASE_NAME = "rethink_logs.db"
        private const val PRAGMA = "pragma wal_checkpoint(full)"
        private const val TABLE_NAME_DNS_LOGS = "DnsLogs"
        // previous table name for dns logs
        private const val TABLE_NAME_PREVIOUS_DNS = "DNSLogs"
        private const val TABLE_NAME_CONN_TRACKER = "ConnectionTracker"
        private var rethinkDnsDbPath = ""
        var isFreshInstall = true

        // setJournalMode() is added as part of issue #344
        // modified the journal mode from TRUNCATE to AUTOMATIC.
        // The actual value will be TRUNCATE when the it is a low-RAM device.
        // Otherwise, WRITE_AHEAD_LOGGING will be used.
        // https://developer.android.com/reference/android/arch/persistence/room/RoomDatabase.JournalMode#automatic
        fun buildDatabase(context: Context): LogDatabase {
            rethinkDnsDbPath = context.getDatabasePath(AppDatabase.DATABASE_NAME).toString()
            isFreshInstall = Utilities.isFreshInstall(context)

            return Room.databaseBuilder(
                    context.applicationContext,
                    LogDatabase::class.java,
                    LOGS_DATABASE_NAME
                )
                .setJournalMode(JournalMode.AUTOMATIC)
                .addCallback(roomCallback)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .fallbackToDestructiveMigration() // recreate the database if no migration is found
                .build()
        }

        private val roomCallback: Callback =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    if (isFreshInstall) return
                    // need to call populateDatabase() only if the app is not a fresh install
                    // and the version is less than 6, as older versions had logs in the main db
                    if (db.version > 5) return
                    populateDatabase(db)
                }
            }

        private fun populateDatabase(db: SupportSQLiteDatabase) {
            try {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS 'ConnectionTracker' ('id' INTEGER NOT NULL,'appName' TEXT DEFAULT '' NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT DEFAULT ''  NOT NULL, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'blockedByRule' TEXT DEFAULT '' NOT NULL, 'flag' TEXT  DEFAULT '' NOT NULL, 'dnsQuery' TEXT DEFAULT '', 'timeStamp' INTEGER NOT NULL,PRIMARY KEY (id)  )"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS 'DnsLogs' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'queryStr' TEXT NOT NULL, 'time' INTEGER NOT NULL, 'flag' TEXT NOT NULL, 'resolver' TEXT NOT NULL, 'latency' INTEGER NOT NULL, 'typeName' TEXT NOT NULL, 'isBlocked' INTEGER NOT NULL, 'blockLists' LONGTEXT NOT NULL,  'serverIP' TEXT NOT NULL, 'relayIP' TEXT NOT NULL, 'responseTime' INTEGER NOT NULL, 'response' TEXT NOT NULL, 'status' TEXT NOT NULL,'dnsType' INTEGER NOT NULL, 'responseIps' TEXT NOT NULL) "
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dnslogs_querystr ON  DnsLogs(queryStr)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_connectiontracker_ipaddress ON  ConnectionTracker(ipAddress)"
                )

                // to avoid the exception, the transaction should be ended before the
                // "attach database" is called.
                // here mainDB is the database which is LogDatabase
                // attach the rethinkDB to the LogDatabase
                db.setTransactionSuccessful()
                db.endTransaction()
                // disable WAL option before attaching the database
                db.disableWriteAheadLogging()
                db.beginTransaction()
                db.execSQL("ATTACH DATABASE '$rethinkDnsDbPath' AS tempDb")
                // delete logs from main database
                db.execSQL("delete from main.$TABLE_NAME_DNS_LOGS")
                db.execSQL("delete from main.$TABLE_NAME_CONN_TRACKER")
                // no need to proceed if the table does not exist
                if (!tableExists(db, "tempDb.$TABLE_NAME_PREVIOUS_DNS")) {
                    db.execSQL("DETACH DATABASE tempDb")
                    db.enableWriteAheadLogging()
                    return
                }

                // insert Dns and network logs to the new database tables
                db.execSQL(
                    "INSERT INTO main.$TABLE_NAME_DNS_LOGS SELECT * FROM tempDb.$TABLE_NAME_PREVIOUS_DNS"
                )
                if (tableExists(db, "tempDb.$TABLE_NAME_CONN_TRACKER")) {
                    db.execSQL(
                        "INSERT INTO main.$TABLE_NAME_CONN_TRACKER SELECT * FROM tempDb.$TABLE_NAME_CONN_TRACKER"
                    )
                }
                db.enableWriteAheadLogging()
            } catch (ignored: Exception) {
                Logger.e(
                    "MIGRATION",
                    "error migrating from v1to2 on log db: ${ignored.message}",
                    ignored
                )
            }
        }

        private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
            var cursor: Cursor? = null
            return try {
                cursor = db.query("SELECT * FROM $table LIMIT 1")
                cursor.moveToFirst()
                // in the table if it exists, otherwise it will return -1
                cursor.getInt(0) > 0
            } catch (e: SQLiteException) {
                // return false if the table does not exist
                false
            } finally {
                // close the cursor
                cursor?.close()
            }
        }

        private val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE DnsLogs add column resolverId TEXT DEFAULT '' NOT NULL")
                }
            }

        private val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker add column blocklists TEXT DEFAULT '' NOT NULL"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_DnsLogs_queryStr ON DnsLogs(queryStr)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_DnsLogs_responseIps ON DnsLogs(responseIps)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_DnsLogs_isBlocked ON DnsLogs(isBlocked)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_DnsLogs_blockLists ON DnsLogs(blockLists)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_ConnectionTracker_ipAddress ON ConnectionTracker(ipAddress)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_ConnectionTracker_appName ON ConnectionTracker(appName)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_ConnectionTracker_dnsQuery ON ConnectionTracker(dnsQuery)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_ConnectionTracker_blockedByRule ON ConnectionTracker(blockedByRule)"
                    )
                }
            }

        private val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker add column connId TEXT DEFAULT '' NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker add column downloadBytes INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker add column uploadBytes INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker add column duration INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker add column synack INTEGER DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker add column message TEXT DEFAULT '' NOT NULL"
                    )
                }
            }

        private val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker ADD COLUMN proxyDetails TEXT DEFAULT '' NOT NULL"
                    )
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker ADD COLUMN connType TEXT DEFAULT '' NOT NULL"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS 'RethinkLog' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'appName' TEXT DEFAULT '' NOT NULL, 'uid' INTEGER NOT NULL, 'ipAddress' TEXT DEFAULT ''  NOT NULL, 'port' INTEGER NOT NULL, 'protocol' INTEGER NOT NULL,'isBlocked' INTEGER NOT NULL, 'proxyDetails' TEXT DEFAULT '' NOT NULL, 'flag' TEXT  DEFAULT '' NOT NULL, 'dnsQuery' TEXT DEFAULT '', 'timeStamp' INTEGER NOT NULL,  'connId' TEXT DEFAULT '' NOT NULL, 'downloadBytes' INTEGER DEFAULT 0 NOT NULL, 'uploadBytes' INTEGER DEFAULT 0 NOT NULL, 'duration' INTEGER DEFAULT 0 NOT NULL, 'synack' INTEGER DEFAULT 0 NOT NULL, 'message' TEXT DEFAULT '' NOT NULL, 'connType' TEXT DEFAULT '' NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_RethinkLog_ipAddress ON RethinkLog(ipAddress)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_RethinkLog_appName ON RethinkLog(appName)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_RethinkLog_dnsQuery ON RethinkLog(dnsQuery)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_DnsLogs_time ON DnsLogs(time)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_ConnectionTracker_isBlocked_timeStamp ON ConnectionTracker(isBlocked, timeStamp)"
                    )
                    db.execSQL(
                        "ALTER TABLE ConnectionTracker ADD COLUMN usrId INT DEFAULT 0 NOT NULL"
                    )
                    db.execSQL(
                        "CREATE TABLE 'AlertRegistry' ('id' INTEGER NOT NULL, 'alertTitle' TEXT NOT NULL, 'alertType' TEXT NOT NULL, 'alertCount' INTEGER NOT NULL, 'alertTime' INTEGER NOT NULL, 'alertMessage' TEXT NOT NULL, 'alertCategory' TEXT NOT NULL, 'alertSeverity' TEXT NOT NULL, 'alertActions' TEXT NOT NULL, 'alertStatus' TEXT NOT NULL, 'alertSolution' TEXT NOT NULL, 'isRead' INTEGER NOT NULL, isDeleted INTEGER NOT NULL, isCustom INTEGER NOT NULL, isNotified INTEGER NOT NULL, PRIMARY KEY (id))"
                    )
                    db.execSQL("ALTER TABLE DnsLogs ADD COLUMN msg TEXT DEFAULT '' NOT NULL")
                }
            }

        private val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // add a new column upstreamBlock to DNS log table with default as false
                    db.execSQL(
                        "ALTER TABLE DnsLogs ADD COLUMN upstreamBlock INTEGER DEFAULT 0 NOT NULL"
                    )
                }
            }
    }

    fun checkPoint() {
        logsDao().checkpoint(SimpleSQLiteQuery(PRAGMA))
        logsDao().vacuum(SimpleSQLiteQuery("VACUUM"))
    }

    abstract fun connectionTrackerDAO(): ConnectionTrackerDAO

    abstract fun rethinkConnectionLogDAO(): RethinkLogDao

    abstract fun dnsLogDAO(): DnsLogDAO

    abstract fun logsDao(): LogDatabaseRawQueryDao

    fun connectionTrackerRepository() = ConnectionTrackerRepository(connectionTrackerDAO())

    fun rethinkConnectionLogRepository() = RethinkLogRepository(rethinkConnectionLogDAO())

    fun dnsLogRepository() = DnsLogRepository(dnsLogDAO())
}
