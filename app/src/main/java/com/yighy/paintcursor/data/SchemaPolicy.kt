package com.yighy.paintcursor.data

/**
 * Decides whether the database is still allowed to throw itself away when it meets a schema
 * it has no migration for.
 *
 * While the app is pre-1.0 and only being tested, wiping is a fair trade for not writing a
 * migration per experiment. Once 1.0 ships, other people's work is in that file: a missing
 * migration has to fail loudly at startup instead of quietly deleting their projects.
 *
 * Enforced rather than documented, so shipping 1.0 removes the fallback on its own and nobody
 * has to remember to.
 */
object SchemaPolicy {

    /** From this major version onward, every schema change needs a real Migration. */
    const val MIGRATIONS_REQUIRED_FROM_MAJOR = 1

    /**
     * Anything unparseable counts as "past 1.0": if the version can't be read, the safe
     * assumption is the one that protects data rather than the one that discards it.
     */
    fun allowsDestructiveFallback(versionName: String?): Boolean {
        val major = versionName?.trim()?.substringBefore('.')?.toIntOrNull() ?: return false
        return major < MIGRATIONS_REQUIRED_FROM_MAJOR
    }
}
