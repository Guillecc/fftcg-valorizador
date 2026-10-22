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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
                    OutlinedButton(onClick = { scope.launch { refresh(force = true) } }) { Text("Reintentar") }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Importar CSV") }
                        TextButton(onClick = { shareCsv(ctx, entries) }, enabled = entries.isNotEmpty()) { Text("Exportar CSV") }
                        TextButton(onClick = onRefresh) { Text("Actualizar") }
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
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            label = { Text("Código (1-001H) o nombre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (q.isNotBlank() && results.isEmpty()) Text("Sin resultados", color = MaterialTheme.colorScheme.onSurfaceVariant)
        CardList(results, onPick)
    }
}

@Composable
fun CardList(cards: List<CardInfo>, onPick: (CardInfo) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(cards, key = { it.id }) { c ->
            CardRow(
                card = c,
                title = c.name,
                subtitle = "${c.code} · ${c.set}",
                foil = false,
                right = usd(c.normal),
                rightSub = "Foil ${usd(c.foil)}",
                onClick = { onPick(c) },
            )
        }
    }
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
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso") }
        }
        return
    }

    var found by remember { mutableStateOf<List<CardInfo>>(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        CameraScanner(
            paused = found.isNotEmpty(),
            onCode = { key -> found = CardIndex.lookup(key) },
            modifier = Modifier.fillMaxWidth().weight(1.5f),
        )
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
            if (found.isEmpty()) {
                Text(
                    "Encuadra la carta en el marco, con el código (ej: 1-001H) dentro de la zona punteada. " +
                        "Si no lo lee, acerca la cámara al código.",
                    Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${found.first().code.substringBefore('/')}: elige la versión",
                        Modifier.weight(1f), fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { found = emptyList() }) { Text("Seguir escaneando") }
                }
                CardList(found) { onPick(it) { found = emptyList() } }
            }
        }
    }
}

// ------------------------------------------------------------------ Componentes

@Composable
fun CardRow(card: CardInfo?, title: String, subtitle: String, foil: Boolean, right: String, rightSub: String, onClick: () -> Unit) {
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
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(right, fontWeight = FontWeight.Bold)
                Text(rightSub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BigCard(card: CardInfo?) {
    AsyncImage(
        model = card?.image, contentDescription = card?.name, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(260.dp),
    )
}

@Composable
fun QtyPicker(qty: Int, onQty: (Int) -> Unit, min: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { if (qty > min) onQty(qty - 1) else if (min == 0) onQty(0) }) { Text("−") }
        Text("$qty", Modifier.padding(horizontal = 16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onQty(qty + 1) }) { Text("+") }
    }
}

@Composable
fun AddDialog(card: CardInfo, fx: Double, onDismiss: () -> Unit, onAdd: (Boolean, Int) -> Unit) {
    var foil by remember { mutableStateOf(card.normal == null && card.foil != null) }
    var qty by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name) },
        text = {
            Column {
                BigCard(card)
                Text("${card.code} · ${card.set} · ${card.rarity}", fontSize = 13.sp)
                Text("Normal ${usd(card.normal)} · Foil ${usd(card.foil)}", fontSize = 13.sp)
                card.price(foil)?.let { Text("${clp(it * qty * fx)} por ${qty}", fontSize = 13.sp, color = GOLD) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Foil", Modifier.weight(1f))
                    Switch(checked = foil, onCheckedChange = { foil = it })
                }
                QtyPicker(qty, { qty = it })
            }
        },
        confirmButton = { Button(onClick = { onAdd(foil, qty) }) { Text("Agregar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EntryDialog(entry: Entry, card: CardInfo?, fx: Double, onDismiss: () -> Unit, onQty: (Int) -> Unit) {
    val ctx = LocalContext.current
    val unit = card?.price(entry.foil)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card?.name ?: "Carta ${entry.id}") },
        text = {
            Column {
                BigCard(card)
                Text("${card?.code.orEmpty()} · ${card?.set.orEmpty()} · ${card?.rarity.orEmpty()}${if (entry.foil) " · Foil" else ""}", fontSize = 13.sp)
                Text("Precio de mercado: ${usd(unit)} c/u", fontSize = 13.sp)
                unit?.let { Text("Total: ${usd(it * entry.qty)} · ${clp(it * entry.qty * fx)}", fontSize = 13.sp, color = GOLD) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                QtyPicker(entry.qty, onQty, min = 0)
                Text("En 0 se quita de la colección.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { card?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.url))) } }, enabled = card != null) {
                Text("Últimas ventas")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
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
            Button(onClick = { text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let(onSet) }) { Text("Fijar") }
        },
        dismissButton = { TextButton(onClick = { onSet(null) }) { Text("Usar dólar del día") } },
    )
}
