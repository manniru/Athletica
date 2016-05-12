package com.dimitrioskanellopoulos.athletica.grid.rows;

import com.dimitrioskanellopoulos.athletica.grid.columns.Column;

public interface RowInterface {
    void addColumn(String name, Column column);

    void removeColumn(String name);

    Column getColumn(String name);

    Column[] getAllColumns();

    Float getVerticalMargin();

    void setVerticalMargin(Float verticalMargin);
}