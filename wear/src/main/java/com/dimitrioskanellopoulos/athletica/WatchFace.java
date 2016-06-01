package com.dimitrioskanellopoulos.athletica;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.Log;

import com.dimitrioskanellopoulos.athletica.grid.Grid;
import com.dimitrioskanellopoulos.athletica.grid.GridRenderer;
import com.dimitrioskanellopoulos.athletica.grid.columns.AmPmColumn;
import com.dimitrioskanellopoulos.athletica.grid.columns.BatteryIconColumn;
import com.dimitrioskanellopoulos.athletica.grid.columns.BatteryLevelColumn;
import com.dimitrioskanellopoulos.athletica.grid.columns.Column;
import com.dimitrioskanellopoulos.athletica.grid.columns.ColumnFactory;
import com.dimitrioskanellopoulos.athletica.grid.columns.DateColumn;
import com.dimitrioskanellopoulos.athletica.grid.columns.GoogleFitStepsColumn;
import com.dimitrioskanellopoulos.athletica.grid.columns.SunriseColumn;
import com.dimitrioskanellopoulos.athletica.grid.columns.SunsetColumn;
import com.dimitrioskanellopoulos.athletica.grid.columns.TimeColumn;
import com.dimitrioskanellopoulos.athletica.grid.rows.Row;
import com.dimitrioskanellopoulos.athletica.helpers.EmulatorHelper;
import com.dimitrioskanellopoulos.athletica.helpers.SunriseSunsetHelper;

import java.util.Map;

public class WatchFace {
    private static final String TAG = "Watchface";

    private static final Grid grid = new Grid();

    private final Context context;
    private final Resources resources;

    private final Typeface fontAwesome;
    private Typeface defaultTypeface;
    private boolean isRound;
    private boolean ambientMode = false;
    private boolean isVisible = false;
    private boolean interlace = true;
    private boolean dayNightMode = false;

    private int chinSize;
    private Boolean invertBlackAndWhite;

    /**
     * The WatchFace. Everything the user sees. No extra init or data manipulation
     */
    public WatchFace(Context context) {

        this.context = context;
        resources = context.getResources();

        // Default typeface
        defaultTypeface = Typeface.SANS_SERIF;

        // Add FontAwesome paint for icons
        fontAwesome = Typeface.createFromAsset(context.getAssets(), "fonts/fontawesome-webfont.ttf");

        // Add Row for time
        addRowForTime();

        // Add Row for date
        addRowForDate();

        // Add Row for sunrise and sunset
        addRowForSunriseSunset();

        grid.putRow("sensorsRow", new Row());


        // Add Row for battery level
        addRowForBattery();
    }



    public void draw(Canvas canvas, Rect bounds) {

        GridRenderer.renderGrid(canvas, bounds, grid, chinSize);

        if (interlace) {
            GridRenderer.interlaceCanvas(canvas, bounds, Color.BLACK, ambientMode ? 100 : 70);
        }
    }

    private void addRowForTime() {
        Row timeRow = new Row();
        TimeColumn timeColumn = new TimeColumn(context, defaultTypeface, resources.getDimension(R.dimen.time_size), grid.getTextColor());
        timeRow.putColumn("timeColumn", timeColumn);
        grid.putRow("timeRow", timeRow);
    }

    private void addRowForDate() {
        Row dateRow = new Row();
        DateColumn dateColumn = new DateColumn(context, defaultTypeface, resources.getDimension(R.dimen.date_size), grid.getTextColor());
        dateRow.putColumn("dateColumn", dateColumn);
        grid.putRow("dateRow", dateRow);
    }

    private void addRowForSunriseSunset() {
        Row sunriseSunsetRow = new Row();
        // Icon
        Column sunriseIconColumn = new Column(context, fontAwesome, resources.getDimension(R.dimen.icon_size), grid.getTextColor());
        sunriseIconColumn.setText(resources.getString(R.string.icon_sunrise));
        sunriseIconColumn.setHorizontalMargin(resources.getDimension(R.dimen.icon_margin));
        sunriseSunsetRow.putColumn("sunriseIconColumn", sunriseIconColumn);
        // Column
        SunriseColumn sunriseColumn = new SunriseColumn(context, defaultTypeface, resources.getDimension(R.dimen.text_size), grid.getTextColor());
        sunriseColumn.setHorizontalMargin(resources.getDimension(R.dimen.column_margin));
        sunriseSunsetRow.putColumn("sunriseColumn", sunriseColumn);

        // Icon
        Column sunsetIconColumn = new Column(context, fontAwesome, resources.getDimension(R.dimen.icon_size), grid.getTextColor());
        sunsetIconColumn.setHorizontalMargin(resources.getDimension(R.dimen.icon_margin));
        sunsetIconColumn.setText(resources.getString(R.string.icon_sunset));
        sunriseSunsetRow.putColumn("sunsetIconColumn", sunsetIconColumn);

        // Column
        SunsetColumn sunsetColumn = new SunsetColumn(context, defaultTypeface, resources.getDimension(R.dimen.text_size), grid.getTextColor());
        sunriseSunsetRow.putColumn("sunsetColumn", sunsetColumn);

        grid.putRow("sunriseSunsetRow", sunriseSunsetRow);
    }

    public Boolean hasSensorColumn(Integer sensorType) {
        if (grid.getRow("sensorsRow").getColumn(sensorType.toString()) != null) {
            return true;
        }
        return false;
    }

    public void addSensorColumn(Integer sensorType) {
        // Add icon
        if (grid.getRow("sensorsRow").getAllColumns().size() >= 3) {
            grid.getRow("sensorsRow").getAllColumns().values().toArray(new Column[0])[grid.getRow("sensorsRow").getAllColumns().size() - 1].setHorizontalMargin(resources.getDimension(R.dimen.column_margin));
        }
        Column sensorIconColumn = ColumnFactory.getIconColumnForSensorType(context, sensorType, fontAwesome, resources.getDimension(R.dimen.icon_size), grid.getTextColor());
        sensorIconColumn.setHorizontalMargin(resources.getDimension(R.dimen.icon_margin));
        grid.getRow("sensorsRow").putColumn(sensorType.toString() + "_icon", sensorIconColumn);

        // Add column depending on emu
        Column sensorColumn;
        if (EmulatorHelper.isEmulator()){
            sensorColumn = new Column(context, defaultTypeface, resources.getDimension(R.dimen.text_size), grid.getTextColor());
            sensorColumn.setText("60");
        }else {
            sensorColumn = ColumnFactory.getColumnForSensorType(context, sensorType, defaultTypeface, resources.getDimension(R.dimen.text_size), grid.getTextColor());
        }

        sensorColumn.setIsVisible(isVisible);
        sensorColumn.setHorizontalMargin(resources.getDimension(R.dimen.units_margin));
        grid.getRow("sensorsRow").putColumn(sensorType.toString(), sensorColumn);

        // Add units
        Column sensorUnitsColumn = ColumnFactory.getUnitsColumnForSensorType(context, sensorType, defaultTypeface, resources.getDimension(R.dimen.units_size), grid.getTextColor());
        grid.getRow("sensorsRow").putColumn(sensorType.toString() + "_units", sensorUnitsColumn);
    }

    public void removeAllSensorColumns() {
        grid.getRow("sensorsRow").removeAllColumns();
    }

    public void removeSensorColumn(Integer sensorType) {
        // @todo should stop etc
        grid.getRow("sensorsRow").removeColumn(sensorType.toString() + "_icon");
        grid.getRow("sensorsRow").removeColumn(sensorType.toString());
        grid.getRow("sensorsRow").removeColumn(sensorType.toString() + "_units");
    }

    private void addRowForBattery() {
        Row batteryRow = new Row();
        batteryRow.setMarginBottom(resources.getDimension(R.dimen.row_margin_bottom));
        //Icon
        BatteryIconColumn batteryIconColumn = new BatteryIconColumn(context, fontAwesome, resources.getDimension(R.dimen.icon_size), grid.getTextColor());
        batteryIconColumn.setHorizontalMargin(resources.getDimension(R.dimen.icon_margin));
        batteryRow.putColumn("batteryIconColumn", batteryIconColumn);

        // Column
        BatteryLevelColumn batteryLevelColumn = new BatteryLevelColumn(context, defaultTypeface, resources.getDimension(R.dimen.battery_text_size), grid.getTextColor());
        batteryRow.putColumn("batteryLevelColumn", batteryLevelColumn);

        grid.putRow("batteryRow", batteryRow);
    }

    /**
     * Toggles the ambient or not mode for all the paints
     */
    public void setInAmbientMode(boolean inAmbientMode) {
        ambientMode = inAmbientMode;
        grid.setInAmbientMode(inAmbientMode);
    }

    /**
     * Toggles the visible or not mode for all the columns
     */
    public void setIsVisible(boolean isVisible) {
        this.isVisible = isVisible;
        grid.setIsVisible(isVisible);
    }

    /**
     * Run's tasks for every column
     */
    public void runTasks() {
        // Fow now do it only on the sensor columns
        for (Map.Entry<String, Column> columnEntry : grid.getRow("sensorsRow").getAllColumns().entrySet()) {
            Column column = columnEntry.getValue();
            column.runTasks();
        }
        setGridColors();
    }


    public void setTimeFormat24(Boolean timeFormat24) {
        TimeColumn timeColumn = (TimeColumn) grid.getRow("timeRow").getColumn("timeColumn");
        timeColumn.setTimeFormat24(timeFormat24);

        if (timeFormat24) {
            grid.getRow("timeRow").removeColumn("amPmColumn");
        } else {
            AmPmColumn amPmColumn = new AmPmColumn(context, defaultTypeface, resources.getDimension(R.dimen.time_am_pm_size), grid.getTextColor());
            grid.getRow("timeRow").putColumn("amPmColumn", amPmColumn);
        }
    }

    public void setShowDateNamesFormat(Boolean showDateNamesFormat) {
        DateColumn dateColumn = (DateColumn) grid.getRow("dateRow").getColumn("dateColumn");
        dateColumn.setShowDateNamesFormat(showDateNamesFormat);
    }

    public void shouldInterlace(Boolean shouldInterlace) {
        this.interlace = shouldInterlace;
    }

    public void setInvertBlackAndWhite(Boolean invertBlackAndWhite) {
        this.invertBlackAndWhite = invertBlackAndWhite;
        setGridColors();
    }

    public void showGoogleFitSteps(Boolean showGoogleFitSteps){
        Log.d(TAG, "Show steps " + showGoogleFitSteps.toString());
        if (showGoogleFitSteps){
            if (grid.getRow("googleFitRow") == null ) {
                // Icon
                Column googleFitStepsIconColumn = new Column(context, fontAwesome, resources.getDimension(R.dimen.icon_size), grid.getTextColor());
                googleFitStepsIconColumn.setHorizontalMargin(resources.getDimension(R.dimen.icon_margin));
                googleFitStepsIconColumn.setText(resources.getString(R.string.icon_google_fit_steps));
                Row googleFitRow = new Row();
                googleFitRow.putColumn("googleFitSteps_icon", googleFitStepsIconColumn);

                // Steps
                GoogleFitStepsColumn googleFitStepsColumn = new GoogleFitStepsColumn(context, defaultTypeface, resources.getDimension(R.dimen.text_size), grid.getTextColor());
                googleFitRow.putColumn("googleFitSteps", googleFitStepsColumn);
                grid.putRow("googleFitRow", googleFitRow);
            }
        }else{
            grid.removeRow("googleFitRow");
        }
    }

    private void setGridColors() {
        if (invertBlackAndWhite) {
            if (dayNightMode) {
                grid.setBackgroundColor(SunriseSunsetHelper.isDay() ? Color.WHITE : Color.BLACK);
                grid.setTextColor(SunriseSunsetHelper.isDay() ? Color.BLACK : Color.WHITE);
            } else {
                grid.setBackgroundColor(Color.WHITE);
                grid.setTextColor(Color.BLACK);
            }
        } else {
            if (dayNightMode) {
                grid.setBackgroundColor(SunriseSunsetHelper.isDay() ? Color.BLACK : Color.WHITE);
                grid.setTextColor(SunriseSunsetHelper.isDay() ? Color.WHITE : Color.BLACK);
            } else {
                grid.setBackgroundColor(Color.BLACK);
                grid.setTextColor(Color.WHITE);
            }
        }

    }

    public void setIsRound(boolean round) {
        isRound = round;
    }

    public void setChinSize(Integer chinSize) {
        this.chinSize = chinSize;
    }

    public void setDayNightMode(boolean dayNightMode) {
        this.dayNightMode = dayNightMode;
    }
}
