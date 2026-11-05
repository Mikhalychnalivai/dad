package com.telescope.model;

import java.time.ZonedDateTime;

/**
 * Астрономические вычисления — перевод координат.
 *
 * Реализует перевод экваториальных координат (RA/DEC) в горизонтальные
 * (AZ/ALT) на основе стандартных астрономических формул.
 *
 * Алгоритм:
 *  1. По дате/времени и долготе наблюдателя вычисляется Звёздное время Гринвича (GST).
 *  2. Из GST и долготы получается Местное звёздное время (LST).
 *  3. Из LST и RA объекта вычисляется Часовой угол (HA).
 *  4. Из HA, DEC и широты наблюдателя вычисляются AZ и ALT.
 *
 * Вся тригонометрия выполняется в стандартной библиотеке Java (java.lang.Math).
 * Внешние зависимости не нужны.
 *
 * ВАЖНО: расчёты дают приближённый результат с точностью ~0.1°,
 * достаточной для любительского телескопа.
 */
public class AstronomyCalculator {

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Результат перевода координат.
     */
    public record HorizontalCoords(double azimuth, double altitude) {
        /** Является ли объект видимым (выше горизонта)? */
        public boolean isVisible() { return altitude > 0; }
    }

    /**
     * Переводит экваториальные координаты объекта в горизонтальные
     * для заданного наблюдателя и момента времени.
     *
     * @param ra       прямое восхождение объекта, часы (0–24)
     * @param dec      склонение объекта, градусы (–90…+90)
     * @param settings настройки наблюдателя (широта, долгота, время)
     * @return горизонтальные координаты (AZ 0–360°, ALT –90…+90°)
     */
    public static HorizontalCoords toHorizontal(double ra, double dec,
                                                ObserverSettings settings) {
        ZonedDateTime time = settings.getObservationTime();
        double latRad  = Math.toRadians(settings.getLatitude());
        double decRad  = Math.toRadians(dec);

        // Местное звёздное время (в часах)
        double lst = localSiderealTime(time, settings.getLongitude());

        // Часовой угол (HA) в градусах
        double ha  = (lst - ra) * 15.0; // часы → градусы
        double haRad = Math.toRadians(ha);

        // Высота над горизонтом
        double sinAlt = Math.sin(decRad) * Math.sin(latRad)
                + Math.cos(decRad) * Math.cos(latRad) * Math.cos(haRad);
        double altDeg = Math.toDegrees(Math.asin(sinAlt));

        // Азимут (0° = север, по часовой стрелке)
        double cosAlt = Math.cos(Math.toRadians(altDeg));
        double cosAz  = (Math.sin(decRad) - Math.sin(latRad) * sinAlt)
                / (Math.cos(latRad) * cosAlt);
        // Клампинг от ошибок округления
        cosAz = Math.max(-1.0, Math.min(1.0, cosAz));

        double azDeg = Math.toDegrees(Math.acos(cosAz));

        // Корректируем четверть азимута по знаку часового угла
        if (Math.sin(haRad) > 0) {
            azDeg = 360.0 - azDeg;
        }

        return new HorizontalCoords(azDeg, altDeg);
    }

    /**
     * Рассчитывает скорость изменения азимута объекта (°/с).
     * Используется TrackingService для определения скорости двигателя.
     *
     * @param ra       RA объекта, часы
     * @param dec      DEC объекта, градусы
     * @param settings настройки наблюдателя
     * @param dt       интервал времени для разностной производной, секунды
     * @return массив [dAZ/dt, dALT/dt] в градусах/секунду
     */
    public static double[] angularVelocity(double ra, double dec,
                                           ObserverSettings settings,
                                           double dt) {
        HorizontalCoords pos0 = toHorizontal(ra, dec, settings);

        // Временно сдвигаем время на dt
        ObserverSettings shifted = shiftedSettings(settings, dt);
        HorizontalCoords pos1 = toHorizontal(ra, dec, shifted);

        double dAz  = angleDiff(pos1.azimuth(),  pos0.azimuth());
        double dAlt = pos1.altitude() - pos0.altitude();

        return new double[]{ dAz / dt, dAlt / dt };
    }

    // ── Звёздное время ────────────────────────────────────────────────────────

    /**
     * Вычисляет Местное звёздное время (LST) в часах.
     * Основано на алгоритме Jean Meeus, "Astronomical Algorithms", гл. 12.
     */
    public static double localSiderealTime(ZonedDateTime time, double longitudeDeg) {
        double jd  = toJulianDate(time);
        double gst = greenwichSiderealTime(jd); // в часах
        double lst = gst + longitudeDeg / 15.0; // +долгота в часах
        return normalizeHours(lst);
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    /**
     * Переводит ZonedDateTime в Юлианскую дату.
     */
    public static double toJulianDate(ZonedDateTime dt) {
        int Y = dt.getYear();
        int M = dt.getMonthValue();
        int D = dt.getDayOfMonth();
        double h = dt.getHour() + dt.getMinute() / 60.0 + dt.getSecond() / 3600.0;

        if (M <= 2) { Y--; M += 12; }

        int A = Y / 100;
        int B = 2 - A + A / 4;

        return Math.floor(365.25 * (Y + 4716))
                + Math.floor(30.6001 * (M + 1))
                + D + h / 24.0 + B - 1524.5;
    }

    /**
     * Звёздное время Гринвича в часах.
     */
    private static double greenwichSiderealTime(double jd) {
        double T = (jd - 2451545.0) / 36525.0; // Юлианские столетия от J2000.0
        // Формула из Meeus (точность ~0.1 сек для 1900–2100)
        double gst0 = 100.4606184 + 36000.77004 * T + 0.000387933 * T * T
                - (T * T * T) / 38710000.0;
        // Добавляем часть суток
        double dayFrac = (jd - Math.floor(jd) - 0.5) * 24.0; // часы UT
        double gst = gst0 / 15.0 + dayFrac * 1.00273790935; // в часах

        return normalizeHours(gst);
    }

    /** Нормализует часы в диапазон [0, 24). */
    private static double normalizeHours(double h) {
        h = h % 24.0;
        return h < 0 ? h + 24.0 : h;
    }

    /** Разность двух азимутов с учётом перехода через 0/360. */
    private static double angleDiff(double a2, double a1) {
        double d = a2 - a1;
        if (d > 180)  d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** Создаёт копию настроек со временем, сдвинутым на dt секунд. */
    private static ObserverSettings shiftedSettings(ObserverSettings src, double dt) {
        ObserverSettings s = new ObserverSettings();
        s.setLatitude(src.getLatitude());
        s.setLongitude(src.getLongitude());
        s.setTimezone(src.getTimezone());
        s.setUseSystemTime(false);
        s.setManualTime(src.getObservationTime()
                .plusSeconds((long) dt)
                .plusNanos((long) ((dt % 1) * 1e9)));
        return s;
    }
}
