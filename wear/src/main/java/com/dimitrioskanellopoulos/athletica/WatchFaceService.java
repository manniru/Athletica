package com.dimitrioskanellopoulos.athletica;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.dimitrioskanellopoulos.athletica.configuration.ConfigurationHelper;
import com.dimitrioskanellopoulos.athletica.helpers.EmulatorHelper;
import com.dimitrioskanellopoulos.athletica.helpers.SunriseSunsetHelper;
import com.dimitrioskanellopoulos.athletica.permissions.PermissionsHelper;
import com.dimitrioskanellopoulos.athletica.sensors.AveragingCallbackSensor;
import com.dimitrioskanellopoulos.athletica.sensors.CallbackSensor;
import com.dimitrioskanellopoulos.athletica.sensors.CallbackSensorFactory;
import com.dimitrioskanellopoulos.athletica.sensors.interfaces.OnSensorAverageEventCallbackInterface;
import com.dimitrioskanellopoulos.athletica.sensors.interfaces.OnSensorEventCallbackInterface;
import com.dimitrioskanellopoulos.athletica.sensors.interfaces.OnSensorTriggerCallbackInterface;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.DecimalFormat;
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

    /**
     * Handler for various messages
     */
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

    private class Engine extends CanvasWatchFaceService.Engine implements
            OnSensorEventCallbackInterface,
            OnSensorAverageEventCallbackInterface,
            OnSensorTriggerCallbackInterface,
            GoogleApiClient.ConnectionCallbacks,
            DataApi.DataListener,
            GoogleApiClient.OnConnectionFailedListener {
        private static final String TAG = "Engine";

        /**
         * The location update intervals: 1hour in ms
         */
        private static final long LOCATION_UPDATE_INTERVAL_MS = 3600000;
        private static final long LOCATION_UPDATE_FASTEST_INTERVAL_MS = 3600000;
        /**
         * How often the onTimeTick actions should run
         */
        private final long RUN_ON_TICK_TASKS_EVERY_MS = !EmulatorHelper.isEmulator() ? 15 * 60 * 1000 : 1 * 60 * 1000; // 15 Minutes
        /**
         * Handler for updating the time
         */
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        /**
         * How many sensors we want to utilize concurrently
         */
        private final Integer maxActiveSensors = 1;
        /**
         * The active sensors list. These sensors are the active ones at runtime
         */
        private final LinkedHashMap<Integer, AveragingCallbackSensor> activeSensors = new LinkedHashMap<Integer, AveragingCallbackSensor>();
        /**
         * Don't be kinky on this. It's the vibrating system service. Useful for haptic feedback
         */
        private final Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        /**
         * The sensor manager service
         */
        private final SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        /**
         * The location request we will be making
         */
        private final LocationRequest locationRequest = new LocationRequest()
                .setInterval(LOCATION_UPDATE_INTERVAL_MS)
                .setFastestInterval(LOCATION_UPDATE_FASTEST_INTERVAL_MS)
                .setPriority(LocationRequest.PRIORITY_LOW_POWER);
        /**
         * Whether tha Battery receiver is registered
         */
        boolean isRegisteredBatteryInfoReceiver = false;

        /**
         * Whether tha location receiver is registered
         */
        boolean isRegisteredLocationReceiver = false;
        /**
         * When the onTickActions were run last time in ms
         */
        private Calendar lastOnTimeTickTasksRun = Calendar.getInstance();
        /**
         * Whether tha timezone receiver is registered
         */
        private boolean isRegisteredTimeZoneReceiver = false;
        /**
         * The watchface. Used for drawing and updating the view/watchface
         */
        private WatchFace watchFace;
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
        private final BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver() {
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
                watchFace.updateBatteryLevel(level);
            }
        };
        /**
         * Broadcast receiver for location intent
         */
        private final LocationListener locationChangedReceiver = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                Log.d(TAG, "Location changed");
                Log.d(TAG, "Provider: " + location.getProvider());
                Log.d(TAG, "Lat: " + location.getLatitude());
                Log.d(TAG, "Long: " + location.getLongitude());
                Log.d(TAG, "Altitude: " + location.getAltitude());
                Log.d(TAG, "Accuracy: " + location.getAccuracy());
                updateSunriseAndSunset(location);
            }
        };
        /**
         * A helper for google api that can be shared within the app
         */
        private GoogleApiClient googleApiClient;
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
                Sensor.TYPE_AMBIENT_TEMPERATURE,
                Sensor.TYPE_LIGHT,
                Sensor.TYPE_MAGNETIC_FIELD,
        };
        /**
         * The available sensors. Cross of supported by the app sensors and supported by the device
         */
        private ArrayList<Integer> availableSensorTypes = new ArrayList<Integer>();
        private PermissionsHelper permissionsHelper;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // Set the style
            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_VISIBLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setAcceptsTapEvents(true)
                    .setShowSystemUiTime(false)
                    .setViewProtectionMode(WatchFaceStyle.PROGRESS_MODE_NONE)
                    .build());

            // Create a watch face
            watchFace = new WatchFace(WatchFaceService.this);
            watchFace.inAmbientMode(false);

            // Add the helper
            permissionsHelper = new PermissionsHelper(getApplicationContext(), new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BODY_SENSORS});

            // Get a Google API client
            googleApiClient = new GoogleApiClient.Builder(WatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .addApi(LocationServices.API)
                    .build();

            // Activate the "next" sensors
            activateNextSensors();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            stopListeningToSensors();
            unregisterBatteryInfoReceiver();
            unregisterTimeZoneReceiver();
            if (googleApiClient.isConnected()) {
                googleApiClient.disconnect();
            }
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d(TAG, "Visibility changed: " + visible);
            if (visible) {
                // Connect to Google API
                googleApiClient.connect();
                // Check for timezone changes
                registerTimeZoneReceiver();
                // Check for battery changes
                registerBatteryInfoReceiver();
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

                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    // Unregister location receiver to save up in case of a foreground app
                    unregisterLocationReceiver();
                    googleApiClient.disconnect();
                }
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            // Obvious
            watchFace.inAmbientMode(inAmbientMode);

            // When we are active show realtime data from the sensors. Start listening
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
            runOnTimeTickTasks();
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
                    activateNextSensors();
                    startListeningToSensors();
                    vibrator.vibrate(new long[]{0, 50, 50}, -1);
                    break;

                case WatchFaceService.TAP_TYPE_TOUCH:
                    break;

                case WatchFaceService.TAP_TYPE_TOUCH_CANCEL:
                    break;
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "Google API connected");
            registerLocationReceiver();
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
            updateConfigDataItemAndUiOnStartup();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Google API connection suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "Google API connection failed");
        }

        @Override
        public void handleOnSensorChangedEvent(Sensor sensor, Integer sensorType, float[] eventValues) {
            // Special cases for special sensors :-)
            DecimalFormat decimalFormat = new DecimalFormat("#.#");
            switch (sensorType) {
                case Sensor.TYPE_PRESSURE:
                    watchFace.updateSensorText(sensorType, decimalFormat.format(eventValues[0]));
                    break;
                case Sensor.TYPE_HEART_RATE:
                    if (Math.round(eventValues[0]) > 180) {
                        vibrator.vibrate(new long[]{0, 250, 500, 250, 100, 250, 50, 250, 50}, -1);
                    }
                default:
                    watchFace.updateSensorText(sensorType, decimalFormat.format(Math.round(eventValues[0])));
                    break;
            }
            Log.d(TAG, "Updated value for sensor: " + sensorType);
            Log.d(TAG, "Invalidating view");
            postInvalidate();
        }

        @Override
        public void handleOnSensorAverageChangedEvent(Sensor sensor, Integer sensorType, float[] eventValues) {
            handleOnSensorChangedEvent(sensor, sensorType, eventValues);
        }

        @Override
        public void handleOnSensorTriggerEvent(Sensor sensor, Integer sensorType, float[] eventValues) {
        }

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        ConfigurationHelper.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Config DataItem updated:" + config);
                }
                // This can happen from this method more often when phone changes
                updateUiForConfigDataMap(config);
            }
        }


        /**
         * Finds and sets all the available and supported sensors
         */
        private void findAndSetAvailableSensorTypes() {
            // Clear all enabled
            availableSensorTypes.clear();
            // Add the ones supported by the device and the app
            for (int supportedSensorType : supportedSensorTypes) {
                // If the sensor is heart rate we need to ask permissions
                if (supportedSensorType == Sensor.TYPE_HEART_RATE) {
                    if (!permissionsHelper.hasPermission(Manifest.permission.BODY_SENSORS) && permissionsHelper.canAskAgainForPermission(Manifest.permission.BODY_SENSORS)) {
                        permissionsHelper.askForPermission(Manifest.permission.BODY_SENSORS);
                    }
                }
                if (sensorManager.getDefaultSensor(supportedSensorType) != null) {
                    Log.d(TAG, "Available sensor: " + sensorManager.getDefaultSensor(supportedSensorType).getStringType());
                    availableSensorTypes.add(supportedSensorType);
                    // Small hack here to add a pressure altitude sensor
                    if (supportedSensorType == Sensor.TYPE_PRESSURE) {
                        availableSensorTypes.add(CallbackSensor.TYPE_PRESSURE_ALTITUDE);
                        Log.d(TAG, "Available sensor: TYPE_PRESSURE_ALTITUDE");
                    }

                }
            }
        }

        private void registerTimeZoneReceiver() {
            if (isRegisteredTimeZoneReceiver) {
                return;
            }
            isRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterTimeZoneReceiver() {
            if (!isRegisteredTimeZoneReceiver) {
                return;
            }
            isRegisteredTimeZoneReceiver = false;
            unregisterReceiver(mTimeZoneReceiver);
        }

        private void registerLocationReceiver() {
            if (!googleApiClient.isConnected()) {
                Log.d(TAG, "Google API client is not ready yet, wont register for location updates");
                return;
            }
            if (isRegisteredLocationReceiver) {
                Log.d(TAG, "Location listener is registered nothing to do");
                return;
            }
            // Check permissions (hopefully the receiver wont be registered
            if (!permissionsHelper.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                if (permissionsHelper.canAskAgainForPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    permissionsHelper.askForPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
                    Log.d(TAG, "Asking for location permissions");
                }
                return;
            }

            isRegisteredLocationReceiver = true;
            LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, locationChangedReceiver);
            Log.d(TAG, "Listening for location updates");
        }

        private void unregisterLocationReceiver() {
            if (!googleApiClient.isConnected()) {
                Log.d(TAG, "Google API client is not ready yet, wont unregister listener");
                return;
            }
            if (!isRegisteredLocationReceiver) {
                Log.d(TAG, "Location listener is not registered nothing to do");
                return;
            }
            isRegisteredLocationReceiver = false;
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, locationChangedReceiver);
            Log.d(TAG, "Stopped listening for location updates");
        }

        private void updateConfigDataItemAndUiOnStartup() {
            ConfigurationHelper.fetchConfigDataMap(googleApiClient,
                    new ConfigurationHelper.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.
                            ConfigurationHelper.setDefaultValuesForMissingConfigKeys(startupConfig);
                            ConfigurationHelper.putConfigDataItem(googleApiClient, startupConfig);
                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }

        private void updateUiForConfigDataMap(final DataMap config) {
            boolean uiUpdated = false;
            for (String configKey : config.keySet()) {
                if (!config.containsKey(configKey)) {
                    continue;
                }
                Boolean value = config.getBoolean(configKey);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Found watch face config key: " + configKey + " -> "
                            + Boolean.toString(value));
                }
                if (updateUiForKey(configKey, value)) {
                    uiUpdated = true;
                }
            }
            if (uiUpdated) {
                invalidate();
            }
        }

        /**
         * Updates the color of a UI item according to the given {@code configKey}. Does nothing if
         * {@code configKey} isn't recognized.
         *
         * @return whether UI has been updated
         */
        private boolean updateUiForKey(String configKey, Boolean value) {
            if (configKey.equals(ConfigurationHelper.KEY_TIME_FORMAT)) {
                watchFace.setTimeFormat24(value);
            } else if (configKey.equals(ConfigurationHelper.KEY_DATE_NAMES)) {
                watchFace.setShowDateNamesFormat(value);
            } else if (configKey.equals(ConfigurationHelper.KEY_INTERLACE)) {
                watchFace.shouldInterlace(value);
            } else if (configKey.equals(ConfigurationHelper.KEY_INVERT_BLACK_AND_WHITE)) {
                watchFace.setInvertBlackAndWhite(value);
                setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                        .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                        .setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_VISIBLE)
                        .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                        .setAcceptsTapEvents(true)
                        .setShowSystemUiTime(false)
                        .setViewProtectionMode(!value ? WatchFaceStyle.PROGRESS_MODE_NONE : WatchFaceStyle.PROTECT_STATUS_BAR | WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                        .build());
            } else {
                Log.w(TAG, "Ignoring unknown config key: " + configKey);
                return false;
            }
            return true;
        }

        private void unregisterBatteryInfoReceiver() {
            if (!isRegisteredBatteryInfoReceiver) {
                return;
            }
            unregisterReceiver(batteryInfoReceiver);
            isRegisteredBatteryInfoReceiver = false;
        }

        private void registerBatteryInfoReceiver() {
            if (isRegisteredBatteryInfoReceiver) {
                return;
            }
            registerReceiver(batteryInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            isRegisteredBatteryInfoReceiver = true;
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

        /**
         * Activates the next sensors
         */
        private void activateNextSensors() {
            Log.d(TAG, "Activating next available sensor(s)");
            findAndSetAvailableSensorTypes();
            // If there are no sensors to activate exit
            if (availableSensorTypes.size() == 0) {
                return;
            }
            // Find the active sensors position in the available sensors
            int countFound = 0;
            int lastFoundIndex = 0;
            for (Integer availableSensorType : availableSensorTypes) {
                if (!activeSensors.containsKey(availableSensorType)) {
                    continue;
                }
                // Found one
                countFound += 1;
                // If we found all
                if (countFound == maxActiveSensors) {
                    // Get the index that the last was found
                    lastFoundIndex = availableSensorTypes.indexOf(availableSensorType);
                    // Stop we don't need to loop more
                    break;
                }
            }
            // Deactivate all sensors
            deactivateAllSensors();
            // Enable the next ones (+1)
            for (int i = 0; i < maxActiveSensors; i++) {
                // Check if we hit the last
                lastFoundIndex += 1;
                if (lastFoundIndex >= availableSensorTypes.size()) {
                    // Reset the index to start
                    lastFoundIndex = 0;
                }
                // Activate
                activateSensor(availableSensorTypes.get(lastFoundIndex));
            }
        }

        /**
         * Activate a specific type of sensor
         */
        private void activateSensor(Integer sensorType) {
            activeSensors.put(sensorType, CallbackSensorFactory.getCallbackSensor(getApplicationContext(), sensorType, this, this));
            watchFace.addSensorColumn(sensorType);
        }

        /**
         * Deactivate a specific type of sensor
         */
        private void deactivateSensor(Integer sensorType) {
            activeSensors.get(sensorType).stopListening();
            activeSensors.remove(sensorType);
            watchFace.removeSensor(sensorType);
        }

        /**
         * Deactivate all types of active sensors
         */
        private void deactivateAllSensors() {
            for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                deactivateSensor(entry.getKey());
            }
        }

        /**
         * Updates the sunrise and sunset according to a location if possible
         */
        private void updateSunriseAndSunset(@NonNull Location location) {
            Pair<String, String> sunriseSunset = SunriseSunsetHelper.getSunriseAndSunset(location, TimeZone.getDefault().getID());
            watchFace.updateSunriseSunset(sunriseSunset);
            invalidate();
            Log.d(TAG, "Successfully updated sunrise");
        }

        private void startListeningToSensors() {
            for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                entry.getValue().startListening();
            }
        }

        private void calculateAverageForActiveSensors() {
            for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                entry.getValue().getAverage();
            }
        }

        private void stopListeningToSensors() {
            for (Map.Entry<Integer, AveragingCallbackSensor> entry : activeSensors.entrySet()) {
                entry.getValue().stopListening();
            }
        }

        /**
         * Run's tasks according to the current time
         */
        private void runOnTimeTickTasks() {
            Calendar now = Calendar.getInstance();
            if (now.getTimeInMillis() - lastOnTimeTickTasksRun.getTimeInMillis() < RUN_ON_TICK_TASKS_EVERY_MS) {
                return;
            }
            Log.d(TAG, "Running onTimeTickTasks");
            lastOnTimeTickTasksRun = now;

            if (EmulatorHelper.isEmulator()) {
                Location location = new Location("dummy");
                location.setLatitude(41);
                location.setLongitude(11);
                location.setTime(System.currentTimeMillis());
                location.setAccuracy(3.0f);
                updateSunriseAndSunset(location);
                deactivateAllSensors();
                watchFace.addSensorColumn(Sensor.TYPE_HEART_RATE);
                watchFace.updateSensorText(Sensor.TYPE_HEART_RATE, "128");
            }

            calculateAverageForActiveSensors();
        }
    }
}
