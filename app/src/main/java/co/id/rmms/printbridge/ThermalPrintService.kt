package co.id.rmms.printbridge
import co.id.rmms.printbridge.data.AppDatabase
import co.id.rmms.printbridge.data.PrinterPreferences
import co.id.rmms.printbridge.printer.TemplateProcessor
import co.id.rmms.printbridge.printer.ThermalPrinterEngine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThermalPrintService : Service() {
    companion object {
        const val CHANNEL_ID = "rmms_print_bridge_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_STANDBY = "ACTION_START_STANDBY"
        const val ACTION_PRINT_FROM_URI = "ACTION_PRINT_FROM_URI"
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
        const val EXTRA_PAYLOAD = "extra_payload"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val printQueue = Channel<PrintJob>(Channel.UNLIMITED)

    private lateinit var database: AppDatabase
    private lateinit var preferences: PrinterPreferences
    private lateinit var printerEngine: ThermalPrinterEngine

    data class PrintJob(val templateId: String, val rawPayload: String)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        preferences = PrinterPreferences(this)
        printerEngine = ThermalPrinterEngine(this)

        createNotificationChannel()
        startForegroundWithProperType("Standby menunggu perintah cetak")
        startQueueWorker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PRINT_FROM_URI -> {
                val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID) ?: ""
                val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: "{}"
                if (templateId.isNotEmpty()) {
                    printQueue.trySend(PrintJob(templateId, payload))
                }
            }
        }
        return START_STICKY
    }

    private fun startQueueWorker() {
        serviceScope.launch {
            for (job in printQueue) {
                processJob(job)
            }
        }
    }

    private suspend fun processJob(job: PrintJob) {
        val macAddress = preferences.printerMacFlow.first()
        if (macAddress.isBlank()) {
            showToast("Gagal cetak: Printer Bluetooth belum dipilih di RMMS Print Bridge")
            return
        }

        val template = database.templateDao().getTemplateById(job.templateId)
        if (template == null) {
            showToast("Gagal cetak: Template '${job.templateId}' tidak ditemukan")
            return
        }

        try {
            val jsonString = TemplateProcessor.decodePayloadToJson(job.rawPayload)
            val rawRendered = TemplateProcessor.renderTemplate(template.templateBody, jsonString)

            val formattedText = TemplateProcessor.formatAndWrapText(
                renderedText = rawRendered,
                maxCharsPerLine = template.charPerLine,
                alignColon = template.alignColon,
                colonBySection = template.colonBySection,
                indentWrappedText = template.indentWrappedText,
                justifyLabelValue = template.justifyLabelValue,
                showColonInLeftRight = template.showColonInLeftRight
            )

            val result = printerEngine.executePrint(macAddress, template, formattedText)
            result.onFailure { err ->
                showToast("Error Bluetooth: ${err.localizedMessage}")
            }
        } catch (e: Exception) {
            showToast("Error Format Data: ${e.localizedMessage}")
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startForegroundWithProperType(statusText: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RMMS Print Bridge Aktif")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Layanan Cetak Latar Belakang",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}