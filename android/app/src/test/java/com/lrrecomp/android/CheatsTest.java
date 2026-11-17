package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The CTRL dialog's cheat switches (Cheats.java): bits, the value sent to the game, test extras. */
public class CheatsTest {
    @Test
    public void bitsAreDistinctSingleBits() {
        int seen = 0;
        for (Cheats.Cheat cheat : Cheats.ALL) {
            assertEquals("one bit per cheat", 1, Integer.bitCount(cheat.bit));
            assertEquals("bits must not repeat", 0, seen & cheat.bit);
            assertNotEquals(0, cheat.label);
            seen |= cheat.bit;
        }
        assertEquals(seen, Cheats.MASK);
    }

    @Test
    public void knownBitsMatchTheGameSide() {
        // src/lr_cheats.h: kLrCheatInfiniteEp = 1, kLrCheatFreezeClock = 2
        // (tests/host/check_cheat_bits.py checks the two files against each other).
        assertEquals(1, Cheats.ALL[0].bit);
        assertEquals(2, Cheats.ALL[1].bit);
    }

    @Test
    public void withSwitchesOneBit() {
        int bits = 0;
        bits = Cheats.with(bits, 2, true);
        assertEquals(2, bits);
        bits = Cheats.with(bits, 1, true);
        assertEquals(3, bits);
        bits = Cheats.with(bits, 2, false);
        assertEquals(1, bits);
        assertEquals(0, Cheats.with(0, 1 << 20, true));  // unknown cheat: nothing
    }

    @Test
    public void optionValueIsTheMaskInDecimal() {
        assertEquals("0", Cheats.optionValue(0));
        assertEquals("3", Cheats.optionValue(3));
        assertEquals(Integer.toString(Cheats.MASK), Cheats.optionValue(-1));
    }

    @Test
    public void parseExtra() {
        assertEquals(-1, Cheats.parseExtra(null));
        assertEquals(-1, Cheats.parseExtra("on"));
        assertEquals(-1, Cheats.parseExtra("-2"));
        assertEquals(0, Cheats.parseExtra("0"));
        assertEquals(3, Cheats.parseExtra(" 3 "));
        assertTrue(Cheats.parseExtra("255") <= Cheats.MASK);
    }
}
