package cl.bovedawilson.data.local.db

import android.content.Context
import androidx.room.Room

/**
 * Único punto de construcción de [VaultDatabase]. Vive en `:data:local` para que Room no
 * se filtre al grafo de dependencias de los módulos superiores: `:data:sync` pide la base
 * de datos, no sabe cómo se arma.
 *
 * No se activa ninguna estrategia de migración destructiva (`docs/architecture.md` §7,
 * verificado por la prueba de higiene G-69): una migración ausente debe romper de forma
 * visible, nunca borrar la bóveda del usuario.
 */
object VaultDatabaseFactory {

    private const val DATABASE_NAME = "boveda-wilson.db"

    fun create(context: Context): VaultDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            VaultDatabase::class.java,
            DATABASE_NAME
        ).build()
}
