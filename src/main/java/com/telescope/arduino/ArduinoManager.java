package com.telescope.arduino;

import com.fazecast.jSerialComm.SerialPort;
import com.telescope.model.CommandLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Управляет последовательным соединением с Arduino через COM-порт.
 *
 * Использует библиотеку jSerialComm.
 * Поддерживает:
 *  – поиск доступных портов;
 *  – подключение / отключение;
 *  – отправку текстовых команд;
 *  – асинхронное чтение ответов от Arduino.
 *
 * Если Arduino не подключена, приложение работает в демонстрационном режиме:
 * команды логируются, но не отправляются.
 */
public class ArduinoManager {

    // Настройки последовательного порта (должны совпадать с прошивкой Arduino)
    private static final int BAUD_RATE    = 9600;
    private static final int DATA_BITS    = 8;
    private static final int STOP_BITS    = SerialPort.ONE_STOP_BIT;
    private static final int PARITY       = SerialPort.NO_PARITY;
    private static final int READ_TIMEOUT = 100; // мс

    private SerialPort serialPort;
    private OutputStream outputStream;
    private InputStream  inputStream;

    /** Поток для асинхронного чтения данных от Arduino. */
    private final ExecutorService readerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arduino-reader");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean reading = false;

    private final CommandLogger logger;

    /** true — соединение установлено. */
    private final BooleanProperty connected = new SimpleBooleanProperty(false);

    /** Имя текущего порта или «Демо-режим». */
    private final StringProperty statusText = new SimpleStringProperty("Не подключено");

    /** Последний ответ, полученный от Arduino. */
    private final StringProperty lastResponse = new SimpleStringProperty("");

    public ArduinoManager(CommandLogger logger) {
        this.logger = logger;
    }

    // ── Обнаружение портов ─────────────────────────────────────────────────────

    /**
     * Возвращает список имён всех доступных COM-портов.
     */
    public List<String> getAvailablePorts() {
        List<String> names = new ArrayList<>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            names.add(port.getSystemPortName());
        }
        return names;
    }

    // ── Подключение / отключение ───────────────────────────────────────────────

    /**
     * Подключается к указанному COM-порту.
     *
     * @param portName системное имя порта, например "COM3" или "/dev/ttyUSB0"
     * @return true при успешном подключении
     */
    public boolean connect(String portName) {
        if (isConnected()) {
            disconnect();
        }

        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(BAUD_RATE);
        serialPort.setNumDataBits(DATA_BITS);
        serialPort.setNumStopBits(STOP_BITS);
        serialPort.setParity(PARITY);
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT, 0
        );

        if (!serialPort.openPort()) {
            logger.logError("Не удалось открыть порт: " + portName);
            updateStatus(false, "Ошибка порта " + portName);
            return false;
        }

        outputStream = serialPort.getOutputStream();
        inputStream  = serialPort.getInputStream();

        updateStatus(true, portName);
        logger.logInfo("Подключено к " + portName + " (" + BAUD_RATE + " бод)");

        startReading();
        return true;
    }

    /**
     * Закрывает соединение с Arduino.
     */
    public void disconnect() {
        reading = false;

        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            logger.logInfo("Соединение закрыто");
        }

        serialPort    = null;
        outputStream  = null;
        inputStream   = null;

        updateStatus(false, "Не подключено");
    }

    // ── Отправка команд ────────────────────────────────────────────────────────

    /**
     * Отправляет текстовую команду на Arduino.
     * Команда дополняется символом новой строки (\n), как ожидает прошивка.
     *
     * Если соединение отсутствует — команда только логируется (демо-режим).
     *
     * @param command команда, например "RIGHT", "SPEED1:50"
     */
    public void sendCommand(String command) {
        logger.logCommand(command);

        if (!isConnected()) {
            // Демо-режим: команда записана в журнал, но не отправлена
            logger.logInfo("[демо] Команда выполнена локально");
            return;
        }

        try {
            byte[] data = (command + "\n").getBytes(StandardCharsets.UTF_8);
            outputStream.write(data);
            outputStream.flush();
        } catch (Exception e) {
            logger.logError("Ошибка отправки: " + e.getMessage());
            disconnect();
        }
    }

    // ── Свойства состояния ─────────────────────────────────────────────────────

    public boolean isConnected() {
        return connected.get();
    }

    public BooleanProperty connectedProperty() {
        return connected;
    }

    public StringProperty statusTextProperty() {
        return statusText;
    }

    public StringProperty lastResponseProperty() {
        return lastResponse;
    }

    // ── Внутренние методы ──────────────────────────────────────────────────────

    /** Запускает фоновый поток чтения ответов от Arduino. */
    private void startReading() {
        reading = true;
        readerThread.submit(() -> {
            StringBuilder buffer = new StringBuilder();
            byte[] singleByte = new byte[1];

            while (reading && serialPort != null && serialPort.isOpen()) {
                try {
                    int bytesRead = inputStream.read(singleByte);
                    if (bytesRead > 0) {
                        char c = (char) singleByte[0];
                        if (c == '\n') {
                            String line = buffer.toString().trim();
                            if (!line.isEmpty()) {
                                final String response = line;
                                logger.logResponse(response);
                                Platform.runLater(() -> lastResponse.set(response));
                            }
                            buffer.setLength(0);
                        } else {
                            buffer.append(c);
                        }
                    }
                } catch (Exception e) {
                    if (reading) {
                        logger.logError("Ошибка чтения: " + e.getMessage());
                        Platform.runLater(this::disconnect);
                        break;
                    }
                }
            }
        });
    }

    /** Обновляет статус подключения в потоке JavaFX. */
    private void updateStatus(boolean isConnected, String status) {
        Platform.runLater(() -> {
            connected.set(isConnected);
            statusText.set(status);
        });
    }
}
