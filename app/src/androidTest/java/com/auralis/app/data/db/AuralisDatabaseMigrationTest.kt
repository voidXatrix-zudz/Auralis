package com.auralis.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test that opens every historical schema (1..10), migrates it up to the current
 * version with the real [AuralisDatabase] migrations and validates the result against the exported
 * schema. Every table that exists at the end is then queried, so a migration that forgets a table,
 * index, column or foreign key fails the build instead of crashing a user's launch.
 *
 * Run on a device/emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AuralisDatabaseMigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuralisDatabase::class.java,
    )

    @Test
    fun migrateEveryVersionToLatestAndQueryEveryTable() {
        for (start in 1 until LATEST) {
            val name = "auralis-migration-$start"

            helper.createDatabase(name, start).apply {
                // `sources` exists from version 1, so a row here proves user data survives the hops.
                execSQL(
                    "INSERT INTO `sources` (`id`, `kind`, `name`, `treeUri`, `addedAt`, `lastScanAt`) " +
                        "VALUES ('src', 'TREE', 'Music', NULL, 0, NULL)",
                )
                close()
            }

            val database = helper.runMigrationsAndValidate(name, LATEST, true, *MIGRATIONS)
            try {
                TABLES.forEach { table ->
                    database.query("SELECT * FROM `$table` LIMIT 0").use { it.moveToFirst() }
                }
                database.query("SELECT COUNT(*) FROM `sources`").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("seeded source row should survive migration from v$start", 1, cursor.getInt(0))
                }
            } finally {
                database.close()
            }
        }
    }

    private companion object {
        const val LATEST = 12

        val MIGRATIONS = arrayOf(
            AuralisDatabase.MIGRATION_1_2,
            AuralisDatabase.MIGRATION_2_3,
            AuralisDatabase.MIGRATION_3_4,
            AuralisDatabase.MIGRATION_4_5,
            AuralisDatabase.MIGRATION_5_6,
            AuralisDatabase.MIGRATION_6_7,
            AuralisDatabase.MIGRATION_7_8,
            AuralisDatabase.MIGRATION_8_9,
            AuralisDatabase.MIGRATION_9_10,
            AuralisDatabase.MIGRATION_10_11,
            AuralisDatabase.MIGRATION_11_12,
        )

        /** Every table the v11 schema defines; each must be present and queryable after migration. */
        val TABLES = listOf(
            "sources",
            "tracks",
            "lyrics",
            "playlists",
            "playlist_entries",
            "favorites",
            "play_history",
            "search_history",
            "queue_items",
            "play_events",
            "youtube_likes",
            "youtube_recents",
            "downloads",
            "playlist_yt_entries",
            "spotify_imports",
            "spotify_import_items",
            "yt_match_cache",
            "youtube_sync_ops",
            "provider_cache",
            "artist_images",
            "youtube_cache",
        )
    }
}
