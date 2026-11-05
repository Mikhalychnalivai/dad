package com.telescope.model;

import javafx.beans.property.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Настройки наблюдателя: местоположение, часовой пояс и время.
 *
 * Используются для перевода экваториальных координат (RA/DEC)
 * в горизонтальные (AZ/ALT) с учётом места и момента наблюдения.
 *
 * Все поля — JavaFX-свойства для двусторонней привязки к UI.
 */
public class ObserverSettings {

    // ── Местоположение ─────────────────────────────────────────────────────────

    /** Географическая широта наблюдателя, градусы (–90 … +90). */
    private final DoubleProperty latitude = new SimpleDoubleProperty(55.751244); // Москва по умолчанию

    /** Географическая долгота наблюдателя, градусы (–180 … +180). */
    private final DoubleProperty longitude = new SimpleDoubleProperty(37.618423);

    // ── Часовой пояс ───────────────────────────────────────────────────────────

    /** Идентификатор часового пояса, например "Europe/Moscow". */
    private final StringProperty timezone = new SimpleStringProperty("Europe/Moscow");

    // ── Переключатель: использовать системное время или заданное ──────────────

    /** true — использовать текущее системное время. */
    private final BooleanProperty useSystemTime = new SimpleBooleanProperty(true);

    /** Вручную заданное время наблюдения (если useSystemTime = false). */
    private volatile ZonedDateTime manualTime = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));

    // ── Геттеры / сеттеры ──────────────────────────────────────────────────────

    public DoubleProperty  latitudeProperty()    { return latitude; }
    public DoubleProperty  longitudeProperty()   { return longitude; }
    public StringProperty  timezoneProperty()    { return timezone; }
    public BooleanProperty useSystemTimeProperty(){ return useSystemTime; }

    public double getLatitude()   { return latitude.get(); }
    public double getLongitude()  { return longitude.get(); }
    public String getTimezone()   { return timezone.get(); }
    public boolean isUseSystemTime() { return useSystemTime.get(); }

    public void setLatitude(double v)  { latitude.set(clamp(v, -90, 90)); }
    public void setLongitude(double v) { longitude.set(clamp(v, -180, 180)); }
    public void setTimezone(String tz) { timezone.set(tz); }
    public void setUseSystemTime(boolean v) { useSystemTime.set(v); }
    public void setManualTime(ZonedDateTime t) { this.manualTime = t; }

    /**
     * Возвращает актуальное время наблюдения.
     * Если useSystemTime = true — текущее системное время в заданном часовом поясе.
     * Иначе — вручную установленное время.
     */
    public ZonedDateTime getObservationTime() {
        if (isUseSystemTime()) {
            try {
                return ZonedDateTime.now(ZoneId.of(getTimezone()));
            } catch (Exception e) {
                return ZonedDateTime.now();
            }
        }
        return manualTime;
    }

    /** Краткое описание местоположения для отображения в UI. */
    public String getLocationSummary() {
        return String.format("%.4f°, %.4f°", getLatitude(), getLongitude());
    }

    // ── Вспомогательные методы ─────────────────────────────────────────────────

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
