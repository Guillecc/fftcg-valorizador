package cl.guillecc.fftcg

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<CardInfo?>(null) }
    var onAdded by remember { mutableStateOf<() -> Unit>({}) }

    fun persist() {
        val copy = entries.toList()
        scope.launch(Dispatchers.IO) { Collection.save(ctx, copy) }
    }

    fun replace(list: List<Entry>) {
        entries.clear()
        entries.addAll(list)
        persist()
    }

    suspend fun refresh(force: Boolean) {
        val err = withContext(Dispatchers.IO) { runCatching { CardIndex.refreshIfStale(ctx, force) }.exceptionOrNull() }
        indexVersion++
        if (err != null) snack.showSnackbar("No se pudieron descargar los precios (${err.message})")
        withContext(Dispatchers.IO) { runCatching { Fx.refreshIfStale(ctx) } }
        fx = Fx.current(ctx)
    }

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) {
            CardIndex.load(ctx)
            runCatching { Collection.load(ctx) }.getOrDefault(emptyList())
        }
        entries.addAll(saved)
        indexVersion++
        loading = false
        refresh(force = false)
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
                    Text("Descargando cartas y precios de TCGplayer…")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { scope.launch { refresh(force = true) } }) { Text("Reintentar", maxLines = 1) }
                }
                tab == 0 -> CollectionScreen(
                    entries = entries,
                    version = version,
                    fx = fx,
                    onChange = { replace(it) },
                    onFxChange = { Fx.setManual(ctx, it); fx = Fx.current(ctx) },
                    onRefresh = { scope.launch { refresh(force = true); snack.showSnackbar("Precios al ${CardIndex.updated}") } },
                    onMessage = { scope.launch { snack.showSnackbar(it) } },
                )
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
}

// ------------------------------------------------------------------ Colección

@Composable
fun CollectionScreen(
    entries: List<Entry>,
    version: Int,
    fx: Double,
    onChange: (List<Entry>) -> Unit,
    onFxChange: (Double?) -> Unit,
    onRefresh: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val rows = remember(entries.toList(), version) {
        entries.map { it to CardIndex.byId(it.id) }
            .sortedByDescending { (e, c) -> c?.price(e.foil)?.times(e.qty) ?: -1.0 }
    }
    val totalUsd = rows.sumOf { (e, c) -> (c?.price(e.foil) ?: 0.0) * e.qty }
    val missing = rows.count { (e, c) -> c?.price(e.foil) == null }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var editFx by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        if (text == null) { onMessage("No se pudo leer el archivo"); return@rememberLauncherForActivityResult }
        val (ok, failed) = Collection.fromCsv(text)
        onChange(ok.fold(entries.toList()) { acc, e -> Collection.add(acc, e.id, e.foil, e.qty) })
        onMessage("Importadas ${ok.size} líneas" + if (failed.isEmpty()) "" else ". Sin match: ${failed.joinToString(", ")}")
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Valor de la colección", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(usd(totalUsd), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(clp(totalUsd * fx), fontSize = 20.sp, color = GOLD)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${entries.sumOf { it.qty }} cartas · ${if (missing > 0) "$missing sin precio · " else ""}" +
                                "precios TCGplayer al ${CardIndex.updated}",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "1 USD = ${clp(fx)}${if (Fx.manual(ctx) != null) " (fijo)" else " (dólar observado)"} · cambiar",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { editFx = true }.padding(vertical = 4.dp),
                        )
                    }
                    // Acciones en un menú para que no se amontonen en pantallas angostas
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
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
                                text = { Text("Actualizar precios", maxLines = 1) },
                                onClick = { menu = false; onRefresh() },
                            )
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
            val unit = c?.price(e.foil)
            CardRow(
                card = c,
                title = (if (unit != null && i < 10) "#${i + 1} " else "") + (c?.name ?: "Carta ${e.id}"),
                subtitle = "${c?.code.orEmpty()} · ${c?.set.orEmpty()}",
                foil = e.foil,
                right = usd(unit?.times(e.qty)),
                rightSub = "×${e.qty} · ${usd(unit)}",
                warning = c != null && c.finish(e.foil) != null && !c.inStock(e.foil),
                onClick = { detail = e },
            )
        }
    }

    detail?.let { e ->
        val c = CardIndex.byId(e.id)
        EntryDialog(
            entry = e, card = c, fx = fx,
            onDismiss = { detail = null },
            onQty = { q ->
                val list = entries.toMutableList()
                val i = list.indexOf(e)
                if (i >= 0) {
                    if (q <= 0) list.removeAt(i) else list[i] = e.copy(qty = q)
                    onChange(list)
                }
                detail = if (q <= 0) null else e.copy(qty = q)
            },
        )
    }
    if (editFx) FxDialog(current = fx, onDismiss = { editFx = false }, onSet = { onFxChange(it); editFx = false })
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
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("El escáner necesita permiso de cámara.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso", maxLines = 1) }
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
fun EntryDialog(entry: Entry, card: CardInfo?, fx: Double, onDismiss: () -> Unit, onQty: (Int) -> Unit) {
    val unit = card?.price(entry.foil)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card?.name ?: "Carta ${entry.id}") },
        text = {
            DetailBlock {
                BigCard(card)
                Spacer(Modifier.height(16.dp))
                CardInfoRows(card, finish = if (entry.foil) "Foil" else "Normal")
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Precio de mercado: ${usd(unit)} c/u", fontSize = 13.sp)
                    if (card != null) PriceNotes(card, entry.foil)
                    unit?.let { Text("Total: ${usd(it * entry.qty)} · ${clp(it * entry.qty * fx)}", fontSize = 13.sp, color = GOLD) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copias", Modifier.weight(1f))
                    QtyPicker(entry.qty, onQty, min = 0)
                }
                Spacer(Modifier.height(4.dp))
                Text("En 0 se quita de la colección.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (card != null) {
                    Spacer(Modifier.height(16.dp))
                    LinkButtons(card, entry.foil)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", maxLines = 1) } },
    )
}

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
