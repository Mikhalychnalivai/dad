package com.telescope.model;

/**
 * Небесный объект из каталога.
 *
 * Хранит экваториальные координаты (RA/DEC в градусах),
 * название, тип и описание.
 *
 * RA хранится в часах (0–24), DEC в градусах (–90…+90).
 */
public class CelestialObject {

    /** Тип небесного объекта. */
    public enum Type {
        STAR("Звезда"),
        PLANET("Планета"),
        NEBULA("Туманность"),
        GALAXY("Галактика"),
        CLUSTER("Скопление"),
        OTHER("Прочее");

        private final String displayName;
        Type(String name) { this.displayName = name; }
        public String getDisplayName() { return displayName; }
    }

    private final String name;
    private final Type   type;
    private final double ra;   // прямое восхождение, часы (0–24)
    private final double dec;  // склонение, градусы (–90…+90)
    private final String description;
    private final double magnitude; // звёздная величина (меньше = ярче)

    public CelestialObject(String name, Type type,
                           double ra, double dec,
                           double magnitude, String description) {
        this.name        = name;
        this.type        = type;
        this.ra          = ra;
        this.dec         = dec;
        this.magnitude   = magnitude;
        this.description = description;
    }

    // ── Геттеры ────────────────────────────────────────────────────────────────

    public String getName()        { return name; }
    public Type   getType()        { return type; }
    public double getRa()          { return ra; }
    public double getDec()         { return dec; }
    public double getMagnitude()   { return magnitude; }
    public String getDescription() { return description; }

    /** RA в формате ЧЧ:ММ:СС */
    public String getRaFormatted() {
        int h = (int) ra;
        int m = (int) ((ra - h) * 60);
        int s = (int) (((ra - h) * 60 - m) * 60);
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    /** DEC в формате ±ГГ°ММ'СС" */
    public String getDecFormatted() {
        double absDec = Math.abs(dec);
        int d = (int) absDec;
        int m = (int) ((absDec - d) * 60);
        int s = (int) (((absDec - d) * 60 - m) * 60);
        return String.format("%s%02d°%02d'%02d\"",
                dec >= 0 ? "+" : "-", d, m, s);
    }

    @Override
    public String toString() {
        return name + " (" + type.getDisplayName() + ")";
    }
}
