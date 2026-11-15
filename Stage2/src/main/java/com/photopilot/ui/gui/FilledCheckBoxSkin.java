package com.photopilot.ui.gui;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.skin.CheckBoxSkin;
import javafx.scene.layout.StackPane;

/**
 * Skin of every CheckBox in PhotoPilot, assigned centrally by photopilot.css ({@code -fx-skin}). Selected and
 * indeterminate state, mouse, Space, focus traversal, mnemonics, label layout and accessibility are the standard
 * {@link CheckBoxSkin}'s. This replaces the presentation of the indicator only: the standard outlined box becomes a
 * rounded square ({@code .check-indicator}) holding the mark ({@code .check-mark}), both drawn entirely by theme
 * tokens in photopilot.css, so the accent, Light/Dark mode and Transparency follow every theme change in place.
 */
public class FilledCheckBoxSkin extends CheckBoxSkin {
    public FilledCheckBoxSkin(CheckBox checkBox) {
        super(checkBox);
        // The standard skin sizes and places its box through fields, never by style class; renamed, the box keeps that
        // role while modena's bevelled outline and mark rules no longer apply to it.
        for (Node child : getChildren())
            if (child instanceof StackPane box && box.getStyleClass().contains("box")) {
                box.getStyleClass().setAll("check-indicator");
                for (Node mark : box.getChildren()) mark.getStyleClass().setAll("check-mark");
            }
    }
}
