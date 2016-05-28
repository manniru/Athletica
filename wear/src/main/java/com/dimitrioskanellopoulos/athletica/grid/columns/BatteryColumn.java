package com.dimitrioskanellopoulos.athletica.grid.columns;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.util.Log;

import com.dimitrioskanellopoulos.athletica.R;

public abstract class BatteryColumn extends Column {
    private static final String TAG = "BatteryColumn";
    private final Context context;

    protected static float batteryLevel = 0.0f;

    /**
     * Whether tha Battery receiver is registered
     */
    protected static boolean isRegisteredBatteryInfoReceiver = false;

    /**
     * Broadcast receiver for updating the battery level
     */
    private static final BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            // Just in case
            int level = 0;
            if (batteryStatus != null) {
                level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            }
            //int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            //float batteryPct = level / (float) scale;
            batteryLevel = level;
        }
    };

    public BatteryColumn(Context context, Typeface paintTypeface, Float paintTextSize, int paintColor) {
        super(paintTypeface, paintTextSize, paintColor);
        this.context = context.getApplicationContext();
    }

    @Override
    public void setIsVisible(Boolean isVisible) {
        super.setIsVisible(isVisible);
        if (isVisible) {
            registerBatteryInfoReceiver();
        }else {
            unregisterBatteryInfoReceiver();
        }

    }

    private void unregisterBatteryInfoReceiver() {
        if (!isRegisteredBatteryInfoReceiver) {
            return;
        }
        context.unregisterReceiver(batteryInfoReceiver);
        isRegisteredBatteryInfoReceiver = false;
        Log.d(TAG, "Unregistered receiver");
    }

    private void registerBatteryInfoReceiver() {
        if (isRegisteredBatteryInfoReceiver) {
            return;
        }
        context.registerReceiver(batteryInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        isRegisteredBatteryInfoReceiver = true;
        Log.d(TAG, "Registered receiver");

    }

    @Override
    public void start() {
        super.start();
        // Maybe should register receiver
    }

    @Override
    public void destroy() {
        unregisterBatteryInfoReceiver();
        super.destroy();
    }
}
