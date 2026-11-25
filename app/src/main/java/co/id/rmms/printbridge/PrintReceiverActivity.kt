package co.id.rmms.printbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat

class PrintReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingUri(intent?.data)
        finish()
    }

    private fun handleIncomingUri(uri: Uri?) {
        if (uri == null || uri.scheme != "rmmsprintbridge") return

        val templateId = uri.getQueryParameter("template_id") ?: return
        val payload = uri.getQueryParameter("payload") ?: "{}"

        val serviceIntent = Intent(this, ThermalPrintService::class.java).apply {
            action = ThermalPrintService.ACTION_PRINT_FROM_URI
            putExtra(ThermalPrintService.EXTRA_TEMPLATE_ID, templateId)
            putExtra(ThermalPrintService.EXTRA_PAYLOAD, payload)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}