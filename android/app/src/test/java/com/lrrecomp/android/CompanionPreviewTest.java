package com.lrrecomp.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Renders the bottom screen's scenes with demo data to PNGs on the PC (real Skia through
 * Robolectric's native graphics), for design work without the device:
 *   ./gradlew :app:testDebugUnitTest --tests '*CompanionPreviewTest*'  -> app/build/companion_preview/
 * Uses the APK assets (maps, HUD pieces) when they have been staged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CompanionPreviewTest {
    private static final int W = 1240, H = 1080;

    static void render(Context ctx, String name, String state, float[] pose) throws Exception {
        CompanionView v = new CompanionView(ctx);
        v.layout(0, 0, W, H);
        v.setState(CompanionState.parse(state));
        v.setState(CompanionState.parse(state));  // text is accepted once two reads agree
        if (pose != null) {
            v.setPose(pose[0], pose[1], pose[2], pose[3]);
            v.animate(1000);
            for (int i = 0; i < 60; i++) v.animate(1000 + 16 * (i + 1));
        }
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        File dir = new File("build/companion_preview");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test
    public void scenes() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        String markers = ";mk=quest:-260.8:461.2,shop:-278.0:444.0,warp:-240.0:412.0,door:-283.0:434.0,inn:-256.0:443.0"
            + ",npc:-267.8:461.2,npc:-250.0:470.0,mainquest:-150.0:560.0;quest=Talk to potential witnesses.;region=Luxerion;place=North Station Plaza";
        String field = "v=1;mode=1;navi=00010002:-315.030:267.431;worldx=-259.5;worldz=448.3;heading=0.6;camheading=-0.07"
            + ";clock=21:07;day=1" + markers;
        render(ctx, "battle", CompanionState.demoBattle(2400), null);
        RuntimeActivity.renderPrefs(ctx).edit().putString(CompanionView.PREF_MAP_MODE, "area").commit();
        render(ctx, "map_area", field, null);
        RuntimeActivity.renderPrefs(ctx).edit().putString(CompanionView.PREF_MAP_MODE, "follow").commit();
        render(ctx, "map_follow", field, new float[] { -259.5f, 448.3f, 0.6f, -0.07f });
        render(ctx, "map_follow_turned", field, new float[] { -259.5f, 448.3f, 2.2f, 1.4f });
        render(ctx, "world", "v=1;mode=1;clock=08:30;day=3", null);
        render(ctx, "fanart", "v=1;mode=2", null);
    }
}
