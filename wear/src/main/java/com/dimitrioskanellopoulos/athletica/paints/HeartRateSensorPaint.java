package com.dimitrioskanellopoulos.athletica.paints;

public class HeartRateSensorPaint extends SensorPaint {

    private final static String icon = "\uf21e";
    private final static String units = "bpm";

    @Override
    public String getIcon() {
        return icon;
    }

    @Override
    public String getUnits() {
        return units;
    }
}
