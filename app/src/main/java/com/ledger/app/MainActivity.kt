package com.ledger.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* ————— design tokens ————— */
val Paper = Color(0xFFF3F6F4)
val Ink = Color(0xFF101F19)
val Green = Color(0xFF1E5C46)
val Mint = Color(0xFFDCEDE4)
val MintBar = Color(0xFF9DC3B1)
val GrayBar = Color(0xFFDFE7E2)
val Slate = Color(0xFF5B6770)
val Muted = Color(0xFF8A8F85)
val Signal = Color(0xFFC2452D)
val SignalBg = Color(0xFFF6E3DE)
val ToggleBg = Color(0xFFE6EDE8)

/* ————— data ————— */
data class Expense(
    val id: String,
    val amount: Double,
    val category: String,
    val note: String,
    val ts: Long
)

data class Category(val id: String, val label: String, val dot: Color)

val CATEGORIES = listOf(
    Category("food", "Food & drink", Color(0xFFE8A13C)),
    Category("transport", "Transport", Color(0xFF3C7DA6)),
    Category("shopping", "Shopping", Color(0xFFA65CA0)),
    Category("bills", "Bills", Color(0xFF5B6770)),
    Category("health", "Health", Color(0xFFC2452D)),
    Category("fun", "Fun", Color(0xFF2FA07A)),
    Category("other", "Other", Color(0xFF8A8F85))
)

fun catById(id: String): Category = CATEGORIES.find { it.id == id } ?: CATEGORIES.last()

/* ————— storage (SharedPreferences + JSON) ————— */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("ledger", Context.MODE_PRIVATE)

    fun load(c: Context): Pair<List<Expense>, Double?> {
        val p = prefs(c)
        val list = mutableListOf<Expense>()
        try {
            val arr = JSONArray(p.getString("expenses", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Expense(
                        id = o.getString("id"),
                        amount = o.getDouble("amount"),
                        category = o.getString("category"),
                        note = o.optString("note", ""),
                        ts = o.getLong("ts")
                    )
                )
            }
        } catch (_: Exception) {
        }
        val b = p.getFloat("budget", -1f)
        return list.sortedByDescending { it.ts } to if (b > 0f) b.toDouble() else null
    }

    fun save(c: Context, expenses: List<Expense>, budget: Double?) {
        val arr = JSONArray()
        for (e in expenses) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("amount", e.amount)
                    .put("category", e.category)
                    .put("note", e.note)
                    .put("ts", e.ts)
            )
        }
        prefs(c).edit()
            .putString("expenses", arr.toString())
            .putFloat("budget", budget?.toFloat() ?: -1f)
            .apply()
    }
}

/* ————— helpers ————— */
private val inrFormat: NumberFormat =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

fun money(n: Double): String = inrFormat.format(n)

private val zone: ZoneId = ZoneId.systemDefault()
fun dayStartMs(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
fun tsToDate(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

/* ————— activity ————— */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LedgerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerApp() {
    val ctx = LocalContext.current

    var expenses by remember { mutableStateOf(listOf<Expense>()) }
    var budget by remember { mutableStateOf<Double?>(null) }
    var view by remember { mutableStateOf(0) } // 0 = today, 1 = week, 2 = month
    var showSheet by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (e, b) = Store.load(ctx)
        expenses = e
        budget = b
    }

    val persist = { Store.save(ctx, expenses, budget) }

    /* period math */
    val today = LocalDate.now()
    val dayFrom = dayStartMs(today)
    val dayTo = dayStartMs(today.plusDays(1))
    val weekStart = today.with(DayOfWeek.MONDAY)
    val weekFrom = dayStartMs(weekStart)
    val weekTo = dayStartMs(weekStart.plusDays(7))
    val monthStart = today.withDayOfMonth(1)
    val monthFrom = dayStartMs(monthStart)
    val monthTo = dayStartMs(monthStart.plusMonths(1))

    fun sumIn(from: Long, to: Long): Double =
        expenses.filter { it.ts >= from && it.ts < to }.sumOf { it.amount }

    val from: Long; val to: Long; val prevFrom: Long; val prevTo: Long
    val label: String; val prevLabel: String
    when (view) {
        0 -> {
            from = dayFrom; to = dayTo
            prevFrom = dayStartMs(today.minusDays(1)); prevTo = dayFrom
            label = "SPENT TODAY"; prevLabel = "yesterday"
        }
        1 -> {
            from = weekFrom; to = weekTo
            prevFrom = dayStartMs(weekStart.minusDays(7)); prevTo = weekFrom
            label = "SPENT THIS WEEK"; prevLabel = "last week"
        }
        else -> {
            from = monthFrom; to = monthTo
            prevFrom = dayStartMs(monthStart.minusMonths(1)); prevTo = monthFrom
            label = "SPENT THIS MONTH"; prevLabel = "last month"
        }
    }

    val total = sumIn(from, to)
    val prevTotal = sumIn(prevFrom, prevTo)
    val delta: Double? = if (prevTotal > 0) (total - prevTotal) / prevTotal * 100 else null
    val savedVsPrev = delta != null && delta < 0

    val listItems = expenses.filter { it.ts >= from && it.ts < to }

    val byCat = listItems.groupBy { it.category }
        .map { (id, items) -> catById(id) to items.sumOf { it.amount } }
        .sortedByDescending { it.second }

    /* bars */
    data class Bar(val label: String, val value: Double, val isNow: Boolean)
    val bars: List<Bar> = when (view) {
        0 -> (0..6).map { i ->
            val d = today.minusDays((6 - i).toLong())
            Bar(
                d.format(DateTimeFormatter.ofPattern("E", Locale.ENGLISH)).take(1),
                sumIn(dayStartMs(d), dayStartMs(d.plusDays(1))),
                d == today
            )
        }
        1 -> (0..6).map { i ->
            val d = weekStart.plusDays(i.toLong())
            Bar(
                d.format(DateTimeFormatter.ofPattern("E", Locale.ENGLISH)).take(1),
                sumIn(dayStartMs(d), dayStartMs(d.plusDays(1))),
                d == today
            )
        }
        else -> (1..monthStart.lengthOfMonth()).map { day ->
            val d = monthStart.withDayOfMonth(day)
            Bar(day.toString(), sumIn(dayStartMs(d), dayStartMs(d.plusDays(1))), d == today)
        }
    }
    val maxBar = maxOf(bars.maxOfOrNull { it.value } ?: 0.0, 1.0)

    val spentThisMonth = sumIn(monthFrom, monthTo)
    val overBudget = budget != null && spentThisMonth > budget!!

    /* ————— UI ————— */
    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(top = 16.dp, bottom = 110.dp)
            ) {
                /* header */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Row {
                        Text("LEDGER", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Ink, letterSpacing = 1.sp)
                        Text(".", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Green)
                    }
                    Text(
                        today.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Slate
                    )
                }

                Spacer(Modifier.height(18.dp))

                /* period toggle */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ToggleBg)
                        .padding(3.dp)
                ) {
                    listOf("Today", "Week", "Month").forEachIndexed { i, t ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (view == i) Green else Color.Transparent)
                                .clickable { view = i }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = if (view == i) Paper else Color(0xFF3B4A43)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                /* always-visible budget counter (Today / Week views) */
                if (budget != null && view != 2) {
                    val left = budget!! - spentThisMonth
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (overBudget) SignalBg else Mint)
                            .clickable { view = 2 }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (overBudget) "BUDGET OVER BY" else "BUDGET LEFT THIS MONTH",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            color = if (overBudget) Signal else Green
                        )
                        Text(
                            money(abs(left)),
                            fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            color = if (overBudget) Signal else Green
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                }

                /* hero total */
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Slate)
                Text(
                    money(total),
                    fontSize = if (money(total).length > 11) 36.sp else 48.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = Ink
                )
                Spacer(Modifier.height(6.dp))
                if (delta != null) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (savedVsPrev) Mint else SignalBg)
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            (if (savedVsPrev) "▼ " else "▲ ") +
                                "${abs(delta).roundToInt()}% ${if (savedVsPrev) "less" else "more"} than $prevLabel",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (savedVsPrev) Green else Signal
                        )
                    }
                } else {
                    Text("Nothing recorded $prevLabel to compare with", fontSize = 12.sp, color = Muted)
                }

                Spacer(Modifier.height(22.dp))

                /* mini bar chart */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (view == 2) 2.dp else 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    bars.forEach { b ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            val h = maxOf((b.value / maxBar * 58).dp.value, 3f).dp
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(h)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            b.isNow -> Green
                                            b.value > 0 -> MintBar
                                            else -> GrayBar
                                        }
                                    )
                            )
                            if (view != 2) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    b.label, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    color = if (b.isNow) Green else Muted,
                                    fontWeight = if (b.isNow) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
                if (view == 2) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        Text("${bars.size}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }

                /* budget card (Month view) */
                if (view == 2) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White,
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("MONTHLY BUDGET", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Slate)
                                Text(
                                    if (budget != null) "Edit" else "Set budget",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Green,
                                    modifier = Modifier.clickable { showBudgetDialog = true }
                                )
                            }
                            if (budget != null) {
                                val b = budget!!
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        money(spentThisMonth), fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold, color = if (overBudget) Signal else Ink
                                    )
                                    Text("of ${money(b)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Muted)
                                }
                                Spacer(Modifier.height(8.dp))
                                val pct = (spentThisMonth / b).coerceIn(0.0, 1.0).toFloat()
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape)
                                        .background(ToggleBg)
                                ) {
                                    Box(
