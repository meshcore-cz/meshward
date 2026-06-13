package cz.meshcore.meshward.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v3: NodeIDs widened 8→10 bytes (protocol v3), so old peer/contact ids are stale —
// destructive migration wipes the v2 store. See docs/PROTOCOL.md migration §17.
@Database(
    entities = [Message::class, Contact::class, Channel::class, DiscoveredContact::class, Reaction::class, Echo::class, MeshCoreHeard::class],
    version = 16,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        // v6→v7: add MeshCore carrier columns to messages. Additive, so migrate in place
        // (preserve joined channels / contacts / history) rather than wiping the store.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCoreType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCoreRoute TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCoreHops INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCorePacketId TEXT NOT NULL DEFAULT ''")
            }
        }

        // v7→v8: keep discovered_contacts.lastAdvertisedMs as local receipt time and store the
        // MeshCore node's own advertised timestamp separately.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE discovered_contacts ADD COLUMN nodeAdvertisedMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v8→v9: mark contacts that came from a bridged MeshCore node, and record which Sidepath
        // node bridged a channel message. Both additive — migrate in place.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN isMeshCore INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN bridgeHex TEXT NOT NULL DEFAULT ''")
            }
        }

        // v9→v10: add the reactions table (emoji reactions on messages). Additive.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reactions` (" +
                        "`messageId` TEXT NOT NULL, `authorHex` TEXT NOT NULL, " +
                        "`emoji` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`messageId`, `authorHex`))"
                )
            }
        }

        // v10→v11: record that a channel message was relayed onto MeshCore by a gateway
        // (ACK_BRIDGED). Additive — migrate in place.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN bridgedToMeshCore INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN bridgedByHex TEXT NOT NULL DEFAULT ''")
            }
        }

        // v11→v12: persist echoes of our own messages + the outgoing packet bytes, so the echo
        // count / delivery proof / packet details survive an app restart. Additive.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN packetHex TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `echoes` (" +
                        "`messageId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                        "`rssi` INTEGER NOT NULL, `forwarderHex` TEXT NOT NULL, " +
                        "`viaMeshCore` INTEGER NOT NULL, `packetHex` TEXT NOT NULL, " +
                        "PRIMARY KEY(`messageId`, `timestampMs`))"
                )
            }
        }

        // v12→v13: persist the inner MeshCore packet bytes on bridged messages, so the message's
        // "Examine" / MeshCore packet details survive a restart (not just while it's in the Rx Log).
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN meshCorePacketHex TEXT NOT NULL DEFAULT ''")
            }
        }

        // v13→v14: persist a received message's link RSSI and, for a delivered DM, the raw ACK
        // datagram + its receipt time (so the round-trip delay and ACK packet detail survive a
        // restart). Additive — migrate in place.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN rssi INTEGER NOT NULL DEFAULT ${Int.MIN_VALUE}")
                db.execSQL("ALTER TABLE messages ADD COLUMN ackPacketHex TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN ackTimestampMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v14→v15: persist every distinct-path reception ("heard") of a bridged MeshCore channel
        // message, so the full set of MeshCore paths survives a restart. Additive — new table.
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meshcore_heards` (" +
                        "`messageId` TEXT NOT NULL, `contentId` TEXT NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, `rssi` INTEGER NOT NULL, " +
                        "`forwarderHex` TEXT NOT NULL, `hopCount` INTEGER NOT NULL, " +
                        "`pathHashSize` INTEGER NOT NULL, `routeLabel` TEXT NOT NULL, " +
                        "`hopsHex` TEXT NOT NULL, `packetHex` TEXT NOT NULL, " +
                        "`carrierHex` TEXT NOT NULL, PRIMARY KEY(`messageId`, `contentId`))"
                )
            }
        }

        // v15→v16: distinguish a manually-set contact name from a MeshCore advert-seeded one, so
        // later adverts can refresh the seeded name without overwriting a user's Rename. Additive.
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN nameIsCustom INTEGER NOT NULL DEFAULT 0")
                // Existing non-blank names predate the advert-refresh feature; treat them as pinned
                // so we don't suddenly overwrite a name the user may have chosen.
                db.execSQL("UPDATE contacts SET nameIsCustom = 1 WHERE localName != ''")
            }
        }

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "meshward.db",
            ).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
