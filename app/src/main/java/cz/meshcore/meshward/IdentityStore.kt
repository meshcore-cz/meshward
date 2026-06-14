package cz.meshcore.meshward

import android.content.Context
import cz.meshcore.sidepath.protocol.Sidepath
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** One identity (profile): its own keypair seed and its own Room database file. */
data class IdentityRef(
    val id: String,
    val seedHex: String,   // may be "" for the migrated legacy identity until resolved at first run
    val dbName: String,
    val createdAt: Long,
    val lastActiveMs: Long = 0L,   // wall-clock of the last time this identity was the active one (0 = never / not yet)
) {
    /** True for the migrated original identity (reuses the legacy database + pre-identity settings). */
    val isLegacy: Boolean get() = dbName == LEGACY_DB_NAME
}

/** The legacy single-identity database file; reused as identity #0 so existing data is preserved. */
private const val LEGACY_DB_NAME = "meshward.db"

/**
 * Synchronous registry of identities, backed by SharedPreferences so it can be read at
 * [ChatViewModel] construction (before the Room DB is opened). Holds the identity list and the
 * active id. Per-identity *settings* live (namespaced) in the app's DataStore; per-identity *data*
 * lives in each identity's own Room file ([IdentityRef.dbName]).
 *
 * The seed of a brand-new identity is generated here; the migrated legacy identity's seed is left
 * blank and resolved later from the legacy DataStore seed (see [ChatViewModel.initializeService]).
 */
class IdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("meshward_identities", Context.MODE_PRIVATE)

    /** All identities, in creation order. Bootstraps the legacy identity #0 on first ever access. */
    fun all(): List<IdentityRef> {
        ensureBootstrapped()
        return parse(prefs.getString(KEY_IDS, null))
    }

    /** The currently active identity (always present — bootstraps if needed). */
    fun active(): IdentityRef {
        ensureBootstrapped()
        val list = parse(prefs.getString(KEY_IDS, null))
        val activeId = prefs.getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    /**
     * Marks [id] active. Stamps the identity we're leaving with the current time as its last-active
     * moment, so each non-active identity can show how long ago it was last in use. No-op if unknown.
     */
    fun setActive(id: String) {
        val list = all()
        if (list.none { it.id == id }) return
        val prev = prefs.getString(KEY_ACTIVE, null)
        if (prev != null && prev != id) {
            write(list.map { if (it.id == prev) it.copy(lastActiveMs = System.currentTimeMillis()) else it })
        }
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Creates a new identity with its own fresh DB file; [seedHex] is generated when null. */
    fun create(seedHex: String? = null): IdentityRef {
        ensureBootstrapped()
        val id = UUID.randomUUID().toString().substring(0, 8)
        val ref = IdentityRef(
            id = id,
            seedHex = seedHex ?: generateSeed(),
            dbName = "meshward_$id.db",
            createdAt = System.currentTimeMillis(),
        )
        write(all() + ref)
        return ref
    }

    /** Resolves the legacy identity's seed once it's known (from the legacy DataStore seed). */
    fun setSeed(id: String, seedHex: String) {
        write(all().map { if (it.id == id) it.copy(seedHex = seedHex) else it })
    }

    /** Removes an identity and deletes its database file. Refuses to remove the active or last one. */
    fun delete(context: Context, id: String) {
        val list = all()
        if (list.size <= 1 || prefs.getString(KEY_ACTIVE, null) == id) return
        val ref = list.firstOrNull { it.id == id } ?: return
        context.applicationContext.deleteDatabase(ref.dbName)
        write(list.filterNot { it.id == id })
    }

    private fun ensureBootstrapped() {
        if (prefs.contains(KEY_IDS)) return
        // First run on a build with identities: adopt the existing single identity as #0. Its seed is
        // resolved lazily from the legacy DataStore seed, so keep it blank and reuse meshward.db.
        val id = UUID.randomUUID().toString().substring(0, 8)
        val first = IdentityRef(id = id, seedHex = "", dbName = LEGACY_DB_NAME, createdAt = System.currentTimeMillis())
        write(listOf(first))
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun write(list: List<IdentityRef>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("seed", r.seedHex)
                    .put("db", r.dbName)
                    .put("created", r.createdAt)
                    .put("lastActive", r.lastActiveMs),
            )
        }
        prefs.edit().putString(KEY_IDS, arr.toString()).apply()
    }

    private fun parse(json: String?): List<IdentityRef> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                IdentityRef(
                    id = o.getString("id"),
                    seedHex = o.optString("seed", ""),
                    dbName = o.optString("db", LEGACY_DB_NAME),
                    createdAt = o.optLong("created", 0L),
                    lastActiveMs = o.optLong("lastActive", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun generateSeed(): String =
        ByteArray(Sidepath.SEED_BYTES).also { SecureRandom().nextBytes(it) }.toHex()

    private companion object {
        const val KEY_IDS = "ids"
        const val KEY_ACTIVE = "active"
    }
}
