package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The CTRL dialog's Resolution and Wide screen choices as game arguments (RuntimeActivity.addResolutionArguments,
 * wideWidth, wideFrameWidth).
 */
public class ResolutionArgsTest {
    private static final float PHONE_19_9 = 3040f / 1440f, PHONE_20_9 = 2400f / 1080f, ULTRAWIDE = 3440f / 1440f;

    private static List<String> argsFor(String height) {
        return argsFor(height, 0);
    }

    private static List<String> argsFor(String height, int wideWidth) {
        List<String> args = new ArrayList<>();
        RuntimeActivity.addResolutionArguments(args, height, wideWidth);
        return args;
    }

    @Test
    public void consoleSizeAddsNothing() {
        assertTrue(argsFor("720").isEmpty());
        assertTrue(argsFor(RuntimeActivity.DEFAULT_RENDER_HEIGHT).isEmpty());
        assertTrue(argsFor("unknown").isEmpty());
    }

    @Test
    public void smallerScreen() {
        assertEquals(Arrays.asList("--lr_render_height=540"), argsFor("540"));
    }

    @Test
    public void fullHdDoublesTheEdram() {
        assertEquals(Arrays.asList("--lr_render_height=1080", "--gpu_edram_tiles=4096"), argsFor("1080"));
    }

    @Test
    public void everyChoiceIsKnown() {
        assertEquals(Arrays.asList("540", "720", "1080"), Arrays.asList(RuntimeActivity.RENDER_HEIGHTS));
        assertTrue(Arrays.asList(RuntimeActivity.RENDER_HEIGHTS).contains(RuntimeActivity.DEFAULT_RENDER_HEIGHT));
        for (String height : RuntimeActivity.RENDER_HEIGHTS) {
            assertEquals(Integer.parseInt(height), RuntimeActivity.renderHeight(height));
        }
        assertEquals(720, RuntimeActivity.renderHeight("unknown"));
    }

    @Test
    public void wideIsOffOnSixteenByNineAndWhenUnticked() {
        assertEquals(0, RuntimeActivity.wideWidth(true, 720, 16f / 9f));
        assertEquals(0, RuntimeActivity.wideWidth(true, 720, 4f / 3f));
        assertEquals(0, RuntimeActivity.wideWidth(false, 720, PHONE_19_9));
    }

    @Test
    public void wideWidthFollowsTheDisplay() {
        assertEquals(1520, RuntimeActivity.wideWidth(true, 720, PHONE_19_9));
        assertEquals(1600, RuntimeActivity.wideWidth(true, 720, PHONE_20_9));
        assertEquals(1136, RuntimeActivity.wideWidth(true, 540, PHONE_19_9));
        assertEquals(1728, RuntimeActivity.wideWidth(true, 720, ULTRAWIDE));
        // Multiples of 16, wider than 16:9.
        for (int height : new int[] { 540, 720, 1080 }) {
            for (float aspect : new float[] { 1.8f, 2f, PHONE_19_9, PHONE_20_9, ULTRAWIDE, 3f, 4f }) {
                int width = RuntimeActivity.wideWidth(true, height, aspect);
                assertEquals(0, width % 16);
                assertTrue(width == 0 || width > height * 16 / 9);
            }
        }
    }

    @Test
    public void wideWidthFitsTheDoubledEdram() {
        // Colour and depth side by side: 2 x ceil(width / 80) x ceil(height / 16) tiles of 4096 (lr_render_size.cpp).
        for (int height : new int[] { 540, 720, 1080 }) {
            for (float aspect : new float[] { PHONE_19_9, PHONE_20_9, ULTRAWIDE, 3f, 4f }) {
                int width = RuntimeActivity.wideWidth(true, height, aspect);
                assertTrue(width <= 2560);
                assertTrue(2 * ((width + 79) / 80) * ((height + 15) / 16) <= 4096);
            }
        }
        assertEquals(2400, RuntimeActivity.wideWidth(true, 1080, ULTRAWIDE));
    }

    @Test
    public void wideFrameKeepsSevenTwentyLines() {
        assertEquals(1520, RuntimeActivity.wideFrameWidth(1520, 720));
        assertEquals(1520, RuntimeActivity.wideFrameWidth(1136, 540));
        assertEquals(1600, RuntimeActivity.wideFrameWidth(2400, 1080));
        assertEquals(1728, RuntimeActivity.wideFrameWidth(1728, 720));
    }

    @Test
    public void wideArguments() {
        assertEquals(Arrays.asList("--lr_render_height=720", "--lr_render_width=1520", "--video_mode_width=1520",
            "--video_mode_height=720", "--gpu_edram_tiles=4096"), argsFor("720", 1520));
        assertEquals(Arrays.asList("--lr_render_height=540", "--lr_render_width=1136", "--video_mode_width=1520",
            "--video_mode_height=720", "--gpu_edram_tiles=4096"), argsFor("540", 1136));
        assertEquals(Arrays.asList("--lr_render_height=1080", "--lr_render_width=2400", "--video_mode_width=1600",
            "--video_mode_height=720", "--gpu_edram_tiles=4096"), argsFor("1080", 2400));
    }

    @Test
    public void stretchedMoviesKeepTheirShape() {
        assertEquals(null, RuntimeActivity.stretchedMovieAspect(false, 20f / 9f));
        assertEquals(null, RuntimeActivity.stretchedMovieAspect(true, 16f / 9f));
        assertEquals(null, RuntimeActivity.stretchedMovieAspect(true, 16f / 10f));
        assertEquals("2.2222", RuntimeActivity.stretchedMovieAspect(true, 20f / 9f));
    }
}
