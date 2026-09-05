package com.example.accountability

import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.accountability.WidgetStateRepository.widgetMessageFlow

class AccountabilityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AccountabilityWidget()
}

class AccountabilityWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val message by context.widgetMessageFlow.collectAsState(initial = null)
            WidgetContent(message)
        }
    }
}

@Composable
fun WidgetContent(message: String?) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (message.isNullOrEmpty()) {
            Text("No scheduled events to track right now. You're safe... for now.")
        } else {
            Text(
                text = message,
                style = TextStyle()
            )
            Spacer(modifier = GlanceModifier.padding(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Button(
                    text = "Did It",
                    onClick = actionRunCallback<ClearWidgetAction>(),
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(modifier = GlanceModifier.padding(8.dp))
                Button(
                    text = "Skipped It",
                    onClick = actionRunCallback<ClearWidgetAction>(),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }
    }
}

class ClearWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // Clear Datastore message
        WidgetStateRepository.updateWidgetMessage(context, null)

        // Update the widget UI
        AccountabilityWidget().updateAll(context)

        // Cancel the system notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1001)
    }
}
