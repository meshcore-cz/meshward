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
    entities = [Message::class, Contact::class, Channel::class, DiscoveredContact::class, Reaction::class, Echo::class, MeshCoreHeard::class, MeshCorePath::class, NodeAnnouncement::class, MeshNetwork::class],
    version = 23,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun exploreDao(): ExploreDao

    companion object {
        // One open instance per database file — each identity (profile) has its own file, so the
        // app may hold several. Keyed by dbName.
        private val instances = HashMap<String, ChatDatabase>()

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

        // v16→v17: persist the latest announcement (Sidepath ANNOUNCE / MeshCore ADVERT) per node so
        // the profile's "Last announcement" survives a restart. Additive — new table.
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `node_announcements` (" +
                        "`nodeHex` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`pubKeyHex` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                        "`rawHex` TEXT NOT NULL, PRIMARY KEY(`nodeHex`, `source`))"
                )
            }
        }

        // v17→v18: add the mesh_networks table (user-added MeshCore Networks; built-in defaults are
        // bundled by sidepath-protocol from res/networks.json, not stored here). Additive — migrate in place.
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mesh_networks` (" +
                        "`code` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`freqMhz` REAL NOT NULL, `bandwidthKhz` REAL NOT NULL, " +
                        "`spreadingFactor` INTEGER NOT NULL, `codingRate` INTEGER NOT NULL, " +
                        "`analyzerUrls` TEXT NOT NULL, `mqttEndpoints` TEXT NOT NULL, " +
                        "`geoJson` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "PRIMARY KEY(`code`))"
                )
            }
        }

        // v18→v19: tier discovered MeshCore contacts to the bridge network they came through.
        // Additive `networkCode` column (blank for Sidepath-native and pre-existing rows).
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `discovered_contacts` ADD COLUMN `networkCode` TEXT NOT NULL DEFAULT ''")
            }
        }

        // v19→v20: cache extra per-node metadata fetched from a CoreScope analyzer as a JSON blob.
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `discovered_contacts` ADD COLUMN `analyzerData` TEXT NOT NULL DEFAULT ''")
            }
        }

        // v20→v21: record the bridged MeshCore network code on each message, so its chip shows the
        // network it actually crossed (blank for native Sidepath and pre-existing rows).
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `networkCode` TEXT NOT NULL DEFAULT ''")
            }
        }

        // v21→v22: persist the latest MeshCore returned PATH learned per contact.
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meshcore_paths` (" +
                        "`nodeHex` TEXT NOT NULL, `pubKeyHex` TEXT NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, `routeLabel` TEXT NOT NULL, " +
                        "`pathHex` TEXT NOT NULL, `pathHashSize` INTEGER NOT NULL, " +
                        "`hopCount` INTEGER NOT NULL, `extraType` INTEGER NOT NULL, " +
                        "`ackCrc` INTEGER NOT NULL, `packetHex` TEXT NOT NULL, " +
                        "`contentId` TEXT NOT NULL, PRIMARY KEY(`nodeHex`))"
                )
            }
        }

        // v22→v23: add indexes backing Paging 3 (messages page by peerHex+timestampMs; discovered
        // contacts order by lastAdvertisedMs). Index names must match Room's generated scheme so the
        // post-migration schema validation passes. Additive — migrate in place.
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_peerHex_timestampMs` ON `messages` (`peerHex`, `timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_discovered_contacts_lastAdvertisedMs` ON `discovered_contacts` (`lastAdvertisedMs`)")
            }
        }

        fun get(context: Context, dbName: String = "meshward.db"): ChatDatabase = synchronized(this) {
            instances[dbName] ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                dbName,
            ).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
                .fallbackToDestructiveMigration()
                .build().also { instances[dbName] = it }
        }
    }
}
