package cl.bovedawilson.app.ui.screens

/**
 * Rutas de navegación. No transportan contenido sensible: solo identificadores aleatorios
 * de ítem (`SECURITY.md` §1 prohíbe pasar secretos por argumentos de
 * navegación).
 */
const val ROUTE_CREATE_VAULT = "create-vault"
const val ROUTE_CLOUD_ACCESS = "cloud-access"
const val ROUTE_UNLOCK = "unlock"
const val ROUTE_ITEMS = "items"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_BACKUP = "backup"
const val ROUTE_ITEM_PATTERN = "item/{itemId}"

fun routeForItem(itemId: String): String = "item/$itemId"

fun routeForNewItem(): String = routeForItem(cl.bovedawilson.app.ui.editor.NEW_ITEM_ID)
