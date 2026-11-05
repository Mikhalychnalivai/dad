package com.telescope.model;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;

import java.util.List;

/**
 * Каталог небесных объектов с возможностью поиска.
 *
 * Содержит набор встроенных объектов (планеты, звёзды, туманности, галактики).
 * Поддерживает фильтрацию по названию и типу через {@link FilteredList}.
 *
 * В дальнейшем можно расширить загрузкой из файла или базы данных.
 */
public class CelestialCatalog {

    private final ObservableList<CelestialObject> allObjects =
            FXCollections.observableArrayList();

    private final FilteredList<CelestialObject> filteredObjects =
            new FilteredList<>(allObjects, p -> true);

    /** Текущий поисковый запрос. */
    private String searchQuery = "";

    /** Текущий фильтр по типу (null = все типы). */
    private CelestialObject.Type typeFilter = null;

    public CelestialCatalog() {
        loadBuiltinObjects();
    }

    // ── Поиск и фильтрация ────────────────────────────────────────────────────

    /**
     * Устанавливает поисковый запрос (поиск по названию, регистронезависимо).
     */
    public void setSearch(String query) {
        this.searchQuery = query == null ? "" : query.trim().toLowerCase();
        applyFilter();
    }

    /**
     * Устанавливает фильтр по типу объекта. null — показать все.
     */
    public void setTypeFilter(CelestialObject.Type type) {
        this.typeFilter = type;
        applyFilter();
    }

    /** Сбрасывает все фильтры. */
    public void clearFilters() {
        searchQuery = "";
        typeFilter = null;
        filteredObjects.setPredicate(p -> true);
    }

    /** Возвращает отфильтрованный список для привязки к TableView/ListView. */
    public FilteredList<CelestialObject> getFilteredObjects() {
        return filteredObjects;
    }

    /** Возвращает полный список объектов. */
    public ObservableList<CelestialObject> getAllObjects() {
        return allObjects;
    }

    // ── Встроенный каталог ────────────────────────────────────────────────────

    private void loadBuiltinObjects() {
        List<CelestialObject> objects = List.of(

            // ── Планеты Солнечной системы ─────────────────────────────────────
            // (координаты планет меняются — здесь приближённые значения эпохи J2000)
            new CelestialObject("Луна",     CelestialObject.Type.PLANET,  5.53,  23.0, -12.6,
                    "Естественный спутник Земли. Координаты меняются ежедневно."),
            new CelestialObject("Марс",     CelestialObject.Type.PLANET,  5.69,  24.5,  1.8,
                    "Четвёртая планета Солнечной системы. Видна невооружённым глазом."),
            new CelestialObject("Юпитер",   CelestialObject.Type.PLANET,  4.71,  22.0, -2.9,
                    "Крупнейшая планета Солнечной системы."),
            new CelestialObject("Сатурн",   CelestialObject.Type.PLANET,  8.58,  20.5,  0.7,
                    "Планета с кольцами. Хорошо видна в небольшой телескоп."),
            new CelestialObject("Венера",   CelestialObject.Type.PLANET,  4.83,  22.0, -4.6,
                    "Самая яркая планета, видна на закате/рассвете."),

            // ── Яркие звёзды ──────────────────────────────────────────────────
            new CelestialObject("Сириус",     CelestialObject.Type.STAR,  6.7525, -16.7161, -1.46,
                    "Ярчайшая звезда ночного неба. α Большого Пса."),
            new CelestialObject("Канопус",    CelestialObject.Type.STAR,  6.3992, -52.6957, -0.74,
                    "Вторая по яркости звезда. α Киля."),
            new CelestialObject("Арктур",     CelestialObject.Type.STAR, 14.2612,  19.1824, -0.05,
                    "Ярчайшая звезда северного полушария. α Волопаса."),
            new CelestialObject("Вега",       CelestialObject.Type.STAR, 18.6156,  38.7836,  0.03,
                    "Яркая звезда Летнего треугольника. α Лиры."),
            new CelestialObject("Капелла",    CelestialObject.Type.STAR,  5.2781,  45.9980,  0.08,
                    "Яркая жёлтая звезда. α Возничего."),
            new CelestialObject("Ригель",     CelestialObject.Type.STAR,  5.2423,  -8.2016,  0.13,
                    "Голубой сверхгигант. β Ориона."),
            new CelestialObject("Процион",    CelestialObject.Type.STAR,  7.6553,   5.2250,  0.34,
                    "Яркая звезда Зимнего треугольника. α Малого Пса."),
            new CelestialObject("Бетельгейзе", CelestialObject.Type.STAR, 5.9195,   7.4071,  0.50,
                    "Красный сверхгигант. α Ориона."),
            new CelestialObject("Альтаир",    CelestialObject.Type.STAR, 19.8464,   8.8683,  0.77,
                    "Быстро вращающаяся звезда. α Орла."),
            new CelestialObject("Альдебаран", CelestialObject.Type.STAR,  4.5988,  16.5093,  0.85,
                    "Красный гигант, «глаз Тельца». α Тельца."),
            new CelestialObject("Антарес",    CelestialObject.Type.STAR, 16.4901, -26.4320,  1.09,
                    "Красный сверхгигант. α Скорпиона."),
            new CelestialObject("Денеб",      CelestialObject.Type.STAR, 20.6905,  45.2803,  1.25,
                    "Сверхгигант, вершина Летнего треугольника. α Лебедя."),
            new CelestialObject("Полярная",   CelestialObject.Type.STAR,  2.5302,  89.2641,  1.97,
                    "Полярная звезда, α Малой Медведицы. Указывает на север."),
            new CelestialObject("Кастор",     CelestialObject.Type.STAR,  7.5766,  31.8883,  1.58,
                    "Визуально двойная звезда. α Близнецов."),
            new CelestialObject("Поллукс",    CelestialObject.Type.STAR,  7.7553,  28.0262,  1.14,
                    "Яркий оранжевый гигант. β Близнецов."),

            // ── Туманности ────────────────────────────────────────────────────
            new CelestialObject("Туманность Ориона",  CelestialObject.Type.NEBULA,  5.5883,  -5.3911, 4.0,
                    "M42. Эмиссионная туманность. Хорошо видна в бинокль."),
            new CelestialObject("Туманность Андромеды", CelestialObject.Type.GALAXY, 0.7122,  41.2689, 3.44,
                    "M31. Ближайшая к нам крупная галактика."),
            new CelestialObject("Крабовидная туманность", CelestialObject.Type.NEBULA, 5.5754, 22.0145, 8.4,
                    "M1. Остаток сверхновой 1054 года."),
            new CelestialObject("Туманность Кольцо",  CelestialObject.Type.NEBULA, 18.8928,  33.0297, 8.8,
                    "M57. Планетарная туманность в созвездии Лиры."),
            new CelestialObject("Туманность Гантель", CelestialObject.Type.NEBULA, 19.9938,  22.7214, 7.5,
                    "M27. Крупная планетарная туманность в Лисичке."),

            // ── Галактики ─────────────────────────────────────────────────────
            new CelestialObject("Галактика Водоворот", CelestialObject.Type.GALAXY, 13.4981,  47.1952, 8.4,
                    "M51. Спиральная галактика с видимой структурой рукавов."),
            new CelestialObject("Галактика Сомбреро",  CelestialObject.Type.GALAXY, 12.6664,  -11.6230, 8.0,
                    "M104. Примечательна тёмной полосой пыли."),
            new CelestialObject("Галактика Боде",       CelestialObject.Type.GALAXY, 9.9266,   69.0680, 6.9,
                    "M81. Яркая спиральная галактика в Большой Медведице."),
            new CelestialObject("NGC 224",              CelestialObject.Type.GALAXY, 0.7122,   41.2689, 3.44,
                    "Другое обозначение Туманности Андромеды M31."),

            // ── Звёздные скопления ────────────────────────────────────────────
            new CelestialObject("Плеяды",      CelestialObject.Type.CLUSTER, 3.7881,  24.1167, 1.6,
                    "M45. Молодое рассеянное скопление в Тельце. Видно невооружённым глазом."),
            new CelestialObject("Гиады",       CelestialObject.Type.CLUSTER, 4.2833,  15.8667, 0.5,
                    "Ближайшее к Земле рассеянное скопление."),
            new CelestialObject("Шаровое M13", CelestialObject.Type.CLUSTER, 16.6948,  36.4613, 5.8,
                    "M13. Великое Геркулесово шаровое скопление."),
            new CelestialObject("Шаровое M3",  CelestialObject.Type.CLUSTER, 13.7035,  28.3771, 6.2,
                    "M3. Одно из лучших шаровых скоплений северного полушария."),
            new CelestialObject("Двойное скопление", CelestialObject.Type.CLUSTER, 2.3550,  57.1333, 4.3,
                    "NGC 869 + NGC 884 в Персее. Эффектная пара скоплений.")
        );

        allObjects.addAll(objects);
    }

    // ── Вспомогательные методы ─────────────────────────────────────────────────

    private void applyFilter() {
        filteredObjects.setPredicate(obj -> {
            boolean matchesName = searchQuery.isEmpty()
                    || obj.getName().toLowerCase().contains(searchQuery);
            boolean matchesType = typeFilter == null
                    || obj.getType() == typeFilter;
            return matchesName && matchesType;
        });
    }
}
