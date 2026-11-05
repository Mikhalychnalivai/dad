package com.telescope.ui;

import com.telescope.model.AstronomyCalculator;
import com.telescope.model.CelestialObject;
import com.telescope.model.ObserverSettings;
import com.telescope.model.TrackingService;
import javafx.animation.AnimationTimer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Полноценная интерактивная астрономическая карта звёздного неба.
 *
 * Возможности:
 *  ─ 320+ звёзд с реальными координатами J2000 (подмножество Hipparcos)
 *  ─ 50+ объектов Мессье (туманности, галактики, скопления)
 *  ─ 27 созвездий с линиями и названиями
 *  ─ Окраска звёзд по индексу B-V (синие, белые, жёлтые, красные гиганты)
 *  ─ Перетаскивание + зум колёсиком
 *  ─ Клик по объекту → панель информации
 *  ─ Переключение: сетка / линии созвездий / имена звёзд / имена созвездий / объекты DSO
 *  ─ Прицел телескопа + маркер сопровождаемого объекта
 *  ─ Поиск объекта по имени → анимированный переход
 *
 * Система координат карты:
 *   Экваториальная — RA (0–24 ч) по горизонтали, DEC (–90…+90°) по вертикали.
 *   viewRA/viewDEC — координаты центра экрана.
 *   scale — пикселей на градус.
 */
public class SkyMapCanvas extends Canvas {

    // ── Настройки отображения ─────────────────────────────────────────────────

    private boolean showGrid        = true;
    private boolean showConLines    = true;
    private boolean showConLabels   = true;
    private boolean showStarNames   = true;
    private boolean showDso         = true;

    // ── Состояние просмотра ───────────────────────────────────────────────────

    private double viewRA  = 5.5;    // RA центра экрана (часы)
    private double viewDEC = 5.0;    // DEC центра экрана (градусы)
    private double scale   = 7.0;    // пикселей на градус

    private static final double SCALE_MIN =  1.5;
    private static final double SCALE_MAX = 120.0;

    // ── Перетаскивание ────────────────────────────────────────────────────────

    private double dragX0, dragY0, dragRA0, dragDEC0;
    private boolean isDragging = false;

    // ── Прицел телескопа ──────────────────────────────────────────────────────

    private double crosshairRA  = 5.5;
    private double crosshairDEC = 0.0;

    // ── Сопровождение ─────────────────────────────────────────────────────────

    private CelestialObject trackedObject  = null;
    private boolean         isTracking     = false;

    // ── Выбранный объект ─────────────────────────────────────────────────────

    private final ObjectProperty<SkyObject> selectedObject = new SimpleObjectProperty<>();

    /** Колбэк: вызывается при выборе объекта на карте. */
    private Consumer<SkyObject> onObjectSelected;

    // ── Поиск / анимированный переезд ────────────────────────────────────────

    private double targetRA  = -1;
    private double targetDEC = 0;
    private boolean animating = false;

    // ── Каталог ──────────────────────────────────────────────────────────────

    private final SkyStarCatalog catalog = new SkyStarCatalog();

    // ── Настройки наблюдателя (для расчёта видимости) ────────────────────────

    private ObserverSettings observer;

    // ── Анимация ─────────────────────────────────────────────────────────────

    private final AnimationTimer timer;
    private long startNanos;
    private long lastFrameNanos;

    // ─────────────────────────────────────────────────────────────────────────

    public SkyMapCanvas(ObserverSettings observer) {
        this.observer = observer;
        setWidth(800);
        setHeight(600);

        setupInteraction();

        startNanos    = System.nanoTime();
        lastFrameNanos = startNanos;

        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                tick(now);
                draw(now);
                lastFrameNanos = now;
            }
        };
        timer.start();

        widthProperty().addListener(e  -> draw(System.nanoTime()));
        heightProperty().addListener(e -> draw(System.nanoTime()));
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    public void setCrosshair(double ra, double dec)    { crosshairRA = ra; crosshairDEC = dec; }
    public void setTrackedObject(CelestialObject o, boolean tracking) {
        this.trackedObject = o; this.isTracking = tracking;
    }
    public void setObserver(ObserverSettings o) { this.observer = o; }
    public void setOnObjectSelected(Consumer<SkyObject> cb) { this.onObjectSelected = cb; }
    public ObjectProperty<SkyObject> selectedObjectProperty() { return selectedObject; }

    /** Плавно перемещает карту к указанным координатам. */
    public void goTo(double ra, double dec) {
        targetRA  = ra;
        targetDEC = dec;
        animating = true;
    }

    public void setShowGrid(boolean v)      { showGrid      = v; }
    public void setShowConLines(boolean v)  { showConLines  = v; }
    public void setShowConLabels(boolean v) { showConLabels = v; }
    public void setShowStarNames(boolean v) { showStarNames = v; }
    public void setShowDso(boolean v)       { showDso       = v; }

    public void stop() { timer.stop(); }

    // ── Анимация плавного перемещения ─────────────────────────────────────────

    private void tick(long now) {
        if (!animating) return;
        double dt = (now - lastFrameNanos) / 1e9;
        double k  = Math.min(1.0, dt * 4.0); // скорость перелёта

        double dRA = raAngularDiff(targetRA, viewRA);
        double dDEC = targetDEC - viewDEC;

        viewRA  = normalizeRA(viewRA  + dRA  * k);
        viewDEC = clamp(viewDEC + dDEC * k, -85, 85);

        if (Math.abs(dRA * 15) < 0.05 && Math.abs(dDEC) < 0.05) {
            viewRA  = targetRA;
            viewDEC = targetDEC;
            animating = false;
        }
    }

    // ── Взаимодействие ────────────────────────────────────────────────────────

    private void setupInteraction() {
        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                isDragging = true;
                dragX0 = e.getX(); dragY0 = e.getY();
                dragRA0 = viewRA;  dragDEC0 = viewDEC;
                animating = false;
            }
        });
        setOnMouseDragged(e -> {
            if (!isDragging) return;
            double dx = e.getX() - dragX0;
            double dy = e.getY() - dragY0;
            viewRA  = normalizeRA(dragRA0 - dx / (scale * 15.0));
            viewDEC = clamp(dragDEC0 + dy / scale, -85, 85);
        });
        setOnMouseReleased(e -> isDragging = false);

        setOnScroll(e -> {
            double f = e.getDeltaY() > 0 ? 1.18 : 1.0 / 1.18;
            scale = clamp(scale * f, SCALE_MIN, SCALE_MAX);
        });

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !isDragging) {
                handleClick(e.getX(), e.getY());
            }
        });
    }

    private void handleClick(double px, double py) {
        double W = getWidth(), H = getHeight();
        SkyObject best  = null;
        double    bestD = Double.MAX_VALUE;
        double    pickR = 12.0; // радиус захвата в пикселях

        // Проверяем звёзды
        for (SkyStarCatalog.StarEntry s : catalog.getStars()) {
            if (s.mag() > magLimit()) continue;
            double sx = raToX(s.ra(), W), sy = decToY(s.dec(), H);
            double d = dist2(px, py, sx, sy);
            if (d < pickR * pickR && d < bestD) {
                bestD = d;
                best  = new SkyObject(s.name().isEmpty() ? s.bayer() : s.name(),
                        SkyObject.Type.STAR, s.ra(), s.dec(), s.mag(), s.constAbbr());
            }
        }
        // Проверяем DSO
        if (showDso) {
            for (SkyStarCatalog.DsoEntry d : catalog.getDso()) {
                if (d.mag() > magLimit() + 5) continue;
                double sx = raToX(d.ra(), W), sy = decToY(d.dec(), H);
                double ds = dist2(px, py, sx, sy);
                if (ds < (pickR * 1.8) * (pickR * 1.8) && ds < bestD) {
                    bestD = ds;
                    best  = new SkyObject("M" + d.number() + " " + d.name(),
                            dsoType(d.type()), d.ra(), d.dec(), d.mag(), "");
                }
            }
        }

        if (best != null) {
            selectedObject.set(best);
            if (onObjectSelected != null) onObjectSelected.accept(best);
        } else {
            selectedObject.set(null);
            if (onObjectSelected != null) onObjectSelected.accept(null);
        }
    }

    // ── Главная отрисовка ─────────────────────────────────────────────────────

    private void draw(long now) {
        double W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;

        GraphicsContext gc = getGraphicsContext2D();
        double t = (now - startNanos) / 1e9;

        drawBackground(gc, W, H);
        if (showGrid)      drawGrid(gc, W, H);
        if (showConLines)  drawConstellationLines(gc, W, H);
        drawDso(gc, W, H);
        drawStars(gc, W, H, t);
        if (showConLabels) drawConstellationLabels(gc, W, H);
        drawSelectedHighlight(gc, W, H);
        drawTrackedObjectMarker(gc, W, H, t);
        drawCrosshair(gc, W, H, t);
        drawHUD(gc, W, H);
    }

    // ── Фон ──────────────────────────────────────────────────────────────────

    private void drawBackground(GraphicsContext gc, double W, double H) {
        gc.setFill(Color.rgb(4, 4, 8));
        gc.fillRect(0, 0, W, H);

        // Лёгкий виньет по краям
        for (int i = 0; i < 4; i++) {
            double a = 0.06 - i * 0.012;
            double margin = i * 30.0;
            gc.setFill(Color.color(0, 0, 0, a));
            gc.fillRect(0, 0, margin, H);
            gc.fillRect(W - margin, 0, margin, H);
            gc.fillRect(0, 0, W, margin);
            gc.fillRect(0, H - margin, W, margin);
        }
    }

    // ── Координатная сетка ────────────────────────────────────────────────────

    private void drawGrid(GraphicsContext gc, double W, double H) {
        gc.save();

        // DEC-линии (горизонтальные)
        double decStep = niceStep(60.0 / scale);
        double decMin  = viewDEC - H / scale / 2.0;
        double decMax  = viewDEC + H / scale / 2.0;
        double dec0    = Math.floor(decMin / decStep) * decStep;

        gc.setStroke(Color.color(0.3, 0.5, 0.9, 0.18));
        gc.setLineWidth(0.6);
        gc.setFont(Font.font("Consolas", 9));
        gc.setFill(Color.color(0.4, 0.6, 1.0, 0.5));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BOTTOM);

        for (double d = dec0; d <= decMax + decStep; d += decStep) {
            double y = decToY(d, H);
            gc.strokeLine(0, y, W, y);
            if (d > -89 && d < 89)
                gc.fillText(String.format("%+.0f°", d), 4, y - 2);
        }

        // RA-линии (вертикальные)
        double raSpanH = W / (scale * 15.0);
        double raStep  = niceHourStep(raSpanH / 8.0);
        double raLeft  = viewRA - raSpanH / 2.0;
        double ra0     = Math.floor(raLeft / raStep) * raStep;

        gc.setTextBaseline(VPos.TOP);
        for (double r = ra0; r <= raLeft + raSpanH + raStep; r += raStep) {
            double x = raToX(r, W);
            gc.strokeLine(x, 0, x, H);
            gc.fillText(String.format("%.1fh", normalizeRA(r)), x + 2, 2);
        }

        gc.restore();
    }

    // ── Линии созвездий ───────────────────────────────────────────────────────

    private void drawConstellationLines(GraphicsContext gc, double W, double H) {
        gc.save();
        gc.setStroke(Color.color(0.35, 0.55, 1.0, 0.28));
        gc.setLineWidth(0.9);

        for (SkyStarCatalog.ConstellationDef con : catalog.getConstellations()) {
            for (int[] pair : con.lines()) {
                SkyStarCatalog.StarEntry a = findStarById(pair[0]);
                SkyStarCatalog.StarEntry b = findStarById(pair[1]);
                if (a == null || b == null) continue;

                double x1 = raToX(a.ra(), W), y1 = decToY(a.dec(), H);
                double x2 = raToX(b.ra(), W), y2 = decToY(b.dec(), H);

                // Пропускаем линии, которые пересекают экран через RA=0/24
                if (Math.abs(x2 - x1) > W * 0.6) continue;

                boolean vis1 = inView(x1, y1, W, H, 20);
                boolean vis2 = inView(x2, y2, W, H, 20);
                if (!vis1 && !vis2) continue;

                gc.strokeLine(x1, y1, x2, y2);
            }
        }
        gc.restore();
    }

    // ── Звёзды ───────────────────────────────────────────────────────────────

    private void drawStars(GraphicsContext gc, double W, double H, double t) {
        double limit = magLimit();
        SkyObject sel = selectedObject.get();

        for (SkyStarCatalog.StarEntry s : catalog.getStars()) {
            if (s.mag() > limit) continue;
            double sx = raToX(s.ra(), W), sy = decToY(s.dec(), H);
            if (!inView(sx, sy, W, H, 4)) continue;

            // Мерцание
            double phase    = (s.ra() * 137.5 + s.dec() * 73.1) % (Math.PI * 2);
            double twinkle  = Math.sin(t * (1.4 + (s.id() % 10) * 0.1) + phase) * 0.10;

            // Радиус: зависит от зв.вел и масштаба
            double r = starRadius(s.mag(), scale);

            // Цвет по B-V
            Color c = bvToColor(s.bv(), twinkle);

            // Свечение для ярких звёзд
            if (r > 2.0) {
                gc.setFill(c.deriveColor(0, 0.6, 1, 0.07 + twinkle * 0.04));
                gc.fillOval(sx - r * 5, sy - r * 5, r * 10, r * 10);
                gc.setFill(c.deriveColor(0, 0.8, 1, 0.15 + twinkle * 0.05));
                gc.fillOval(sx - r * 3, sy - r * 3, r * 6, r * 6);
            }

            // Ядро
            gc.setFill(c);
            gc.fillOval(sx - r, sy - r, r * 2, r * 2);

            // Имя
            if (showStarNames && s.isNamed() && !s.name().isEmpty()) {
                double nameThreshold = s.mag() < 1.5 ? 4.0 : s.mag() < 2.5 ? 7.0 : 15.0;
                if (scale > nameThreshold) {
                    gc.setFill(Color.color(0.80, 0.88, 1.0, 0.80));
                    gc.setFont(Font.font("Segoe UI", 10));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.fillText(s.name(), sx + r + 3, sy + 3);
                }
            }
        }
    }

    // ── Объекты DSO ───────────────────────────────────────────────────────────

    private void drawDso(GraphicsContext gc, double W, double H) {
        if (!showDso) return;
        double limit = magLimit() + 5;

        for (SkyStarCatalog.DsoEntry d : catalog.getDso()) {
            if (d.mag() > limit) continue;
            double sx = raToX(d.ra(), W), sy = decToY(d.dec(), H);
            if (!inView(sx, sy, W, H, 10)) continue;

            // Угловой размер → пиксели
            double pxSize = Math.max(4.0, d.sizeArcmin() * scale / 60.0);
            pxSize = Math.min(pxSize, 80.0);

            switch (d.type()) {
                case OPEN_CLUSTER    -> drawOpenCluster(gc, sx, sy, pxSize);
                case GLOB_CLUSTER    -> drawGlobCluster(gc, sx, sy, pxSize);
                case NEBULA          -> drawNebula(gc, sx, sy, pxSize, Color.color(0.3, 0.6, 1.0, 0.55));
                case PLANETARY_NEB   -> drawNebula(gc, sx, sy, Math.max(pxSize, 6), Color.color(0.2, 0.9, 0.7, 0.65));
                case SUPERNOVA_REM   -> drawNebula(gc, sx, sy, pxSize, Color.color(0.9, 0.3, 0.2, 0.50));
                case GALAXY          -> drawGalaxy(gc, sx, sy, pxSize);
            }

            // Подпись DSO
            if (scale > 4.0 && d.mag() < 9.5) {
                gc.setFill(Color.color(0.6, 0.75, 1.0, 0.70));
                gc.setFont(Font.font("Segoe UI", 9));
                gc.setTextAlign(TextAlignment.LEFT);
                String label = "M" + d.number();
                gc.fillText(label, sx + pxSize / 2 + 3, sy + 3);
                if (scale > 8.0 && !d.name().isEmpty()) {
                    gc.setFill(Color.color(0.6, 0.75, 1.0, 0.45));
                    gc.fillText(d.name(), sx + pxSize / 2 + 3, sy + 13);
                }
            }
        }
    }

    private void drawOpenCluster(GraphicsContext gc, double cx, double cy, double r) {
        gc.setStroke(Color.color(1.0, 0.9, 0.3, 0.70));
        gc.setLineWidth(1.0);
        gc.setLineDashes(2, 2);
        gc.strokeOval(cx - r / 2, cy - r / 2, r, r);
        gc.setLineDashes();
        // Маленькие точки-звёздочки внутри
        gc.setFill(Color.color(1.0, 0.9, 0.3, 0.55));
        double[] angles = {0, 60, 120, 180, 240, 300};
        for (double a : angles) {
            double ax = cx + Math.cos(Math.toRadians(a)) * r * 0.35;
            double ay = cy + Math.sin(Math.toRadians(a)) * r * 0.35;
            gc.fillOval(ax - 1, ay - 1, 2, 2);
        }
    }

    private void drawGlobCluster(GraphicsContext gc, double cx, double cy, double r) {
        r = r / 2;
        // Внешнее свечение
        gc.setFill(Color.color(1.0, 0.85, 0.4, 0.10));
        gc.fillOval(cx - r * 2, cy - r * 2, r * 4, r * 4);
        // Центр
        gc.setFill(Color.color(1.0, 0.92, 0.6, 0.55));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);
        // Крест
        gc.setStroke(Color.color(1.0, 0.85, 0.4, 0.6));
        gc.setLineWidth(0.7);
        gc.strokeLine(cx - r * 1.5, cy, cx + r * 1.5, cy);
        gc.strokeLine(cx, cy - r * 1.5, cx, cy + r * 1.5);
    }

    private void drawNebula(GraphicsContext gc, double cx, double cy, double r, Color col) {
        gc.setFill(col.deriveColor(0, 1, 1, 0.08));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);
        gc.setFill(col.deriveColor(0, 1, 1, 0.25));
        gc.fillOval(cx - r * 0.5, cy - r * 0.5, r, r);
        gc.setStroke(col.deriveColor(0, 1, 1, 0.55));
        gc.setLineWidth(0.8);
        gc.strokeRect(cx - r / 2, cy - r / 2, r, r);
    }

    private void drawGalaxy(GraphicsContext gc, double cx, double cy, double r) {
        r = r / 2;
        gc.save();
        gc.translate(cx, cy);
        gc.rotate(35); // наклон эллипса
        gc.setFill(Color.color(0.9, 0.8, 0.5, 0.08));
        gc.fillOval(-r * 2, -r * 0.7, r * 4, r * 1.4);
        gc.setFill(Color.color(0.95, 0.88, 0.6, 0.30));
        gc.fillOval(-r, -r * 0.4, r * 2, r * 0.8);
        gc.setFill(Color.color(1.0, 0.95, 0.7, 0.55));
        gc.fillOval(-r * 0.2, -r * 0.1, r * 0.4, r * 0.2);
        gc.restore();
    }

    // ── Подписи созвездий ─────────────────────────────────────────────────────

    private void drawConstellationLabels(GraphicsContext gc, double W, double H) {
        if (scale < 2.5) return;

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD,
                Math.max(10, Math.min(14, scale * 1.0))));

        for (SkyStarCatalog.ConstellationDef con : catalog.getConstellations()) {
            if (con.lines().isEmpty()) continue;
            // Центр = среднее по звёздам
            double sumX = 0, sumY = 0; int cnt = 0;
            for (int[] pair : con.lines()) {
                SkyStarCatalog.StarEntry s = findStarById(pair[0]);
                if (s == null) continue;
                sumX += raToX(s.ra(), W);
                sumY += decToY(s.dec(), H);
                cnt++;
            }
            if (cnt == 0) continue;
            double cx = sumX / cnt, cy = sumY / cnt;
            if (!inView(cx, cy, W, H, 0)) continue;

            gc.setFill(Color.color(0.45, 0.65, 1.0, 0.60));
            gc.fillText(con.nameRu(), cx, cy);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
    }

    // ── Выделение выбранного объекта ──────────────────────────────────────────

    private void drawSelectedHighlight(GraphicsContext gc, double W, double H) {
        SkyObject sel = selectedObject.get();
        if (sel == null) return;

        double sx = raToX(sel.ra(), W), sy = decToY(sel.dec(), H);
        if (!inView(sx, sy, W, H, 30)) return;

        long now = System.nanoTime();
        double t  = (now - startNanos) / 1e9;
        double pulse = 0.6 + Math.sin(t * 3) * 0.3;

        gc.setStroke(Color.color(1.0, 1.0, 0.3, 0.8 * pulse));
        gc.setLineWidth(1.2);
        double r = 14 + Math.sin(t * 3) * 2;
        gc.strokeOval(sx - r, sy - r, r * 2, r * 2);
        gc.setStroke(Color.color(1.0, 1.0, 0.3, 0.3 * pulse));
        gc.setLineWidth(0.7);
        gc.strokeOval(sx - r * 1.7, sy - r * 1.7, r * 3.4, r * 3.4);
    }

    // ── Маркер сопровождаемого объекта ────────────────────────────────────────

    private void drawTrackedObjectMarker(GraphicsContext gc, double W, double H, double t) {
        if (trackedObject == null) return;
        double sx = raToX(trackedObject.getRa(), W);
        double sy = decToY(trackedObject.getDec(), H);
        if (!inView(sx, sy, W, H, 20)) return;

        Color c = isTracking
                ? Color.color(0.2, 1.0, 0.4, 0.7 + Math.sin(t * 2) * 0.2)
                : Color.color(0.8, 0.8, 0.2, 0.5);

        gc.setStroke(c);
        gc.setLineWidth(1.5);
        double r = 18;
        // Угловые скобки
        gc.strokeLine(sx - r, sy - r, sx - r + 6, sy - r);
        gc.strokeLine(sx - r, sy - r, sx - r,     sy - r + 6);
        gc.strokeLine(sx + r, sy - r, sx + r - 6, sy - r);
        gc.strokeLine(sx + r, sy - r, sx + r,     sy - r + 6);
        gc.strokeLine(sx - r, sy + r, sx - r + 6, sy + r);
        gc.strokeLine(sx - r, sy + r, sx - r,     sy + r - 6);
        gc.strokeLine(sx + r, sy + r, sx + r - 6, sy + r);
        gc.strokeLine(sx + r, sy + r, sx + r,     sy + r - 6);
    }

    // ── Прицел телескопа ──────────────────────────────────────────────────────

    private void drawCrosshair(GraphicsContext gc, double W, double H, double t) {
        double sx = raToX(crosshairRA, W);
        double sy = decToY(crosshairDEC, H);

        // Если прицел за экраном — стрелка на краю
        if (!inView(sx, sy, W, H, 0)) {
            double angle = Math.atan2(sy - H / 2, sx - W / 2);
            double ex = W / 2 + Math.cos(angle) * (Math.min(W, H) * 0.44);
            double ey = H / 2 + Math.sin(angle) * (Math.min(W, H) * 0.44);
            gc.setFill(Color.color(1.0, 0.85, 0.0, 0.8));
            gc.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText("⊕", ex, ey);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BASELINE);
            return;
        }

        double sz = 20;
        double pulse = 0.7 + Math.sin(t * 1.5) * 0.15;

        gc.setStroke(Color.color(1.0, 0.85, 0.2, 0.95 * pulse));
        gc.setLineWidth(1.3);
        gc.strokeLine(sx - sz, sy, sx - 5,  sy);
        gc.strokeLine(sx + 5,  sy, sx + sz, sy);
        gc.strokeLine(sx, sy - sz, sx, sy - 5);
        gc.strokeLine(sx, sy + 5,  sx, sy + sz);

        gc.setStroke(Color.color(1.0, 0.85, 0.2, 0.45 * pulse));
        gc.setLineWidth(0.8);
        gc.strokeOval(sx - 9, sy - 9, 18, 18);
        gc.strokeOval(sx - 20, sy - 20, 40, 40);
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    private void drawHUD(GraphicsContext gc, double W, double H) {
        // Координаты центра
        String coords = String.format("RA %05.2fh  DEC %+06.2f°   ×%.0f",
                viewRA, viewDEC, scale / 7.0);

        gc.setFont(Font.font("Consolas", 11));
        gc.setFill(Color.color(0, 0, 0, 0.45));
        gc.fillRoundRect(6, H - 24, coords.length() * 6.8 + 10, 18, 4, 4);
        gc.setFill(Color.color(0.65, 0.78, 1.0, 0.85));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(coords, 11, H - 15);

        // Подсказки при мелком масштабе
        if (scale < 4) {
            gc.setFill(Color.color(0.55, 0.65, 0.9, 0.50));
            gc.setFont(Font.font("Segoe UI", 10));
            gc.fillText("Колёсико — зум   Перетаскивание — прокрутка   Клик — выбор объекта",
                    10, 16);
        }

        // Информационная панель выбранного объекта
        SkyObject sel = selectedObject.get();
        if (sel != null) {
            drawInfoPanel(gc, W, H, sel);
        }

        gc.setTextBaseline(VPos.BASELINE);
    }

    private void drawInfoPanel(GraphicsContext gc, double W, double H, SkyObject obj) {
        double panW = 210, panH = 85;
        double px = W - panW - 10, py = 10;

        gc.setFill(Color.color(0.04, 0.06, 0.12, 0.88));
        gc.fillRoundRect(px, py, panW, panH, 8, 8);
        gc.setStroke(Color.color(0.4, 0.55, 1.0, 0.5));
        gc.setLineWidth(0.8);
        gc.strokeRoundRect(px, py, panW, panH, 8, 8);

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        gc.setFill(Color.color(1.0, 0.95, 0.7, 1.0));
        gc.fillText(obj.name(), px + 10, py + 10);

        gc.setFont(Font.font("Consolas", 10));
        gc.setFill(Color.color(0.7, 0.80, 1.0, 0.85));
        gc.fillText("Тип: " + obj.typeLabel(), px + 10, py + 28);
        gc.fillText(String.format("RA  %5.2fh  DEC %+6.2f°", obj.ra(), obj.dec()), px + 10, py + 42);
        gc.setFill(Color.color(0.6, 0.70, 0.9, 0.70));
        gc.fillText(String.format("Зв.вел. %.1f    %s", obj.mag(), obj.constName()), px + 10, py + 56);

        // Расчётное положение
        if (observer != null) {
            AstronomyCalculator.HorizontalCoords pos =
                    AstronomyCalculator.toHorizontal(obj.ra(), obj.dec(), observer);
            String vis = pos.isVisible() ? "видим ✓" : "ниже горизонта";
            gc.setFill(pos.isVisible()
                    ? Color.color(0.3, 1.0, 0.5, 0.80)
                    : Color.color(1.0, 0.4, 0.3, 0.80));
            gc.fillText(String.format("AZ %.1f°  ALT %+.1f°  %s",
                    pos.azimuth(), pos.altitude(), vis), px + 10, py + 69);
        }

        gc.setTextBaseline(VPos.BASELINE);
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    /** RA (часы) → экранный X. RA возрастает вправо→влево (как на небе). */
    private double raToX(double ra, double W) {
        double dRA = raAngularDiff(ra, viewRA); // разность в часах, –12…+12
        return W / 2.0 - dRA * 15.0 * scale;
    }

    /** DEC (градусы) → экранный Y. DEC возрастает вниз→вверх. */
    private double decToY(double dec, double H) {
        return H / 2.0 - (dec - viewDEC) * scale;
    }

    /** Разность RA в часах с учётом перехода 0/24. */
    private double raAngularDiff(double ra1, double ra2) {
        double d = ra1 - ra2;
        if (d >  12) d -= 24;
        if (d < -12) d += 24;
        return d;
    }

    private double normalizeRA(double ra) {
        ra = ra % 24.0;
        return ra < 0 ? ra + 24.0 : ra;
    }

    private double clamp(double v, double mn, double mx) {
        return Math.max(mn, Math.min(mx, v));
    }

    private double dist2(double x1, double y1, double x2, double y2) {
        return (x1-x2)*(x1-x2) + (y1-y2)*(y1-y2);
    }

    private boolean inView(double x, double y, double W, double H, double margin) {
        return x >= -margin && x <= W + margin && y >= -margin && y <= H + margin;
    }

    /** Максимальная звёздная величина для отображения (зависит от зума). */
    private double magLimit() {
        return 3.5 + Math.log(scale / 1.5 + 1) * 1.8;
    }

    private double starRadius(double mag, double sc) {
        double base = (6.5 - mag) * 0.5 * (sc / 7.0);
        return clamp(base, 0.5, 8.0);
    }

    private double niceStep(double raw) {
        double[] steps = {0.5, 1, 2, 5, 10, 15, 30, 45, 60, 90};
        for (double s : steps) { if (s >= raw) return s; }
        return 90;
    }

    private double niceHourStep(double rawH) {
        double[] steps = {0.25, 0.5, 1, 2, 3, 4, 6, 8, 12};
        for (double s : steps) { if (s >= rawH) return s; }
        return 12;
    }

    /** Цвет звезды по индексу B-V (спектральный класс). */
    private Color bvToColor(double bv, double twinkle) {
        double b = Math.min(1.0, Math.max(0.3, 0.65 + twinkle));
        if (bv < -0.3) return Color.color(0.60 * b, 0.70 * b, 1.00 * b, 1.0); // O/B — синие
        if (bv < 0.0)  return Color.color(0.75 * b, 0.83 * b, 1.00 * b, 1.0); // B/A
        if (bv < 0.3)  return Color.color(0.92 * b, 0.95 * b, 1.00 * b, 1.0); // A/F — белые
        if (bv < 0.6)  return Color.color(1.00 * b, 1.00 * b, 0.88 * b, 1.0); // F/G — жёлто-белые
        if (bv < 1.0)  return Color.color(1.00 * b, 0.90 * b, 0.60 * b, 1.0); // G/K — жёлтые
        if (bv < 1.4)  return Color.color(1.00 * b, 0.72 * b, 0.38 * b, 1.0); // K — оранжевые
        return              Color.color(1.00 * b, 0.45 * b, 0.20 * b, 1.0); // M — красные гиганты
    }

    private SkyObject.Type dsoType(SkyStarCatalog.ObjType t) {
        return switch (t) {
            case GALAXY          -> SkyObject.Type.GALAXY;
            case GLOB_CLUSTER    -> SkyObject.Type.GLOB_CLUSTER;
            case OPEN_CLUSTER    -> SkyObject.Type.OPEN_CLUSTER;
            case NEBULA, SUPERNOVA_REM -> SkyObject.Type.NEBULA;
            case PLANETARY_NEB   -> SkyObject.Type.PLANETARY_NEB;
            default              -> SkyObject.Type.OTHER;
        };
    }

    /** Быстрый поиск звезды по id (линейный, N=320, достаточно быстро). */
    private SkyStarCatalog.StarEntry findStarById(int id) {
        for (SkyStarCatalog.StarEntry s : catalog.getStars()) {
            if (s.id() == id) return s;
        }
        return null;
    }

    // ── Внутренняя модель выбранного объекта ──────────────────────────────────

    /** Унифицированный объект карты (звезда или DSO). */
    public record SkyObject(
            String name, Type type, double ra, double dec, double mag, String constName
    ) {
        public enum Type { STAR, GALAXY, GLOB_CLUSTER, OPEN_CLUSTER, NEBULA, PLANETARY_NEB, OTHER }

        public String typeLabel() {
            return switch (type) {
                case STAR         -> "Звезда";
                case GALAXY       -> "Галактика";
                case GLOB_CLUSTER -> "Шаровое скопление";
                case OPEN_CLUSTER -> "Рассеянное скопление";
                case NEBULA       -> "Туманность";
                case PLANETARY_NEB-> "Планетарная туманность";
                default           -> "Объект";
            };
        }
    }
}
