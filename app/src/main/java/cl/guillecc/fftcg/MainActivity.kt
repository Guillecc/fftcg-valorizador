package cl.guillecc.fftcg

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8FD3FF), secondary = Color(0xFF4FA6E0))) {
                AppScreen()
            }
        }
    }
}

private val GOLD = Color(0xFFE8C46A)
private val clpFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CL"))

/** Motivo legible de un error de red o de archivo. */
fun errorReason(e: Throwable): String = when (e) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "sin conexión a internet"
    else -> e.message ?: "error desconocido"
}

fun usd(v: Double?) = if (v == null) "—" else String.format(Locale.US, "US$ %,.2f", v)
fun clp(v: Double) = "$ " + clpFormat.format(Math.round(v))

@Composable
fun AppScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val entries = remember { mutableStateListOf<Entry>() }
    var indexVersion by remember { mutableIntStateOf(0) } // cambia al cargar/descargar el índice
    var loading by remember { mutableStateOf(true) }
    var fx by remember { mutableStateOf(Fx.current(ctx)) }
    var fxSource by remember { mutableStateOf(Fx.source(ctx)) }
    var refreshing by remember { mutableStateOf(false) } // descarga del índice en curso
    var indexError by remember { mutableStateOf<String?>(null) } // motivo de la última descarga fallida
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<CardInfo?>(null) }
    var onAdded by remember { mutableStateOf<() -> Unit>({}) }
    var available by remember { mutableStateOf<Update?>(null) } // versión nueva: muestra el aviso en Colección
    var updating by remember { mutableStateOf<Update?>(null) } // diálogo de actualización abierto

    fun persist() {
        Collection.saveAsync(ctx, entries.toList()) { e ->
            scope.launch { snack.showSnackbar("No se pudo guardar la colección (${e.message})") }
        }
    }

    fun replace(list: List<Entry>) {
        entries.clear()
        entries.addAll(list)
        persist()
    }

    suspend fun refresh(force: Boolean) {
        if (refreshing) return
        refreshing = true
        indexError = null
        val err = withContext(Dispatchers.IO) { runCatching { CardIndex.refreshIfStale(ctx, force) }.exceptionOrNull() }
        refreshing = false
        indexVersion++
        if (err != null) {
            val reason = errorReason(err)
            indexError = reason
            // Sin índice, el error se muestra en pantalla con Reintentar; con índice, basta un aviso
            if (CardIndex.ready) snack.showSnackbar("No se pudieron descargar los precios ($reason)")
        }
        withContext(Dispatchers.IO) { runCatching { Fx.refreshIfStale(ctx) } }
        fx = Fx.current(ctx)
        fxSource = Fx.source(ctx)
    }

    fun checkUpdate() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.check(ctx) } }
            r.onSuccess { u ->
                if (u != null) {
                    available = u
                    updating = u
                } else {
                    snack.showSnackbar("Ya tienes la última versión (${Updater.installedVersion(ctx)})")
                }
            }.onFailure { snack.showSnackbar("No se pudo buscar actualización (${it.message})") }
        }
    }

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) {
            runCatching { Updater.clean(ctx) }
            CardIndex.load(ctx)
            Collection.load(ctx)
        }
        entries.addAll(saved.entries)
        indexVersion++
        loading = false
        saved.message?.let { msg -> scope.launch { snack.showSnackbar(msg, withDismissAction = true, duration = SnackbarDuration.Indefinite) } }
        refresh(force = false)
        // Una vez por inicio (GitHub permite 60 consultas por hora sin cuenta); si falla, no se avisa
        withContext(Dispatchers.IO) { runCatching { Updater.check(ctx) }.getOrNull() }?.let { available = it }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                listOf("▤" to "Colección", "⌕" to "Buscar", "◎" to "Escanear").forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Leer indexVersion aquí hace que las pantallas se recompongan cuando llegan precios nuevos
            val version = indexVersion
            when {
                loading -> Text("Cargando…", Modifier.align(Alignment.Center))
                !CardIndex.ready -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val err = indexError
                    if (refreshing || err == null) {
                        Text("Descargando cartas y precios de TCGplayer…", textAlign = TextAlign.Center)
                    } else {
                        Text("No se pudieron descargar las cartas", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Necesitas internet la primera vez. Motivo: $err",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { scope.launch { refresh(force = true) } }) { Text("Reintentar", maxLines = 1) }
                    }
                }
                tab == 0 -> Column(Modifier.fillMaxSize()) {
                    available?.let { u -> UpdateBanner(u, onClick = { updating = u }) }
                    Box(Modifier.weight(1f)) {
                        CollectionScreen(
                            entries = entries,
                            version = version,
                            fx = fx,
                            fxSource = fxSource,
                            onChange = { replace(it) },
                            onFxChange = { Fx.setManual(ctx, it); fx = Fx.current(ctx); fxSource = Fx.source(ctx) },
                            onRefresh = { scope.launch { refresh(force = true); snack.showSnackbar("Precios al ${CardIndex.updated}") } },
                            onCheckUpdate = { checkUpdate() },
                            onMessage = { scope.launch { snack.showSnackbar(it) } },
                        )
                    }
                }
                tab == 1 -> SearchScreen(onPick = { adding = it; onAdded = {} })
                else -> ScanScreen(onPick = { card, resume -> adding = card; onAdded = resume })
            }
        }
    }

    adding?.let { card ->
        AddDialog(
            card = card,
            fx = fx,
            onDismiss = { adding = null },
            onAdd = { foil, qty ->
                replace(Collection.add(entries, card.id, foil, qty))
                adding = null
                onAdded()
                scope.launch { snack.showSnackbar("Agregada: ${card.name}${if (foil) " (foil)" else ""} ×$qty") }
            },
        )
    }

    updating?.let { u ->
        // "Ahora no" también oculta el aviso hasta el próximo inicio
        UpdateDialog(update = u, onDismiss = { updating = null; available = null })
    }
}

// ------------------------------------------------------------------ Colección

@Composable
fun CollectionScreen(
    entries: List<Entry>,
    version: Int,
    fx: Double,
    fxSource: String,
    onChange: (List<Entry>) -> Unit,
    onFxChange: (Double?) -> Unit,
    onRefresh: () -> Unit,
    onCheckUpdate: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val installed = remember { Updater.installedVersion(ctx) }
    val rows = remember(entries.toList(), version) {
        entries.map { it to CardIndex.byId(it.id) }
            .sortedByDescending { (e, c) -> e.unit(c)?.times(e.qty) ?: -1.0 }
    }
    val totalUsd = rows.sumOf { (e, c) -> (e.unit(c) ?: 0.0) * e.qty }
    val missing = rows.count { (e, c) -> e.unit(c) == null }
    // Se guarda la clave (id, foil) para que el diálogo siempre muestre la versión actual de la línea
    var detail by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var editFx by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<CsvResult?>(null) } // CSV leído, esperando Agregar/Reemplazar
    var backups by remember { mutableStateOf<List<Backup>?>(null) } // diálogo de respaldos abierto

    fun importMessage(r: CsvResult): String {
        val shown = r.failed.take(10).joinToString(", ")
        val more = if (r.failed.size > 10) " y ${r.failed.size - 10} más" else ""
        return "Importadas ${r.entries.size} líneas" + if (r.failed.isEmpty()) "" else ". Sin match: $shown$more"
    }

    fun applyImport(r: CsvResult, replaceAll: Boolean) {
        val base = if (replaceAll) emptyList() else entries.toList()
        onChange(r.entries.fold(base) { acc, e -> Collection.add(acc, e.id, e.foil, e.qty, e.manual) })
        onMessage(importMessage(r))
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("sin acceso")
                    Collection.fromCsv(input.use { Net.readLimited(it, MAX_CSV_BYTES) })
                }
            }
            r.onFailure {
                onMessage(if (it.message == "archivo demasiado grande") "El archivo es muy grande (máximo 1 MB)" else "No se pudo leer el archivo")
            }.onSuccess { res ->
                when {
                    res.entries.isEmpty() -> onMessage(importMessage(res))
                    entries.isEmpty() -> applyImport(res, replaceAll = false)
                    else -> pendingImport = res
                }
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    // Encabezado: título y menú en la misma fila, centrados verticalmente
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Valor de la colección", Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // Acciones en un menú para que no se amontonen en pantallas angostas
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(AppIcons.MoreVert, contentDescription = "Más opciones", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Importar CSV", maxLines = 1) },
                                    onClick = { menu = false; importer.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Exportar CSV", maxLines = 1) },
                                    onClick = { menu = false; shareCsv(ctx, entries) },
                                    enabled = entries.isNotEmpty(),
                                )
                                DropdownMenuItem(
                                    text = { Text("Restaurar respaldo", maxLines = 1) },
                                    onClick = {
                                        menu = false
                                        scope.launch { backups = withContext(Dispatchers.IO) { Collection.backups(ctx) } }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Actualizar precios", maxLines = 1) },
                                    onClick = { menu = false; onRefresh() },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Buscar actualización", maxLines = 1)
                                            Text(
                                                "Tienes $installed", fontSize = 12.sp, maxLines = 1,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = { menu = false; onCheckUpdate() },
                                )
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(usd(totalUsd), fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(clp(totalUsd * fx), fontSize = 20.sp, color = GOLD)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${entries.sumOf { it.qty }} cartas · ${if (missing > 0) "$missing sin precio · " else ""}" +
                                "precios TCGplayer al ${CardIndex.updated}",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Objetivo táctil de 48dp de alto para el enlace "cambiar"
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { editFx = true },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text("1 USD = ${clp(fx)} ($fxSource) · cambiar", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) item {
            Text(
                "Tu colección está vacía. Agrega cartas desde Buscar o Escanear, o importa un CSV (codigo,cantidad,foil).",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(rows, key = { _, (e, _) -> "${e.id}-${e.foil}" }) { i, (e, c) ->
            val unit = e.unit(c)
            CardRow(
                card = c,
                title = (if (unit != null && i < 10) "#${i + 1} " else "") + (c?.name ?: "Carta ${e.id}"),
                subtitle = "${c?.code.orEmpty()} · ${c?.set.orEmpty()}",
                foil = e.foil,
                right = usd(unit?.times(e.qty)),
                rightSub = "×${e.qty} · ${usd(unit)}",
                rightNote = if (e.manual != null) "manual" else null,
                warning = c != null && c.finish(e.foil) != null && !c.inStock(e.foil),
                onClick = { detail = e.id to e.foil },
            )
        }
    }

    val opened = detail?.let { (id, foil) -> entries.firstOrNull { it.id == id && it.foil == foil } }
    if (opened != null) {
        // Reemplaza (o quita, con null) la línea abierta en el diálogo
        val update: (Entry?) -> Unit = { new ->
            val list = entries.toMutableList()
            val i = list.indexOfFirst { it.id == opened.id && it.foil == opened.foil }
            if (i >= 0) {
                if (new == null) list.removeAt(i) else list[i] = new
                onChange(list)
            }
        }
        EntryDialog(
            entry = opened, card = CardIndex.byId(opened.id), fx = fx,
            onDismiss = { detail = null },
            onQty = { q -> update(opened.copy(qty = q)) },
            onManual = { m -> update(opened.copy(manual = m)) },
            onRemove = { update(null); detail = null },
        )
    }
    if (editFx) FxDialog(current = fx, onDismiss = { editFx = false }, onSet = { onFxChange(it); editFx = false })

    pendingImport?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Importar CSV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("¿Agregar a tu colección o reemplazarla?")
                    Text(
                        "El archivo trae ${r.entries.size} líneas. Agregar suma copias; Reemplazar deja solo las del archivo.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // Tres opciones apiladas a todo el ancho: en una fila no caben sin apretarse
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pendingImport = null; applyImport(r, replaceAll = false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Agregar", maxLines = 1)
                    }
                    OutlinedButton(onClick = { pendingImport = null; applyImport(r, replaceAll = true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reemplazar", maxLines = 1)
                    }
                    TextButton(onClick = { pendingImport = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancelar", maxLines = 1)
                    }
                }
            },
        )
    }

    backups?.let { list ->
        RestoreDialog(
            backups = list,
            onDismiss = { backups = null },
            onRestore = { b ->
                backups = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Collection.readBackup(b.file) } }
                    r.onSuccess { onChange(it); onMessage("Colección restaurada del ${dayMonth(b.date)}") }
                        .onFailure { onMessage("No se pudo leer el respaldo") }
                }
            },
        )
    }
}

private const val MAX_CSV_BYTES = 1L shl 20

private fun dayMonth(d: LocalDate) = String.format(Locale.US, "%02d-%02d-%d", d.dayOfMonth, d.monthValue, d.year)

/** Respaldos diarios locales: elegir uno y confirmar antes de reemplazar la colección. */
@Composable
fun RestoreDialog(backups: List<Backup>, onDismiss: () -> Unit, onRestore: (Backup) -> Unit) {
    var chosen by remember { mutableStateOf<Backup?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restaurar respaldo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (backups.isEmpty()) {
                    Text("Aún no hay respaldos. Se guarda uno por día al modificar la colección.", color = muted)
                } else {
                    Text("Se guarda uno por día; se conservan los últimos 7.", fontSize = 13.sp, color = muted)
                    Spacer(Modifier.height(8.dp))
                    for (b in backups) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { chosen = b }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(dayMonth(b.date), Modifier.weight(1f), maxLines = 1)
                            Text("${b.cards} ${if (b.cards == 1) "carta" else "cartas"}", color = muted, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", maxLines = 1) } },
    )
    chosen?.let { b ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            text = { Text("¿Reemplazar tu colección por el respaldo del ${dayMonth(b.date)} (${b.cards} cartas)?") },
            confirmButton = { Button(onClick = { chosen = null; onRestore(b) }) { Text("Restaurar", maxLines = 1) } },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Cancelar", maxLines = 1) } },
        )
    }
}

private fun shareCsv(ctx: Context, entries: List<Entry>) {
    val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
    val f = File(dir, "coleccion_fftcg.csv")
    f.writeText("﻿" + Collection.toCsv(entries))
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Exportar colección"))
}

// ------------------------------------------------------------------ Buscar

@Composable
fun SearchScreen(onPick: (CardInfo) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val results = remember(q) { CardIndex.search(q) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (q.isNotBlank() && results.isEmpty()) Text("Sin resultados", color = MaterialTheme.colorScheme.onSurfaceVariant)
        CardList(results, onPick)
    }
}

/** Campo de búsqueda: lupa, botón para borrar, esquinas de 12dp y sin borde. */
@Composable
fun SearchField(q: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = q, onValueChange = onChange,
        placeholder = { Text("Código (1-001H) o nombre", maxLines = 1) },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (q.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(AppIcons.Close, contentDescription = "Borrar", tint = muted, modifier = Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

@Composable
fun CardList(cards: List<CardInfo>, onPick: (CardInfo) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(cards, key = { it.id }) { c -> CardItem(c) { onPick(c) } }
    }
}

/** Fila de resultado de búsqueda/escaneo; [note] es una línea apagada opcional (ej: "Coincide por nombre"). */
@Composable
fun CardItem(c: CardInfo, note: String? = null, onClick: () -> Unit) {
    // Precio principal: el normal; si solo hay foil, el foil con su etiqueta
    val normal = c.price(false)
    val foil = c.price(true)
    val shownFoil = normal == null && (foil != null || c.defaultFoil)
    CardRow(
        card = c,
        title = c.name,
        subtitle = "${c.code} · ${c.set}",
        foil = false,
        right = (normal ?: foil)?.let { usd(it) } ?: "Sin precio",
        rightTag = if (normal == null && foil != null) "Foil" else null,
        rightSub = if (normal != null && foil != null) "Foil ${usd(foil)}" else "",
        warning = c.finish(shownFoil) != null && !c.inStock(shownFoil),
        note = note,
        onClick = onClick,
    )
}

// ------------------------------------------------------------------ Escanear

@Composable
fun ScanScreen(onPick: (CardInfo, () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by rememberSaveable { mutableStateOf(false) } // ya se pidió y el usuario lo negó
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) denied = true
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // Al volver de los ajustes del sistema, revisar si ya lo dio
    LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    if (!granted) {
        // Negado "para siempre": el sistema ya no muestra el diálogo, hay que ir a los ajustes de la app
        val activity = ctx as? Activity
        val blocked = denied && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("El escáner necesita permiso de cámara.", textAlign = TextAlign.Center)
            if (blocked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Actívalo en Ajustes → Permisos → Cámara.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (blocked) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                } else {
                    ask.launch(Manifest.permission.CAMERA)
                }
            }) { Text("Dar permiso", maxLines = 1) }
        }
        return
    }

    var hit by remember { mutableStateOf<ScanHit?>(null) }
    var searching by remember { mutableStateOf(false) } // el usuario pidió buscar a mano ("¿No está? Buscar")
    var q by remember { mutableStateOf("") }
    val h = hit
    val result = remember(h) { h?.let { scanResult(it) } }
    val results = remember(q) { CardIndex.search(q) }
    // Código fuera del índice (o sin cartas): se ofrece buscar de inmediato
    val unknown = h != null && (!h.known || result?.byCode.isNullOrEmpty())
    val showSearch = searching || unknown
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus() } }

    fun reset() {
        hit = null
        searching = false
        q = ""
    }

    Column(Modifier.fillMaxSize()) {
        CameraScanner(
            paused = h != null,
            onCode = { hit = it; searching = false; q = "" },
            // Al buscar, la cámara (pausada) cede espacio a los resultados
            modifier = Modifier.fillMaxWidth().weight(if (showSearch) 0.5f else 1.5f),
        )
        Column(Modifier.fillMaxWidth().weight(1f).imePadding().padding(horizontal = 12.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            if (h == null || result == null) {
                Text(
                    "Encuadra la carta en el marco, con el código (ej: 1-001H) dentro de la zona punteada. " +
                        "Si no lo lee, acerca la cámara al código.",
                    Modifier.padding(vertical = 12.dp), color = muted,
                )
            } else {
                val code = result.byCode.firstOrNull { h.key in it.keys }?.code?.substringBefore('/') ?: h.key
                val n = result.byCode.size
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (unknown) "${h.key} no está en TCGplayer" else "$code · $n ${if (n == 1) "versión" else "versiones"}",
                        Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { reset() }) { Text("Escanear otra", maxLines = 1, softWrap = false) }
                }
                if (showSearch) SearchField(q, { q = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(focus))
                // Al agregar una carta que no trae este código (o elegida por nombre/búsqueda), se recuerda la asociación
                val pick: (CardInfo, Boolean) -> Unit = { c, learn ->
                    onPick(c) {
                        if (learn || h.key !in c.keys) CardIndex.remember(ctx, h.key, c.id)
                        reset()
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (q.isBlank()) {
                        if (unknown) item(key = "hint") {
                            Text(
                                if (result.byName.isEmpty()) "Búscala por nombre; la próxima vez se reconocerá sola."
                                else "Elige la carta o búscala; la próxima vez se reconocerá sola.",
                                fontSize = 13.sp, color = muted,
                            )
                        }
                        // Si el nombre leído no calza con ninguna carta de este código, el código puede estar mal
                        // publicado: las coincidencias por nombre van primero
                        val nameFirst = !result.codeMatchesName && result.byName.isNotEmpty()
                        if (nameFirst) items(result.byName, key = { "n${it.id}" }) { c -> CardItem(c, note = "Coincide por nombre") { pick(c, true) } }
                        items(result.byCode, key = { "c${it.id}" }) { c -> CardItem(c) { pick(c, false) } }
                        if (!nameFirst) items(result.byName, key = { "n${it.id}" }) { c -> CardItem(c, note = "Coincide por nombre") { pick(c, true) } }
                        if (!showSearch) item(key = "buscar") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { searching = true }) { Text("¿No está? Buscar", maxLines = 1, softWrap = false) }
                            }
                        }
                    } else {
                        if (results.isEmpty()) item(key = "vacio") { Text("Sin resultados", color = muted) }
                        items(results, key = { "s${it.id}" }) { c -> CardItem(c) { pick(c, true) } }
                    }
                }
            }
        }
    }
}

/** Candidatos de un escaneo: por código y, aparte, cartas cuyo nombre se leyó en la carta. */
private class ScanResult(val byCode: List<CardInfo>, val byName: List<CardInfo>, val codeMatchesName: Boolean)

private fun scanResult(hit: ScanHit): ScanResult {
    val pinned = CardIndex.overrideFor(hit.key)
    // La asociada por el usuario primero; luego las versiones cuyo nombre aparece en el texto leído
    val byCode = CardIndex.lookup(hit.key).distinctBy { it.id }.sortedBy {
        when {
            it.id == pinned -> 0
            CardIndex.nameInText(it, hit.text) -> 1
            else -> 2
        }
    }
    val byName = CardIndex.byNameInText(
        hit.text, limit = 5, prefix = hit.key.substringBefore('-'), exclude = byCode.map { it.id }.toSet(),
    )
    val codeMatchesName = byCode.any { it.id == pinned || CardIndex.nameInText(it, hit.text) }
    return ScanResult(byCode, byName, codeMatchesName)
}

// ------------------------------------------------------------------ Componentes

@Composable
fun CardRow(
    card: CardInfo?,
    title: String,
    subtitle: String,
    foil: Boolean,
    right: String,
    rightSub: String,
    onClick: () -> Unit,
    rightTag: String? = null,
    rightNote: String? = null,
    warning: Boolean = false,
    note: String? = null,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = card?.image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 48.dp, height = 67.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (foil) Text("✦ Foil", fontSize = 12.sp, color = GOLD)
                if (warning) Text(NO_STOCK, fontSize = 12.sp, color = AMBER, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note != null) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rightTag != null) {
                        Text(rightTag, fontSize = 11.sp, color = GOLD)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(right, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (rightSub.isNotEmpty()) Text(rightSub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (rightNote != null) Text(rightNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

private val AMBER = Color(0xFFF59E0B)
private const val NO_STOCK = "Sin stock en TCGplayer"

/** Contenido desplazable del detalle: un bloque centrado de ancho acotado donde imagen y texto comparten borde. */
@Composable
fun DetailBlock(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 256.dp).fillMaxWidth(), content = content)
    }
}

/** Imagen a todo el ancho con la proporción de la carta (63×88); Crop recorta el margen blanco de TCGplayer. */
@Composable
fun BigCard(card: CardInfo?) {
    AsyncImage(
        model = card?.image, contentDescription = card?.name, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(63f / 88f).clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(72.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontSize = 13.sp)
    }
}

@Composable
fun CardInfoRows(card: CardInfo?, finish: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InfoRow("Código", card?.code ?: "—")
        InfoRow("Set", card?.set ?: "—")
        InfoRow("Rareza", card?.rarity?.takeIf { it.isNotEmpty() } ?: "—")
        if (finish != null) InfoRow("Terminación", finish)
    }
}

/** Avisos de la terminación elegida: sin ventas recientes y/o sin stock. */
@Composable
fun PriceNotes(card: CardInfo, foil: Boolean) {
    val f = card.finish(foil) ?: return
    if (f.market == null && f.low != null) {
        Text("Sin ventas recientes; precio publicado más bajo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!card.inStock(foil)) Text(NO_STOCK, fontSize = 12.sp, color = AMBER)
}

@Composable
fun LinkButtons(card: CardInfo, foil: Boolean) {
    val ctx = LocalContext.current
    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, url) in listOf("Últimas ventas en TCGplayer" to card.url, "Vendidos en eBay" to card.ebayUrl(foil))) {
            OutlinedButton(
                onClick = { open(url) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Selector de copias: un contenedor redondeado con tres celdas iguales (−, número, +). */
@Composable
fun QtyPicker(qty: Int, onQty: (Int) -> Unit, min: Int = 1) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.height(48.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepCell("−", enabled = qty > min) { onQty(qty - 1) }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text("$qty", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
        }
        StepCell("+", enabled = true) { onQty(qty + 1) }
    }
}

@Composable
private fun StepCell(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 22.sp, color = if (enabled) color else color.copy(alpha = 0.38f))
    }
}

@Composable
fun AddDialog(card: CardInfo, fx: Double, onDismiss: () -> Unit, onAdd: (Boolean, Int) -> Unit) {
    var foil by remember { mutableStateOf(card.defaultFoil) }
    var qty by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name) },
        text = {
            DetailBlock {
                BigCard(card)
                Spacer(Modifier.height(16.dp))
                CardInfoRows(card)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Normal ${usd(card.price(false))} · Foil ${usd(card.price(true))}", fontSize = 13.sp)
                    PriceNotes(card, foil)
                    if (card.price(foil) == null) {
                        Text(
                            "Esta versión no tiene precio en ${if (foil) "foil" else "normal"} en TCGplayer",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    card.price(foil)?.let { Text("${clp(it * qty * fx)} por ${qty}", fontSize = 13.sp, color = GOLD) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Foil", Modifier.weight(1f))
                    Switch(checked = foil, onCheckedChange = { foil = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copias", Modifier.weight(1f))
                    QtyPicker(qty, { qty = it })
                }
                Spacer(Modifier.height(16.dp))
                LinkButtons(card, foil)
            }
        },
        confirmButton = { Button(onClick = { onAdd(foil, qty) }) { Text("Agregar", maxLines = 1) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", maxLines = 1) } },
    )
}

@Composable
fun EntryDialog(
    entry: Entry,
    card: CardInfo?,
    fx: Double,
    onDismiss: () -> Unit,
    onQty: (Int) -> Unit,
    onManual: (Double?) -> Unit,
    onRemove: () -> Unit,
) {
    val market = card?.price(entry.foil)
    val unit = entry.unit(card)
    val name = card?.name ?: "Carta ${entry.id}"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            DetailBlock {
                BigCard(card)
                Spacer(Modifier.height(16.dp))
                CardInfoRows(card, finish = if (entry.foil) "Foil" else "Normal")
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.manual != null) {
                        Text("Precio manual: ${usd(entry.manual)} c/u", fontSize = 13.sp)
                        Text("Mercado TCGplayer: ${usd(market)} c/u", fontSize = 12.sp, color = muted)
                    } else {
                        Text("Precio de mercado: ${usd(market)} c/u", fontSize = 13.sp)
                        if (card != null) PriceNotes(card, entry.foil)
                    }
                    unit?.let { Text("Total: ${usd(it * entry.qty)} · ${clp(it * entry.qty * fx)}", fontSize = 13.sp, color = GOLD) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copias", Modifier.weight(1f))
                    QtyPicker(entry.qty, onQty)
                }
                Spacer(Modifier.height(16.dp))
                ManualPrice(manual = entry.manual, hasMarket = market != null, onManual = onManual)
                if (card != null) {
                    Spacer(Modifier.height(16.dp))
                    LinkButtons(card, entry.foil)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { confirmRemove = true }) {
                        Text("Quitar de la colección", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", maxLines = 1) } },
    )

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text("¿Quitar $name${if (entry.foil) " (foil)" else ""}?") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text("Quitar", color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar", maxLines = 1) } },
        )
    }
}

/** Precio por copia anotado a mano (ej: última venta), para cartas sin precio en TCGplayer o para reemplazarlo. */
@Composable
fun ManualPrice(manual: Double?, hasMarket: Boolean, onManual: (Double?) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember(manual) { mutableStateOf(manual?.let { plain(it) } ?: "") }
    val parsed = Collection.parsePrice(text) // finito y 0 < p ≤ 100.000; acepta "12,5" y "12.5"
    val canSave = parsed != null && parsed != manual
    fun save() {
        if (!canSave) return
        focus.clearFocus()
        onManual(parsed)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Precio manual (US$)")
        Text(
            if (hasMarket) "Opcional: reemplaza el precio de mercado"
            else "TCGplayer no tiene precio para esta carta. Revisa la última venta en TCGplayer o eBay y anótala aquí.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("ej: 999", maxLines = 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { save() }, enabled = canSave,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Guardar", maxLines = 1, softWrap = false) }
        }
        if (manual != null) {
            TextButton(onClick = { focus.clearFocus(); onManual(null) }) {
                Text("Quitar precio manual", maxLines = 1, softWrap = false)
            }
        }
    }
}

/** 999.0 -> "999", 12.5 -> "12.5": sin decimales sobrantes en el campo. */
private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@Composable
fun FxDialog(current: Double, onDismiss: () -> Unit, onSet: (Double?) -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", current)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tipo de cambio") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("CLP por 1 USD") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            Button(onClick = { text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let(onSet) }) { Text("Fijar", maxLines = 1) }
        },
        dismissButton = { TextButton(onClick = { onSet(null) }) { Text("Usar dólar del día", maxLines = 1) } },
    )
}

// ------------------------------------------------------------------ Actualización

/** Aviso delgado sobre la colección cuando hay una versión nueva. */
@Composable
fun UpdateBanner(update: Update, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Nueva versión ${update.version} disponible",
                Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClick) { Text("Actualizar", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun UpdateDialog(update: Update, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Updater.installedVersion(ctx) }
    var progress by remember { mutableStateOf<Float?>(null) } // null = sin descarga en curso
    var file by remember { mutableStateOf<File?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloading = progress != null && file == null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun install(f: File) {
        runCatching { Updater.install(ctx, f) }
            .onSuccess { needsPermission = !it }
            .onFailure { error = "No se pudo abrir el instalador" }
    }

    /** Reusar una descarga: se vuelve a verificar (fuera del hilo principal) justo antes de instalar. */
    fun verifyAndInstall(f: File) {
        error = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Updater.verifyApk(ctx, f, update.code.toLong()) }
                    .onFailure { f.delete() }
                    .isSuccess
            }
            if (ok) {
                install(f)
            } else {
                file = null
                progress = null
                error = Updater.NOT_VERIFIED
            }
        }
    }

    fun download() {
        error = null
        progress = 0f
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // El avance llega desde el hilo de IO: se aplica en el hilo principal
                    Updater.download(ctx, update) { p -> scope.launch { if (file == null && progress != null) progress = p } }
                }
            }
            r.onSuccess { file = it; install(it) }
                .onFailure {
                    progress = null
                    error = if (it is VerificationException) Updater.NOT_VERIFIED else "Falló la descarga (${errorReason(it)})"
                }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Actualizar a ${update.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val mb = String.format(Locale.forLanguageTag("es-CL"), "%.1f", update.size / 1_048_576.0)
                Text("Tienes $installed" + if (update.size > 0) " · descarga de $mb MB" else "", fontSize = 14.sp, color = muted)
                val p = progress
                if (downloading && p != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Descargando… ${(p * 100).toInt()} %", fontSize = 13.sp, color = muted)
                }
                if (file != null) {
                    Text(
                        if (needsPermission) "Permite instalar apps desde FFTCG Valor y vuelve a tocar Instalar"
                        else "Descarga lista. Confirma en el instalador; tu colección se mantiene.",
                        fontSize = 14.sp,
                    )
                }
                error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val f = file
            if (f != null) {
                Button(onClick = { verifyAndInstall(f) }) { Text("Instalar", maxLines = 1) }
            } else {
                Button(onClick = { download() }, enabled = !downloading) { Text("Descargar", maxLines = 1) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text(if (file != null) "Cerrar" else "Ahora no", maxLines = 1) }
        },
    )
}
