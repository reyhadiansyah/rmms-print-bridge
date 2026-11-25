package co.id.rmms.printbridge.printer
import co.id.rmms.printbridge.data.FontSizePreset
import co.id.rmms.printbridge.data.PaperType
import co.id.rmms.printbridge.data.PrintTemplate

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThermalPrinterEngine(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun executePrint(
        macAddress: String,
        template: PrintTemplate,
        renderedContent: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter = bluetoothManager.adapter
                ?: return@withContext Result.failure(Exception("Bluetooth tidak tersedia di perangkat ini"))

            val device = adapter.getRemoteDevice(macAddress)
            val connection = BluetoothConnection(device)
            connection.connect()

            when (template.paperType) {
                PaperType.CONTINUOUS -> {
                    val printer = EscPosPrinter(
                        connection,
                        203,
                        template.paperWidthMm.toFloat(),
                        template.charPerLine
                    )
                    val escPosFormatted = buildEscPosContent(printer, template, renderedContent)
                    printer.printFormattedTextAndCut(escPosFormatted)
                    printer.disconnectPrinter()
                }

                PaperType.GAP -> {
                    val trimmed = renderedContent.trim()
                    val tsplBytes = if (trimmed.uppercase().startsWith("SIZE") || trimmed.uppercase().startsWith("CLS")) {
                        (trimmed + "\r\n").toByteArray(Charsets.UTF_8)
                    } else {
                        val labelBitmap = PrintVisualRenderer.renderLabelBitmapForTspl(template, renderedContent)
                        PrintVisualRenderer.buildTsplBitmapPayload(template, labelBitmap)
                    }

                    val chunkSize = 1024
                    var offset = 0
                    while (offset < tsplBytes.size) {
                        val end = (offset + chunkSize).coerceAtMost(tsplBytes.size)
                        val chunk = tsplBytes.copyOfRange(offset, end)
                        connection.write(chunk)
                        connection.send(15)
                        offset = end
                    }
                    connection.disconnect()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun buildEscPosContent(
        printer: EscPosPrinter,
        template: PrintTemplate,
        formattedText: String
    ): String {
        val parsedLines = TemplateProcessor.parseTemplateLines(formattedText)
        val widthPx = printer.printerWidthPx.coerceAtLeast(320)
        val sb = StringBuilder()

        for (line in parsedLines) {
            when (line) {
                is ParsedPrintLine.BarcodeLine -> {
                    val bmp = PrintVisualRenderer.generateBarcodeBitmap(
                        content = line.value,
                        widthPx = (widthPx * 0.90f).toInt(),
                        heightPx = PrintVisualRenderer.IDEAL_BARCODE_HEIGHT_DOTS
                    )
                    if (bmp != null) {
                        val hex = PrinterTextParserImg.bitmapToHexadecimalString(printer, bmp)
                        sb.append("[C]<img>$hex</img>\n")
                        sb.append("[C]${line.value}\n")
                    }
                }

                is ParsedPrintLine.QrCodeLine -> {
                    val bmp = PrintVisualRenderer.generateQrBitmap(
                        content = line.value,
                        sizePx = PrintVisualRenderer.IDEAL_QR_SIZE_DOTS
                    )
                    if (bmp != null) {
                        val hex = PrinterTextParserImg.bitmapToHexadecimalString(printer, bmp)
                        sb.append("[C]<img>$hex</img>\n")
                    }
                }

                is ParsedPrintLine.TextLine -> {
                    val needsRasterRender = template.defaultFontSize != FontSizePreset.NORMAL ||
                            line.segments.any {
                                it.italic || it.strikethrough || it.fontScale == InlineFontScale.SMALL
                            }

                    if (needsRasterRender && line.segments.any { it.text.isNotBlank() }) {
                        val lineBmp = PrintVisualRenderer.renderSingleTextLineForEscPos(
                            textLine = line,
                            printerWidthPx = widthPx,
                            charPerLine = template.charPerLine
                        )
                        val hex = PrinterTextParserImg.bitmapToHexadecimalString(printer, lineBmp)
                        sb.append("[L]<img>$hex</img>\n")
                    } else {
                        val alignTag = when (line.alignment) {
                            LineAlignment.CENTER -> "[C]"
                            LineAlignment.RIGHT -> "[R]"
                            LineAlignment.LEFT -> "[L]"
                        }
                        sb.append(alignTag)
                        for (seg in line.segments) {
                            var chunk = seg.text
                            if (seg.underline) chunk = "<u>$chunk</u>"
                            if (seg.bold) chunk = "<b>$chunk</b>"
                            chunk = when (seg.fontScale) {
                                InlineFontScale.TALL -> "<font size='tall'>$chunk</font>"
                                InlineFontScale.WIDE -> "<font size='wide'>$chunk</font>"
                                InlineFontScale.BIG -> "<font size='big'>$chunk</font>"
                                else -> chunk
                            }
                            sb.append(chunk)
                        }
                        sb.append("\n")
                    }
                }
            }
        }

        return sb.toString()
    }
}