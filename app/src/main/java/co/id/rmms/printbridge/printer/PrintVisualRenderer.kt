package co.id.rmms.printbridge.printer
import co.id.rmms.printbridge.data.PrintTemplate

import android.graphics.*
import co.id.rmms.printbridge.data.PaperType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

data class LabelFitValidation(
    val isOverflow: Boolean,
    val requiredHeightMm: Int,
    val availableHeightMm: Int,
    val reasons: List<String>
)

object PrintVisualRenderer {
    private const val DOTS_PER_MM = 8
    private const val MARGIN_X_DOTS = 16f
    private const val MARGIN_TOP_DOTS = 16f
    private const val MARGIN_BOTTOM_DOTS = 12f

    const val IDEAL_QR_SIZE_DOTS = 160
    const val IDEAL_BARCODE_HEIGHT_DOTS = 64

    fun generateBarcodeBitmap(
        content: String,
        widthPx: Int = 360,
        heightPx: Int = IDEAL_BARCODE_HEIGHT_DOTS
    ): Bitmap? {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) return null

        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 4)
            val bitMatrix = MultiFormatWriter().encode(
                cleanContent,
                BarcodeFormat.CODE_128,
                widthPx.coerceAtLeast(160),
                heightPx.coerceAtLeast(48),
                hints
            )
            val w = bitMatrix.width
            val h = bitMatrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generateQrBitmap(
        content: String,
        sizePx: Int = IDEAL_QR_SIZE_DOTS
    ): Bitmap? {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) return null

        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = MultiFormatWriter().encode(
                cleanContent,
                BarcodeFormat.QR_CODE,
                sizePx.coerceAtLeast(100),
                sizePx.coerceAtLeast(100),
                hints
            )
            val w = bitMatrix.width
            val h = bitMatrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun validateLabelFit(
        template: PrintTemplate,
        formattedText: String
    ): LabelFitValidation {
        val widthDots = ((template.paperWidthMm.coerceAtLeast(20) * DOTS_PER_MM) / 8) * 8
        val printableWidth = (widthDots - (MARGIN_X_DOTS * 2)).coerceAtLeast(80f)
        val parsedLines = TemplateProcessor.parseTemplateLines(formattedText)
        val reasons = mutableListOf<String>()

        val hasBarcode = parsedLines.any { it is ParsedPrintLine.BarcodeLine && it.value.isNotBlank() }
        val hasQr = parsedLines.any { it is ParsedPrintLine.QrCodeLine && it.value.isNotBlank() }

        if (hasBarcode && printableWidth < 160f) {
            reasons.add("Lebar kertas terlalu sempit untuk mencetak Barcode Batang")
        }
        if (hasQr && printableWidth < IDEAL_QR_SIZE_DOTS) {
            reasons.add("Lebar kertas terlalu sempit untuk mencetak QR Code")
        }

        if (template.paperType == PaperType.CONTINUOUS) {
            return LabelFitValidation(
                isOverflow = reasons.isNotEmpty(),
                requiredHeightMm = 0,
                availableHeightMm = 0,
                reasons = reasons
            )
        }

        val maxHeightDots = template.paperHeightMm.coerceAtLeast(15) * DOTS_PER_MM.toFloat()
        val usableHeightDots = maxHeightDots - MARGIN_BOTTOM_DOTS
        val baseTextSize = calculateCalibratedTextSize(printableWidth, template.charPerLine)

        var cursorY = MARGIN_TOP_DOTS
        var textOverflow = false
        var barcodeOverflow = false
        var qrOverflow = false

        for (line in parsedLines) {
            when (line) {
                is ParsedPrintLine.TextLine -> {
                    val lineHeight = measureTextLineHeight(line, baseTextSize)
                    cursorY += lineHeight
                    if (cursorY > usableHeightDots) {
                        textOverflow = true
                    }
                }

                is ParsedPrintLine.BarcodeLine -> {
                    if (line.value.isNotBlank()) {
                        val barcodeBlockHeight = measureBarcodeBlockHeight(baseTextSize)
                        cursorY += barcodeBlockHeight
                        if (cursorY > usableHeightDots) {
                            barcodeOverflow = true
                        }
                    }
                }

                is ParsedPrintLine.QrCodeLine -> {
                    if (line.value.isNotBlank()) {
                        val qrBlockHeight = IDEAL_QR_SIZE_DOTS + 16f
                        cursorY += qrBlockHeight
                        if (cursorY > usableHeightDots) {
                            qrOverflow = true
                        }
                    }
                }
            }
        }

        if (textOverflow) {
            reasons.add("Konten melebihi tinggi label")
        }
        if (barcodeOverflow) {
            reasons.add("Ruang vertikal label tidak mencukupi untuk Barcode Batang")
        }
        if (qrOverflow) {
            reasons.add("Ruang vertikal label tidak mencukupi untuk QR Code")
        }

        val requiredMm = ceil((cursorY + MARGIN_BOTTOM_DOTS) / DOTS_PER_MM.toDouble()).toInt()
        val isOverflow = reasons.isNotEmpty() || cursorY > usableHeightDots

        return LabelFitValidation(
            isOverflow = isOverflow,
            requiredHeightMm = requiredMm,
            availableHeightMm = template.paperHeightMm,
            reasons = reasons.distinct()
        )
    }

    fun renderLabelBitmapForTspl(
        template: PrintTemplate,
        formattedText: String
    ): Bitmap {
        val widthDots = ((template.paperWidthMm.coerceAtLeast(25) * DOTS_PER_MM) / 8) * 8
        val maxHeightDots = (template.paperHeightMm.coerceAtLeast(15) * DOTS_PER_MM)
        val printableWidth = (widthDots - (MARGIN_X_DOTS * 2)).coerceAtLeast(100f)

        val baseTextSize = calculateCalibratedTextSize(
            printableWidthPx = printableWidth,
            charPerLine = template.charPerLine
        )

        val parsedLines = TemplateProcessor.parseTemplateLines(formattedText)
        val tempBitmap = Bitmap.createBitmap(widthDots, maxHeightDots, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tempBitmap)
        canvas.drawColor(Color.WHITE)

        var cursorY = MARGIN_TOP_DOTS

        for (line in parsedLines) {
            if (cursorY >= maxHeightDots - 4f) break

            when (line) {
                is ParsedPrintLine.TextLine -> {
                    cursorY = drawTextLineOnCanvas(
                        canvas = canvas,
                        textLine = line,
                        startY = cursorY,
                        marginX = MARGIN_X_DOTS,
                        printableWidth = printableWidth,
                        baseTextSize = baseTextSize
                    )
                }

                is ParsedPrintLine.BarcodeLine -> {
                    val barcodeWidth = (printableWidth * 0.90f).toInt().coerceIn(160, widthDots - 32)
                    val bmp = generateBarcodeBitmap(line.value, barcodeWidth, IDEAL_BARCODE_HEIGHT_DOTS)
                    if (bmp != null) {
                        val drawX = (widthDots - bmp.width) / 2f
                        canvas.drawBitmap(bmp, drawX, cursorY + 4f, null)
                        cursorY += bmp.height + 8f

                        val labelPaint = createSegmentPaint(
                            segment = StyledTextSegment(line.value),
                            baseTextSize = (baseTextSize * 0.80f).coerceAtLeast(14f)
                        )
                        val textW = labelPaint.measureText(line.value)
                        val fm = labelPaint.fontMetrics
                        canvas.drawText(
                            line.value,
                            (widthDots - textW) / 2f,
                            cursorY - fm.ascent,
                            labelPaint
                        )
                        cursorY += (fm.descent - fm.ascent) + 8f
                    }
                }

                is ParsedPrintLine.QrCodeLine -> {
                    val bmp = generateQrBitmap(line.value, IDEAL_QR_SIZE_DOTS)
                    if (bmp != null) {
                        val drawX = (widthDots - bmp.width) / 2f
                        canvas.drawBitmap(bmp, drawX, cursorY + 4f, null)
                        cursorY += bmp.height + 12f
                    }
                }
            }
        }

        val finalHeight = ceil(cursorY + MARGIN_BOTTOM_DOTS).toInt().coerceIn(48, maxHeightDots)
        return Bitmap.createBitmap(tempBitmap, 0, 0, widthDots, finalHeight)
    }

    fun renderSingleTextLineForEscPos(
        textLine: ParsedPrintLine.TextLine,
        printerWidthPx : Int,
        charPerLine: Int
    ): Bitmap {
        val safeWidth = ((printerWidthPx.coerceAtLeast(240)) / 8) * 8
        val baseTextSize = calculateCalibratedTextSize(
            printableWidthPx = safeWidth.toFloat(),
            charPerLine = charPerLine
        )

        val lineHeight = measureTextLineHeight(textLine, baseTextSize)
        val bitmapHeight = ceil(lineHeight).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(safeWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        drawTextLineOnCanvas(
            canvas = canvas,
            textLine = textLine,
            startY = 2f,
            marginX = 0f,
            printableWidth = safeWidth.toFloat(),
            baseTextSize = baseTextSize
        )
        return bitmap
    }

    fun buildTsplBitmapPayload(template: PrintTemplate, bitmap: Bitmap): ByteArray {
        val widthBytes = (bitmap.width + 7) / 8
        val heightDots = bitmap.height

        val header = buildString {
            append("SIZE ${template.paperWidthMm} mm, ${template.paperHeightMm} mm\r\n")
            append("GAP ${template.gapMm} mm, 0 mm\r\n")
            append("DIRECTION 1\r\n")
            append("CLS\r\n")
            append("BITMAP 0,0,$widthBytes,$heightDots,0,")
        }.toByteArray(Charsets.UTF_8)

        val bitmapData = ByteArray(widthBytes * heightDots)
        val rowPixels = IntArray(bitmap.width)

        for (y in 0 until heightDots) {
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (byteCol in 0 until widthBytes) {
                var byteVal = 0xFF
                for (bit in 0 until 8) {
                    val x = byteCol * 8 + bit
                    if (x < bitmap.width) {
                        val color = rowPixels[x]
                        val r = Color.red(color)
                        val g = Color.green(color)
                        val b = Color.blue(color)
                        val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
                        if (luminance < 160) {
                            byteVal = byteVal and (0x80 shr bit).inv()
                        }
                    }
                }
                bitmapData[y * widthBytes + byteCol] = byteVal.toByte()
            }
        }

        val footer = "\r\nPRINT 1,1\r\n".toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream(header.size + bitmapData.size + footer.size)
        out.write(header)
        out.write(bitmapData)
        out.write(footer)
        return out.toByteArray()
    }

    private fun calculateCalibratedTextSize(
        printableWidthPx: Float,
        charPerLine: Int
    ): Float {
        val safeChars = charPerLine.coerceAtLeast(16)
        val probePaint = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = 20f
        }
        val ratio = probePaint.measureText("M") / 20f
        return ((printableWidthPx / safeChars) / ratio).coerceIn(12f, 48f)
    }

    private fun measureTextLineHeight(
        textLine: ParsedPrintLine.TextLine,
        baseTextSize: Float
    ): Float {
        if (textLine.segments.all { it.text.isEmpty() }) {
            return baseTextSize * 0.95f
        }
        var maxAscent = 0f
        var maxDescent = 0f
        for (seg in textLine.segments) {
            val fm = createSegmentPaint(seg, baseTextSize).fontMetrics
            maxAscent = max(maxAscent, -fm.ascent)
            maxDescent = max(maxDescent, fm.descent)
        }
        return maxAscent + maxDescent + 6f
    }

    private fun measureBarcodeBlockHeight(baseTextSize: Float): Float {
        val labelPaint = createSegmentPaint(
            segment = StyledTextSegment("0"),
            baseTextSize = (baseTextSize * 0.80f).coerceAtLeast(14f)
        )
        val fm = labelPaint.fontMetrics
        val labelHeight = (fm.descent - fm.ascent)
        return 4f + IDEAL_BARCODE_HEIGHT_DOTS + 8f + labelHeight + 8f
    }

    private fun drawTextLineOnCanvas(
        canvas: Canvas,
        textLine: ParsedPrintLine.TextLine,
        startY: Float,
        marginX: Float,
        printableWidth: Float,
        baseTextSize: Float
    ): Float {
        if (textLine.segments.all { it.text.isEmpty() }) {
            return startY + (baseTextSize * 0.95f)
        }

        val paints = textLine.segments.map { createSegmentPaint(it, baseTextSize) }
        var totalLineWidth = 0f
        var maxAscent = 0f
        var maxDescent = 0f

        textLine.segments.forEachIndexed { idx, seg ->
            val p = paints[idx]
            totalLineWidth += p.measureText(seg.text)
            val fm = p.fontMetrics
            maxAscent = max(maxAscent, -fm.ascent)
            maxDescent = max(maxDescent, fm.descent)
        }

        var drawX = when (textLine.alignment) {
            LineAlignment.CENTER -> marginX + ((printableWidth - totalLineWidth) / 2f).coerceAtLeast(0f)
            LineAlignment.RIGHT -> marginX + (printableWidth - totalLineWidth).coerceAtLeast(0f)
            LineAlignment.LEFT -> marginX
        }

        val baselineY = startY + maxAscent
        textLine.segments.forEachIndexed { idx, seg ->
            val p = paints[idx]
            canvas.drawText(seg.text, drawX, baselineY, p)
            drawX += p.measureText(seg.text)
        }

        return startY + maxAscent + maxDescent + 6f
    }

    private fun createSegmentPaint(
        segment: StyledTextSegment,
        baseTextSize: Float
    ): Paint {
        val styleInt = when{
            segment.bold && segment.italic -> Typeface.BOLD_ITALIC
            segment.bold -> Typeface.BOLD
            segment.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        var size = baseTextSize
        var scaleX = 1.0f

        when (segment.fontScale) {
            InlineFontScale.SMALL -> {
                size = baseTextSize * 0.78f
                scaleX = 1.0f
            }
            InlineFontScale.NORMAL -> {
                size = baseTextSize
                scaleX = 1.0f
            }
            InlineFontScale.TALL -> {
                size = baseTextSize * 1.55f
                scaleX = 0.65f
            }

            InlineFontScale.WIDE -> {
                size = baseTextSize
                scaleX = 1.65f
            }

            InlineFontScale.BIG -> {
                size = baseTextSize * 1.55f
                scaleX = 1.0f
            }
        }

        return Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            typeface = Typeface.create(Typeface.MONOSPACE, styleInt)
            textSize = size.coerceAtLeast(12f)
            textScaleX = scaleX
            isUnderlineText = segment.underline
            isStrikeThruText = segment.strikethrough
            isFakeBoldText = segment.bold
        }
    }
}