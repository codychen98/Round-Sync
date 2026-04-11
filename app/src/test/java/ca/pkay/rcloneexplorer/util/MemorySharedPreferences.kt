package ca.pkay.rcloneexplorer.util

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] for unit tests (no Robolectric).
 */
class MemorySharedPreferences : SharedPreferences {

    private val map = mutableMapOf<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized
    override fun getAll(): MutableMap<String, *> = HashMap(map)

    @Synchronized
    override fun getString(key: String, defValue: String?): String? {
        if (!map.containsKey(key)) return defValue
        return map[key] as String?
    }

    @Synchronized
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        if (!map.containsKey(key)) {
            return defValues?.let { HashSet(it) }
        }
        @Suppress("UNCHECKED_CAST")
        val set = map[key] as Set<String>
        return HashSet(set)
    }

    @Synchronized
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    @Synchronized
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    @Synchronized
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    @Synchronized
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue

    @Synchronized
    override fun contains(key: String): Boolean = map.containsKey(key)

    @Synchronized
    override fun edit(): SharedPreferences.Editor = MemoryEditor(this)

    @Synchronized
    fun applyBatch(editor: MemoryEditor) {
        if (editor.clearAll) {
            map.clear()
            editor.clearAll = false
            editor.removed.clear()
            editor.pending.clear()
            return
        }
        for (k in editor.removed) {
            map.remove(k)
        }
        for ((k, v) in editor.pending) {
            if (v === MemoryEditor.REMOVE) {
                map.remove(k)
            } else {
                map[k] = v
            }
        }
        editor.removed.clear()
        editor.pending.clear()
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners.remove(listener)
    }

    class MemoryEditor(private val prefs: MemorySharedPreferences) : SharedPreferences.Editor {

        internal val pending = mutableMapOf<String, Any?>()
        internal val removed = mutableSetOf<String>()
        internal var clearAll: Boolean = false

        companion object {
            val REMOVE = Any()
        }

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            removed.remove(key)
            pending[key] = value ?: REMOVE
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            removed.remove(key)
            if (values == null) {
                pending[key] = REMOVE
            } else {
                pending[key] = HashSet(values)
            }
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            removed.remove(key)
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            removed.remove(key)
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            removed.remove(key)
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            removed.remove(key)
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending.remove(key)
            removed.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            pending.clear()
            removed.clear()
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            prefs.applyBatch(this)
            return true
        }

        override fun apply() {
            prefs.applyBatch(this)
        }
    }
}
