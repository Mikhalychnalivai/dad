package com.telescope.model;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Управляет координатами телескопа.
 *
 * Хранит:
 *  – горизонтальные координаты: азимут (AZ) и высота (ALT)
 *  – экваториальные координаты: прямое восхождение (RA) и склонение (DEC)
 *
 * Все поля реализованы как JavaFX-свойства для двусторонней привязки к UI.
 */
public class CoordinateManager {

    // ── Горизонтальные координаты ──────────────────────────────────────────────

    /** Азимут: 0–360° */
    private final DoubleProperty azimuth = new SimpleDoubleProperty(0.0);

    /** Высота над горизонтом: –90° … +90° */
    private final DoubleProperty altitude = new SimpleDoubleProperty(0.0);

    // ── Экваториальные координаты ──────────────────────────────────────────────

    /** Прямое восхождение в формате ЧЧ:ММ:СС */
    private final StringProperty rightAscension = new SimpleStringProperty("00:00:00");

    /** Склонение в формате ±ГГ°ММ'СС" */
    private final StringProperty declination = new SimpleStringProperty("+00°00'00\"");

    // ── Геттеры / сеттеры свойств ──────────────────────────────────────────────

    public DoubleProperty azimuthProperty()    { return azimuth; }
    public DoubleProperty altitudeProperty()   { return altitude; }
    public StringProperty raProperty()         { return rightAscension; }
    public StringProperty decProperty()        { return declination; }

    public double getAzimuth()  { return azimuth.get(); }
    public double getAltitude() { return altitude.get(); }
    public String getRA()       { return rightAscension.get(); }
    public String getDEC()      { return declination.get(); }

    public void setAzimuth(double value)  { azimuth.set(clampAzimuth(value)); }
    public void setAltitude(double value) { altitude.set(clampAltitude(value)); }
    public void setRA(String value)       { rightAscension.set(value); }
    public void setDEC(String value)      { declination.set(value); }

    // ── Имитация движения ──────────────────────────────────────────────────────

    /**
     * Обновляет горизонтальные координаты на основе шагов двигателей.
     * Вызывается из TelescopeController в демонстрационном режиме.
     *
     * @param deltaAz  изменение азимута в градусах
     * @param deltaAlt изменение высоты в градусах
     */
    public void updateFromMotorSteps(double deltaAz, double deltaAlt) {
        setAzimuth(getAzimuth() + deltaAz);
        setAltitude(getAltitude() + deltaAlt);
        // В реальном приложении здесь будет пересчёт в RA/DEC
        updateEquatorial();
    }

    /**
     * Возвращает отформатированную строку азимута: "123.45°"
     */
    public String getAzimuthFormatted() {
        return String.format("%.2f°", getAzimuth());
    }

    /**
     * Возвращает отформатированную строку высоты: "+45.30°"
     */
    public String getAltitudeFormatted() {
        return String.format("%+.2f°", getAltitude());
    }

    // ── Вспомогательные методы ─────────────────────────────────────────────────

    /** Ограничивает азимут диапазоном [0, 360). */
    private double clampAzimuth(double az) {
        az = az % 360.0;
        return az < 0 ? az + 360.0 : az;
    }

    /** Ограничивает высоту диапазоном [–90, +90]. */
    private double clampAltitude(double alt) {
        return Math.max(-90.0, Math.min(90.0, alt));
    }

    /**
     * Упрощённое «обновление» экваториальных координат для демонстрационного режима.
     * В реальном приложении здесь нужен полноценный астрономический расчёт.
     */
    private void updateEquatorial() {
        // Простая имитация: линейное масштабирование для демо
        double ra = (getAzimuth() / 360.0) * 24.0;
        int raH = (int) ra;
        int raM = (int) ((ra - raH) * 60);
        int raS = (int) (((ra - raH) * 60 - raM) * 60);
        rightAscension.set(String.format("%02d:%02d:%02d", raH, raM, raS));

        double dec = getAltitude();
        int decD = (int) Math.abs(dec);
        int decM = (int) ((Math.abs(dec) - decD) * 60);
        int decS = (int) (((Math.abs(dec) - decD) * 60 - decM) * 60);
        declination.set(String.format("%s%02d°%02d'%02d\"",
                dec >= 0 ? "+" : "-", decD, decM, decS));
    }
}
