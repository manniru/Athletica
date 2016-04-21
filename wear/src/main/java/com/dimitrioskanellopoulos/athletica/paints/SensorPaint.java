package com.dimitrioskanellopoulos.athletica.paints;


import com.dimitrioskanellopoulos.athletica.paints.interfaces.SensorPaintInterface;

public class SensorPaint extends TextPaint implements SensorPaintInterface {

    protected String icon;
    protected String units;

    @Override
    public void setText(String text) {
        this.text = text;
    }

    @Override
    public String getText() {
        return getIcon() + " " + text + " " + getUnits();
    }

    @Override
    public String getIcon() {
        return icon;
    }

    @Override
    public String getUnits() {
        return units;
    }
}
