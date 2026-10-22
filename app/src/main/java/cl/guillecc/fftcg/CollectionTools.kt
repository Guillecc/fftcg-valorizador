package cl.guillecc.fftcg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Una línea de la colección con su carta del índice (null si ya no está en el índice). */
typealias ColLine = Pair<Entry, CardInfo?>

val TrendUp = Color(0xFF5BD47A)
val TrendDown = Color(0xFFFF7A7A)

/** Orden de la lista; [key] es lo que se guarda en prefs (estable aunque R8 renombre el enum). */
enum class SortBy(val key: String, val label: String) {
    TOTAL("total", "Valor total ↓"),
    UNIT("unit", "Precio unitario ↓"),
    CHANGE7("change7", "Cambio 7 días"),
    NAME("name", "Nombre A–Z"),
    CODE("code", "Código"),
    SET("set", "Set");

    companion object {
        fun of(key: String?): SortBy = values().firstOrNull { it.key == key } ?: TOTAL
    }
}

/** Elementos de FFTCG: valor en el índice y nombre en pantalla. */
val ELEMENTS = listOf(
    "Fire" to "Fuego", "Ice" to "Hielo", "Wind" to "Viento", "Earth" to "Tierra",
    "Lightning" to "Rayo", "Water" to "Agua", "Light" to "Luz", "Dark" to "Oscuridad",
)

private val FINISHES = listOf("Todas", "Normal", "Foil")

fun CardInfo.elementList(): List<String> =
    element?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** Filtros de la colección (no se guardan). [finish]: 0 todas, 1 normal, 2 foil. */
data class ColFilter(
    val finish: Int = 0,
    val elements: Set<String> = emptySet(),
    val rarities: Set<String> = emptySet(),
    val sets: Set<Int> = emptySet(),
) {
    val count get() = (if (finish != 0) 1 else 0) + elements.size + rarities.size + sets.size
    val active get() = count > 0

    fun matches(e: Entry, c: CardInfo?): Boolean {
        if (finish == 1 && e.foil) return false
        if (finish == 2 && !e.foil) return false
        if (elements.isNotEmpty() && (c == null || c.elementList().none { it in elements })) return false
        if (rarities.isNotEmpty() && (c == null || c.rarity !in rarities)) return false
        if (sets.isNotEmpty() && (c == null || c.groupId !in sets)) return false
        return true
    }
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

/** Cambio de precio de la línea; las de precio manual no tienen tendencia. */
fun entryChange(e: Entry, c: CardInfo?, days: Int): Double? = if (e.manual != null) null else change(c, e.foil, days)

private val CODE_PARTS = Regex("^([A-Z0-9]+)-+0*(\\d+)")

/** "1-001H" < "2-001H" < "10-001H" < "PR-001": prefijo numérico, luego texto, luego número. */
private fun codeOrder(c: CardInfo?): Triple<Int, String, Int> {
    val code = c?.code?.let { baseCode(it).uppercase() } ?: return Triple(Int.MAX_VALUE, "", 0)
    val m = CODE_PARTS.find(code) ?: return Triple(Int.MAX_VALUE - 1, code, 0)
    val p = m.groupValues[1]
    return Triple(p.toIntOrNull() ?: 10_000, p, m.groupValues[2].toIntOrNull() ?: 0)
}

private val CODE_CMP: Comparator<ColLine> = compareBy<ColLine>(
    { codeOrder(it.second).first },
    { codeOrder(it.second).second },
    { codeOrder(it.second).third },
)

fun sortLines(lines: List<ColLine>, sort: SortBy): List<ColLine> = when (sort) {
    SortBy.TOTAL -> lines.sortedByDescending { (e, c) -> e.unit(c)?.times(e.qty) ?: -1.0 }
    SortBy.UNIT -> lines.sortedByDescending { (e, c) -> e.unit(c) ?: -1.0 }
    // Más subieron primero; sin datos al final
    SortBy.CHANGE7 -> lines.sortedWith(
        compareBy<ColLine>({ entryChange(it.first, it.second, 7) == null }, { -(entryChange(it.first, it.second, 7) ?: 0.0) })
    )
    SortBy.NAME -> lines.sortedWith(
        compareBy<ColLine>({ it.second == null }, { it.second?.name?.lowercase().orEmpty() }).then(CODE_CMP)
            .thenBy { it.first.foil }
    )
    SortBy.CODE -> lines.sortedWith(CODE_CMP.thenBy { it.second?.name?.lowercase().orEmpty() }.thenBy { it.first.foil })
    SortBy.SET -> lines.sortedWith(
        compareBy<ColLine>({ it.second == null }, { it.second?.set?.lowercase().orEmpty() }).then(CODE_CMP)
            .thenBy { it.first.foil }
    )
}

/** Valor de mercado de las mismas líneas hoy y hace [days] días (solo las que tienen ambos precios). */
class ValueChange(val now: Double, val past: Double) {
    val diff get() = now - past
    val pct get() = diff / past
}

fun collectionChange(lines: List<ColLine>, days: Int): ValueChange? {
    var now = 0.0
    var past = 0.0
    var n = 0
    for ((e, c) in lines) {
        if (e.manual != null || c == null) continue
        val cur = c.finish(e.foil)?.market ?: continue
        val old = c.past(e.foil, days) ?: continue
        now += cur * e.qty
        past += old * e.qty
        n++
    }
    return if (n > 0 && past > 0) ValueChange(now, past) else null
}

/** Un set en la vista agrupada: líneas visibles, subtotal y completitud (códigos distintos que tienes / del set). */
class SetGroup(val groupId: Int, val name: String, val lines: List<ColLine>, val total: Double, val owned: Int, val size: Int)

/** Agrupa [visible] por set (orden: subtotal ↓). La completitud se cuenta sobre toda la colección ([all]). */
fun groupBySet(visible: List<ColLine>, all: List<ColLine>): List<SetGroup> {
    val owned = all.mapNotNull { it.second }.groupBy { it.groupId }
        .mapValues { (_, cs) -> cs.map { baseCode(it.code) }.filter { it.isNotEmpty() }.toSet().size }
    return visible.groupBy { it.second?.groupId ?: -1 }.map { (gid, ls) ->
        SetGroup(
            groupId = gid,
            name = ls.firstNotNullOfOrNull { l -> l.second?.set?.takeIf { it.isNotEmpty() } } ?: "Sin set",
            lines = ls,
            total = ls.sumOf { (e, c) -> (e.unit(c) ?: 0.0) * e.qty },
            owned = if (gid >= 0) owned[gid] ?: 0 else 0,
            size = if (gid >= 0) CardIndex.setSize(gid) else 0,
        )
    }.sortedByDescending { it.total }
}

/** "▲ 12%" / "▼ 5%"; null si no hay dato o el cambio es menor a 1 %. */
fun trendLabel(d: Double?): String? {
    if (d == null || abs(d) < 0.01) return null
    return (if (d > 0) "▲ " else "▼ ") + "${(abs(d) * 100).roundToInt()}%"
}

/** "2026-10-15" -> "15-10". */
private fun shortDate(iso: String?): String? =
    iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.let { String.format(Locale.US, "%02d-%02d", it.dayOfMonth, it.monthValue) }

// ------------------------------------------------------------------ Componentes

/** Línea del total: "▲ US$ 12.40 (3.1%) vs hace 7 días (15-10)". */
@Composable
fun ValueChangeText(ch: ValueChange, date: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val flat = abs(ch.pct) < 0.0005
    val color = when {
        flat -> muted
        ch.diff > 0 -> TrendUp
        else -> TrendDown
    }
    val arrow = when {
        flat -> "="
        ch.diff > 0 -> "▲"
        else -> "▼"
    }
    val main = "$arrow ${usd(abs(ch.diff))} (${String.format(Locale.US, "%.1f%%", abs(ch.pct) * 100)})"
    val rest = " vs hace 7 días" + (shortDate(date)?.let { " ($it)" } ?: "")
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(main) }
            withStyle(SpanStyle(color = muted)) { append(rest) }
        },
        fontSize = 13.sp,
    )
}

/** "Hace 7 días: US$ x · Hace 30 días: US$ y" (precio de mercado), solo si hay historial. */
@Composable
fun PastPrices(card: CardInfo, foil: Boolean) {
    val parts = listOfNotNull(
        card.past(foil, 7)?.let { "Hace 7 días: ${usd(it)}" },
        card.past(foil, 30)?.let { "Hace 30 días: ${usd(it)}" },
    )
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Fila sobre la lista: botón "Ordenar y filtrar" (con cantidad de filtros activos) y resumen del filtro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionToolbar(activeFilters: Int, summary: String?, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onOpen, contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Icon(AppIcons.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ordenar y filtrar", maxLines = 1, softWrap = false)
            if (activeFilters > 0) {
                Spacer(Modifier.width(8.dp))
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text("$activeFilters") }
            }
        }
        if (summary != null) {
            Text(
                summary, Modifier.padding(top = 4.dp), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Encabezado de set en la vista agrupada: nombre, subtotal y completitud. Fondo opaco porque queda fijo arriba. */
@Composable
fun SetHeader(g: SetGroup) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 8.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(usd(g.total), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (g.size > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${g.owned} / ${g.size} cartas", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (g.owned.toFloat() / g.size).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Hoja inferior de Ordenar y filtrar. Los cambios se aplican al instante; "Listo" cierra. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortFilterSheet(
    sort: SortBy,
    grouped: Boolean,
    filter: ColFilter,
    rarities: List<String>,
    sets: List<Pair<Int, String>>,
    onSort: (SortBy) -> Unit,
    onGrouped: (Boolean) -> Unit,
    onFilter: (ColFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close() {
        scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetBody(sort, grouped, filter, rarities, sets, onSort, onGrouped, onFilter, onDone = { close() })
    }
}

@Composable
private fun ColumnScope.SheetBody(
    sort: SortBy,
    grouped: Boolean,
    filter: ColFilter,
    rarities: List<String>,
    sets: List<Pair<Int, String>>,
    onSort: (SortBy) -> Unit,
    onGrouped: (Boolean) -> Unit,
    onFilter: (ColFilter) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Ordenar por", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SortBy.values().forEach { s ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = s == sort, role = Role.RadioButton, onClick = { onSort(s) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s == sort, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(s.label, maxLines = 1)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
                .toggleable(value = grouped, role = Role.Switch, onValueChange = onGrouped),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Agrupar por set", Modifier.weight(1f), maxLines = 1)
            Switch(checked = grouped, onCheckedChange = null)
        }

        SectionTitle("Terminación")
        ChipRow {
            FINISHES.forEachIndexed { i, label ->
                FilterChip(
                    selected = filter.finish == i,
                    onClick = { onFilter(filter.copy(finish = i)) },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }

        SectionTitle("Elemento")
        ChipRow {
            ELEMENTS.forEach { (value, label) ->
                FilterChip(
                    selected = value in filter.elements,
                    onClick = { onFilter(filter.copy(elements = filter.elements.toggle(value))) },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }

        if (rarities.isNotEmpty()) {
            SectionTitle("Rareza")
            ChipRow {
                rarities.forEach { r ->
                    FilterChip(
                        selected = r in filter.rarities,
                        onClick = { onFilter(filter.copy(rarities = filter.rarities.toggle(r))) },
                        label = { Text(r, maxLines = 1) },
                    )
                }
            }
        }

        if (sets.isNotEmpty()) {
            SectionTitle("Set")
            ChipRow {
                sets.forEach { (gid, name) ->
                    FilterChip(
                        selected = gid in filter.sets,
                        onClick = { onFilter(filter.copy(sets = filter.sets.toggle(gid))) },
                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { onFilter(ColFilter()) }, enabled = filter.active, modifier = Modifier.weight(1f)) {
            Text("Limpiar", maxLines = 1)
        }
        Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Listo", maxLines = 1) }
    }
}
