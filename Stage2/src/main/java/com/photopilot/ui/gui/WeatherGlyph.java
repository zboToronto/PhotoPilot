package com.photopilot.ui.gui;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Small vector weather icon for a provider WMO condition category (EnvironmentPresentation.category): sun or moon
 * (decided by the solar model's elevation at that hour, not by the provider), cloud, and rain, snow, fog or lightning
 * marks. Drawn on a 24 x 24 grid, scaled to {@code size}; colours come from page-scoped CSS so both themes apply. The
 * icon is decoration: the condition is always also given in words next to it.
 */
final class WeatherGlyph extends StackPane {
    private static final String SUN = "M12 7.5a4.5 4.5 0 1 1 0 9a4.5 4.5 0 1 1 0-9z";
    private static final String RAYS = "M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M4.6 19.4l2.1-2.1M17.3 6.7l2.1-2.1";
    private static final String MOON = "M15.5 3.5a8.5 8.5 0 1 0 5 13.6a7 7 0 0 1-5-13.6z";
    private static final String CLOUD = "M7 19.5h10.5a4.25 4.25 0 0 0 .4-8.48A6 6 0 0 0 6.4 12.1A3.7 3.7 0 0 0 7 19.5z";
    private static final String SMALL_SUN = "M8 3.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7z";
    private static final String SMALL_RAYS = "M8 0.5v1.6M8 11.9v1.6M1 7h1.6M13.4 7h1.6M3.1 2.1l1.1 1.1M11.8 2.1l-1.1 1.1";
    private static final String SMALL_MOON = "M9.5 2a5.5 5.5 0 1 0 4 8.8a4.6 4.6 0 0 1-4-8.8z";
    private static final String RAIN = "M8 21l-1 2.5M12 21l-1 2.5M16 21l-1 2.5";
    private static final String SNOW = "M8 21.6h.01M12 22.6h.01M16 21.6h.01";
    private static final String FOG = "M4 21.5h16M6 23.5h12";
    private static final String BOLT = "M13 17.5l-3 4h3l-2 3";

    WeatherGlyph(String category, boolean night, double size) {
        getStyleClass().add("env-glyph");
        var group = new Group();
        switch (category) {
            case "clear" -> { if (night) group.getChildren().add(path(MOON, "env-glyph-moon")); else group.getChildren().addAll(path(RAYS, "env-glyph-rays"), path(SUN, "env-glyph-sun")); }
            case "mainlyClear", "partlyCloudy" -> {
                if (night) group.getChildren().add(path(SMALL_MOON, "env-glyph-moon"));
                else group.getChildren().addAll(path(SMALL_RAYS, "env-glyph-rays"), path(SMALL_SUN, "env-glyph-sun"));
                group.getChildren().add(path(CLOUD, "env-glyph-cloud"));
            }
            case "fog" -> group.getChildren().addAll(path(CLOUD, "env-glyph-cloud"), path(FOG, "env-glyph-fog"));
            case "drizzle", "rain", "showers" -> group.getChildren().addAll(path(CLOUD, "env-glyph-cloud"), path(RAIN, "env-glyph-rain"));
            case "snow", "snowShowers" -> group.getChildren().addAll(path(CLOUD, "env-glyph-cloud"), path(SNOW, "env-glyph-snow"));
            case "thunder" -> group.getChildren().addAll(path(CLOUD, "env-glyph-cloud"), path(BOLT, "env-glyph-bolt"));
            default -> group.getChildren().add(path(CLOUD, "env-glyph-cloud"));
        }
        double scale = size / 24;
        group.setScaleX(scale); group.setScaleY(scale);
        var holder = new Group(group);
        getChildren().add(holder);
        setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size);
        setMouseTransparent(true);
    }
    private static SVGPath path(String content, String style) {
        var path = new SVGPath(); path.setContent(content); path.getStyleClass().add(style); return path;
    }
}
