package com.dimitrioskanellopoulos.athletica;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceHolder;

import android.location.Location;
import android.view.WindowInsets;

import com.dimitrioskanellopoulos.athletica.sensors.AveragingCallbackSensor;
import com.dimitrioskanellopoulos.athletica.sensors.CallbackSensorFactory;
import com.dimitrioskanellopoulos.athletica.sensors.interfaces.OnSensorAverageEventCallbackInterface;
import com.dimitrioskanellopoulos.athletica.sensors.interfaces.OnSensorEventCallbackInterface;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class WatchFaceService extends CanvasWatchFaceService {

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public CanvasWatchFaceService.Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            OnSensorEventCallbackInterface, OnSensorAverageEventCallbackInterface {

        private static final String TAG = "Engine";

        /**
         * Whether we are on Marshmallow and permissions checks are needed
         */
        private final Boolean requiresRuntimePermissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

        /**
         * Handler for updating the time
         */
        private final Handler mUpdateTimeHandler = new EngineHandler(this);

        /**
         * Broadcast receiver for updating the timezone
         */
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                watchFace.updateTimeZoneWith(TimeZone.getTimeZone(intent.getStringExtra("time-zone")));
            }
        };

        /**
         * Broadcast receiver for updating the battery level
         */
        final BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = context.registerReceiver(null, ifilter);
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                //int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                //float batteryPct = level / (float) scale;
                watchFace.updateBatteryLevel(level);
            }
        };

        /**
         * Whether tha timezone reciever is registered
         */
        boolean mRegisteredTimeZoneReceiver = false;

        /**
         * The watchface. Used for drawing and updating the view/watchface
         */
        private WatchFace watchFace;

        /**
         * The location engine that helps for retrieving location and it's updates
         */
        private LocationEngine locationEngine;

        /**
         * A helper for google api that can be shared within the app
         */
        private GoogleApiHelper googleApiHelper;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        /**
         * The supported sensor types for this watch face. Not the supported ones by the device
         */
        private int[] supportedSensorTypes = {
                Sensor.TYPE_PRESSURE,
                Sensor.TYPE_HEART_RATE,
                Sensor.TYPE_TEMPERATURE,
        };

        /**
         * The enabled activeSensors (activeSensors we want to display their values)
         */
        ArrayList<Integer> enabledSensorTypes = new ArrayList<Integer>();

        /**
         * The active sensors list. These sensors are the active ones at runtime
         */
        private final LinkedHashMap<Integer, AveragingCallbackSensor> activeSensors = new LinkedHashMap<Integer, AveragingCallbackSensor>();

        /**
         * Don't be kinky on this. It's the virbrating system service. Useful for haptic feedback
         */
        private final Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        /**
         * The sensor manager service
         */
        private final SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // Set the style
            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_HIDDEN)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setAcceptsTapEvents(true)
                    .setShowSystemUiTime(false)
                    .build());

            // Create a watch face
            watchFace = new WatchFace(WatchFaceService.this);

            // Get a google api helper
            googleApiHelper = new GoogleApiHelper(WatchFaceService.this);

            // Get a location engine
            locationEngine = new LocationEngine(googleApiHelper);

            initializeSensors();
        }

        /**
         * Initialized the sensors. Checks which ones the app supports and which ones the device
         */
        private void initializeSensors() {
            for (int supportedSensorType : supportedSensorTypes) {
                if (sensorManager.getDefaultSensor(supportedSensorType) != null) {
                    Log.d(TAG, "Enabled sensor: " + supportedSensorType);
                    enabledSensorTypes.add(supportedSensorType);
                }
            }

            // Activate the 1st sensor if available
            if (enabledSensorTypes.size() > 0) {
                activateSensor(enabledSensorTypes.get(0));
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            googleApiHelper.disconnect();
            stopListeningToSensors();
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                // Check for timezone changes
                registerTimeZoneReceiver();
                // Check for battery changes
                registerBatteryInfoReceiver();
                // Update sunrise and sunset
                updateSunriseAndSunset();
                // Start updating sensor values
                startListeningToSensors();
                // Update time zone in case it changed while we weren't visible.
                watchFace.updateTimeZoneWith(TimeZone.getDefault());
            } else {
                // Stop checking for timezone updates
                unregisterTimeZoneReceiver();
                // Stop checking for battery level changes
                unregisterBatteryInfoReceiver();
                // Stop updating sensor values
                stopListeningToSensors();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            watchFace.setAntiAlias(!inAmbientMode);
            watchFace.setShowSeconds(!isInAmbientMode());

            if (inAmbientMode) {
                stopListeningToSensors();
            } else {
                startListeningToSensors();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            watchFace.setIsRound(insets.isRound());
            watchFace.setChinSize(insets.getSystemWindowInsetBottom());
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            runOnTimeTickActions();
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            super.onDraw(canvas, bounds);
            watchFace.draw(canvas, bounds);
        }

        @Override
        public void onTapCommand(
                @TapType int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case WatchFaceService.TAP_TYPE_TAP:
                    checkSelfPermissions();

                    // Go over the active sensors. Should be only one for now
                    Integer activeSensorType = enabledSensorTypes.get(0);
                    Integer nextSensorIndex = 0;
                    Integer activeSensorIndex = -1;
                    for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                        activeSensorIndex = enabledSensorTypes.indexOf(entry.getKey());
                        // If found break the loop
                        if (activeSensorIndex != -1) {
                            activeSensorType = entry.getKey();
                            break;
                        }
                    }
                    // If it was the last in the list get the first
                    if (activeSensorIndex != enabledSensorTypes.size() - 1) {
                        nextSensorIndex = activeSensorIndex + 1;
                    }
                    deactivateSensor(activeSensorType);
                    activateSensor(enabledSensorTypes.get(nextSensorIndex));
                    startListeningToSensors();
                    long[] pattern = {0, 50, 50, 50, 50};
                    vibrator.vibrate(pattern, -1);
                    break;

                case WatchFaceService.TAP_TYPE_TOUCH:
                    break;

                case WatchFaceService.TAP_TYPE_TOUCH_CANCEL:
                    break;
            }
        }

        /**
         * Checks if the watchface service has the required permissions to at 100%
         */
        private void checkSelfPermissions() {
            if (!requiresRuntimePermissions) {
                return;
            }

            Intent permissionIntent = new Intent(
                    getApplicationContext(),
                    PermissionActivity.class);
            permissionIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permissionIntent);
        }

        @Override
        public void handleOnSensorChangedEvent(SensorEvent event) {
            switch (event.sensor.getType()) {
                case Sensor.TYPE_PRESSURE:
                    event.values[0] = locationEngine.getAltitudeFromPressure(event.values[0]);
                default:
                    break;
            }
            watchFace.updateSensorPaintText(event.sensor.getType(), String.format("%d", Math.round(event.values[0])));
            Log.d(TAG, "Updated value for sensor: " + event.sensor.getStringType());
            Log.d(TAG, "Invalidating view");
            postInvalidate();
        }

        @Override
        public void handleOnSensorAverageChangedEvent(SensorEvent event) {
            handleOnSensorChangedEvent(event);
        }

        private void registerTimeZoneReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterTimeZoneReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void activateSensor(Integer sensorType) {
            activeSensors.put(sensorType, CallbackSensorFactory.getCallbackSensor(getApplicationContext(), sensorType, this, this));
            watchFace.addSensorPaint(sensorType);
        }

        private void deactivateSensor(Integer sensorType) {
            activeSensors.get(sensorType).stopListening();
            activeSensors.remove(sensorType);
            watchFace.removeSensorPaint(sensorType);
        }

        private void updateSunriseAndSunset() {
            //@todo should check last time
            Location location = locationEngine.getLastKnownLocation();
            if (location == null) {
                // If its a real device continue to run
                // @todo solve this with timezone
                if (!EmulatorHelper.isEmulator()) {
                    Log.e(TAG, "Could not update sunrise/sunset because no location was found");
                    return;
                }
                location = new Location("dummyprovider");
                location.setLatitude(20.3);
                location.setLongitude(52.6);
                location.setAltitude(650.0);
                location.setTime(System.currentTimeMillis());
                location.setAccuracy(40.0f);
            }
            Pair<String, String> sunriseSunset = SunriseSunsetTimesService.getSunriseAndSunset(location, TimeZone.getDefault().getID());
            watchFace.updateSunriseSunset(sunriseSunset);
            Log.d(TAG, "Successfully updated sunrise");
        }

        private void unregisterBatteryInfoReceiver() {
            unregisterReceiver(batteryInfoReceiver);
        }

        private void registerBatteryInfoReceiver() {
            registerReceiver(batteryInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }

        private void startListeningToSensors() {
            for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                if (!entry.getValue().isListening()) {
                    entry.getValue().startListening();
                }
            }
        }

        private void calculateAverageForActiveSensors() {
            for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                if (!entry.getValue().isListening()) {
                    entry.getValue().getAverage();
                }
            }
        }

        private void stopListeningToSensors() {
            for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                if (entry.getValue().isListening()) {
                    entry.getValue().stopListening();
                }
            }
        }

        private void runOnTimeTickActions() {
            // @todo this is wrong
            Calendar rightNow = Calendar.getInstance();
            int hour = rightNow.get(Calendar.HOUR_OF_DAY);
            int minute = rightNow.get(Calendar.MINUTE);
            int second = rightNow.get(Calendar.SECOND);
            // Every 15 minutes
            if (minute % 15 == 0) {
                calculateAverageForActiveSensors();
            }
            if (minute == 0) {
                updateSunriseAndSunset();
            }
        }
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFaceService.Engine> mWeakReference;

        public EngineHandler(WatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
