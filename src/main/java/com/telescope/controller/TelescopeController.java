package com.telescope.controller;

import com.telescope.arduino.ArduinoManager;
import com.telescope.model.CoordinateManager;
import com.telescope.model.CommandLogger;
import com.telescope.model.StepperMotorTracker;
import javafx.animation.AnimationTimer;

/**
 * Управляет движением телескопа.
 *
 * Отвечает за:
 *  – формирование и отправку команд движения;
 *  – обработку удержания кнопок (непрерывное движение);
 *  – обновление расчётных координат и шагов двигателей.
 *
 * Учитывает механические пределы двигателей через {@link StepperMotorTracker}.
 */
public class TelescopeController {

    // ── Команды протокола Arduino ──────────────────────────────────────────────
    public static final String CMD_RIGHT  = "RIGHT";
    public static final String CMD_LEFT   = "LEFT";
    public static final String CMD_UP     = "UP";
    public static final String CMD_DOWN   = "DOWN";
    public static final String CMD_STOP   = "STOP";
    public static final String CMD_SPEED1 = "SPEED1:";
    public static final String CMD_SPEED2 = "SPEED2:";

    // Интервал повторной отправки при удержании кнопки
    private static final long   HOLD_INTERVAL_NS = 150_000_000L; // 150 мс
    // Угловой шаг за один тик в демо-режиме (градусы)
    private static final double COORD_STEP = 0.1;
    // Шагов за один тик демо (для учёта StepperMotorTracker)
    private static final int    STEPS_PER_TICK = 2;

    private final ArduinoManager     arduino;
    private final CoordinateManager  coordinates;
    private final CommandLogger      logger;
    private final StepperMotorTracker motorAz;
    private final StepperMotorTracker motorAlt;

    private String activeCommand = null;
    private long   lastSendTime  = 0;
    private int    speed1 = 50;
    private int    speed2 = 50;

    private final AnimationTimer holdTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (activeCommand != null && (now - lastSendTime) >= HOLD_INTERVAL_NS) {
                sendMovementCommand(activeCommand);
                lastSendTime = now;
            }
        }
    };

    public TelescopeController(ArduinoManager arduino,
                               CoordinateManager coordinates,
                               CommandLogger logger,
                               StepperMotorTracker motorAz,
                               StepperMotorTracker motorAlt) {
        this.arduino     = arduino;
        this.coordinates = coordinates;
        this.logger      = logger;
        this.motorAz     = motorAz;
        this.motorAlt    = motorAlt;
        holdTimer.start();
    }

    // ── Управление движением ──────────────────────────────────────────────────

    public void onDirectionPressed(String command) {
        activeCommand = command;
        sendMovementCommand(command);
        lastSendTime = System.nanoTime();
    }

    public void onDirectionReleased() {
        activeCommand = null;
        stop();
    }

    public void stop() {
        arduino.sendCommand(CMD_STOP);
    }

    // ── Скорость ──────────────────────────────────────────────────────────────

    public void setSpeed1(int speed) {
        this.speed1 = clampSpeed(speed);
        arduino.sendCommand(CMD_SPEED1 + this.speed1);
    }

    public void setSpeed2(int speed) {
        this.speed2 = clampSpeed(speed);
        arduino.sendCommand(CMD_SPEED2 + this.speed2);
    }

    public int getSpeed1() { return speed1; }
    public int getSpeed2() { return speed2; }

    // ── Внутренние методы ─────────────────────────────────────────────────────

    private void sendMovementCommand(String command) {
        // Проверяем механические пределы перед отправкой
        if (!checkLimits(command)) return;

        arduino.sendCommand(command);

        // Обновляем расчётное положение
        updateDemoCoordinates(command);
        updateSteps(command);
    }

    /**
     * Проверяет, допустимо ли движение в данном направлении с учётом пределов.
     */
    private boolean checkLimits(String command) {
        return switch (command) {
            case CMD_RIGHT -> { if (!motorAz.canMoveForward())  { logger.logError("Дв1 (AZ): достигнут максимальный предел!"); yield false; } yield true; }
            case CMD_LEFT  -> { if (!motorAz.canMoveBackward()) { logger.logError("Дв1 (AZ): достигнут минимальный предел!"); yield false; } yield true; }
            case CMD_UP    -> { if (!motorAlt.canMoveForward()) { logger.logError("Дв2 (ALT): достигнут максимальный предел!"); yield false; } yield true; }
            case CMD_DOWN  -> { if (!motorAlt.canMoveBackward()){ logger.logError("Дв2 (ALT): достигнут минимальный предел!"); yield false; } yield true; }
            default -> true;
        };
    }

    /** Обновляет шаги двигателей при движении в демо-режиме. */
    private void updateSteps(String command) {
        double speedFactor = speed1 / 50.0;
        int steps = (int) (STEPS_PER_TICK * speedFactor);
        switch (command) {
            case CMD_RIGHT -> motorAz.addSteps(steps);
            case CMD_LEFT  -> motorAz.addSteps(-steps);
            case CMD_UP    -> motorAlt.addSteps(steps);
            case CMD_DOWN  -> motorAlt.addSteps(-steps);
        }
    }

    /** Обновляет расчётные координаты в демо-режиме. */
    private void updateDemoCoordinates(String command) {
        if (arduino.isConnected()) return; // реальный режим — координаты от Arduino
        double speedFactor = speed1 / 50.0;
        switch (command) {
            case CMD_RIGHT -> coordinates.updateFromMotorSteps( COORD_STEP * speedFactor, 0);
            case CMD_LEFT  -> coordinates.updateFromMotorSteps(-COORD_STEP * speedFactor, 0);
            case CMD_UP    -> coordinates.updateFromMotorSteps(0,  COORD_STEP * speedFactor);
            case CMD_DOWN  -> coordinates.updateFromMotorSteps(0, -COORD_STEP * speedFactor);
        }
    }

    private int clampSpeed(int s) { return Math.max(0, Math.min(100, s)); }
}
