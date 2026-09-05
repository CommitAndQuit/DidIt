package com.example.accountability

import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.accountability.WidgetStateRepository.widgetAckFlow
import com.example.accountability.WidgetStateRepository.widgetMessageFlow

class AccountabilityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AccountabilityWidget()
}

class AccountabilityWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val message by context.widgetMessageFlow.collectAsState(initial = null)
            val ack by context.widgetAckFlow.collectAsState(initial = null)
            // GlanceTheme applies Material 3 colors (dynamic / Material You on S+),
            // giving the widget a solid, legible surface on any wallpaper.
            GlanceTheme {
                WidgetContent(message = message, ack = ack)
            }
        }
    }
}

@Composable
private fun WidgetContent(message: String?, ack: String?) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(24.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !ack.isNullOrEmpty() -> AckState(ack)
            !message.isNullOrEmpty() -> PromptState(message)
            else -> IdleState()
        }
    }
}

@Composable
private fun PromptState(message: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(GlanceModifier.height(14.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                iconRes = R.drawable.ic_check,
                description = "I did it",
                container = GlanceTheme.colors.primaryContainer,
                tint = GlanceTheme.colors.onPrimaryContainer,
                onClick = actionRunCallback<CompletedAction>(),
            )
            Spacer(GlanceModifier.width(20.dp))
            IconButton(
                iconRes = R.drawable.ic_close,
                description = "I skipped it",
                container = GlanceTheme.colors.errorContainer,
                tint = GlanceTheme.colors.onErrorContainer,
                onClick = actionRunCallback<SkippedAction>(),
            )
        }
    }
}

@Composable
private fun AckState(ack: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(56.dp)
                .cornerRadius(28.dp)
                .background(GlanceTheme.colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_check),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                modifier = GlanceModifier.size(28.dp),
            )
        }
        Spacer(GlanceModifier.height(12.dp))
        Text(
            text = ack,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun IdleState() {
    Text(
        text = "No scheduled events to track right now. You're safe... for now.",
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 14.sp,
        ),
    )
}

@Composable
private fun IconButton(
    iconRes: Int,
    description: String,
    container: androidx.glance.unit.ColorProvider,
    tint: androidx.glance.unit.ColorProvider,
    onClick: androidx.glance.action.Action,
) {
    Box(
        modifier = GlanceModifier
            .size(56.dp)
            .cornerRadius(28.dp)
            .background(container)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(28.dp),
        )
    }
}

/** Tick — mark the task done and show a bit of (grudging) praise. */
class CompletedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateRepository.acknowledge(context, "Good job")
        AccountabilityWidget().updateAll(context)
        cancelNotification(context)
    }
}

/** Cross — dismiss the nag and reset to idle. */
class SkippedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetStateRepository.reset(context)
        AccountabilityWidget().updateAll(context)
        cancelNotification(context)
    }
}

private fun cancelNotification(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(1001)
}
