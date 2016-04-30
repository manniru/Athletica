package com.dimitrioskanellopoulos.athletica.paints;

public class PressureSensorPaint extends SensorPaint {

    private final static String icon = "\uf0c3";
    private final static String units = "hPa";

    @Override
    public String getIcon() {
        return icon;
    }

    @Override
    public String getUnits() {
        return units;
    }
}
