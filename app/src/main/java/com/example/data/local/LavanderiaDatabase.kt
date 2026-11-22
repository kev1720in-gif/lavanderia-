package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.ComparacionDao
import com.example.data.local.dao.ConfiguracionDao
import com.example.data.local.dao.CorreccionOcrDao
import com.example.data.local.dao.EmpresaDao
import com.example.data.local.dao.MarcacionDao
import com.example.data.local.dao.RegistroRecepcionDao
import com.example.data.local.dao.SesionDao
import com.example.data.local.dao.TrabajadorDao
import com.example.data.local.dao.TurnoDao
import com.example.data.local.entities.ComparacionEntity
import com.example.data.local.entities.ConfiguracionEntity
import com.example.data.local.entities.CorreccionOcrEntity
import com.example.data.local.entities.EmpresaEntity
import com.example.data.local.entities.MarcacionEntity
import com.example.data.local.entities.RegistroRecepcionEntity
import com.example.data.local.entities.SesionEntity
import com.example.data.local.entities.TrabajadorEntity
import com.example.data.local.entities.TurnoEntity

@Database(
    entities = [
        TurnoEntity::class,
        EmpresaEntity::class,
        TrabajadorEntity::class,
        SesionEntity::class,
        MarcacionEntity::class,
        RegistroRecepcionEntity::class,
        ComparacionEntity::class,
        ConfiguracionEntity::class,
        CorreccionOcrEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class LavanderiaDatabase : RoomDatabase() {
    abstract fun turnoDao(): TurnoDao
    abstract fun empresaDao(): EmpresaDao
    abstract fun trabajadorDao(): TrabajadorDao
    abstract fun sesionDao(): SesionDao
    abstract fun marcacionDao(): MarcacionDao
    abstract fun registroRecepcionDao(): RegistroRecepcionDao
    abstract fun comparacionDao(): ComparacionDao
    abstract fun configuracionDao(): ConfiguracionDao
    abstract fun correccionOcrDao(): CorreccionOcrDao

    companion object {
        @Volatile
        private var INSTANCE: LavanderiaDatabase? = null

        /**
         * Migración aditiva v1 -> v2:
         * Crea exclusivamente la tabla auxiliar `correcciones_ocr` con sus índices y FK ON DELETE CASCADE.
         * Conserva intactas las 8 tablas existentes (incluidos 657 trabajadores de Turno B y registros de Turno A).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `correcciones_ocr` (
                        `id` TEXT NOT NULL,
                        `turnoId` TEXT NOT NULL,
                        `empresaId` INTEGER NOT NULL,
                        `textoOcrNormalizado` TEXT NOT NULL,
                        `textoOcrOriginal` TEXT NOT NULL,
                        `trabajadorId` TEXT NOT NULL,
                        `vecesUsada` INTEGER NOT NULL,
                        `creadoEn` INTEGER NOT NULL,
                        `actualizadoEn` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`turnoId`) REFERENCES `turnos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`empresaId`) REFERENCES `empresas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`trabajadorId`) REFERENCES `trabajadores`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_correcciones_ocr_turnoId_empresaId_textoOcrNormalizado` " +
                        "ON `correcciones_ocr` (`turnoId`, `empresaId`, `textoOcrNormalizado`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correcciones_ocr_trabajadorId` " +
                        "ON `correcciones_ocr` (`trabajadorId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correcciones_ocr_turnoId_empresaId` " +
                        "ON `correcciones_ocr` (`turnoId`, `empresaId`)"
                )
            }
        }

        /**
         * Mantiene persistencia estricta de la base de datos existente ("lavanderia_control.db").
         * PROHIBIDO utilizar fallbackToDestructiveMigration() o recrear tablas con datos reales.
         */
        fun getInstance(context: Context): LavanderiaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LavanderiaDatabase::class.java,
                    "lavanderia_control.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
