@file:OptIn(ExperimentalMaterial3Api::class)

package cl.guillecc.fftcg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Una línea de la bandeja del modo Lote. [cardId] null = falta elegir la versión o, si [unknown]
 * (el código no está en el índice), buscar la carta.
 */
data class TrayItem(
    val uid: Long,
    val hit: ScanHit,
    val cardId: Int?,
    val foil: Boolean,
    val qty: Int,
    val unknown: Boolean,
)

/** Bandeja del modo Lote, la más reciente primero. Vive en AppScreen: sobrevive al cambio de pestaña. */
class BatchTray {
    val items = mutableStateListOf<TrayItem>()

    /** Última suma automática: (uid, copias que tenía antes; 0 = era nueva). Cualquier cambio a mano la anula. */
    var undo by mutableStateOf<Pair<Long, Int>?>(null)
        private set
    private var nextUid = 0L

    private fun index(uid: Long) = items.indexOfFirst { it.uid == uid }

    /**
     * Suma un escaneo: [card] = carta clara (se agrega con 1 copia y la terminación por defecto), null = por
     * resolver. Si ya está en la bandeja (misma carta, o mismo código sin resolver), suma 1 y la sube arriba.
     */
    fun add(hit: ScanHit, card: CardInfo?, unknown: Boolean) {
        val i = if (card != null) items.indexOfFirst { it.cardId == card.id }
        else items.indexOfFirst { it.cardId == null && it.hit.key == hit.key }
        if (i >= 0) {
            val old = items.removeAt(i)
            items.add(0, old.copy(qty = (old.qty + 1).coerceAtMost(Collection.MAX_QTY)))
            undo = old.uid to old.qty
        } else {
            val item = TrayItem(nextUid++, hit, card?.id, card?.defaultFoil ?: false, 1, unknown)
            items.add(0, item)
            undo = item.uid to 0
        }
    }

    fun undoLast() {
        val (uid, before) = undo ?: return
        undo = null
        val i = index(uid)
        if (i < 0) return
        if (before == 0) items.removeAt(i) else items[i] = items[i].copy(qty = before)
    }

    fun setFoil(uid: Long, foil: Boolean) = edit(uid) { it.copy(foil = foil) }
    fun setQty(uid: Long, qty: Int) = edit(uid) { it.copy(qty = qty.coerceIn(1, Collection.MAX_QTY)) }

    private fun edit(uid: Long, f: (TrayItem) -> TrayItem) {
        undo = null
        val i = index(uid)
        if (i >= 0) items[i] = f(items[i])
    }

    fun remove(uid: Long) {
        undo = null
        val i = index(uid)
        if (i >= 0) items.removeAt(i)
    }

    /** Asigna la carta elegida a una línea por resolver; si esa carta ya está en la bandeja, junta las copias. */
    fun resolve(uid: Long, card: CardInfo) {
        undo = null
        val i = index(uid)
        if (i < 0) return
        val item = items[i]
        val j = items.indexOfFirst { it.uid != uid && it.cardId == card.id && it.foil == card.defaultFoil }
        if (j >= 0) {
            items[j] = items[j].copy(qty = (items[j].qty + item.qty).coerceAtMost(Collection.MAX_QTY))
            items.removeAt(i)
        } else {
            items[i] = item.copy(cardId = card.id, foil = card.defaultFoil, unknown = false)
        }
    }

    fun clear() {
        undo = null
        items.clear()
    }

    /** Saca de la bandeja las líneas resueltas (las por resolver se quedan) y las devuelve como líneas de colección. */
    fun takeResolved(): List<Entry> {
        val done = items.mapNotNull { t -> t.cardId?.let { Entry(it, t.foil, t.qty) } }
        items.removeAll { it.cardId != null }
        undo = null
        return done
    }
}

private fun cards(n: Int) = if (n == 1) "1 carta" else "$n cartas"

/** Selector "Una carta" | "Lote" sobre el escáner. */
@Composable
fun ScanModeSwitch(batch: Boolean, onBatch: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf("Una carta", "Lote").forEachIndexed { i, label ->
            SegmentedButton(
                selected = batch == (i == 1),
                onClick = { onBatch(i == 1) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                icon = {},
                modifier = Modifier.weight(1f),
            ) { Text(label, maxLines = 1, softWrap = false) }
        }
    }
}

/** Bandeja bajo la cámara: total, Vaciar/Guardar y las cartas escaneadas (la más reciente arriba). */
@Composable
fun BatchPanel(tray: BatchTray, onSave: () -> Unit, onOpen: (TrayItem) -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmClear by remember { mutableStateOf(false) }
    val list = tray.items
    val resolved = list.filter { it.cardId != null }
    val count = resolved.sumOf { it.qty }
    val total = resolved.sumOf { t -> (t.cardId?.let { CardIndex.byId(it) }?.price(t.foil) ?: 0.0) * t.qty }
    val pending = list.count { it.cardId == null }
    val undo = tray.undo

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(cards(count), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    usd(total) + if (pending > 0) " · $pending por resolver" else "",
                    fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { confirmClear = true }, enabled = list.isNotEmpty()) {
                Text("Vaciar", maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave, enabled = count > 0) { Text("Guardar", maxLines = 1, softWrap = false) }
        }
        if (list.isEmpty()) {
            Text(
                "Escanea cartas seguidas: cada una se suma aquí con 1 copia. Las que tienen varias versiones " +
                    "quedan para elegir. Toca Guardar al terminar.",
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, color = muted,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.uid }) { t ->
                    val canUndo = undo?.first == t.uid
                    val id = t.cardId
                    if (id == null) {
                        PendingRow(t, canUndo, onOpen = { onOpen(t) }, onUndo = { tray.undoLast() }, onRemove = { tray.remove(t.uid) })
                    } else {
                        TrayRow(
                            t, CardIndex.byId(id), canUndo,
                            onFoil = { tray.setFoil(t.uid, !t.foil) },
                            onQty = { tray.setQty(t.uid, it) },
                            onUndo = { tray.undoLast() },
                            onRemove = { tray.remove(t.uid) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        val n = list.sumOf { it.qty }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text("¿Vaciar la bandeja? Se descartan ${cards(n)} sin guardar.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; tray.clear() }) {
                    Text("Vaciar", color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar", maxLines = 1) } },
        )
    }
}

/** Miniatura de 40dp con la proporción de la carta. */
@Composable
private fun Thumb(card: CardInfo?) {
    AsyncImage(
        model = card?.image, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun UndoButton(onUndo: () -> Unit) {
    TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("Deshacer", maxLines = 1, softWrap = false)
    }
}

/** Carta resuelta: nombre y precio; código · set (y Deshacer si es la última sumada); Foil, copias y quitar. */
@Composable
private fun TrayRow(
    t: TrayItem,
    card: CardInfo?,
    canUndo: Boolean,
    onFoil: () -> Unit,
    onQty: (Int) -> Unit,
    onUndo: () -> Unit,
    onRemove: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val unit = card?.price(t.foil)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 4.dp)) {
            Thumb(card)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card?.name ?: "Carta ${t.cardId}", Modifier.weight(1f),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(unit?.let { usd(it * t.qty) } ?: "Sin precio", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${baseCode(card?.code.orEmpty())} · ${card?.set.orEmpty()}", Modifier.weight(1f),
                        fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (canUndo) UndoButton(onUndo)
                }
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = t.foil, onClick = onFoil, label = { Text("Foil", maxLines = 1) })
                    Spacer(Modifier.weight(1f))
                    MiniQty(t.qty, onQty)
                    IconButton(onClick = onRemove) {
                        Icon(AppIcons.Close, contentDescription = "Quitar", tint = muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Escaneo sin carta asignada: varias versiones ("Elegir versión") o código que no está en el índice. */
@Composable
private fun PendingRow(t: TrayItem, canUndo: Boolean, onOpen: () -> Unit, onUndo: () -> Unit, onRemove: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val versions = if (t.unknown) 0 else CardIndex.lookup(t.hit.key).size
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text("?", fontSize = 18.sp, color = muted) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (t.unknown) "${t.hit.key} no está" else "Elegir versión",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (t.unknown) "Toca para buscarla" else "${t.hit.key} · $versions versiones") +
                        if (t.qty > 1) " · ×${t.qty}" else "",
                    fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (canUndo) UndoButton(onUndo)
            IconButton(onClick = onRemove) {
                Icon(AppIcons.Close, contentDescription = "Quitar", tint = muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Copias en la bandeja: − número +, con círculos de 32dp y área táctil de 48dp. */
@Composable
private fun MiniQty(qty: Int, onQty: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiniStep("−", "Quitar una copia", enabled = qty > 1) { onQty(qty - 1) }
        Text(
            "$qty", Modifier.widthIn(min = 24.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1,
        )
        MiniStep("+", "Sumar una copia", enabled = qty < Collection.MAX_QTY) { onQty(qty + 1) }
    }
}

@Composable
private fun MiniStep(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = 18.sp, color = if (enabled) color else color.copy(alpha = 0.38f)) }
    }
}

/**
 * Hoja para elegir la versión de un escaneo ambiguo. [options]: carta y si al elegirla hay que recordar
 * la asociación (coincidencias por nombre), en el mismo orden que el modo Una carta.
 */
@Composable
fun VersionSheet(
    code: String,
    versions: Int,
    options: List<Pair<CardInfo, Boolean>>,
    onPick: (CardInfo, Boolean) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Text("Elegir versión", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            "$code · $versions ${if (versions == 1) "versión" else "versiones"}",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(options, key = { (c, byName) -> (if (byName) "n" else "c") + c.id }) { (c, byName) ->
                CardItem(c, note = if (byName) "Coincide por nombre" else null) { onPick(c, byName) }
            }
            item(key = "buscar") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onSearch) { Text("¿No está? Buscar", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
