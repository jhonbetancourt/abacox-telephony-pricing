package com.infomedia.abacox.telephonypricing.constants;

public enum RefTable {
    EMPLOYEE(1),
    EXTENSION_RANGE(2),
    INVENTORY(3);

    private final int id;

    RefTable(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    // Optional: Helper method to get the enum from an ID
    public static RefTable fromId(int id) {
        for (RefTable table : RefTable.values()) {
            if (table.getId() == id) {
                return table;
            }
        }
        throw new IllegalArgumentException("Invalid ref_table ID: " + id);
    }

    public static boolean isValidId(int id) {
        for (RefTable table : RefTable.values()) {
            if (table.getId() == id) {
                return true;
            }
        }
        return false;
    }

    /*
    1	funcionario
    2	rangoext
    3	inventario
    4	presufun
    5	invelocal
    6	inveper
    7	personal
    8	hdiinv
     */
}