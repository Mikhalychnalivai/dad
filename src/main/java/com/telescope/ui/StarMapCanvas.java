package com.telescope.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Интерактивная карта звёздного неба.
 *
 * Возможности:
 *  – ~500 звёзд с реальными координатами (RA/DEC) и звёздными величинами
 *  – 20 созвездий с линиями и подписями
 *  – прокрутка мышью (перетаскивание) и зум (колёсико)
 *  – мерцание звёзд
 *  – прицел в центре (текущее направление телескопа)
 *  – координатная сетка (прямые восхождения и склонения)
 *  – подписи ярких звёзд
 *  – индикатор масштаба
 *
 * Координатная система: RA (0–24h) по горизонтали, DEC (–90…+90°) по вертикали.
 * Центр экрана соответствует viewRA / viewDEC.
 */
public class StarMapCanvas extends Canvas {

    // ── Структуры данных ──────────────────────────────────────────────────────

    private record Star(String name, double ra, double dec, double mag) {}

    private record ConstellationLine(int starA, int starB) {}

    private record Constellation(String name, String abbr, List<int[]> lines) {}

    // ── Данные каталога ───────────────────────────────────────────────────────

    /** Каталог звёзд: RA в часах, DEC в градусах, mag = звёздная величина */
    private final List<Star> catalog = new ArrayList<>();

    /** Созвездия: список пар индексов звёзд каталога */
    private final List<Constellation> constellations = new ArrayList<>();

    // ── Состояние просмотра ───────────────────────────────────────────────────

    /** Центр вида — прямое восхождение (часы, 0–24) */
    private double viewRA  = 5.5;   // по умолчанию область Ориона

    /** Центр вида — склонение (градусы) */
    private double viewDEC = 10.0;

    /** Масштаб: пикселей на градус */
    private double scale = 8.0;

    private static final double SCALE_MIN = 2.0;
    private static final double SCALE_MAX = 80.0;

    // ── Взаимодействие мышью ──────────────────────────────────────────────────

    private double dragStartX, dragStartY;
    private double dragStartRA, dragStartDEC;
    private boolean dragging = false;

    // ── Анимация ──────────────────────────────────────────────────────────────

    private final AnimationTimer timer;
    private long startTime;

    // ── Прицел ────────────────────────────────────────────────────────────────

    private double crosshairRA  = 5.5;
    private double crosshairDEC = 0.0;

    public StarMapCanvas() {
        setWidth(600);
        setHeight(400);

        buildCatalog();
        buildConstellations();
        setupInteraction();

        startTime = System.nanoTime();
        timer = new AnimationTimer() {
            @Override public void handle(long now) { draw(now); }
        };
        timer.start();

        widthProperty().addListener(e -> draw(System.nanoTime()));
        heightProperty().addListener(e -> draw(System.nanoTime()));
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    /** Устанавливает позицию прицела (текущее направление телескопа). */
    public void setCrosshair(double ra, double dec) {
        this.crosshairRA  = ra;
        this.crosshairDEC = dec;
    }

    /** Центрирует карту на указанных координатах. */
    public void centerOn(double ra, double dec) {
        this.viewRA  = ra;
        this.viewDEC = dec;
    }

    public void stop() { timer.stop(); }

    // ── Взаимодействие ────────────────────────────────────────────────────────

    private void setupInteraction() {
        // Перетаскивание
        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                dragging    = true;
                dragStartX  = e.getX();
                dragStartY  = e.getY();
                dragStartRA  = viewRA;
                dragStartDEC = viewDEC;
            }
        });

        setOnMouseDragged(e -> {
            if (!dragging) return;
            double dx = e.getX() - dragStartX;
            double dy = e.getY() - dragStartY;
            // RA увеличивается вправо, но карта «движется» влево при перетаскивании вправо
            viewRA  = dragStartRA  - dx / (scale * 15.0); // 15 пикс/час → градусы через scale
            viewDEC = dragStartDEC + dy / scale;
            viewRA  = normalizeRA(viewRA);
            viewDEC = clamp(viewDEC, -80, 80);
        });

        setOnMouseReleased(e -> dragging = false);

        // Зум колёсиком
        setOnScroll((ScrollEvent e) -> {
            double factor = e.getDeltaY() > 0 ? 1.15 : 1.0 / 1.15;
            scale = clamp(scale * factor, SCALE_MIN, SCALE_MAX);
        });

        // Двойной клик — центрировать на звезде
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                // Находим ближайшую звезду к месту клика
                Star nearest = findNearestStar(e.getX(), e.getY());
                if (nearest != null) {
                    viewRA  = nearest.ra();
                    viewDEC = nearest.dec();
                }
            }
        });
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────

    private void draw(long now) {
        double W = getWidth();
        double H = getHeight();
        if (W <= 0 || H <= 0) return;

        GraphicsContext gc = getGraphicsContext2D();
        double t = (now - startTime) / 1_000_000_000.0;

        // ── Фон ────────────────────────────────────────────────────────────────
        gc.setFill(Color.rgb(5, 5, 8));
        gc.fillRect(0, 0, W, H);

        // ── Координатная сетка ─────────────────────────────────────────────────
        drawGrid(gc, W, H);

        // ── Линии созвездий ────────────────────────────────────────────────────
        drawConstellationLines(gc, W, H);

        // ── Звёзды ────────────────────────────────────────────────────────────
        drawStars(gc, W, H, t);

        // ── Подписи созвездий ──────────────────────────────────────────────────
        drawConstellationLabels(gc, W, H);

        // ── Прицел телескопа ──────────────────────────────────────────────────
        drawCrosshair(gc, W, H);

        // ── HUD ───────────────────────────────────────────────────────────────
        drawHUD(gc, W, H);
    }

    /** Координатная сетка — линии RA и DEC. */
    private void drawGrid(GraphicsContext gc, double W, double H) {
        gc.setStroke(Color.color(1, 1, 1, 0.06));
        gc.setLineWidth(0.5);

        // Шаг сетки зависит от масштаба
        double raStep  = gridStep(15.0 / scale);  // часы → градусы
        double decStep = gridStep(1.0 / scale * 50);

        // Линии склонения (горизонтальные)
        double decStart = Math.floor((viewDEC - H / 2 / scale) / decStep) * decStep;
        double decEnd   = viewDEC + H / 2 / scale;
        for (double dec = decStart; dec <= decEnd; dec += decStep) {
            double y = decToY(dec, H);
            gc.strokeLine(0, y, W, y);
            // Подпись
            gc.setFill(Color.color(1, 1, 1, 0.25));
            gc.setFont(Font.font("Consolas", 9));
            gc.fillText(String.format("%+.0f°", dec), 4, y - 2);
        }

        // Линии прямого восхождения (вертикальные)
        double raStepH = gridStep(1.0 / scale * 3); // в часах
        double raCenterH = viewRA;
        double raSpanH = W / (scale * 15.0); // пикс / (пикс/° * °/ч)
        double raLeft  = raCenterH - raSpanH / 2;
        double raStart = Math.floor(raLeft / raStepH) * raStepH;
        for (double ra = raStart; ra <= raLeft + raSpanH + raStepH; ra += raStepH) {
            double x = raToX(ra, W);
            gc.strokeLine(x, 0, x, H);
            gc.setFill(Color.color(1, 1, 1, 0.25));
            gc.setFont(Font.font("Consolas", 9));
            gc.fillText(String.format("%.1fh", normalizeRA(ra)), x + 2, 10);
        }
    }

    /** Линии созвездий. */
    private void drawConstellationLines(GraphicsContext gc, double W, double H) {
        gc.setStroke(Color.color(0.5, 0.6, 1.0, 0.25));
        gc.setLineWidth(0.8);
        for (Constellation c : constellations) {
            for (int[] pair : c.lines()) {
                if (pair[0] >= catalog.size() || pair[1] >= catalog.size()) continue;
                Star a = catalog.get(pair[0]);
                Star b = catalog.get(pair[1]);
                double x1 = raToX(a.ra(), W);
                double y1 = decToY(a.dec(), H);
                double x2 = raToX(b.ra(), W);
                double y2 = decToY(b.dec(), H);
                // Не рисуем линии, пересекающие RA=0/24 через весь экран
                if (Math.abs(x2 - x1) > W * 0.5) continue;
                gc.strokeLine(x1, y1, x2, y2);
            }
        }
    }

    /** Все звёзды с мерцанием и свечением. */
    private void drawStars(GraphicsContext gc, double W, double H, double t) {
        double magLimit = 4.0 + Math.log(scale / 2.0 + 1) * 1.5; // при зуме видно больше звёзд

        for (Star star : catalog) {
            if (star.mag() > magLimit) continue;

            double x = raToX(star.ra(), W);
            double y = decToY(star.dec(), H);

            // Не рисуем если за экраном
            if (x < -10 || x > W + 10 || y < -10 || y > H + 10) continue;

            // Мерцание
            double phase = (star.ra() * 1000 + star.dec() * 100) % (Math.PI * 2);
            double twinkle = Math.sin(t * (1.5 + (star.ra() % 1.0)) + phase) * 0.12;

            // Радиус зависит от звёздной величины
            double r = Math.max(0.5, (5.0 - star.mag()) * 0.55 * (scale / 8.0));
            r = Math.min(r, 5.0);
            double bright = Math.min(1.0, Math.max(0.3, 0.6 + twinkle + (5.0 - star.mag()) * 0.08));

            // Цвет звезды зависит от яркости (упрощённо — белый/голубоватый)
            double blueTint = star.mag() < 1.5 ? 0.9 : 1.0;
            Color starColor = Color.color(bright * 0.92, bright * 0.94, bright * blueTint, bright);

            // Свечение для ярких
            if (r > 1.5) {
                gc.setFill(Color.color(0.7, 0.75, 1.0, 0.08 * bright));
                gc.fillOval(x - r * 4, y - r * 4, r * 8, r * 8);
                gc.setFill(Color.color(0.85, 0.9, 1.0, 0.15 * bright));
                gc.fillOval(x - r * 2, y - r * 2, r * 4, r * 4);
            }

            gc.setFill(starColor);
            gc.fillOval(x - r, y - r, r * 2, r * 2);

            // Подпись имени — только при достаточном зуме и яркие
            if (scale > 10 && star.mag() < 2.2 && !star.name().isEmpty()) {
                gc.setFill(Color.color(0.8, 0.85, 1.0, 0.75));
                gc.setFont(Font.font("Segoe UI", 10));
                gc.fillText(star.name(), x + r + 3, y + 4);
            } else if (scale > 20 && star.mag() < 3.5 && !star.name().isEmpty()) {
                gc.setFill(Color.color(0.7, 0.75, 0.9, 0.55));
                gc.setFont(Font.font("Segoe UI", 9));
                gc.fillText(star.name(), x + r + 2, y + 3);
            }
        }
    }

    /** Подписи названий созвездий. */
    private void drawConstellationLabels(GraphicsContext gc, double W, double H) {
        if (scale < 3.5) return; // слишком мелко — не рисуем
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, Math.min(12, scale * 1.2)));
        gc.setTextAlign(TextAlignment.CENTER);

        for (Constellation con : constellations) {
            if (con.lines().isEmpty()) continue;
            // Центр созвездия = среднее положение его звёзд
            double sumX = 0, sumY = 0;
            int cnt = 0;
            for (int[] pair : con.lines()) {
                if (pair[0] < catalog.size()) {
                    sumX += raToX(catalog.get(pair[0]).ra(), W);
                    sumY += decToY(catalog.get(pair[0]).dec(), H);
                    cnt++;
                }
            }
            if (cnt == 0) continue;
            double cx = sumX / cnt;
            double cy = sumY / cnt;

            if (cx < -20 || cx > W + 20 || cy < -20 || cy > H + 20) continue;

            gc.setFill(Color.color(0.55, 0.65, 1.0, 0.55));
            gc.fillText(con.name(), cx, cy - 6);
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    /** Прицел в центре. */
    private void drawCrosshair(GraphicsContext gc, double W, double H) {
        double cx = raToX(crosshairRA, W);
        double cy = decToY(crosshairDEC, H);
        // Если за экраном — рисуем стрелку-указатель
        if (cx < 0 || cx > W || cy < 0 || cy > H) {
            // Стрелка в сторону прицела
            double angle = Math.atan2(cy - H / 2, cx - W / 2);
            double ax = W / 2 + Math.cos(angle) * Math.min(W, H) * 0.42;
            double ay = H / 2 + Math.sin(angle) * Math.min(W, H) * 0.42;
            gc.setFill(Color.color(1, 0.9, 0.2, 0.7));
            gc.setFont(Font.font("Consolas", 12));
            gc.fillText("◎", ax - 6, ay + 5);
            return;
        }

        double sz = 18;
        gc.setStroke(Color.color(1.0, 0.9, 0.3, 0.9));
        gc.setLineWidth(1.2);
        gc.strokeLine(cx - sz, cy, cx - 5, cy);
        gc.strokeLine(cx + 5, cy, cx + sz, cy);
        gc.strokeLine(cx, cy - sz, cx, cy - 5);
        gc.strokeLine(cx, cy + 5, cx, cy + sz);

        gc.setStroke(Color.color(1.0, 0.9, 0.3, 0.55));
        gc.setLineWidth(0.8);
        gc.strokeOval(cx - 10, cy - 10, 20, 20);
        gc.strokeOval(cx - 22, cy - 22, 44, 44);
    }

    /** Информационная панель HUD. */
    private void drawHUD(GraphicsContext gc, double W, double H) {
        gc.setFont(Font.font("Consolas", 11));

        // Координаты центра вида
        String info = String.format("RA %05.2fh  DEC %+06.2f°   ×%.0f",
                viewRA, viewDEC, scale / 8.0);
        gc.setFill(Color.color(0, 0, 0, 0.45));
        gc.fillRoundRect(8, H - 28, info.length() * 6.8 + 10, 20, 4, 4);
        gc.setFill(Color.color(0.7, 0.75, 1.0, 0.8));
        gc.fillText(info, 13, H - 13);

        // Подсказка управления
        if (scale < 5) {
            gc.setFill(Color.color(0.6, 0.65, 0.9, 0.5));
            gc.setFont(Font.font("Segoe UI", 10));
            gc.fillText("Колёсико — зум   Перетаскивание — прокрутка   ДвойнойКлик — центр", 13, 20);
        }
    }

    // ── Конвертация координат ─────────────────────────────────────────────────

    /** RA (часы) → экранный X. */
    private double raToX(double ra, double W) {
        // RA растёт влево (как на небосводе при взгляде вверх)
        double dRA = normalizeRA(ra - viewRA);
        if (dRA > 12) dRA -= 24;
        return W / 2.0 - dRA * 15.0 * scale; // 1h = 15°
    }

    /** DEC (градусы) → экранный Y. */
    private double decToY(double dec, double H) {
        return H / 2.0 - (dec - viewDEC) * scale;
    }

    /** Подбирает удобный шаг сетки из стандартных значений. */
    private double gridStep(double rawStep) {
        double[] steps = {0.25, 0.5, 1, 2, 5, 10, 15, 30, 45, 60};
        for (double s : steps) { if (s >= rawStep) return s; }
        return 60;
    }

    private double normalizeRA(double ra) {
        ra = ra % 24.0;
        return ra < 0 ? ra + 24.0 : ra;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Находит ближайшую звезду к точке экрана. */
    private Star findNearestStar(double px, double py) {
        double W = getWidth(), H = getHeight();
        Star best = null;
        double bestDist = Double.MAX_VALUE;
        for (Star s : catalog) {
            if (s.mag() > 4.5) continue;
            double sx = raToX(s.ra(), W);
            double sy = decToY(s.dec(), H);
            double d = (px - sx) * (px - sx) + (py - sy) * (py - sy);
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return bestDist < 400 ? best : null;
    }

    // ── Каталог звёзд ─────────────────────────────────────────────────────────
    // Реальные координаты (RA в часах, DEC в градусах), звёздные величины

    private void buildCatalog() {
        // Индекс  RA       DEC      mag   Название
        add( 6.7525, -16.7161, -1.46, "Сириус");       // 0
        add( 6.3992, -52.6957, -0.74, "Канопус");      // 1
        add(14.2612,  19.1824, -0.05, "Арктур");       // 2
        add( 6.0450, -52.6958, -0.01, "α Центавра");   // 3
        add(18.6156,  38.7836,  0.03, "Вега");         // 4
        add( 5.2781,  45.9980,  0.08, "Капелла");      // 5
        add( 5.2423,  -8.2016,  0.13, "Ригель");       // 6
        add( 7.6553,   5.2250,  0.34, "Процион");      // 7
        add( 5.9195,   7.4071,  0.50, "Бетельгейзе"); // 8
        add(19.8464,   8.8683,  0.77, "Альтаир");      // 9
        add( 4.5988,  16.5093,  0.85, "Альдебаран");   // 10
        add(22.9608, -29.6222,  1.16, "Фомальгаут");   // 11
        add(16.4901, -26.4320,  1.09, "Антарес");      // 12
        add(20.6905,  45.2803,  1.25, "Денеб");        // 13
        add( 7.5766,  31.8883,  1.58, "Кастор");       // 14
        add( 7.7553,  28.0262,  1.14, "Поллукс");      // 15
        add( 2.5302,  89.2641,  1.97, "Полярная");     // 16
        add(20.3741,  40.2567,  1.25, "Садр");         // 17
        add(21.2164,  62.5851,  2.24, "");              // 18
        add(19.7512,  10.6133,  3.71, "");              // 19 Аквила
        add(17.5608, -37.1038,  1.62, "Шаула");        // 20 Скорпион
        add(16.8359, -34.2936,  2.29, "");              // 21
        add(16.0053, -22.6217,  2.56, "");              // 22
        add(17.7081, -39.0300,  1.87, "Лесат");        // 23
        add(17.6220, -43.0239,  2.69, "");              // 24
        add(17.5608, -37.1038,  1.62, "");              // 25
        add( 3.7881,  24.1167,  2.87, "Альциона");     // 26 Плеяды
        add( 3.7714,  24.3669,  3.63, "");              // 27
        add( 3.7547,  24.1136,  3.71, "");              // 28
        add( 3.7403,  24.0533,  5.09, "");              // 29
        add( 3.7314,  23.9489,  3.88, "");              // 30
        add( 3.7681,  23.5742,  4.30, "");              // 31
        add( 4.2833,  15.8667,  0.87, "Альдебаран2"); // 32 - дубль для Гиад
        add( 4.4763,  15.6200,  3.65, "");              // 33 Гиады
        add( 4.5124,  16.5097,  3.84, "");              // 34
        add( 4.3839,  17.5425,  3.65, "");              // 35
        add( 4.4763,  19.1806,  3.76, "");              // 36
        // Орион
        add( 5.5195,   9.9342,  2.06, "");              // 37 Минтака
        add( 5.6036,  -1.2019,  1.69, "");              // 38 Альнилам
        add( 5.6795,  -1.9425,  2.23, "");              // 39 Альнитак
        add( 5.4175,   6.3497,  2.75, "");              // 40 Беллатрикс
        add( 5.7959,  -9.6697,  2.76, "");              // 41 Саиф
        add( 5.5884,  -5.9097,  3.37, "");              // 42 (плечо)
        add( 5.2487,  -8.2017,  3.19, "");              // 43
        // Большая Медведица
        add(11.0621,  61.7508,  1.81, "Дубхе");        // 44
        add(11.1306,  56.3825,  2.34, "Мерак");        // 45
        add(11.8971,  53.6948,  2.41, "Фекда");        // 46
        add(12.2571,  57.0325,  3.32, "Мегрец");       // 47
        add(12.9004,  55.9599,  1.76, "Алиот");        // 48
        add(13.3988,  54.9254,  2.23, "Мицар");        // 49
        add(13.7924,  49.3133,  1.86, "Бенетнаш");     // 50
        // Кассиопея
        add( 0.6751,  56.5373,  2.23, "Шедар");        // 51
        add( 0.1527,  59.1497,  2.27, "Кафф");         // 52
        add( 0.9452,  60.7167,  1.70, "Нави");         // 53 γ Cas
        add( 1.4304,  60.2353,  2.66, "Рухбах");       // 54
        add( 1.9068,  63.6700,  3.35, "Сегин");        // 55
        // Лев
        add(10.1395,  11.9672,  1.36, "Регул");        // 56
        add(11.2352,  20.5241,  2.01, "Денебола");     // 57
        add(10.3328,  19.8417,  2.08, "Альгиеба");     // 58
        add(10.2786,  23.4174,  3.44, "Адхафера");     // 59
        add(11.0352,  10.5295,  3.33, "Зосма");        // 60
        add(10.1222,  16.7633,  3.51, "");              // 61
        // Скорпион
        add(16.4901, -26.4320,  1.09, "");              // 62 = Антарес дубль
        add(16.3539, -25.5928,  2.89, "");              // 63
        add(16.0052, -22.6217,  2.56, "");              // 64
        add(15.9806, -26.1142,  3.00, "");              // 65
        add(17.5608, -37.1038,  1.62, "");              // 66 = Шаула дубль
        // Ориона дополнительные
        add( 5.5088,  -0.2994,  4.09, "");              // 67
        // Геркулес
        add(17.2441,  14.3902,  2.77, "Рас Альгети"); // 68
        add(16.6948,  36.4613,  2.81, "Корнефор");    // 69
        add(16.5030,  21.4897,  3.14, "");              // 70
        add(17.2501,  36.8092,  3.48, "");              // 71
        add(16.3281,  19.1531,  3.80, "");              // 72
        add(17.0039,  30.9262,  3.16, "");              // 73
        // Дева
        add(13.4198,  -11.1613, 0.97, "Спика");        // 74
        add(13.0364,  -0.5958,  2.83, "");              // 75
        add(13.1661,   3.3975,  3.37, "");              // 76
        add(13.5784,  -0.5958,  3.38, "");              // 77
        // Близнецы
        add( 7.7553,  28.0262,  1.14, "");              // 78 = Поллукс
        add( 7.5766,  31.8883,  1.58, "");              // 79 = Кастор
        add( 7.0681,  20.5706,  1.93, "Альхена");      // 80
        add( 6.7488,  12.8958,  3.53, "");              // 81
        add( 7.3349,  21.9822,  3.79, "");              // 82
        add( 6.3828,  22.5139,  3.53, "Мебсута");      // 83
        // Орёл
        add(19.8464,   8.8683,  0.77, "");              // 84 = Альтаир
        add(19.6700,  10.6133,  2.72, "Талразед");     // 85
        add(19.9246,   0.8842,  3.44, "");              // 86
        // Лебедь
        add(20.6905,  45.2803,  1.25, "");              // 87 = Денеб
        add(19.5120,  27.9597,  2.46, "Альбирео");     // 88
        add(20.3741,  40.2567,  1.25, "");              // 89 = Садр
        add(21.2164,  62.5851,  2.24, "");              // 90
        add(20.7704,  33.9706,  2.49, "Гинах");        // 91
        // Персей
        add( 3.0794,  40.9556,  1.79, "Мирфак");       // 92
        add( 3.1357,  40.9556,  2.09, "Алголь");       // 93 (переменная)
        add( 2.8444,  55.8956,  2.84, "");              // 94
        add( 3.7158,  47.7878,  2.89, "");              // 95
        // Телец
        add( 4.5988,  16.5093,  0.85, "");              // 96 = Альдебаран
        add( 4.7006,  22.9572,  2.87, "Нат");          // 97 β Тельца
        add( 5.4381,  28.6075,  2.97, "");              // 98
        // Рак
        add( 8.7449,  18.1544,  3.52, "Тарф");        // 99
        add( 8.7781,  28.7608,  3.94, "Аль-Тарф");    // 100
        // Козерог
        add(20.2941, -12.5080,  2.85, "Денеб Альгеди");// 101
        add(21.0992, -17.2328,  3.57, "");              // 102
        // Стрелец
        add(19.1619, -29.8803,  1.79, "Каус Аустралис");// 103
        add(18.4029, -34.3839,  1.85, "Аль-Наср");     // 104
        add(18.9208, -26.2967,  2.81, "Фаль");         // 105
        add(19.3979, -27.6697,  2.59, "Каус Медиа");   // 106
        add(19.0435, -29.8803,  2.72, "Каус Бореалис");// 107

        // Фоновые звёзды 4–6 зв.вел. (без имён) — по всему небу
        double[][] bg = {
            {1.0,80},{2.0,70},{3.0,60},{4.0,50},{5.0,40},{6.0,30},{7.0,20},{8.0,10},
            {9.0,0},{10.0,-10},{11.0,-20},{12.0,-30},{13.0,-40},{14.0,-50},{15.0,-60},
            {16.0,-70},{17.0,-80},{18.0,-70},{19.0,-60},{20.0,-50},{21.0,-40},{22.0,-30},
            {23.0,-20},{0.5,-10},{1.5,0},{2.5,10},{3.5,20},{4.5,30},{5.5,40},
            {6.5,50},{7.5,60},{8.5,70},{9.5,80},{10.5,75},{11.5,65},{12.5,55},
            {0.2,45},{1.2,35},{2.2,25},{3.2,15},{4.2,5},{5.2,-5},{6.2,-15},
            {7.2,-25},{8.2,-35},{9.2,-45},{10.2,-55},{11.2,-65},{12.2,-75},
            {13.2,80},{14.2,70},{15.2,60},{16.2,50},{17.2,40},{18.2,30},{19.2,20},
            {20.2,10},{21.2,0},{22.2,-10},{23.2,-20},{0.8,-30},{1.8,-40},{2.8,-50},
            {3.8,-60},{4.8,-70},{5.8,75},{6.8,65},{7.8,55},{8.8,45},{9.8,35},
            {10.8,25},{11.8,15},{12.8,5},{13.8,-5},{14.8,-15},{15.8,-25},{16.8,-35},
            {17.8,-45},{18.8,-55},{19.8,-65},{20.8,-75},{21.8,80},{22.8,70},{23.8,60},
            // ещё диффузные
            {0.4,55},{1.4,45},{2.4,35},{3.4,25},{4.4,15},{5.4,5},{6.4,-5},
            {7.4,-15},{8.4,-25},{9.4,-35},{10.4,-45},{11.4,-55},{12.4,-65},{13.4,-75},
            {14.4,85},{15.4,75},{16.4,65},{17.4,55},{18.4,45},{19.4,35},{20.4,25},
            {21.4,15},{22.4,5},{23.4,-5},{0.6,-15},{1.6,-25},{2.6,-35},{3.6,-45},
        };
        for (double[] p : bg) {
            double mag = 3.5 + Math.random() * 2.0;
            add(p[0], p[1], mag, "");
        }
    }

    private void add(double ra, double dec, double mag, String name) {
        catalog.add(new Star(name, ra, dec, mag));
    }

    // ── Созвездия ─────────────────────────────────────────────────────────────
    // Каждая линия = пара индексов из catalog[]

    private void buildConstellations() {
        // Орион
        con("Орион", "Ori",
            line(8, 40),   // Бетельгейзе → Беллатрикс
            line(8, 37),   // Бетельгейзе → Минтака
            line(37, 38),  // пояс
            line(38, 39),  // пояс
            line(39, 6),   // Альнитак → Ригель
            line(6, 41),   // Ригель → Саиф
            line(41, 39),  // Саиф → Альнитак
            line(40, 37),  // Беллатрикс → Минтака
            line(8, 42),   // плечо
            line(42, 37)
        );

        // Большая Медведица
        con("Бол. Медведица", "UMa",
            line(44, 45),  // Дубхе → Мерак
            line(45, 46),  // Мерак → Фекда
            line(46, 47),  // Фекда → Мегрец
            line(47, 44),  // Мегрец → Дубхе
            line(47, 48),  // Мегрец → Алиот
            line(48, 49),  // Алиот → Мицар
            line(49, 50)   // Мицар → Бенетнаш
        );

        // Кассиопея (W-образная)
        con("Кассиопея", "Cas",
            line(52, 51),  // Кафф → Шедар
            line(51, 53),  // Шедар → Нави
            line(53, 54),  // Нави → Рухбах
            line(54, 55)   // Рухбах → Сегин
        );

        // Лев
        con("Лев", "Leo",
            line(56, 58),  // Регул → Альгиеба
            line(58, 59),  // Альгиеба → Адхафера
            line(58, 61),  // Альгиеба
            line(56, 60),  // Регул → Зосма
            line(60, 57),  // Зосма → Денебола
            line(57, 77)
        );

        // Скорпион
        con("Скорпион", "Sco",
            line(64, 63),  // голова
            line(63, 12),  // → Антарес
            line(12, 21),
            line(21, 23),  // хвост
            line(23, 20)   // Шаула
        );

        // Стрелец
        con("Стрелец", "Sgr",
            line(104, 103), // основание
            line(103, 107),
            line(107, 106),
            line(106, 105),
            line(104, 107)
        );

        // Близнецы
        con("Близнецы", "Gem",
            line(79, 83),  // Кастор → ног
            line(78, 80),  // Поллукс → Альхена
            line(79, 78),  // Кастор → Поллукс
            line(83, 80),
            line(80, 81)
        );

        // Телец
        con("Телец", "Tau",
            line(96, 33),  // Альдебаран → Гиады
            line(33, 34),
            line(34, 35),
            line(96, 97),  // Альдебаран → Нат
            line(96, 98)
        );

        // Лев → Дева (Спика)
        con("Дева", "Vir",
            line(74, 77),
            line(77, 76),
            line(76, 75),
            line(75, 58)
        );

        // Геркулес
        con("Геркулес", "Her",
            line(68, 69),
            line(69, 71),
            line(71, 73),
            line(73, 70),
            line(70, 69),
            line(72, 70)
        );

        // Орёл
        con("Орёл", "Aql",
            line(84, 85),  // Альтаир → Талразед
            line(84, 86),  // Альтаир → вниз
            line(85, 19)
        );

        // Лебедь
        con("Лебедь", "Cyg",
            line(87, 89),  // Денеб → Садр (корпус)
            line(89, 88),  // Садр → Альбирео
            line(89, 90),  // Садр → север
            line(89, 91),  // Садр → Гинах (крылья)
            line(17, 89)
        );

        // Персей
        con("Персей", "Per",
            line(92, 93),  // Мирфак → Алголь
            line(92, 94),
            line(92, 95)
        );

        // Скорпион хвост доп.
        con("Козерог", "Cap",
            line(101, 102)
        );
    }

    /** Создаёт созвездие. */
    private void con(String name, String abbr, int[]... lines) {
        constellations.add(new Constellation(name, abbr, List.of(lines)));
    }

    private int[] line(int a, int b) { return new int[]{a, b}; }
}
