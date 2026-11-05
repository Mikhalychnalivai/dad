package com.telescope.model;

import com.telescope.arduino.ArduinoManager;
import javafx.application.Platform;
import javafx.beans.property.*;

import java.util.concurrent.*;

/**
 * Сервис автоматического астрономического сопровождения объекта.
 *
 * После запуска каждые {@code UPDATE_INTERVAL_SEC} секунд:
 *  1. Вычисляет текущее положение объекта (AZ/ALT) с учётом вращения Земли.
 *  2. Сравнивает с расчётным положением телескопа.
 *  3. Рассчитывает количество шагов для каждого двигателя.
 *  4. Отправляет команды Arduino (или логирует в демо-режиме).
 *
 * Учёт механики (пределы хода) выполняется через {@link StepperMotorTracker}.
 *
 * ВАЖНО: использует азимутальную монтировку (два независимых двигателя:
 * один на AZ, другой на ALT). Не экваториальная монтировка.
 */
public class TrackingService {

    /** Интервал обновления сопровождения (секунды). */
    private static final double UPDATE_INTERVAL_SEC = 5.0;

    /** Угловая скорость вращения Земли (°/сек). */
    private static final double EARTH_ROT_DEG_PER_SEC = 360.0 / 86164.0; // сидерические сутки

    /** Минимальная высота объекта над горизонтом для сопровождения (°). */
    private static final double MIN_ALTITUDE_DEG = 5.0;

    // ── Зависимости ───────────────────────────────────────────────────────────

    private final ArduinoManager     arduino;
    private final ObserverSettings   observer;
    private final CoordinateManager  coordinates;
    private final StepperMotorTracker motorAz;
    private final StepperMotorTracker motorAlt;
    private final CommandLogger      logger;

    // ── Состояние ─────────────────────────────────────────────────────────────

    public enum State { IDLE, RUNNING, PAUSED }

    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.IDLE);

    /** Объект, за которым ведётся слежение. */
    private volatile CelestialObject target = null;

    /** Точка синхронизации: смещение AZ телескопа относительно расчётного AZ объекта. */
    private volatile double syncOffsetAz  = 0.0;
    private volatile double syncOffsetAlt = 0.0;

    /** Была ли выполнена синхронизация? */
    private final BooleanProperty synced = new SimpleBooleanProperty(false);

    private final StringProperty statusMessage = new SimpleStringProperty("Сопровождение не активно");

    // ── Планировщик ───────────────────────────────────────────────────────────

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tracking-service");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> trackingTask = null;

    // ── Конструктор ───────────────────────────────────────────────────────────

    public TrackingService(ArduinoManager arduino,
                           ObserverSettings observer,
                           CoordinateManager coordinates,
                           StepperMotorTracker motorAz,
                           StepperMotorTracker motorAlt,
                           CommandLogger logger) {
        this.arduino     = arduino;
        this.observer    = observer;
        this.coordinates = coordinates;
        this.motorAz     = motorAz;
        this.motorAlt    = motorAlt;
        this.logger      = logger;
    }

    // ── Управление сопровождением ─────────────────────────────────────────────

    /**
     * Запускает сопровождение выбранного объекта.
     * Требует предварительной синхронизации.
     */
    public boolean start(CelestialObject target) {
        if (target == null) {
            logger.logError("Выберите объект для сопровождения");
            return false;
        }
        if (!synced.get()) {
            logger.logError("Сначала выполните синхронизацию с объектом");
            return false;
        }

        // Проверяем видимость объекта
        AstronomyCalculator.HorizontalCoords pos =
                AstronomyCalculator.toHorizontal(target.getRa(), target.getDec(), observer);
        if (pos.altitude() < MIN_ALTITUDE_DEG) {
            logger.logError(String.format(
                    "%s сейчас ниже горизонта (ALT=%.1f°). Сопровождение невозможно.",
                    target.getName(), pos.altitude()));
            return false;
        }

        this.target = target;
        setState(State.RUNNING);
        setStatus("Сопровождение: " + target.getName());
        logger.logInfo("Запуск сопровождения объекта: " + target.getName());

        // Запускаем с задержкой 1 сек, затем каждые UPDATE_INTERVAL_SEC сек
        trackingTask = scheduler.scheduleAtFixedRate(
                this::trackingTick,
                1L,
                (long) (UPDATE_INTERVAL_SEC * 1000),
                TimeUnit.MILLISECONDS
        );
        return true;
    }

    /** Приостанавливает сопровождение (объект не меняется). */
    public void pause() {
        if (state.get() == State.RUNNING) {
            cancelTask();
            setState(State.PAUSED);
            setStatus("Сопровождение приостановлено: " + (target != null ? target.getName() : ""));
            logger.logInfo("Сопровождение приостановлено");
        }
    }

    /** Возобновляет сопровождение после паузы. */
    public boolean resume() {
        if (state.get() == State.PAUSED && target != null) {
            setState(State.RUNNING);
            setStatus("Сопровождение: " + target.getName());
            logger.logInfo("Сопровождение возобновлено");
            trackingTask = scheduler.scheduleAtFixedRate(
                    this::trackingTick,
                    0L,
                    (long) (UPDATE_INTERVAL_SEC * 1000),
                    TimeUnit.MILLISECONDS
            );
            return true;
        }
        return false;
    }

    /** Полная остановка сопровождения. */
    public void stop() {
        cancelTask();
        target = null;
        setState(State.IDLE);
        setStatus("Сопровождение остановлено");
        logger.logInfo("Сопровождение остановлено");
        arduino.sendCommand("STOP");
    }

    // ── Синхронизация ─────────────────────────────────────────────────────────

    /**
     * Синхронизирует телескоп с выбранным объектом.
     *
     * Сохраняет смещение между текущим механическим положением телескопа
     * и расчётным положением объекта как точку привязки.
     *
     * @param target         объект, с которым синхронизируемся
     * @param currentAz      текущий азимут телескопа (механика), °
     * @param currentAlt     текущая высота телескопа (механика), °
     */
    public void synchronize(CelestialObject target, double currentAz, double currentAlt) {
        AstronomyCalculator.HorizontalCoords calcPos =
                AstronomyCalculator.toHorizontal(target.getRa(), target.getDec(), observer);

        // Смещение = (реальное положение) – (расчётное положение)
        syncOffsetAz  = currentAz  - calcPos.azimuth();
        syncOffsetAlt = currentAlt - calcPos.altitude();

        Platform.runLater(() -> synced.set(true));

        logger.logInfo(String.format(
                "Синхронизация с %s: AZ=%.2f° ALT=%.2f° | смещение: ΔAZ=%.2f° ΔALT=%.2f°",
                target.getName(), currentAz, currentAlt, syncOffsetAz, syncOffsetAlt));
    }

    /** Сбрасывает синхронизацию. */
    public void resetSync() {
        syncOffsetAz  = 0.0;
        syncOffsetAlt = 0.0;
        Platform.runLater(() -> synced.set(false));
        logger.logInfo("Синхронизация сброшена");
    }

    // ── Расчёт текущего положения объекта ────────────────────────────────────

    /**
     * Возвращает расчётное горизонтальное положение объекта с учётом синхронизации.
     * Если синхронизация не выполнена — возвращает чистый астрорасчёт.
     */
    public AstronomyCalculator.HorizontalCoords getTargetPosition(CelestialObject obj) {
        if (obj == null) return new AstronomyCalculator.HorizontalCoords(0, 0);
        AstronomyCalculator.HorizontalCoords raw =
                AstronomyCalculator.toHorizontal(obj.getRa(), obj.getDec(), observer);

        if (synced.get()) {
            // Применяем синхронизационное смещение
            double az  = (raw.azimuth()  + syncOffsetAz + 360) % 360;
            double alt = Math.max(-90, Math.min(90, raw.altitude() + syncOffsetAlt));
            return new AstronomyCalculator.HorizontalCoords(az, alt);
        }
        return raw;
    }

    // ── Геттеры ───────────────────────────────────────────────────────────────

    public ObjectProperty<State>  stateProperty()         { return state; }
    public BooleanProperty        syncedProperty()         { return synced; }
    public StringProperty         statusMessageProperty()  { return statusMessage; }
    public State                  getState()               { return state.get(); }
    public boolean                isSynced()               { return synced.get(); }
    public CelestialObject        getTarget()              { return target; }

    // ── Основной тик сопровождения ────────────────────────────────────────────

    /**
     * Вызывается каждые UPDATE_INTERVAL_SEC секунд.
     * Рассчитывает нужные угловые смещения и отправляет команды двигателям.
     */
    private void trackingTick() {
        if (state.get() != State.RUNNING || target == null) return;

        try {
            AstronomyCalculator.HorizontalCoords targetPos = getTargetPosition(target);

            // Текущее расчётное положение телескопа (по шагам двигателей)
            double currentAz  = motorAz.getCurrentAngleDegrees()  + syncOffsetAz;
            double currentAlt = motorAlt.getCurrentAngleDegrees() + syncOffsetAlt;

            // Ошибка наведения
            double errAz  = angularDiff(targetPos.azimuth(),  currentAz);
            double errAlt = targetPos.altitude() - currentAlt;

            // Проверяем видимость
            if (targetPos.altitude() < MIN_ALTITUDE_DEG) {
                logger.logError(target.getName() + " опустился под горизонт. Сопровождение остановлено.");
                Platform.runLater(this::stop);
                return;
            }

            // Отправляем коррекцию если ошибка > 0.05°
            if (Math.abs(errAz) > 0.05) {
                correctMotor(motorAz, errAz, "RIGHT", "LEFT");
            }
            if (Math.abs(errAlt) > 0.05) {
                correctMotor(motorAlt, errAlt, "UP", "DOWN");
            }

            // Обновляем UI
            Platform.runLater(() -> {
                coordinates.setAzimuth(targetPos.azimuth());
                coordinates.setAltitude(targetPos.altitude());
                setStatus(String.format("Сопровождение: %s | AZ=%.2f° ALT=%.2f°",
                        target.getName(), targetPos.azimuth(), targetPos.altitude()));
            });

        } catch (Exception e) {
            logger.logError("Ошибка сопровождения: " + e.getMessage());
        }
    }

    /**
     * Отправляет команду коррекции одному двигателю.
     * Учитывает механические пределы.
     */
    private void correctMotor(StepperMotorTracker motor, double errorDeg,
                               String cmdPos, String cmdNeg) {
        if (errorDeg > 0) {
            if (!motor.canMoveForward()) {
                logger.logError(motor.getName() + ": достигнут максимальный предел!");
                return;
            }
            long steps = motor.degreesToSteps(Math.abs(errorDeg));
            motor.addSteps(steps);
            arduino.sendCommand(cmdPos);
        } else {
            if (!motor.canMoveBackward()) {
                logger.logError(motor.getName() + ": достигнут минимальный предел!");
                return;
            }
            long steps = motor.degreesToSteps(Math.abs(errorDeg));
            motor.addSteps(-steps);
            arduino.sendCommand(cmdNeg);
        }
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    private double angularDiff(double a2, double a1) {
        double d = a2 - a1;
        if (d > 180)  d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private void cancelTask() {
        if (trackingTask != null && !trackingTask.isDone()) {
            trackingTask.cancel(false);
        }
    }

    private void setState(State s) {
        Platform.runLater(() -> state.set(s));
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> statusMessage.set(msg));
    }
}
