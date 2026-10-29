package com.aura.livewall.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aura.livewall.ai.LlmBrain
import com.aura.livewall.data.*
import com.aura.livewall.service.AuraWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF14141A),
                    primary = Color(0xFF8B5CF6), onPrimary = Color.White
                )
            ) { AuraApp() }
        }
    }
}

@Composable
fun AuraApp() {
    val ctx = LocalContext.current
    val db = remember { AuraDb.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(containerColor = Color.Black) {
                listOf("Модели", "Палитра", "Система").forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) }, alwaysShowLabel = true)
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CarouselTab(db, prefs)
                1 -> PaletteTab(prefs)
                else -> SystemTab(db, prefs)
            }
        }
    }
}

// ------------------------------------------------------------------ карусель моделей
@Composable
fun CarouselTab(db: AuraDb, prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chars by db.characters().all().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<CharacterEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = importModel(ctx, db, uri)
            if (!ok) Toast.makeText(ctx, "Это не .glb", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Персонажи", style = MaterialTheme.typography.headlineSmall)
        if (chars.isEmpty()) {
            Spacer(Modifier.weight(1f))
            Text("Импортируй свои .glb модели", color = Color.Gray)
            Spacer(Modifier.weight(1f))
        } else {
            val pager = rememberPagerState(initialPage = chars.indexOfFirst { it.id == prefs.characterId }.coerceAtLeast(0)) { chars.size }
            // свайп = применение; соседние модели прогреваются в кэш памяти -> переключение без лагов
            LaunchedEffect(pager.settledPage, chars) {
                val p = pager.settledPage
                chars.getOrNull(p)?.let { prefs.characterId = it.id }
                ModelCache.preload(listOfNotNull(chars.getOrNull(p), chars.getOrNull(p + 1), chars.getOrNull(p - 1)).map { it.modelPath })
            }
            HorizontalPager(
                state = pager, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 56.dp), pageSpacing = 14.dp
            ) { page ->
                val c = chars[page]
                val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                Card(
                    modifier = Modifier.fillMaxSize().padding(vertical = 24.dp).graphicsLayer {
                        val s = 1f - 0.12f * off; scaleX = s; scaleY = s; alpha = 1f - 0.5f * off
                    },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14141A)),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(c.name, style = MaterialTheme.typography.headlineMedium)
                            Text("${c.sizeBytes / 1048576} МБ · .glb", color = Color.Gray)
                            Spacer(Modifier.height(12.dp))
                            Text(c.persona.take(160), color = Color.LightGray)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = c }) { Text("Характер") }
                            OutlinedButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    db.characters().delete(c); ModelCache.evict(c.modelPath); File(c.modelPath).delete()
                                }
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("+ Импорт .glb") }
    }

    editing?.let { c ->
        CharacterDialog(c, onDismiss = { editing = null }) { upd ->
            scope.launch(Dispatchers.IO) { db.characters().update(upd) }
            editing = null
        }
    }
}

@Composable
fun CharacterDialog(c: CharacterEntity, onDismiss: () -> Unit, onSave: (CharacterEntity) -> Unit) {
    var name by remember { mutableStateOf(c.name) }
    var persona by remember { mutableStateOf(c.persona) }
    var cp by remember { mutableStateOf(c.chargingPrompt) }
    var lp by remember { mutableStateOf(c.lowBatteryPrompt) }
    var fc by remember { mutableStateOf(c.fallbackCharging) }
    var fl by remember { mutableStateOf(c.fallbackLowBattery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Характер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Имя", name) { name = it }
                Field("Промпт характера", persona) { persona = it }
                Field("Реакция на зарядку (для LLM)", cp) { cp = it }
                Field("Реакция на низкий заряд (для LLM)", lp) { lp = it }
                Field("Запасная реплика: зарядка", fc) { fc = it }
                Field("Запасная реплика: низкий заряд", fl) { fl = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(c.copy(name = name, persona = persona, chargingPrompt = cp, lowBatteryPrompt = lp, fallbackCharging = fc, fallbackLowBattery = fl))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun Field(label: String, v: String, set: (String) -> Unit) =
    OutlinedTextField(value = v, onValueChange = set, label = { Text(label) }, modifier = Modifier.fillMaxWidth())

suspend fun importModel(ctx: Context, db: AuraDb, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "model.glb"
    val dir = File(ctx.filesDir, "models").apply { mkdirs() }
    val out = File(dir, "${System.currentTimeMillis()}.glb")
    ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 16) } } ?: return@withContext false
    val magic = out.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
    if (String(magic) != "glTF") { out.delete(); return@withContext false }
    db.characters().insert(
        CharacterEntity(name = name.substringBeforeLast('.'), modelPath = out.absolutePath, persona = DEFAULT_PERSONA, sizeBytes = out.length())
    )
    true
}

// ------------------------------------------------------------------ палитра / рендер
@Composable
fun PaletteTab(prefs: Prefs) {
    var pal by remember { mutableIntStateOf(prefs.palette) }
    var fps by remember { mutableIntStateOf(prefs.fps) }
    val rgb = Palette.entries[pal].rgb
    val accent by animateColorAsState(Color(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt()), label = "accent")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Палитра", style = MaterialTheme.typography.headlineSmall)
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.85f), Color.Black))))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Palette.entries.forEach { p ->
                FilterChip(selected = pal == p.ordinal, onClick = { pal = p.ordinal; prefs.palette = p.ordinal }, label = { Text(p.label) })
            }
        }
        PrefSlider("Скорость перетекания", prefs.speed, 0.02f..0.4f) { prefs.speed = it }
        PrefSlider("Яркость градиента", prefs.intensity, 0f..1f) { prefs.intensity = it }
        PrefSlider("Масштаб модели", prefs.scale, 0.6f..1.6f) { prefs.scale = it }
        PrefSlider("Смещение по X", prefs.offX, -0.8f..0.8f) { prefs.offX = it }
        PrefSlider("Смещение по Y", prefs.offY, -0.6f..0.6f) { prefs.offY = it }
        PrefSlider("Поворот (°)", prefs.yaw, -180f..180f) { prefs.yaw = it }
        PrefSlider("Жёсткость пружин", prefs.spring, 0.4f..2f) { prefs.spring = it }
        Text("Частота кадров")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90).forEach { f ->
                FilterChip(selected = fps == f, onClick = { fps = f; prefs.fps = f }, label = { Text("$f") })
            }
        }
    }
}

@Composable
fun PrefSlider(label: String, init: Float, range: ClosedFloatingPointRange<Float>, onSet: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(init) }
    Column {
        Text("$label: ${"%.2f".format(v)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = v, onValueChange = { v = it; onSet(it) }, valueRange = range)
    }
}

// ------------------------------------------------------------------ система / LLM / права
@Composable
fun SystemTab(db: AuraDb, prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val brain = remember { LlmBrain.get(ctx) }
    var llm by remember { mutableStateOf(prefs.llmEnabled) }
    val model = brain.modelFile()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Система", style = MaterialTheme.typography.headlineSmall)

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            ctx.startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, AuraWallpaperService::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Установить живые обои") }

        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Разрешить «Поверх других окон» (диалог 5 с)") }

        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Батарея: без ограничений (OxygenOS / HyperOS)") }

        HorizontalDivider()
        Text("Локальная LLM (offline)", style = MaterialTheme.typography.titleMedium)
        Text(
            if (model != null) "Модель: ${model.name}" else "Модель не найдена. Положи Llama-style .task (MediaPipe) сюда:\n${brain.dir().path}",
            color = if (model != null) Color(0xFF86EFAC) else Color(0xFFFCA5A5)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = llm, onCheckedChange = { llm = it; prefs.llmEnabled = it })
            Text("LLM управляет персонажем")
        }
        PrefSlider("Мысли вслух каждые (мин)", prefs.thoughtMin.toFloat(), 5f..120f) { prefs.thoughtMin = it.toInt() }

        HorizontalDivider()
        Text("Приватность", style = MaterialTheme.typography.titleMedium)
        Text("Интернет-разрешения нет. История диалогов хранится в Room и очищается каждый день в 00:00.", color = Color.Gray)
        OutlinedButton(onClick = {
            scope.launch(Dispatchers.IO) { db.history().clear() }
            Toast.makeText(ctx, "История очищена", Toast.LENGTH_SHORT).show()
        }) { Text("Очистить историю сейчас") }
    }
}
