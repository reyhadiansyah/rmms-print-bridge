package co.id.rmms.printbridge
import co.id.rmms.printbridge.data.*
import co.id.rmms.printbridge.printer.*

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed class AppScreen {
    data object Home : AppScreen()
    data class Editor(val template: PrintTemplate? = null) : AppScreen()
}

private enum class LabelValueAlignMode {
    STANDARD,
    ALIGN_COLON,
    LEFT_RIGHT
}

private fun calculateRecommendedChars(paperWidthMm: Int, fontSize: FontSizePreset): Int {
    val multiplier = when (fontSize) {
        FontSizePreset.SMALL -> 0.75f
        FontSizePreset.NORMAL -> 0.60f
        FontSizePreset.LARGE -> 0.45f
    }
    return (paperWidthMm * multiplier).toInt().coerceAtLeast(16)
}

class MainActivity : ComponentActivity() {
    companion object {
        const val APP_NAME = "RMMS Print Bridge"
        const val CREATOR_DIVISION = "Raw Material Warehouse"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getInstance(this)
        val preferences = PrinterPreferences(this)
        val appVersion = getAppVersionName()

        setContent {
            MaterialTheme {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = true
                            isAppearanceLightNavigationBars = true
                        }
                    }
                }

                var showSplash by rememberSaveable { mutableStateOf(true) }
                var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

                LaunchedEffect(Unit) {
                    delay(2500)
                    showSplash = false
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Crossfade(
                        targetState = showSplash,
                        animationSpec = tween(durationMillis = 450),
                        label = "splash_transition"
                    ) { isSplash ->
                        if (isSplash) {
                            SplashScreen(
                                appName = APP_NAME,
                                versionName = appVersion,
                                divisionName = CREATOR_DIVISION
                            )
                        } else {
                            AnimatedContent(
                                targetState = currentScreen,
                                transitionSpec = {
                                    if (targetState is AppScreen.Editor) {
                                        (slideInHorizontally(
                                            animationSpec = tween(300),
                                            initialOffsetX = { fullWidth -> fullWidth }
                                        ) + fadeIn(animationSpec = tween(300))) togetherWith
                                                (slideOutHorizontally(
                                                    animationSpec = tween(300),
                                                    targetOffsetX = { fullWidth -> -fullWidth / 3 }
                                                ) + fadeOut(animationSpec = tween(250)))
                                    } else {
                                        (slideInHorizontally(
                                            animationSpec = tween(300),
                                            initialOffsetX = { fullWidth -> -fullWidth / 3 }
                                        ) + fadeIn(animationSpec = tween(300))) togetherWith
                                                (slideOutHorizontally(
                                                    animationSpec = tween(300),
                                                    targetOffsetX = { fullWidth -> fullWidth }
                                                ) + fadeOut(animationSpec = tween(250)) )
                                    }
                                },
                                label = "screen_navigation_transition"
                            ) { screen ->
                                when (screen) {
                                    is AppScreen.Home -> {
                                        HomeScreen(
                                            context = this@MainActivity,
                                            templateDao = database.templateDao(),
                                            preferences = preferences,
                                            appVersion = appVersion,
                                            onPermissionGranted = { startStandbyPrintService() },
                                            onCreateNewTemplate = {
                                                currentScreen = AppScreen.Editor(template = null)
                                            },
                                            onEditTemplate = { template ->
                                                currentScreen = AppScreen.Editor(template = template)
                                            }
                                        )
                                    }

                                    is AppScreen.Editor -> {
                                        TemplateEditorScreen(
                                            context = this@MainActivity,
                                            templateDao = database.templateDao(),
                                            initialTemplate = screen.template,
                                            onNavigateBack = { currentScreen = AppScreen.Home }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun startStandbyPrintService() {
        try {
            val serviceIntent = Intent(this, ThermalPrintService::class.java).apply {
                action = ThermalPrintService.ACTION_START_STANDBY
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun SplashScreen(
    appName: String,
    versionName: String,
    divisionName: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Logo $appName",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(128.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = appName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "v$versionName",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = divisionName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun UniformTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        ),
        modifier = modifier
    )
}

@Composable
private fun TemplateInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SwipeableTemplateItemCard(
    item: PrintTemplate,
    isRevealed: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val density = LocalDensity.current
    val actionPanelWidthDp = 104.dp
    val maxRevealPx = with(density) { -actionPanelWidthDp.toPx() }

    val animatedOffsetPx by animateFloatAsState(
        targetValue = if (isRevealed) maxRevealPx else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "swipe_offset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(84.dp)
                ) {
                    Button(
                        onClick = {
                            onExpandChange(false)
                            onEditClick()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Edit", fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            onExpandChange(false)
                            onDeleteClick()
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Hapus", fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 148.dp)
                .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                .pointerInput(item.templateId) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            if (totalDrag < -30f) {
                                onExpandChange(true)
                            } else if (totalDrag > 30f) {
                                onExpandChange(false)
                            }
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = if (isRevealed) ">>" else "<<",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                TemplateInfoRow(label = "ID URI", value = item.templateId)

                val paperTypeLabel = if (item.paperType == PaperType.GAP) {
                    "Label (TSPL)"
                } else {
                    "Continuous (ESC/POS)"
                }
                TemplateInfoRow(label = "Tipe Kertas", value = paperTypeLabel)

                val paperSizeValue = if (item.paperType == PaperType.GAP) {
                    "${item.paperWidthMm} x ${item.paperHeightMm} mm (Gap ${item.gapMm} mm)"
                } else {
                    "Lebar ${item.paperWidthMm} mm"
                }
                TemplateInfoRow(label = "Ukuran Kertas", value = paperSizeValue)

                TemplateInfoRow(
                    label = "Karakter per Baris",
                    value = "${item.charPerLine} Karakter"
                )

                TemplateInfoRow(
                    label = "Ukuran Font",
                    value = item.defaultFontSize.label
                )

                val alignModeText = when {
                    item.justifyLabelValue -> "Rata Kiri-Kanan"
                    item.alignColon && item.colonBySection -> "Titik Dua (Per Section)"
                    item.alignColon -> "Titik Dua (Semua Baris)"
                    else -> "Standar"
                }
                TemplateInfoRow(label = "Format Perataan", value = alignModeText)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun HomeScreen(
    context: Context,
    templateDao: TemplateDao,
    preferences: PrinterPreferences,
    appVersion: String,
    onPermissionGranted: () -> Unit,
    onCreateNewTemplate: () -> Unit,
    onEditTemplate: (PrintTemplate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val savedMac by preferences.printerMacFlow.collectAsState(initial = "")
    val savedPrinterName by preferences.printerNameFlow.collectAsState(initial = "Belum ada printer dipilih")
    val templates by templateDao.getAllTemplates().collectAsState(initial = emptyList())

    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var revealedTemplateId by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val btConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else true

        if (btConnectGranted) {
            onPermissionGranted()
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            pairedDevices = btManager.adapter?.bondedDevices?.toList() ?: emptyList()
        } else {
            Toast.makeText(
                context,
                "Izin Bluetooth diperlukan!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted && requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            onPermissionGranted()
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            pairedDevices = btManager.adapter?.bondedDevices?.toList() ?: emptyList()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = MainActivity.APP_NAME,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v$appVersion",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // KONFIGURASI PRINTER BLUETOOTH
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Konfigurasi Printer Bluetooth",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Printer Aktif:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = if (savedMac.isNotBlank()) "$savedPrinterName ($savedMac)" else "Belum ada printer dipilih",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                            pairedDevices = btManager.adapter?.bondedDevices?.toList() ?: emptyList()
                            Toast.makeText(context, "Daftar perangkat diperbarui", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Muat Ulang Perangkat Bluetooth",
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (pairedDevices.isEmpty()) {
                        Text(
                            text = "Tidak ada perangkat Bluetooth terhubung.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        pairedDevices.forEach { device ->
                            val isSelected = savedMac == device.address
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 12.dp)
                                    ) {
                                        Text(
                                            text = device.name ?: "Perangkat Tanpa Nama",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = device.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                preferences.saveSelectedPrinter(
                                                    device.name ?: "Printer",
                                                    device.address
                                                )
                                                Toast.makeText(
                                                    context,
                                                    "Printer ${device.name} dipilih!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        enabled = !isSelected,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .width(100.dp)
                                            .height(40.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(
                                            text = if (isSelected) "Terpilih" else "Pilih",
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // DAFTAR TEMPLATE TERSIMPAN
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Daftar Template Tersimpan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Button(
                        onClick = onCreateNewTemplate,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "+ Buat Template Baru",
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (templates.isEmpty()) {
                        Text(
                            text = "Belum ada template yang disimpan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        templates.forEach { item ->
                            SwipeableTemplateItemCard(
                                item = item,
                                isRevealed = revealedTemplateId == item.templateId,
                                onExpandChange = { expanded ->
                                    revealedTemplateId = if (expanded) item.templateId else null
                                },
                                onEditClick = { onEditTemplate(item) },
                                onDeleteClick = {
                                    scope.launch {
                                        templateDao.deleteTemplate(item)
                                        Toast.makeText(
                                            context,
                                            "Template '${item.title} dihapus",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun TemplateGuideDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Kamus Panduan Template",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GuideSectionBlock(
                        title = "1. Variabel Dinamis (JSON URI Scheme)",
                        items = listOf(
                            "{{nama_var}}" to "Menampilkan nilai dari key JSON Payload.",
                            "{{#items}}...{{/items}}" to "Perulangan untuk data array/list."
                        )
                    )

                    GuideSectionBlock(
                        title = "2. Pemformatan Teks",
                        items = listOf(
                            "<b>teks</b>" to "Huruf Tebal",
                            "<i>teks</i>" to "Huruf Miring",
                            "<u>teks</u>" to "Garis Bawah",
                            "<s>teks</s>" to "Garis Coret"
                        )
                    )

                    GuideSectionBlock(
                        title = "3. Ukuran Font Inline",
                        items = listOf(
                            "<small>teks</small>" to "Ukuran font lebih kecil",
                            "<normal>teks</normal>" to "Ukuran font normal",
                            "<big>teks</big>" to "Ukuran font lebih besar",
                            "<tall>teks</tall>" to "Tinggi font 2x",
                            "<wide>teks</wide>" to "Lebar font 2x"
                        )
                    )

                    GuideSectionBlock(
                        title = "4. Perataan Baris",
                        items = listOf(
                            "[L]teks" to "Rata Kiri",
                            "[C]teks" to "Rata Tengah",
                            "[R]teks" to "Rata Kanan"
                        )
                    )

                    GuideSectionBlock(
                        title = "5. Barcode Batang & QR Code",
                        items = listOf(
                            "[BARCODE]{{kode}}[/BARCODE]" to "Generate Barcode Batang (Code 128) + teks di bawahnya.",
                            "[QRCODE]{{qrcode}}[/QRCODE]" to "Generate QR Code 2D."
                        )
                    )

                    GuideSectionBlock(
                        title = "6. Pemisah Section Titik Dua (:)",
                        items = listOf(
                            "[SECTION]" to "Memisahkan perhitungan titik dua tanpa menambah baris kosong.",
                            "Baris Kosong (Enter)" to "Memisahkan perhitungan titik dua sekaligus memberi jarak 1 baris."
                        )
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Tutup Panduan", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun GuideSectionBlock(
    title: String,
    items: List<Pair<String, String>>
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            items.forEach { (syntax, desc) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = syntax,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StickyLivePreviewPanel(
    paperType: PaperType,
    paperWidthMm: String,
    paperHeightMm: String,
    charPerLine: Int,
    defaultFontSize: FontSizePreset,
    parsedLines: List<ParsedPrintLine>,
    validation: LabelFitValidation
) {
    val rulerLine = remember(charPerLine) { "-".repeat(charPerLine.coerceAtLeast(16)) }
    val baseSp = when (defaultFontSize) {
        FontSizePreset.SMALL -> 9.8f
        FontSizePreset.NORMAL -> 11.5f
        FontSizePreset.LARGE -> 13.8f
    }

    val specInfo = if (paperType == PaperType.GAP) {
        "Label ${paperWidthMm}x${paperHeightMm}mm • $charPerLine Karakter • Font ${defaultFontSize.label} • Est. Tinggi ${validation.requiredHeightMm}mm"
    } else {
        "Continuous ${paperWidthMm}mm • $charPerLine Karakter • Font ${defaultFontSize.label}"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Preview Template Cetak",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = specInfo,
                style = MaterialTheme.typography.labelSmall,
                color = if (validation.isOverflow) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (validation.isOverflow) FontWeight.SemiBold else FontWeight.Normal
            )

            if (validation.isOverflow) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Konten tidak muat di label",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        validation.reasons.forEach { reason ->
                            Text(
                                text = "• $reason",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFFFFEF9),
                border = BorderStroke(
                    width = 1.5.dp,
                    color = if (validation.isOverflow) MaterialTheme.colorScheme.error else Color(0xFFBDBDBD)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 168.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = rulerLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFBDBDBD)
                    )

                    parsedLines.forEach { line ->
                        when (line) {
                            is ParsedPrintLine.TextLine -> {
                                val textAlign = when (line.alignment) {
                                    LineAlignment.CENTER -> TextAlign.Center
                                    LineAlignment.RIGHT -> TextAlign.End
                                    LineAlignment.LEFT -> TextAlign.Start
                                }
                                val annotated = buildAnnotatedString {
                                    line.segments.forEach { seg ->
                                        val segSp = when (seg.fontScale) {
                                            InlineFontScale.SMALL -> (baseSp * 0.80f).sp
                                            InlineFontScale.NORMAL -> baseSp.sp
                                            InlineFontScale.BIG -> (baseSp * 1.40f).sp
                                            InlineFontScale.TALL -> (baseSp * 1.25f).sp
                                            InlineFontScale.WIDE -> (baseSp * 1.15f).sp
                                        }
                                        val decorations = buildList {
                                            if (seg.underline) add(TextDecoration.Underline)
                                            if (seg.strikethrough) add(TextDecoration.LineThrough)
                                        }
                                        val letterSpacing = if (seg.fontScale == InlineFontScale.WIDE) {
                                            2.sp
                                        } else {
                                            0.sp
                                        }
                                        withStyle(
                                            SpanStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = segSp,
                                                fontWeight = if (seg.bold) FontWeight.Bold else FontWeight.Normal,
                                                fontStyle = if (seg.italic) FontStyle.Italic else FontStyle.Normal,
                                                textDecoration = if (decorations.isEmpty()) {
                                                    TextDecoration.None
                                                } else {
                                                    TextDecoration.combine(decorations)
                                                },
                                                letterSpacing = letterSpacing,
                                                color = Color(0xFF1B1B1B)
                                            )
                                        ) {
                                            append(seg.text)
                                        }
                                    }
                                }
                                Text(
                                    text = annotated,
                                    textAlign = textAlign,
                                    lineHeight = (baseSp * 1.40f).sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            is ParsedPrintLine.BarcodeLine -> {
                                val barcodeBmp = remember(line.value) {
                                    PrintVisualRenderer.generateBarcodeBitmap(
                                        content = line.value,
                                        widthPx = 320,
                                        heightPx = PrintVisualRenderer.IDEAL_BARCODE_HEIGHT_DOTS)
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (barcodeBmp != null) {
                                        Image(
                                            bitmap = barcodeBmp.asImageBitmap(),
                                            contentDescription = "Preview Barcode",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .width(210.dp)
                                                .height(44.dp)
                                        )
                                        Text(
                                            text = line.value,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFF1B1B1B)
                                        )
                                    }
                                }
                            }

                            is ParsedPrintLine.QrCodeLine -> {
                                val qrBmp = remember(line.value) {
                                    PrintVisualRenderer.generateQrBitmap(
                                        content = line.value,
                                        sizePx = PrintVisualRenderer.IDEAL_QR_SIZE_DOTS
                                    )
                                }
                                if (qrBmp != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = qrBmp.asImageBitmap(),
                                            contentDescription = "Preview QR Code",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(92.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = rulerLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFBDBDBD)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TemplateEditorScreen(
    context: Context,
    templateDao: TemplateDao,
    initialTemplate: PrintTemplate?,
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val editorBringIntoView = remember { BringIntoViewRequester() }

    val isEditMode = initialTemplate != null
    var showGuideDialog by remember { mutableStateOf(false) }
    var rejectDialogValidation by remember { mutableStateOf<LabelFitValidation?>(null) }

    var templateId by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.templateId ?: "")
    }
    var title by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.title ?: "")
    }
    var paperType by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.paperType ?: PaperType.GAP)
    }
    var paperWidthMm by remember(initialTemplate) {
        mutableStateOf((initialTemplate?.paperWidthMm ?: 70).toString())
    }
    var paperHeightMm by remember(initialTemplate) {
        mutableStateOf((initialTemplate?.paperHeightMm ?: 50).toString())
    }
    var gapMm by remember(initialTemplate) {
        mutableStateOf((initialTemplate?.gapMm ?: 2).toString())
    }
    var defaultFontSize by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.defaultFontSize ?: FontSizePreset.NORMAL)
    }
    var charPerLine by remember(initialTemplate) {
        mutableStateOf(
            (initialTemplate?.charPerLine ?: calculateRecommendedChars(70, FontSizePreset.NORMAL)).toString()
        )
    }

    var alignMode by remember(initialTemplate) {
        mutableStateOf(
            when {
                initialTemplate == null -> LabelValueAlignMode.ALIGN_COLON
                initialTemplate.justifyLabelValue -> LabelValueAlignMode.LEFT_RIGHT
                initialTemplate.alignColon -> LabelValueAlignMode.ALIGN_COLON
                else -> LabelValueAlignMode.STANDARD
            }
        )
    }
    var colonBySection by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.colonBySection ?: true)
    }
    var indentWrappedText by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.indentWrappedText ?: true)
    }
    var showColonInLeftRight by remember(initialTemplate) {
        mutableStateOf(initialTemplate?.showColonInLeftRight ?: false)
    }

    var templateBody by remember(initialTemplate) {
        mutableStateOf(
            initialTemplate?.templateBody ?: (
                    "{{lorem_ipsum}}"
                    )
        )
    }

    if (showGuideDialog) {
        TemplateGuideDialog(onDismiss = { showGuideDialog = false })
    }

    rejectDialogValidation?.let { invalidState ->
        AlertDialog(
            onDismissRequest = { rejectDialogValidation = null },
            title = {
                Text(
                    text = "Template Ditolak (Melebihi Ukuran Label)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (paperType == PaperType.GAP) {
                        Text(
                            text = "Estimasi tinggi konten: ${invalidState.requiredHeightMm} mm (Tinggi label tersedia: ${invalidState.availableHeightMm} mm).",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    invalidState.reasons.forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "Kurangi jumlah baris konten, pilih Ukuran Font Default 'Kecil', atau perbesar ukuran Tinggi/Lebar Label.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { rejectDialogValidation = null }) {
                    Text("Perbaiki Template")
                }
            }
        )
    }

    fun buildCurrentEntity(): PrintTemplate {
        return PrintTemplate(
            templateId = templateId.trim(),
            title = title.trim(),
            paperType = paperType,
            paperWidthMm = paperWidthMm.toIntOrNull() ?: 70,
            paperHeightMm = paperHeightMm.toIntOrNull() ?: 50,
            gapMm = gapMm.toIntOrNull() ?: 2,
            charPerLine = charPerLine.toIntOrNull() ?: calculateRecommendedChars(
                paperWidthMm.toIntOrNull() ?: 70,
                defaultFontSize
            ),
            defaultFontSize = defaultFontSize,
            alignColon = alignMode == LabelValueAlignMode.ALIGN_COLON,
            colonBySection = colonBySection,
            indentWrappedText = indentWrappedText,
            justifyLabelValue = alignMode == LabelValueAlignMode.LEFT_RIGHT,
            showColonInLeftRight = showColonInLeftRight,
            templateBody = templateBody
        )
    }

    val currentEntity = buildCurrentEntity()
    val currentCharLimit = currentEntity.charPerLine.coerceAtLeast(16)

    val formattedPreviewText = remember(
        templateBody,
        currentCharLimit,
        alignMode,
        colonBySection,
        indentWrappedText,
        showColonInLeftRight
    ) {
        try {
            val sampleJson = TemplateProcessor.generateSampleJsonForTemplate()
            val rawRendered = TemplateProcessor.renderTemplate(templateBody, sampleJson)
            TemplateProcessor.formatAndWrapText(
                renderedText = rawRendered,
                maxCharsPerLine = currentCharLimit,
                alignColon = alignMode == LabelValueAlignMode.ALIGN_COLON,
                colonBySection = colonBySection,
                indentWrappedText = indentWrappedText,
                justifyLabelValue = alignMode == LabelValueAlignMode.LEFT_RIGHT,
                showColonInLeftRight = showColonInLeftRight
            )
        } catch (e: Exception) {
            "Error format template: ${e.localizedMessage}"
        }
    }

    val parsedPreviewLines = remember(formattedPreviewText) {
        TemplateProcessor.parseTemplateLines(formattedPreviewText)
    }

    val labelValidation = remember(currentEntity, formattedPreviewText) {
        PrintVisualRenderer.validateLabelFit(currentEntity, formattedPreviewText)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text(
                            text = "←",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = if (isEditMode) "Edit Template" else "Buat Template Baru",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (title.isNotBlank()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // BAGIAN ATAS
            // PREVIEW LABEL
            StickyLivePreviewPanel(
                paperType = paperType,
                paperWidthMm = paperWidthMm,
                paperHeightMm = paperHeightMm,
                charPerLine = currentCharLimit,
                defaultFontSize = defaultFontSize,
                parsedLines = parsedPreviewLines,
                validation = labelValidation
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // BAGIAN BAWAH
            // IDENTITAS & UKURAN KERTAS
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Identitas & Ukuran Kertas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        UniformTextField(
                            value = templateId,
                            onValueChange = { templateId = it },
                            label = "ID Template",
                            modifier = Modifier.fillMaxWidth()
                        )

                        UniformTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = "Nama Template",
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Tipe Kertas:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ModeSelectButton(
                                label = "Continuous (ESC/POS)",
                                selected = paperType == PaperType.CONTINUOUS,
                                onClick = { paperType = PaperType.CONTINUOUS },
                                modifier = Modifier.weight(1f)
                            )
                            ModeSelectButton(
                                label = "Label (TSPL)",
                                selected = paperType == PaperType.GAP,
                                onClick = { paperType = PaperType.GAP },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (paperType == PaperType.CONTINUOUS) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                UniformTextField(
                                    value = paperWidthMm,
                                    onValueChange = {
                                        paperWidthMm = it
                                        val w = it.toIntOrNull()
                                        if (w != null && w > 0) {
                                            charPerLine = calculateRecommendedChars(w, defaultFontSize).toString()
                                        }
                                    },
                                    label = "Lebar Kertas (mm)",
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f)
                                )
                                UniformTextField(
                                    value = charPerLine,
                                    onValueChange = { charPerLine = it },
                                    label = "Karakter per Baris",
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    UniformTextField(
                                        value = paperWidthMm,
                                        onValueChange = {
                                            paperWidthMm = it
                                            val w = it.toIntOrNull()
                                            if (w != null && w > 0) {
                                                charPerLine = calculateRecommendedChars(w, defaultFontSize).toString()
                                            }
                                        },
                                        label = "Lebar Label (mm)",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                    UniformTextField(
                                        value = paperHeightMm,
                                        onValueChange = { paperHeightMm = it },
                                        label = "Tinggi Label (mm)",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    UniformTextField(
                                        value = gapMm,
                                        onValueChange = { gapMm = it },
                                        label = "Jarak Gap (mm)",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                    UniformTextField(
                                        value = charPerLine,
                                        onValueChange = { charPerLine = it },
                                        label = "Karakter per Baris",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Ukuran Font Default:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FontSizePreset.entries.forEach { preset ->
                                ModeSelectButton(
                                    label = preset.label,
                                    selected = defaultFontSize == preset,
                                    onClick = {
                                        defaultFontSize = preset
                                        val w = paperWidthMm.toIntOrNull() ?: 70
                                        charPerLine = calculateRecommendedChars(w, preset).toString()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // KONFIGURASI FORMAT PERATAAN LABEL & NILAI
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Konfigurasi Perataan Label & Nilai",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ModeSelectButton(
                                label = "Standar",
                                selected = alignMode == LabelValueAlignMode.STANDARD,
                                onClick = { alignMode = LabelValueAlignMode.STANDARD },
                                modifier = Modifier.weight(0.85f)
                            )
                            ModeSelectButton(
                                label = "Sejajarkan ':'",
                                selected = alignMode == LabelValueAlignMode.ALIGN_COLON,
                                onClick = { alignMode = LabelValueAlignMode.ALIGN_COLON },
                                modifier = Modifier.weight(1f)
                            )
                            ModeSelectButton(
                                label = "Kiri - Kanan",
                                selected = alignMode == LabelValueAlignMode.LEFT_RIGHT,
                                onClick = { alignMode = LabelValueAlignMode.LEFT_RIGHT },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (alignMode == LabelValueAlignMode.ALIGN_COLON) {
                            SettingSwitchTile(
                                title = "Pisahkan Perataan Titik Dua Per Section",
                                subtitle = "Baris kosong atau tag [SECTION] memisahkan perhitungan posisi ':'",
                                checked = colonBySection,
                                onCheckedChange = { colonBySection = it }
                            )
                            SettingSwitchTile(
                                title = "Sejajarkan Lanjutan Teks dengan ':'",
                                subtitle = "Baris lanjutan dari teks panjang dimulai lurus di belakang titik dua",
                                checked = indentWrappedText,
                                onCheckedChange = { indentWrappedText = it }
                            )
                        }

                        if (alignMode == LabelValueAlignMode.LEFT_RIGHT) {
                            SettingSwitchTile(
                                title = "Tampilkan Tanda Titik Dua (:)",
                                subtitle = "Aktifkan jika ingin tanda ':' tetap dicetak di belakang label sebelah kiri",
                                checked = showColonInLeftRight,
                                onCheckedChange = { showColonInLeftRight = it }
                            )
                        }
                    }
                }

                // EDITOR ISI TEMPLATE & TOMBOL AKSI
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Isi Template",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            OutlinedButton(
                                onClick = { showGuideDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = "ⓘ Info",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        OutlinedTextField(
                            value = templateBody,
                            onValueChange = { templateBody = it },
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp)
                                .bringIntoViewRequester(editorBringIntoView)
                                .onFocusEvent { focusState ->
                                    if (focusState.isFocused) {
                                        scope.launch {
                                            delay(250)
                                            editorBringIntoView.bringIntoView()
                                        }
                                    }
                                }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (templateId.isBlank() || title.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            "ID Template dan Nama Template tidak boleh kosong!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@Button
                                    }
                                    if (labelValidation.isOverflow) {
                                        rejectDialogValidation = labelValidation
                                        return@Button
                                    }
                                    scope.launch {
                                        templateDao.saveTemplate(currentEntity)
                                        Toast.makeText(
                                            context,
                                            "Template '${currentEntity.title}' berhasil disimpan!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onNavigateBack()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text(
                                    text = "Simpan ",
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    if (templateId.isBlank() || title.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            "Isi ID Template dan Nama Template terlebih dahulu!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@OutlinedButton
                                    }
                                    if (labelValidation.isOverflow) {
                                        rejectDialogValidation = labelValidation
                                        return@OutlinedButton
                                    }
                                    scope.launch {
                                        templateDao.saveTemplate(currentEntity)
                                        val sampleJson = TemplateProcessor.generateSampleJsonForTemplate()
                                        val testIntent = Intent(context, ThermalPrintService::class.java).apply {
                                            action = ThermalPrintService.ACTION_PRINT_FROM_URI
                                            putExtra(ThermalPrintService.EXTRA_TEMPLATE_ID, currentEntity.templateId)
                                            putExtra(ThermalPrintService.EXTRA_PAYLOAD, sampleJson)
                                        }
                                        ContextCompat.startForegroundService(context, testIntent)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text(
                                    text = "Tes Cetak",
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ModeSelectButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 6.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 6.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingSwitchTile(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}