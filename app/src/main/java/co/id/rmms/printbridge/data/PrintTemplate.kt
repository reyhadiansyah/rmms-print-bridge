package co.id.rmms.printbridge.data

enum class PaperType {
    CONTINUOUS,
    GAP
}

enum class FontSizePreset(val label: String) {
    SMALL("Kecil"),
    NORMAL("Normal"),
    LARGE("Besar")
}

data class PrintTemplate(
    val templateId: String,
    val title: String,
    val paperType: PaperType,
    val paperWidthMm: Int,
    val paperHeightMm: Int,
    val gapMm: Int,
    val charPerLine: Int,
    val defaultFontSize: FontSizePreset,
    val alignColon: Boolean,
    val colonBySection: Boolean,
    val indentWrappedText: Boolean,
    val justifyLabelValue: Boolean,
    val showColonInLeftRight: Boolean,
    val templateBody: String
)