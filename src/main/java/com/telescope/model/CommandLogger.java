package com.telescope.model;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Журнал команд и системных сообщений.
 * Хранит историю отправленных команд и ответов Arduino.
 * Потокобезопасен — внутренние обновления переносятся в поток JavaFX.
 */
public class CommandLogger {

    private static final int MAX_ENTRIES = 200;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Наблюдаемый список записей для привязки к UI-компонентам. */
    private final ObservableList<String> entries = FXCollections.observableArrayList();

    /** Слушатели, которых нужно уведомлять о новых записях. */
    private final List<Runnable> changeListeners = new ArrayList<>();

    // ── Публичный API ──────────────────────────────────────────────────────────

    /**
     * Добавляет запись об отправленной команде.
     */
    public void logCommand(String command) {
        addEntry("→ " + command);
    }

    /**
     * Добавляет запись об ответе от Arduino.
     */
    public void logResponse(String response) {
        addEntry("← " + response.trim());
    }

    /**
     * Добавляет информационное системное сообщение.
     */
    public void logInfo(String message) {
        addEntry("ℹ " + message);
    }

    /**
     * Добавляет сообщение об ошибке.
     */
    public void logError(String message) {
        addEntry("✗ " + message);
    }

    /**
     * Возвращает наблюдаемый список для привязки к ListView/TextArea.
     */
    public ObservableList<String> getEntries() {
        return entries;
    }

    /**
     * Регистрирует слушателя изменений (для обновления UI).
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Очищает журнал.
     */
    public void clear() {
        runOnFxThread(entries::clear);
    }

    // ── Внутренние методы ──────────────────────────────────────────────────────

    private void addEntry(String text) {
        String timestamped = "[" + LocalTime.now().format(TIME_FMT) + "] " + text;
        runOnFxThread(() -> {
            if (entries.size() >= MAX_ENTRIES) {
                entries.remove(0);
            }
            entries.add(timestamped);
            changeListeners.forEach(Runnable::run);
        });
    }

    /** Переносит выполнение в поток JavaFX, если вызов пришёл из другого потока. */
    private void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
