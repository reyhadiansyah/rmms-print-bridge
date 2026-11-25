package co.id.rmms.printbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TemplateDao(private val dbHelper: AppDatabase) {
    private val templatesFlow = MutableStateFlow<List<PrintTemplate>>(emptyList())

    init {
        refreshList()
    }

    private fun refreshList() {
        templatesFlow.value = queryAllFromDb()
    }

    fun getAllTemplates(): Flow<List<PrintTemplate>> = templatesFlow.asStateFlow()

    suspend fun getTemplateById(id: String): PrintTemplate? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "print_templates",
            null,
            "templateId = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        )
        var result: PrintTemplate? = null
        if (cursor.moveToFirst()) {
            result = cursorToTemplate(cursor)
        }
        cursor.close()
        result
    }

    suspend fun saveTemplate(template: PrintTemplate) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("templateId", template.templateId)
            put("title", template.title)
            put("paperType", template.paperType.name)
            put("paperWidthMm", template.paperWidthMm)
            put("paperHeightMm", template.paperHeightMm)
            put("gapMm", template.gapMm)
            put("charPerLine", template.charPerLine)
            put("defaultFontSize", template.defaultFontSize.name)
            put("alignColon", if (template.alignColon) 1 else 0)
            put("colonBySection", if (template.colonBySection) 1 else 0)
            put("indentWrappedText", if (template.indentWrappedText) 1 else 0)
            put("justifyLabelValue", if (template.justifyLabelValue) 1 else 0)
            put("showColonInLeftRight", if (template.showColonInLeftRight) 1 else 0)
            put("templateBody", template.templateBody)
        }
        db.insertWithOnConflict("print_templates", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshList()
    }

    suspend fun deleteTemplate(template: PrintTemplate) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("print_templates", "templateId = ?", arrayOf(template.templateId))
        refreshList()
    }

    private fun queryAllFromDb(): List<PrintTemplate> {
        val list = mutableListOf<PrintTemplate>()
        val db = dbHelper.readableDatabase
        val cursor = db.query("print_templates", null, null, null, null, null, "title ASC")
        while (cursor.moveToNext()) {
            list.add(cursorToTemplate(cursor))
        }
        cursor.close()
        return list
    }

    private fun cursorToTemplate(cursor: android.database.Cursor): PrintTemplate {
        val alignColonIdx = cursor.getColumnIndex("alignColon")
        val colonSectionIdx = cursor.getColumnIndex("colonBySection")
        val indentWrapIdx = cursor.getColumnIndex("indentWrappedText")
        val justifyIdx = cursor.getColumnIndex("justifyLabelValue")
        val showColonLrIdx = cursor.getColumnIndex("showColonInLeftRight")
        val fontSizeIdx = cursor.getColumnIndex("defaultFontSize")

        val parsedFontSize = if (fontSizeIdx != -1) {
            runCatching { FontSizePreset.valueOf(cursor.getString(fontSizeIdx)) }
                .getOrDefault(FontSizePreset.NORMAL)
        } else {
            FontSizePreset.NORMAL
        }

        return PrintTemplate(
            templateId = cursor.getString(cursor.getColumnIndexOrThrow("templateId")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            paperType = PaperType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("paperType"))),
            paperWidthMm = cursor.getInt(cursor.getColumnIndexOrThrow("paperWidthMm")),
            paperHeightMm = cursor.getInt(cursor.getColumnIndexOrThrow("paperHeightMm")),
            gapMm = cursor.getInt(cursor.getColumnIndexOrThrow("gapMm")),
            charPerLine = cursor.getInt(cursor.getColumnIndexOrThrow("charPerLine")),
            defaultFontSize = parsedFontSize,
            alignColon = if (alignColonIdx != -1) cursor.getInt(alignColonIdx) == 1 else false,
            colonBySection = if (colonSectionIdx != -1) cursor.getInt(colonSectionIdx) == 1 else false,
            indentWrappedText = if (indentWrapIdx != -1) cursor.getInt(indentWrapIdx) == 1 else false,
            justifyLabelValue = if (justifyIdx != -1) cursor.getInt(justifyIdx) == 1 else false,
            showColonInLeftRight = if (showColonLrIdx != -1) cursor.getInt(showColonLrIdx) == 1 else false,
            templateBody = cursor.getString(cursor.getColumnIndexOrThrow("templateBody"))
        )
    }
}

class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "rmms_print_bridge.db", null, 1) {
        private val daoInstance by lazy { TemplateDao(this) }

        fun templateDao(): TemplateDao = daoInstance

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS print_templates (
                        templateId TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        paperType TEXT NOT NULL,
                        paperWidthMm INTEGER NOT NULL,
                        paperHeightMm INTEGER NOT NULL,
                        gapMm INTEGER NOT NULL,
                        charPerLine INTEGER NOT NULL,
                        defaultFontSize TEXT NOT NULL,
                        alignColon INTEGER NOT NULL DEFAULT 0,
                        colonBySection INTEGER NOT NULL DEFAULT 0,
                        indentWrappedText INTEGER NOT NULL DEFAULT 0,
                        justifyLabelValue INTEGER NOT NULL DEFAULT 0,
                        showColonInLeftRight INTEGER NOT NULL DEFAULT 0,
                        templateBody TEXT NOT NULL
                    )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS print_templates")
            onCreate(db)
        }

        companion object {
            @Volatile
            private var INSTANCE: AppDatabase? = null

            fun getInstance(context: Context): AppDatabase {
                return INSTANCE ?: synchronized(this) {
                    AppDatabase(context.applicationContext).also { INSTANCE = it }
                }
            }
        }
    }