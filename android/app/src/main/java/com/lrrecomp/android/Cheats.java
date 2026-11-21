package com.lrrecomp.android;

import android.content.SharedPreferences;

/**
 * Cheats of the CTRL dialog (public issue #1, 2026-11-17). Each cheat is one bit of a mask
 * that the game side reads (src/lr_hooks.cpp, g_lr_cheats): at start through the LR_CHEATS
 * environment variable, while playing through nativeSetOption("cheats", mask). The game side
 * implements each bit; adding a cheat is one entry in {@link #ALL} plus its bit there.
 * Off by default; the switches are saved in renderPrefs ("cheats").
 */
final class Cheats {
    private Cheats() {}

    static final class Cheat {
        final int bit;
        final int label;

        Cheat(int bit, int label) {
            this.bit = bit;
            this.label = label;
        }
    }

    /** In the dialog's order. Bits must match lr_hooks.cpp (kLrCheat*). */
    static final Cheat[] ALL = {
        new Cheat(1, R.string.cheat_infinite_ep),        // EP stays at its maximum
        new Cheat(4, R.string.cheat_infinite_hp),        // Lightning's HP stays at its maximum in battle
        new Cheat(2, R.string.cheat_freeze_clock),       // the game clock stands still
    };

    /** Every bit the game side knows. */
    static final int MASK = mask();

    static final String PREF = "cheats";

    private static int mask() {
        int m = 0;
        for (Cheat cheat : ALL) m |= cheat.bit;
        return m;
    }

    /** The saved switches, limited to known cheats. */
    static int saved(SharedPreferences prefs) {
        return prefs.getInt(PREF, 0) & MASK;
    }

    static void save(SharedPreferences prefs, int bits) {
        prefs.edit().putInt(PREF, bits & MASK).apply();
    }

    /** {@code bits} with {@code bit} switched on or off. */
    static int with(int bits, int bit, boolean on) {
        return (on ? bits | bit : bits & ~bit) & MASK;
    }

    /** The value LR_CHEATS / nativeSetOption("cheats") take: the mask in decimal. */
    static String optionValue(int bits) {
        return Integer.toString(bits & MASK);
    }

    /**
     * Test and bench launches (debug builds): {@code --es LR_CHEATS N} overrides the saved
     * switches for this start without saving them. Null or junk: no override (-1).
     */
    static int parseExtra(String extra) {
        if (extra == null) return -1;
        try {
            int bits = Integer.parseInt(extra.trim());
            return bits < 0 ? -1 : bits & MASK;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
