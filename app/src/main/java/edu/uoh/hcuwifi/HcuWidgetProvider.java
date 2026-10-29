package edu.uoh.hcuwifi;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * 1-Tap Home Screen Widget Provider for instant HCU Wi-Fi authentication.
 */
public class HcuWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_WIDGET_LOGIN = "edu.uoh.hcuwifi.ACTION_WIDGET_LOGIN";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, "Tap to Login", false);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_WIDGET_LOGIN.equals(intent.getAction())) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, HcuWidgetProvider.class);
            int[] allWidgetIds = manager.getAppWidgetIds(thisWidget);

            // Immediate visual feedback
            for (int widgetId : allWidgetIds) {
                updateAppWidget(context, manager, widgetId, "Logging in...", true);
            }

            Toast.makeText(context, "HCU Wi-Fi: Logging in...", Toast.LENGTH_SHORT).show();

            HcuAuthHelper.loginAsync(context, result -> {
                String statusText = result.isSuccess() ? "Online (Connected)" : "Login Failed";
                for (int widgetId : allWidgetIds) {
                    updateAppWidget(context, manager, widgetId, statusText, false);
                }
                Toast.makeText(context, "HCU: " + result.message, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private static void updateAppWidget(Context context, AppWidgetManager appWidgetManager,
                                        int appWidgetId, String statusText, boolean inProgress) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        views.setTextViewText(R.id.widget_status, statusText);

        if (inProgress) {
            views.setTextViewText(R.id.widget_btn_action, "WAIT");
        } else {
            views.setTextViewText(R.id.widget_btn_action, "LOGIN");
        }

        // Tap action sends ACTION_WIDGET_LOGIN broadcast
        Intent intent = new Intent(context, HcuWidgetProvider.class);
        intent.setAction(ACTION_WIDGET_LOGIN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
        views.setOnClickPendingIntent(R.id.widget_btn_action, pendingIntent);
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}
