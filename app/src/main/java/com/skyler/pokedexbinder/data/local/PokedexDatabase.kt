package com.skyler.pokedexbinder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MainBinderEntry::class,
        SecondaryBinderEntry::class,
        ConnectingArtGroup::class,
        ConnectingArtSlot::class,
        PersonalCollectionCache::class,
        PersonalCollectionEntry::class,
        UnownBinderEntry::class
    ],
    version = 9,
    exportSchema = false
)
abstract class PokedexDatabase : RoomDatabase() {
    abstract fun mainBinderDao(): MainBinderDao
    abstract fun secondaryBinderDao(): SecondaryBinderDao
    abstract fun connectingArtDao(): ConnectingArtDao
    abstract fun personalCollectionDao(): PersonalCollectionDao
    abstract fun unownBinderDao(): UnownBinderDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE secondary_binder ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE secondary_binder SET position = id")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE main_binder ADD COLUMN assignedCardName TEXT")
                db.execSQL("ALTER TABLE main_binder ADD COLUMN assignedCardSetName TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // connecting_art_group
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `connecting_art_group` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`name` TEXT NOT NULL, " +
                        "`rows` INTEGER NOT NULL, " +
                        "`cols` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL)"
                )
                // connecting_art_slot (FK -> connecting_art_group.id, cascade delete)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `connecting_art_slot` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`slotIndex` INTEGER NOT NULL, " +
                        "`cardId` TEXT, " +
                        "`cardName` TEXT, " +
                        "`cardImageUrl` TEXT, " +
                        "`owned` INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(`groupId`) REFERENCES `connecting_art_group`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_connecting_art_slot_groupId` " +
                        "ON `connecting_art_slot` (`groupId`)"
                )
                // personal_collection_cache
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personal_collection_cache` (" +
                        "`cardId` TEXT NOT NULL PRIMARY KEY, " +
                        "`pokemonKey` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`imageUrl` TEXT NOT NULL, " +
                        "`setName` TEXT NOT NULL, " +
                        "`releaseDate` TEXT NOT NULL)"
                )
                // personal_collection_entry
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personal_collection_entry` (" +
                        "`cardId` TEXT NOT NULL PRIMARY KEY, " +
                        "`owned` INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `unown_binder` (" +
                        "`letterId` TEXT NOT NULL PRIMARY KEY, " +
                        "`position` INTEGER NOT NULL, " +
                        "`assignedCardId` TEXT, " +
                        "`assignedCardImageUrl` TEXT, " +
                        "`assignedCardName` TEXT, " +
                        "`assignedCardSetName` TEXT)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE main_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
                db.execSQL("ALTER TABLE main_binder ADD COLUMN remarks TEXT")
                db.execSQL("ALTER TABLE main_binder ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE unown_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
                db.execSQL("ALTER TABLE unown_binder ADD COLUMN remarks TEXT")
                db.execSQL("ALTER TABLE unown_binder ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE connecting_art_slot ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
                db.execSQL("ALTER TABLE personal_collection_entry ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
                db.execSQL("ALTER TABLE secondary_binder ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
            }
        }
    }
}
