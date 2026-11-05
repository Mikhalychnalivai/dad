package com.telescope;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * Точка входа в приложение управления мини-телескопом.
 * Загружает главный FXML-экран и применяет тёмную тему.
 */
public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/telescope/main.fxml")
        );

        Scene scene = new Scene(loader.load(), 1280, 800);

        // Подключаем основной CSS с тёмной космической темой
        scene.getStylesheets().add(
                Objects.requireNonNull(
                        getClass().getResource("/com/telescope/styles.css")
                ).toExternalForm()
        );

        primaryStage.setTitle("TelescopeControl — Управление мини-телескопом");
        primaryStage.setMinWidth(1024);
        primaryStage.setMinHeight(680);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
