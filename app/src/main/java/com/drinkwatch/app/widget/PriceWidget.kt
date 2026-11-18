package com.drinkwatch.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import com.drinkwatch.app.data.WidgetItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PriceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = ServiceLocator.prefs(context)
        val items = Api.itemsFromCache(prefs.widgetCache)
        val updatedAt = prefs.widgetCacheAt.takeIf { it > 0L }
        provideContent { WidgetBody(items, updatedAt) }
    }
}

@Composable
private fun WidgetBody(items: List<WidgetItem>, updatedAt: Long?) {
    val ink = ColorProvider(Color(0xFF1B1033))
    val muted = ColorProvider(Color(0xFF6F6880))
    val lime = ColorProvider(Color(0xFF0F7A4A))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFFFBF7F3)))
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = "DrinkWatch",
                style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            val stamp = updatedAt?.let {
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it))
            } ?: "tap to open"
            Text(text = stamp, style = TextStyle(color = muted, fontSize = 10.sp))
        }

        if (items.isEmpty()) {
            Text(
                text = "No prices yet. Open DrinkWatch to refresh.",
                style = TextStyle(color = muted, fontSize = 12.sp),
                modifier = GlanceModifier.padding(top = 8.dp),
            )
            return@Column
        }

        items.take(4).forEach { item ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = item.label,
                        style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    Text(
                        text = item.store + (item.distanceKm?.let { " · " + Price.km(it) } ?: ""),
                        style = TextStyle(color = muted, fontSize = 10.sp),
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

class PriceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PriceWidget()
}
