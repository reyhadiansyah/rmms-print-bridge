package co.id.rmms.printbridge.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "printer_settings")

class PrinterPreferences(private val context: Context) {
    companion object {
        val KEY_MAC_ADDRESS = stringPreferencesKey("printer_mac_address")
        val KEY_PRINTER_NAME = stringPreferencesKey("printer_name")
    }

    val printerMacFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAC_ADDRESS] ?: ""
    }

    val printerNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PRINTER_NAME] ?: "Belum ada printer dipilih"
    }

    suspend fun saveSelectedPrinter(name: String, macAddress: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PRINTER_NAME] = name
            prefs[KEY_MAC_ADDRESS] = macAddress
        }
    }
}