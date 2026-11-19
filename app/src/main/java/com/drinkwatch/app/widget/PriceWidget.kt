package com.drinkwatch.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.drinkwatch.app.MainActivity
import com.drinkwatch.app.data.Api
import com.drinkwatch.app.data.Price
import com.drinkwatch.app.data.ServiceLocator
import com.drinkwatch.app.data.WIDGET_ROW_LIMIT
import com.drinkwatch.app.data.WidgetItem
import com.drinkwatch.app.data.cachedAgeLabel
import com.drinkwatch.app.data.cheapestWidgetItems

// The row geometry, named once so the layout below and the fit estimate in
// [rowsThatFit] cannot drift apart.
private const val WIDGET_PADDING_DP = 12f
private const val ROW_TOP_PADDING_DP = 7f
private const val HEADER_SP = 13f
private const val LABEL_SP = 12f
private const val STORE_SP = 10f

class PriceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = ServiceLocator.prefs(context)
        val cache = Api.cachedWidget(prefs.widgetCache)
        // The header says how old the prices are, not when we last talked to the
        // server: during an outage the copy on disk keeps its frozen age_hours, so
        // a clock time there would read as a refresh that never happened.
        val age = cachedAgeLabel(
            generatedAt = cache.generatedAt,
            oldestAgeHours = cache.items.maxOfOrNull { it.ageHours } ?: 0.0,
            nowMillis = System.currentTimeMillis(),
        )
        provideContent { WidgetBody(cache.items, age) }
    }
}

@Composable
private fun WidgetBody(items: List<WidgetItem>, age: String?) {
    val ink = ColorProvider(Color(0xFF1B1033))
    val muted = ColorProvider(Color(0xFF6F6880))
    val lime = ColorProvider(Color(0xFF0F7A4A))

    // Only lay out the rows the launcher actually gave us room for. Four rows are
    // the promise, not the unconditional layout: a 2-cell widget is 110 dp, which
    // holds the header and two rows, so asking for four clipped the third and hid
    // the fourth. [LocalSize] also shrinks the count when the font scale grows.
    val rows = cheapestWidgetItems(
        items,
        rowsThatFit(LocalSize.current, LocalContext.current.resources.configuration.fontScale),
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFFFBF7F3)))
            .padding(WIDGET_PADDING_DP.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = "DrinkWatch",
                style = TextStyle(color = ink, fontSize = HEADER_SP.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            val stamp = age ?: "tap to open"
            Text(text = stamp, style = TextStyle(color = muted, fontSize = 10.sp))
        }

        // A payload with only suggestion rows has no followed drink to show, so it
        // takes the same copy as an empty cache rather than a bare header.
        if (rows.isEmpty()) {
            Text(
                text = "No prices yet. Open DrinkWatch to refresh.",
                style = TextStyle(color = muted, fontSize = LABEL_SP.sp),
                modifier = GlanceModifier.padding(top = 8.dp),
            )
            return@Column
        }

        rows.forEach { item ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = ROW_TOP_PADDING_DP.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = item.label,
                        style = TextStyle(color = ink, fontSize = LABEL_SP.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    Text(
                        text = item.store + (item.distanceKm?.let { " · " + Price.km(it) } ?: ""),
                        style = TextStyle(color = muted, fontSize = STORE_SP.sp),
                        maxLines = 1,
                    )
                }
                Text(
                    text = Price.audShort(item.priceCents),
                    style = TextStyle(color = lime, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

/**
 * Rows that fit in [size] at the current [fontScale].
 *
 * The height is estimated from the same sizes the rows are laid out with, and
 * the division rounds down, so a row is dropped rather than cut in half. Only
 * the top padding and header are reserved: if the estimate runs a few dp long,
 * the overflow eats the bottom padding before it reaches the last line of text.
 * The floor of one keeps a slightly short measurement from hiding the whole
 * list; [WIDGET_ROW_LIMIT] is the ceiling because the widget only promises four.
 */
private fun rowsThatFit(size: DpSize, fontScale: Float): Int {
    // Glance renders each line single-line; 1.2x the em is close to Android's
    // default line spacing and errs high, which is the safe direction here.
    val textDp = 1.2f * fontScale
    val rowDp = ROW_TOP_PADDING_DP + (LABEL_SP + STORE_SP) * textDp
    val chromeDp = WIDGET_PADDING_DP + HEADER_SP * textDp
    return ((size.height.value - chromeDp) / rowDp).toInt().coerceIn(1, WIDGET_ROW_LIMIT)
}

class PriceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PriceWidget()
}
