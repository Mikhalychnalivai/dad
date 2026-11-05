package com.telescope.controller;

import com.telescope.arduino.ArduinoManager;
import com.telescope.model.*;
import com.telescope.ui.SkyMapCanvas;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Главный контроллер интерфейса.
 *
 * Управляет вкладками: Карта неба, Каталог объектов, Настройки.
 * Интегрирует SkyMapCanvas с каталогом, сопровождением и координатами телескопа.
 */
public class MainController implements Initializable {

    // ── FXML — верхняя панель ─────────────────────────────────────────────────
    @FXML private Label          lblConnectionStatus;
    @FXML private Circle         indicatorCircle;
    @FXML private ComboBox<String> cmbPort;
    @FXML private Button         btnConnect;
    @FXML private Label          lblSystemState;
    @FXML private Label          lblTrackingStatus;

    // ── FXML — кнопки управления ──────────────────────────────────────────────
    @FXML private Button btnUp, btnDown, btnLeft, btnRight, btnStop;

    // ── FXML — скорость ───────────────────────────────────────────────────────
    @FXML private Slider sliderSpeed1, sliderSpeed2;
    @FXML private Label  lblSpeed1Value, lblSpeed2Value;

    // ── FXML — координаты ─────────────────────────────────────────────────────
    @FXML private Label     lblAzimuth, lblAltitude, lblRA, lblDEC;
    @FXML private TextField tfTargetAz, tfTargetAlt;
    @FXML private Button    btnGoTo;

    // ── FXML — карта (контейнер) ──────────────────────────────────────────────
    @FXML private StackPane starMapContainer;

    // ── FXML — каталог ────────────────────────────────────────────────────────
    @FXML private TextField  tfCatalogSearch;
    @FXML private ComboBox<String> cmbTypeFilter;
    @FXML private Button     btnClearSearch;
    @FXML private TableView<CelestialObject>          catalogTable;
    @FXML private TableColumn<CelestialObject, String> colName, colType, colRA, colDEC;
    @FXML private TableColumn<CelestialObject, Double> colMag;
    @FXML private Label  lblSelectedObject, lblObjectCoords, lblObjectDesc;
    @FXML private Label  lblCalcAz, lblCalcAlt, lblVisible;
    @FXML private Button btnSyncObject, btnSelectObject;

    // ── FXML — сопровождение ──────────────────────────────────────────────────
    @FXML private Label  lblTrackingTarget, lblTrackingState, lblSyncStatus;
    @FXML private Circle trackingIndicator;
    @FXML private Button btnStartTracking, btnPauseTracking, btnStopTracking;

    // ── FXML — настройки наблюдателя ─────────────────────────────────────────
    @FXML private TextField   tfLatitude, tfLongitude;
    @FXML private ComboBox<String> cmbTimezone;
    @FXML private Label       lblLocalTime;
    @FXML private Button      btnSaveLocation;

    // ── FXML — настройки двигателей ───────────────────────────────────────────
    @FXML private TextField tfStepsPerRev1, tfMinStep1, tfMaxStep1;
    @FXML private TextField tfStepsPerRev2, tfMinStep2, tfMaxStep2;
    @FXML private Label     lblCurrentStep1, lblCurrentStep2;
    @FXML private Button    btnResetMotor1, btnApplyMotor1;
    @FXML private Button    btnResetMotor2, btnApplyMotor2;

    // ── FXML — нижняя панель ──────────────────────────────────────────────────
    @FXML private Label lblArduinoState, lblMotor1State, lblMotor2State;
    @FXML private Label lblCurrentSpeed, lblStepperStatus;
    @FXML private ListView<String> logListView;

    // ── Бизнес-объекты ────────────────────────────────────────────────────────
    private ArduinoManager      arduinoManager;
    private TelescopeController telescopeCtrl;
    private CoordinateManager   coordinateManager;
    private CommandLogger       commandLogger;
    private CelestialCatalog    catalog;
    private ObserverSettings    observer;
    private StepperMotorTracker motorAz;
    private StepperMotorTracker motorAlt;
    private TrackingService     trackingService;

    // ── Карта ─────────────────────────────────────────────────────────────────
    private SkyMapCanvas skyMap;

    // ── Текущий выбранный объект каталога ─────────────────────────────────────
    private CelestialObject selectedCatalogObject = null;

    private Timeline uiTimer;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z");

    // ── Инициализация ─────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        commandLogger     = new CommandLogger();
        coordinateManager = new CoordinateManager();
        arduinoManager    = new ArduinoManager(commandLogger);
        observer          = new ObserverSettings();
        motorAz  = new StepperMotorTracker("Дв1 (AZ)",  200, -36000, 36000);
        motorAlt = new StepperMotorTracker("Дв2 (ALT)", 200,  -5000,  5000);
        telescopeCtrl   = new TelescopeController(arduinoManager, coordinateManager,
                                                  commandLogger, motorAz, motorAlt);
        catalog         = new CelestialCatalog();
        trackingService = new TrackingService(arduinoManager, observer,
                                              coordinateManager, motorAz, motorAlt,
                                              commandLogger);

        setupSkyMap();
        setupConnectionPanel();
        setupDirectionButtons();
        setupSpeedSliders();
        setupCoordinateBindings();
        setupCatalogTab();
        setupTrackingPanel();
        setupSettingsTab();
        setupLogView();
        setupStatusBar();
        setupUiTimer();

        commandLogger.logInfo("Приложение запущено. Демо-режим активен.");
        updateConnectionIndicator(false);
    }

    // ── Карта звёздного неба ──────────────────────────────────────────────────

    private void setupSkyMap() {
        skyMap = new SkyMapCanvas(observer);

        // Привязка размеров к контейнеру
        skyMap.widthProperty().bind(starMapContainer.widthProperty());
        skyMap.heightProperty().bind(starMapContainer.heightProperty());

        // Тулбар поверх карты
        HBox toolbar = buildMapToolbar();
        StackPane.setAlignment(toolbar, javafx.geometry.Pos.TOP_LEFT);

        starMapContainer.getChildren().addAll(skyMap, toolbar);

        // Когда пользователь кликает на объект на карте — показываем в инфо-панели
        skyMap.setOnObjectSelected(obj -> {
            if (obj == null) return;
            commandLogger.logInfo("Выбрано на карте: " + obj.name());
        });

        // Синхронизируем прицел с координатами телескопа
        coordinateManager.azimuthProperty().addListener((o, a, b) -> updateMapCrosshair());
        coordinateManager.altitudeProperty().addListener((o, a, b) -> updateMapCrosshair());
    }

    /** Тулбар переключателей карты. */
    private HBox buildMapToolbar() {
        HBox bar = new HBox(6);
        bar.setPadding(new Insets(8, 8, 8, 8));
        bar.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 0 0 8 0;");
        bar.setMaxWidth(Region.USE_PREF_SIZE);
        bar.setMaxHeight(Region.USE_PREF_SIZE);

        ToggleButton tbGrid   = mapToggle("Сетка",       true,  v -> skyMap.setShowGrid(v));
        ToggleButton tbLines  = mapToggle("Созвездия",   true,  v -> skyMap.setShowConLines(v));
        ToggleButton tbLabels = mapToggle("Подписи",     true,  v -> skyMap.setShowConLabels(v));
        ToggleButton tbNames  = mapToggle("Имена звёзд", true,  v -> skyMap.setShowStarNames(v));
        ToggleButton tbDso    = mapToggle("DSO",         true,  v -> skyMap.setShowDso(v));

        // Поиск объекта на карте
        TextField tfSearch = new TextField();
        tfSearch.setPromptText("Найти на карте...");
        tfSearch.setPrefWidth(140);
        tfSearch.setStyle(
                "-fx-background-color: rgba(10,10,20,0.75);" +
                "-fx-text-fill: #e0e0e0; -fx-prompt-text-fill: #555;" +
                "-fx-border-color: #444; -fx-border-radius: 4;" +
                "-fx-background-radius: 4; -fx-padding: 3 6 3 6;");

        Button btnFind = new Button("→");
        btnFind.setStyle(
                "-fx-background-color: #2a2a2a; -fx-text-fill: #ddd;" +
                "-fx-border-color: #555; -fx-border-radius: 4;" +
                "-fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        btnFind.setOnAction(e -> searchOnMap(tfSearch.getText().trim()));
        tfSearch.setOnAction(e -> searchOnMap(tfSearch.getText().trim()));

        bar.getChildren().addAll(tbGrid, tbLines, tbLabels, tbNames, tbDso,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                tfSearch, btnFind);
        return bar;
    }

    private ToggleButton mapToggle(String text, boolean selected,
                                   java.util.function.Consumer<Boolean> action) {
        ToggleButton tb = new ToggleButton(text);
        tb.setSelected(selected);
        tb.setStyle(
                "-fx-background-color: " + (selected ? "#2a3a50" : "#1a1a1a") + ";" +
                "-fx-text-fill: " + (selected ? "#aac0e0" : "#666") + ";" +
                "-fx-border-color: #444; -fx-border-radius: 4;" +
                "-fx-background-radius: 4; -fx-cursor: hand;" +
                "-fx-font-size: 11; -fx-padding: 3 8 3 8;");
        tb.selectedProperty().addListener((obs, o, n) -> {
            tb.setStyle(
                    "-fx-background-color: " + (n ? "#2a3a50" : "#1a1a1a") + ";" +
                    "-fx-text-fill: " + (n ? "#aac0e0" : "#666") + ";" +
                    "-fx-border-color: #444; -fx-border-radius: 4;" +
                    "-fx-background-radius: 4; -fx-cursor: hand;" +
                    "-fx-font-size: 11; -fx-padding: 3 8 3 8;");
            action.accept(n);
        });
        return tb;
    }

    /** Ищет объект по имени в каталоге и переносит карту к нему. */
    private void searchOnMap(String query) {
        if (query.isEmpty()) return;
        String lower = query.toLowerCase();

        // Сначала ищем в CelestialCatalog
        for (CelestialObject obj : catalog.getAllObjects()) {
            if (obj.getName().toLowerCase().contains(lower)) {
                skyMap.goTo(obj.getRa(), obj.getDec());
                commandLogger.logInfo("Карта: переход к «" + obj.getName() + "»");
                return;
            }
        }

        // Ищем в звёздном каталоге карты
        commandLogger.logInfo("Объект «" + query + "» не найден в каталоге");
    }

    /** Обновляет прицел карты из расчётных координат RA/DEC. */
    private void updateMapCrosshair() {
        // Пересчитываем AZ/ALT → RA/DEC через обратное преобразование
        // (пока используем прямое восхождение из coordinateManager)
        try {
            // Парсим строку RA "ЧЧ:ММ:СС" → часы
            String raStr = coordinateManager.getRA();
            String[] parts = raStr.split(":");
            if (parts.length == 3) {
                double ra = Integer.parseInt(parts[0])
                        + Integer.parseInt(parts[1]) / 60.0
                        + Integer.parseInt(parts[2]) / 3600.0;
                double dec = coordinateManager.getAltitude(); // приближение для демо
                skyMap.setCrosshair(ra, dec);
            }
        } catch (Exception ignored) {}
    }

    // ── Подключение Arduino ───────────────────────────────────────────────────

    private void setupConnectionPanel() {
        refreshPortList();

        btnConnect.setOnAction(e -> {
            if (arduinoManager.isConnected()) handleDisconnect();
            else handleConnect();
        });

        cmbPort.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) refreshPortList();
        });

        arduinoManager.connectedProperty().addListener((obs, o, n) -> {
            updateConnectionIndicator(n);
            btnConnect.setText(n ? "Отключить" : "Подключить");
            lblSystemState.setText(n ? "Подключено" : "Демо-режим");
            lblArduinoState.setText(n
                    ? "Arduino: " + arduinoManager.statusTextProperty().get()
                    : "Arduino: не подключена");
        });
    }

    private void refreshPortList() {
        cmbPort.getItems().clear();
        List<String> ports = arduinoManager.getAvailablePorts();
        if (ports.isEmpty()) cmbPort.getItems().add("— нет портов —");
        else { cmbPort.getItems().addAll(ports); cmbPort.getSelectionModel().selectFirst(); }
    }

    private void handleConnect() {
        String port = cmbPort.getValue();
        if (port == null || port.startsWith("—")) { showAlert("Выберите COM-порт"); return; }
        commandLogger.logInfo("Подключение к " + port + "...");
        if (!arduinoManager.connect(port))
            showAlert("Не удалось подключиться к " + port + ".\nПроверьте порт.");
    }

    private void handleDisconnect() { arduinoManager.disconnect(); }

    // ── Кнопки направления ────────────────────────────────────────────────────

    private void setupDirectionButtons() {
        bindDirectionButton(btnUp,    TelescopeController.CMD_UP);
        bindDirectionButton(btnDown,  TelescopeController.CMD_DOWN);
        bindDirectionButton(btnLeft,  TelescopeController.CMD_LEFT);
        bindDirectionButton(btnRight, TelescopeController.CMD_RIGHT);

        btnStop.setOnAction(e -> {
            telescopeCtrl.stop();
            updateMotorStates("Остановлен", "Остановлен");
        });
    }

    private void bindDirectionButton(Button btn, String command) {
        btn.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                telescopeCtrl.onDirectionPressed(command);
                updateMotorStates(motorState(command, 1), motorState(command, 2));
                updateStepperStatus();
            }
        });
        btn.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                telescopeCtrl.onDirectionReleased();
                updateMotorStates("Ожидание", "Ожидание");
            }
        });
        btn.setOnMouseExited(e -> {
            if (e.isPrimaryButtonDown()) {
                telescopeCtrl.onDirectionReleased();
                updateMotorStates("Ожидание", "Ожидание");
            }
        });
    }

    // ── Скорость ──────────────────────────────────────────────────────────────

    private void setupSpeedSliders() {
        sliderSpeed1.setMin(1); sliderSpeed1.setMax(100); sliderSpeed1.setValue(50);
        sliderSpeed2.setMin(1); sliderSpeed2.setMax(100); sliderSpeed2.setValue(50);

        sliderSpeed1.valueProperty().addListener((obs, o, n) -> {
            int s = n.intValue();
            lblSpeed1Value.setText(s + "%");
            telescopeCtrl.setSpeed1(s);
            lblCurrentSpeed.setText("Скорость: Дв1=" + s + "% Дв2=" + telescopeCtrl.getSpeed2() + "%");
        });
        sliderSpeed2.valueProperty().addListener((obs, o, n) -> {
            int s = n.intValue();
            lblSpeed2Value.setText(s + "%");
            telescopeCtrl.setSpeed2(s);
            lblCurrentSpeed.setText("Скорость: Дв1=" + telescopeCtrl.getSpeed1() + "% Дв2=" + s + "%");
        });

        lblSpeed1Value.setText("50%");
        lblSpeed2Value.setText("50%");
    }

    // ── Координаты ────────────────────────────────────────────────────────────

    private void setupCoordinateBindings() {
        coordinateManager.azimuthProperty().addListener((o, a, b) ->
                lblAzimuth.setText(coordinateManager.getAzimuthFormatted()));
        coordinateManager.altitudeProperty().addListener((o, a, b) ->
                lblAltitude.setText(coordinateManager.getAltitudeFormatted()));
        coordinateManager.raProperty().addListener((o, a, b) -> lblRA.setText(b));
        coordinateManager.decProperty().addListener((o, a, b) -> lblDEC.setText(b));

        lblAzimuth.setText("0.00°");
        lblAltitude.setText("+0.00°");
        lblRA.setText("00:00:00");
        lblDEC.setText("+00°00'00\"");

        btnGoTo.setOnAction(e -> handleGoTo());
    }

    private void handleGoTo() {
        try {
            double az  = Double.parseDouble(tfTargetAz.getText().trim());
            double alt = Double.parseDouble(tfTargetAlt.getText().trim());
            commandLogger.logInfo(String.format(
                    "Цель: AZ=%.2f° ALT=%.2f° (авто-наведение не реализовано)", az, alt));
        } catch (NumberFormatException ex) {
            showAlert("Введите корректные числовые значения.");
        }
    }

    // ── Каталог объектов ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void setupCatalogTab() {
        colName.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getName()));
        colType.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getType().getDisplayName()));
        colRA.setCellValueFactory(d  ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getRaFormatted()));
        colDEC.setCellValueFactory(d ->
                new javafx.beans.property.SimpleStringProperty(d.getValue().getDecFormatted()));
        colMag.setCellValueFactory(new PropertyValueFactory<>("magnitude"));

        catalogTable.setItems(catalog.getFilteredObjects());

        tfCatalogSearch.textProperty().addListener((obs, o, n) -> catalog.setSearch(n));
        btnClearSearch.setOnAction(e -> {
            tfCatalogSearch.clear();
            cmbTypeFilter.getSelectionModel().clearSelection();
            catalog.clearFilters();
        });

        cmbTypeFilter.getItems().add("Все типы");
        for (CelestialObject.Type t : CelestialObject.Type.values())
            cmbTypeFilter.getItems().add(t.getDisplayName());
        cmbTypeFilter.getSelectionModel().selectFirst();
        cmbTypeFilter.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            if (n.intValue() <= 0) { catalog.setTypeFilter(null); return; }
            catalog.setTypeFilter(CelestialObject.Type.values()[n.intValue() - 1]);
        });

        catalogTable.getSelectionModel().selectedItemProperty().addListener((obs, o, obj) -> {
            selectedCatalogObject = obj;
            updateObjectDetails(obj);
            btnSyncObject.setDisable(obj == null);
            btnSelectObject.setDisable(obj == null);
            // Переносим карту к выбранному объекту
            if (obj != null) skyMap.goTo(obj.getRa(), obj.getDec());
        });

        btnSyncObject.setOnAction(e -> handleSynchronize());
        btnSelectObject.setOnAction(e -> handleSelectForTracking());
    }

    private void updateObjectDetails(CelestialObject obj) {
        if (obj == null) {
            lblSelectedObject.setText("Выберите объект в таблице");
            lblObjectCoords.setText("");
            lblObjectDesc.setText("");
            lblCalcAz.setText("—"); lblCalcAlt.setText("—"); lblVisible.setText("");
            return;
        }

        lblSelectedObject.setText(obj.getName());
        lblObjectCoords.setText(obj.getRaFormatted() + "  " + obj.getDecFormatted());
        lblObjectDesc.setText(obj.getDescription());

        AstronomyCalculator.HorizontalCoords pos =
                AstronomyCalculator.toHorizontal(obj.getRa(), obj.getDec(), observer);
        lblCalcAz.setText(String.format("%.2f°", pos.azimuth()));
        lblCalcAlt.setText(String.format("%+.2f°", pos.altitude()));
        lblVisible.setText(pos.isVisible() ? "✓ Видим" : "✗ Под горизонтом");
        lblVisible.setStyle(pos.isVisible()
                ? "-fx-text-fill: #aaffcc;" : "-fx-text-fill: #ff8888;");
    }

    private void handleSynchronize() {
        if (selectedCatalogObject == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Синхронизация");
        confirm.setHeaderText("Синхронизация с объектом «" + selectedCatalogObject.getName() + "»");
        confirm.setContentText(
                "Убедитесь, что телескоп направлен точно на этот объект.\n\n" +
                "Продолжить?");
        applyDarkTheme(confirm);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                trackingService.synchronize(selectedCatalogObject,
                        coordinateManager.getAzimuth(), coordinateManager.getAltitude());
                lblSyncStatus.setText("✓ Синхронизировано: " + selectedCatalogObject.getName());
                lblSyncStatus.setStyle("-fx-text-fill: #aaffcc;");
                updateTrackingButtons();
            }
        });
    }

    private void handleSelectForTracking() {
        if (selectedCatalogObject == null) return;
        lblTrackingTarget.setText(selectedCatalogObject.getName()
                + " (" + selectedCatalogObject.getType().getDisplayName() + ")");
        // Показываем маркер цели на карте
        skyMap.setTrackedObject(selectedCatalogObject, false);
        commandLogger.logInfo("Цель сопровождения: " + selectedCatalogObject.getName());
        updateTrackingButtons();
    }

    // ── Сопровождение ─────────────────────────────────────────────────────────

    private void setupTrackingPanel() {
        trackingIndicator.setFill(Color.web("#555555"));
        trackingIndicator.setEffect(null);

        btnStartTracking.setOnAction(e -> handleStartTracking());
        btnPauseTracking.setOnAction(e -> handlePauseResumeTracking());
        btnStopTracking.setOnAction(e  -> handleStopTracking());

        trackingService.stateProperty().addListener((obs, o, n) -> updateTrackingUI(n));
        trackingService.statusMessageProperty().addListener((obs, o, n) -> lblTrackingStatus.setText(n));
        trackingService.syncedProperty().addListener((obs, o, n) -> updateTrackingButtons());

        updateTrackingUI(TrackingService.State.IDLE);
    }

    private void handleStartTracking() {
        if (selectedCatalogObject == null) {
            showAlert("Сначала выберите объект во вкладке «Объекты»"); return;
        }
        if (!trackingService.start(selectedCatalogObject))
            showAlert("Не удалось запустить сопровождение.\nСм. журнал команд.");
        else
            skyMap.setTrackedObject(selectedCatalogObject, true);
    }

    private void handlePauseResumeTracking() {
        TrackingService.State s = trackingService.getState();
        if (s == TrackingService.State.RUNNING) {
            trackingService.pause();
            skyMap.setTrackedObject(selectedCatalogObject, false);
        } else if (s == TrackingService.State.PAUSED) {
            trackingService.resume();
            skyMap.setTrackedObject(selectedCatalogObject, true);
        }
    }

    private void handleStopTracking() {
        trackingService.stop();
        skyMap.setTrackedObject(null, false);
    }

    private void updateTrackingUI(TrackingService.State state) {
        switch (state) {
            case IDLE -> {
                trackingIndicator.setFill(Color.web("#555555"));
                trackingIndicator.setEffect(null);
                lblTrackingState.setText("Не активно");
                lblTrackingState.setStyle("-fx-text-fill: #666;");
                btnStartTracking.setDisable(selectedCatalogObject == null || !trackingService.isSynced());
                btnPauseTracking.setDisable(true);
                btnStopTracking.setDisable(true);
                btnPauseTracking.setText("⏸ Пауза");
            }
            case RUNNING -> {
                trackingIndicator.setFill(Color.web("#aaffcc"));
                trackingIndicator.setEffect(createGlow(Color.web("#aaffcc"), 8));
                lblTrackingState.setText("Сопровождение");
                lblTrackingState.setStyle("-fx-text-fill: #aaffcc;");
                btnStartTracking.setDisable(true);
                btnPauseTracking.setDisable(false);
                btnStopTracking.setDisable(false);
                btnPauseTracking.setText("⏸ Пауза");
            }
            case PAUSED -> {
                trackingIndicator.setFill(Color.web("#ffcc44"));
                trackingIndicator.setEffect(createGlow(Color.web("#ffcc44"), 6));
                lblTrackingState.setText("Пауза");
                lblTrackingState.setStyle("-fx-text-fill: #ffcc44;");
                btnStartTracking.setDisable(true);
                btnPauseTracking.setDisable(false);
                btnStopTracking.setDisable(false);
                btnPauseTracking.setText("▶ Продолжить");
            }
        }
    }

    private void updateTrackingButtons() {
        boolean synced   = trackingService.isSynced();
        boolean hasTarget = selectedCatalogObject != null;
        TrackingService.State st = trackingService.getState();
        btnStartTracking.setDisable(!synced || !hasTarget || st != TrackingService.State.IDLE);
        if (!synced) {
            lblSyncStatus.setText("Синхронизация не выполнена");
            lblSyncStatus.setStyle("-fx-text-fill: #ffcc44;");
        }
    }

    // ── Настройки ─────────────────────────────────────────────────────────────

    private void setupSettingsTab() {
        tfLatitude.setText(String.valueOf(observer.getLatitude()));
        tfLongitude.setText(String.valueOf(observer.getLongitude()));

        cmbTimezone.getItems().addAll(List.of(
                "Europe/Moscow","Europe/Kaliningrad","Asia/Yekaterinburg",
                "Asia/Omsk","Asia/Krasnoyarsk","Asia/Irkutsk",
                "Asia/Yakutsk","Asia/Vladivostok","Asia/Magadan",
                "Europe/London","Europe/Berlin","Europe/Paris",
                "America/New_York","America/Chicago","America/Los_Angeles","UTC"));
        cmbTimezone.setValue(observer.getTimezone());

        btnSaveLocation.setOnAction(e -> handleSaveLocation());

        tfStepsPerRev1.setText(String.valueOf(motorAz.getStepsPerRevolution()));
        tfMinStep1.setText(String.valueOf(motorAz.getMinStep()));
        tfMaxStep1.setText(String.valueOf(motorAz.getMaxStep()));
        tfStepsPerRev2.setText(String.valueOf(motorAlt.getStepsPerRevolution()));
        tfMinStep2.setText(String.valueOf(motorAlt.getMinStep()));
        tfMaxStep2.setText(String.valueOf(motorAlt.getMaxStep()));

        btnResetMotor1.setOnAction(e -> { motorAz.resetToHome();  updateStepperStatus(); commandLogger.logInfo("Дв1 HOME"); });
        btnResetMotor2.setOnAction(e -> { motorAlt.resetToHome(); updateStepperStatus(); commandLogger.logInfo("Дв2 HOME"); });
        btnApplyMotor1.setOnAction(e -> handleApplyMotor(motorAz,  tfStepsPerRev1, tfMinStep1, tfMaxStep1, "Двигатель 1"));
        btnApplyMotor2.setOnAction(e -> handleApplyMotor(motorAlt, tfStepsPerRev2, tfMinStep2, tfMaxStep2, "Двигатель 2"));

        motorAz.currentStepProperty().addListener((o, a, b)  -> updateStepperStatus());
        motorAlt.currentStepProperty().addListener((o, a, b) -> updateStepperStatus());
    }

    private void handleSaveLocation() {
        try {
            double lat = Double.parseDouble(tfLatitude.getText().trim());
            double lon = Double.parseDouble(tfLongitude.getText().trim());
            if (lat < -90 || lat > 90)  { showAlert("Широта: –90…+90"); return; }
            if (lon < -180 || lon > 180){ showAlert("Долгота: –180…+180"); return; }
            observer.setLatitude(lat);
            observer.setLongitude(lon);
            if (cmbTimezone.getValue() != null) observer.setTimezone(cmbTimezone.getValue());
            skyMap.setObserver(observer);
            trackingService.resetSync();
            commandLogger.logInfo(String.format("Местоположение: %.4f°, %.4f°", lat, lon));
        } catch (NumberFormatException ex) {
            showAlert("Введите корректные числовые значения.");
        }
    }

    private void handleApplyMotor(StepperMotorTracker motor,
                                  TextField tfSPR, TextField tfMin, TextField tfMax, String name) {
        try {
            long min = Long.parseLong(tfMin.getText().trim());
            long max = Long.parseLong(tfMax.getText().trim());
            if (min >= max) { showAlert("Мин. предел должен быть меньше макс."); return; }
            motor.setMinStep(min); motor.setMaxStep(max);
            commandLogger.logInfo(name + ": пределы [" + min + ", " + max + "]");
            updateStepperStatus();
        } catch (NumberFormatException ex) {
            showAlert("Введите целые числа для пределов.");
        }
    }

    // ── Журнал ────────────────────────────────────────────────────────────────

    private void setupLogView() {
        logListView.setItems(commandLogger.getEntries());
        commandLogger.getEntries().addListener((ListChangeListener<String>) c -> {
            while (c.next()) {
                if (c.wasAdded()) Platform.runLater(() ->
                        logListView.scrollTo(commandLogger.getEntries().size() - 1));
            }
        });
    }

    // ── Статусная строка ──────────────────────────────────────────────────────

    private void setupStatusBar() {
        lblArduinoState.setText("Arduino: не подключена");
        lblMotor1State.setText("Двигатель 1: ожидание");
        lblMotor2State.setText("Двигатель 2: ожидание");
        lblCurrentSpeed.setText("Скорость: Дв1=50% Дв2=50%");
    }

    // ── Таймер обновления UI ──────────────────────────────────────────────────

    private void setupUiTimer() {
        uiTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            try {
                ZonedDateTime now = observer.getObservationTime();
                lblLocalTime.setText(now.format(TIME_FMT));
            } catch (Exception ignored) {}

            if (selectedCatalogObject != null) updateObjectDetails(selectedCatalogObject);
            updateStepperStatus();
        }));
        uiTimer.setCycleCount(Animation.INDEFINITE);
        uiTimer.play();
    }

    // ── Вспомогательные ──────────────────────────────────────────────────────

    private void updateStepperStatus() {
        String s1 = String.format("%d/%.1f°", motorAz.getCurrentStep(),  motorAz.getCurrentAngleDegrees());
        String s2 = String.format("%d/%.1f°", motorAlt.getCurrentStep(), motorAlt.getCurrentAngleDegrees());
        lblCurrentStep1.setText(s1);
        lblCurrentStep2.setText(s2);
        lblStepperStatus.setText("AZ:" + s1 + "  ALT:" + s2);
    }

    private void updateConnectionIndicator(boolean on) {
        Color c = on ? Color.web("#aaffcc") : Color.web("#ff5555");
        indicatorCircle.setFill(c);
        indicatorCircle.setEffect(createGlow(c, on ? 8 : 6));
        lblConnectionStatus.setText(on ? "Подключено" : "Демо-режим");
    }

    private javafx.scene.effect.DropShadow createGlow(Color color, double radius) {
        javafx.scene.effect.DropShadow g = new javafx.scene.effect.DropShadow();
        g.setColor(color); g.setRadius(radius); g.setSpread(0.4);
        return g;
    }

    private void updateMotorStates(String m1, String m2) {
        lblMotor1State.setText("Двигатель 1: " + m1);
        lblMotor2State.setText("Двигатель 2: " + m2);
    }

    private String motorState(String cmd, int motor) {
        return switch (cmd) {
            case TelescopeController.CMD_RIGHT -> motor == 1 ? "Вправо" : "Ожидание";
            case TelescopeController.CMD_LEFT  -> motor == 1 ? "Влево"  : "Ожидание";
            case TelescopeController.CMD_UP    -> motor == 2 ? "Вверх"  : "Ожидание";
            case TelescopeController.CMD_DOWN  -> motor == 2 ? "Вниз"   : "Ожидание";
            default                            -> "Ожидание";
        };
    }

    private void applyDarkTheme(Dialog<?> dlg) {
        dlg.getDialogPane().getStylesheets().addAll(btnConnect.getScene().getStylesheets());
        dlg.getDialogPane().getStyleClass().add("dark-dialog");
    }

    private void showAlert(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Информация"); a.setHeaderText(null); a.setContentText(msg);
        applyDarkTheme(a); a.showAndWait();
    }
}
