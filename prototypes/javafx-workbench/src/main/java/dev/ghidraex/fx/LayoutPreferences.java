package dev.ghidraex.fx;

import java.util.prefs.Preferences;

final class LayoutPreferences {
    private static final String LEFT_DIVIDER = "left-divider";
    private static final String RIGHT_DIVIDER = "right-divider";
    private static final String ACTIVE_TAB = "active-tab";
    private final Preferences preferences = Preferences.userRoot().node("dev/ghidraex/fx-workbench");

    double leftDivider() {
        return clamp(preferences.getDouble(LEFT_DIVIDER, 0.205), 0.12, 0.35);
    }

    double rightDivider() {
        return clamp(preferences.getDouble(RIGHT_DIVIDER, 0.79), 0.62, 0.90);
    }

    int activeTab() {
        return Math.max(0, Math.min(1, preferences.getInt(ACTIVE_TAB, 0)));
    }

    void saveDividers(double left, double right) {
        preferences.putDouble(LEFT_DIVIDER, left);
        preferences.putDouble(RIGHT_DIVIDER, right);
    }

    void saveActiveTab(int index) {
        preferences.putInt(ACTIVE_TAB, index);
    }

    void reset() {
        preferences.remove(LEFT_DIVIDER);
        preferences.remove(RIGHT_DIVIDER);
        preferences.remove(ACTIVE_TAB);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
