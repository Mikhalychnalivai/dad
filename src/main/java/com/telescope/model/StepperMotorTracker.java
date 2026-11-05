package com.telescope.model;

import javafx.beans.property.*;

/**
 * Учёт положения шагового двигателя.
 *
 * Поскольку физических датчиков положения нет, позиция рассчитывается
 * на основе подсчёта шагов. Предусмотрена ручная установка нулевого положения.
 *
 * Защищает от выхода за механические пределы — при достижении лимита
 * движение в данном направлении запрещается.
 */
public class StepperMotorTracker {

    private final String name; // "Двигатель 1 (AZ)" / "Двигатель 2 (ALT)"

    // ── Текущее положение ─────────────────────────────────────────────────────

    /** Текущий шаг (положительный = CW, отрицательный = CCW). */
    private final LongProperty currentStep = new SimpleLongProperty(0L);

    // ── Механические пределы ─────────────────────────────────────────────────

    /** Минимально допустимый шаг (нижний предел). */
    private long minStep;

    /** Максимально допустимый шаг (верхний предел). */
    private long maxStep;

    // ── Параметры шагового двигателя ─────────────────────────────────────────

    /** Количество шагов на один полный оборот (360°). */
    private final int stepsPerRevolution;

    // ── Флаги ─────────────────────────────────────────────────────────────────

    /** true — двигатель достиг нижнего предела. */
    private final BooleanProperty atMinLimit = new SimpleBooleanProperty(false);

    /** true — двигатель достиг верхнего предела. */
    private final BooleanProperty atMaxLimit = new SimpleBooleanProperty(false);

    /**
     * @param name              отображаемое имя двигателя
     * @param stepsPerRevolution шагов на оборот (типично 200 для 1.8°/шаг)
     * @param minStep           нижний механический предел в шагах
     * @param maxStep           верхний механический предел в шагах
     */
    public StepperMotorTracker(String name, int stepsPerRevolution,
                               long minStep, long maxStep) {
        this.name               = name;
        this.stepsPerRevolution = stepsPerRevolution;
        this.minStep            = minStep;
        this.maxStep            = maxStep;
    }

    // ── Управление шагами ─────────────────────────────────────────────────────

    /**
     * Добавляет указанное количество шагов (положительное — вперёд, отрицательное — назад).
     * Если новое положение выходит за пределы — обрезается до лимита.
     *
     * @return фактическое количество выполненных шагов (меньше delta при достижении предела)
     */
    public long addSteps(long delta) {
        long newStep = currentStep.get() + delta;

        if (newStep <= minStep) {
            long actual = minStep - currentStep.get();
            currentStep.set(minStep);
            updateLimitFlags();
            return actual;
        }

        if (newStep >= maxStep) {
            long actual = maxStep - currentStep.get();
            currentStep.set(maxStep);
            updateLimitFlags();
            return actual;
        }

        currentStep.set(newStep);
        updateLimitFlags();
        return delta;
    }

    /**
     * Принудительно устанавливает текущий шаг как нулевую позицию (HOME).
     * Используется для ручной установки исходного положения.
     */
    public void resetToHome() {
        currentStep.set(0L);
        updateLimitFlags();
    }

    /**
     * Устанавливает текущую позицию в конкретный шаг.
     * Полезно при синхронизации с реальным положением.
     */
    public void setCurrentStep(long step) {
        currentStep.set(Math.max(minStep, Math.min(maxStep, step)));
        updateLimitFlags();
    }

    // ── Конвертация шаги ↔ градусы ────────────────────────────────────────────

    /** Переводит текущее положение в градусы (0° = HOME). */
    public double getCurrentAngleDegrees() {
        return (double) currentStep.get() / stepsPerRevolution * 360.0;
    }

    /** Переводит угол в градусах в количество шагов (от HOME). */
    public long degreesToSteps(double degrees) {
        return Math.round(degrees / 360.0 * stepsPerRevolution);
    }

    /**
     * Рассчитывает, сколько шагов нужно сделать чтобы достичь целевого угла.
     * Возвращает 0 если цель за пределами механики.
     */
    public long stepsToAngle(double targetDegrees) {
        long targetStep = degreesToSteps(targetDegrees);
        long delta = targetStep - currentStep.get();

        // Проверяем можно ли выполнить это перемещение
        long newPos = currentStep.get() + delta;
        if (newPos < minStep || newPos > maxStep) return 0;
        return delta;
    }

    // ── Проверка пределов ─────────────────────────────────────────────────────

    /** Можно ли двигаться в положительном направлении? */
    public boolean canMoveForward() {
        return currentStep.get() < maxStep;
    }

    /** Можно ли двигаться в отрицательном направлении? */
    public boolean canMoveBackward() {
        return currentStep.get() > minStep;
    }

    // ── Геттеры ───────────────────────────────────────────────────────────────

    public String  getName()              { return name; }
    public long    getCurrentStep()       { return currentStep.get(); }
    public long    getMinStep()           { return minStep; }
    public long    getMaxStep()           { return maxStep; }
    public int     getStepsPerRevolution(){ return stepsPerRevolution; }
    public boolean isAtMinLimit()         { return atMinLimit.get(); }
    public boolean isAtMaxLimit()         { return atMaxLimit.get(); }

    public LongProperty    currentStepProperty() { return currentStep; }
    public BooleanProperty atMinLimitProperty()  { return atMinLimit; }
    public BooleanProperty atMaxLimitProperty()  { return atMaxLimit; }

    public void setMinStep(long v) { this.minStep = v; }
    public void setMaxStep(long v) { this.maxStep = v; }

    /** Краткое состояние для отображения в статусной строке. */
    public String getStatusSummary() {
        String limits = isAtMinLimit() ? " [МИН]" : isAtMaxLimit() ? " [МАКС]" : "";
        return String.format("%s: шаг=%d (%.1f°)%s",
                name, currentStep.get(), getCurrentAngleDegrees(), limits);
    }

    // ── Внутренние методы ─────────────────────────────────────────────────────

    private void updateLimitFlags() {
        atMinLimit.set(currentStep.get() <= minStep);
        atMaxLimit.set(currentStep.get() >= maxStep);
    }
}
