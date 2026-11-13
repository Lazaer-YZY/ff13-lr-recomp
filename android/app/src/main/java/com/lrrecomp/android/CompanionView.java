package com.lrrecomp.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import java.io.File;
import java.util.Locale;

/**
 * Draws the bottom screen in the look of the game's battle HUD: bronze frames, dark crimson
 * plates, pink-red schema names, the red ATB gauge and green HP gauge, the controller's face
 * button colours. Three scenes:
 *  - battle: the three schemata side by side, the active one lit; touching another one presses
 *    LB or RB once (the game's schema change), so a change is one press either way;
 *  - field: the area map with Lightning's position (when the native side reports one);
 *  - everything else (title, cutscenes, menus, loading): the game's fan art as ES-DE shows it.
 */
final class CompanionView extends View {
    /** XInput bits. */
    private static final int LB = 0x0100, RB = 0x0200;
    private static final int PRESS_MS = 180;
    /** Base layout size (the Thor's bottom screen); everything scales from it. */
    private static final float BASE_W = 1240f, BASE_H = 1080f;

    // Colours sampled from the battle HUD (out/esde/media/battle.png).
    private static final int BRONZE_HI = 0xFFE9D3A0, BRONZE = 0xFFB08D57, BRONZE_LO = 0xFF5C4527;
    private static final int PLATE_HI = 0xFF5A1620, PLATE_LO = 0xFF26070C;
    private static final int NAME_PINK = 0xFFFF7088, TEXT = 0xFFF4E6E2, TEXT_DIM = 0xFFB9A49C;
    private static final int ATB_RED = 0xFFD8323C, ATB_RED_LO = 0xFF7A1018, ATB_LABEL = 0xFF5FD0B8;
    private static final int HP_GREEN = 0xFF4DBF3A, HP_GREEN_LO = 0xFF1E5A16;
    private static final int[] BUTTON_COLORS = { 0xFFE6C33C, 0xFF3A78D8, 0xFFD8443A, 0xFF52B044 };
    private static final String[] BUTTON_LETTERS = { "Y", "X", "B", "A" };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The game's text face (closest match Verdana): assets/fonts/hud_text.ttf when the build bundled one. */
    private Typeface hudFont;

    private Typeface hudBold;

    /** The battle HUD's heavier command and schema lettering (Verdana Bold match). */
    private Typeface hudBold() {
        if (hudBold == null) {
            try {
                hudBold = Typeface.createFromAsset(getContext().getAssets(), "fonts/hud_bold.ttf");
            } catch (RuntimeException e) {
                hudBold = Typeface.create(hudFont(), Typeface.BOLD);
            }
        }
        return hudBold;
    }

    private Typeface hudFont() {
        if (hudFont == null) {
            try {
                hudFont = Typeface.createFromAsset(getContext().getAssets(), "fonts/hud_text.ttf");
            } catch (RuntimeException e) {
                hudFont = Typeface.create("sans-serif", Typeface.NORMAL);
            }
        }
        return hudFont;
    }
    private final RectF[] cards = { new RectF(), new RectF(), new RectF() };
    private final RectF tmp = new RectF();
    private final Rect src = new Rect();
    private final RectF mapFrame = new RectF();
    private CompanionMaps maps;
    private CompanionHud hud;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float ox, oy;  // the 1240x1080 layout's origin on this screen (centred)

    private CompanionState state = new CompanionState();
    private Bitmap fanart;
    private boolean fanartTried;
    /** Schema the player touched, shown lit until the game reports it (or 800 ms pass). */
    private int pending = -1;
    private long pendingUntil;
    private float s = 1f;

    CompanionView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setKeepScreenOn(true);
    }

    /**
     * Takes the newest state (drawing thread). True when the picture changes: CompanionView is
     * not attached to a window; CompanionSurface draws it into its surface only then.
     */
    private String shownQuest = "", questCandidate = "";

    boolean setState(CompanionState next) {
        boolean changed = false;
        if (pending >= 0 && (next.active == pending || SystemClock.uptimeMillis() > pendingUntil)) {
            pending = -1;
            changed = true;
        }
        // The objective is read from the game's text while the game may be rewriting it (the owner saw "Mi"
        // for a minute when the clock passed 6 AM): a new text counts once two reads agree, and a
        // shorter beginning of the shown text never replaces it.
        if (!next.quest.equals(shownQuest)) {
            boolean truncated = !next.quest.isEmpty() && !shownQuest.isEmpty() && shownQuest.startsWith(next.quest);
            if (next.quest.equals(questCandidate) && !truncated) shownQuest = next.quest;
            questCandidate = next.quest;
        } else {
            questCandidate = next.quest;
        }
        next.quest = shownQuest;
        if (!next.signature().equals(state.signature()) || !next.quest.equals(state.quest)) changed = true;
        state = next;
        return changed;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        s = Math.min(w / BASE_W, h / BASE_H);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        switch (state.scene()) {
            case BATTLE: drawBattle(canvas); break;
            case MAP: drawMap(canvas); break;
            default: drawFanart(canvas); break;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Battle
    // ---------------------------------------------------------------------------------------

    private void drawBattle(Canvas c) {
        hud = CompanionHud.fresh(hud, getContext());
        if (hud.ok) {
            drawBattleHud(c);
            return;
        }
        int w = getWidth(), h = getHeight();
        drawBackdrop(c, w, h);
        float margin = 28 * s, gap = 22 * s;
        float top = 150 * s;
        drawHp(c, margin, 30 * s, w - margin, 120 * s);
        float cw = (w - 2 * margin - 2 * gap) / 3f;
        int active = pending >= 0 ? pending : state.active;
        for (int i = 0; i < 3; i++) {
            float x = margin + i * (cw + gap);
            cards[i].set(x, top, x + cw, h - 30 * s);
            drawSchema(c, cards[i], state.schemata[i], i == active, i);
        }
    }

    // ---- Battle, drawn with the game's own HUD pieces (CompanionHud), as the in-game HUD:
    // one row per schema: name plate, garb, the long ATB gauge; the ability cross (Y top,
    // X left, B right, A bottom) around the command dial. Enemy and Lightning on top.

    // The game's HUD metal is grey steel (its frames, gauge housings, dial), not bronze.
    /** The HUD colours each schema slot (command bars, name, ATB gauge): red, olive yellow, blue (sampled on the Thor). */
    private static final int[][] SLOT_PLATE = {
        { 0xFF8C4E4B, 0xFF733E3C, 0xFF4E2826 }, { 0xFF6E6C26, 0xFF5B5A1B, 0xFF3C3C10 }, { 0xFF22407A, 0xFF122A5C, 0xFF081A44 } };
    private static final int[] SLOT_NAME = { 0xFFEC6462, 0xFFE6D452, 0xFF5C9CFF };
    private static final int[] SLOT_NAME_DIM = { 0xFF7E6462, 0xFF7A7458, 0xFF5E6A84 };
    private static final int[] SLOT_ATB = { 0xFFD8323C, 0xFFC8B83A, 0xFF3A78E0 };
    private int plateSlot;
    private static final int TINT_BRONZE = 0xC8C5BE, TINT_BRONZE_DIM = 0x6E6B66;
    private static final float ROW_Y = 205f, ROW_H = 266f, ROW_GAP = 8f;

    private float X(float v) { return ox + v * s; }
    private float Y(float v) { return oy + v * s; }

    /** Text with the HUD's dark outline. align: -1 left, 0 centre, 1 right; y = vertical centre. */
    private void label(Canvas c, String v, float x, float y, float size, Typeface face, int colour, int align, float outline) {
        label(c, v, x, y, size, face, colour, align, outline, 0xFF0A0404);
    }

    private void label(Canvas c, String v, float x, float y, float size, Typeface face, int colour, int align, float outline,
                       int outlineColour) {
        Paint.Align a = align < 0 ? Paint.Align.LEFT : align > 0 ? Paint.Align.RIGHT : Paint.Align.CENTER;
        text.setTypeface(face);
        text.setTextSize(size);
        text.setTextAlign(a);
        text.setColor(colour);
        text.setMaskFilter(null);
        stroke.set(text);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(outline);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(outlineColour);
        Paint.FontMetrics fm = text.getFontMetrics();
        float base = y - (fm.ascent + fm.descent) / 2f;
        if (outline > 0) c.drawText(v, x, base, stroke);
        c.drawText(v, x, base, text);
    }

    /** A number or label in the game's own font (CompanionHud.text); the system font as fallback. */
    private void gameText(Canvas c, boolean digits, String v, float x, float y, float h, int colour, int align) {
        if (hud != null && hud.text(c, digits, v, x, y, h, colour & 0xFFFFFF, align)) return;
        label(c, v, x, y, h * 1.25f, hudFont(), colour, align, 2 * s);
    }

    private void hudGauge(Canvas c, float x, float y, float w, float h, float fill, int colour, int segments, int frame) {
        float top = y + h * 0.28f, bot = y + h * 0.72f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0E0607);
        c.drawRect(x + 10 * s, top, x + w - 10 * s, bot, paint);
        if (fill > 0f) {
            float right = x + 10 * s + (w - 20 * s) * Math.min(1f, fill);
            paint.setShader(new LinearGradient(0, top, 0, bot, new int[] { lighten(colour), colour, darken(colour) },
                new float[] { 0f, 0.35f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(x + 10 * s, top, right, bot, paint);
            paint.setShader(null);
        }
        if (segments > 1) {
            paint.setColor(0xFF0E0607);
            float step = (w - 20 * s) / segments;
            for (int i = 1; i < segments; i++) {
                float sx = x + 10 * s + i * step;
                c.drawRect(sx - 1.5f * s, top, sx + 1.5f * s, bot, paint);
            }
        }
        hud.slice3(c, "gp_parts01", CompanionHud.GAUGE, 60, x, y, w, h, frame, 255);
    }

    private void hudPlate(Canvas c, float x, float y, float w, float h, boolean active, String name, String button) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        // Active: the battle HUD's command bars in the schema slot's colour; otherwise the menus' graphite.
        paint.setShader(new LinearGradient(0, y, 0, y + h, active ? SLOT_PLATE[plateSlot]
            : new int[] { 0xFF2A2A2E, 0xFF1A1A1D, 0xFF101012 }, new float[] { 0f, 0.48f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(x + 6 * s, y + 5 * s, x + w - 6 * s, y + h - 5 * s, paint);
        paint.setShader(null);
        hud.slice3(c, "ws_pnl01", CompanionHud.PLATE, 16, x, y, w, h, active ? TINT_BRONZE : TINT_BRONZE_DIM, 255);
        if (button != null) {
            float b = h - 14 * s;
            float bx = "B".equals(button) ? x + 6 * s : x + w - h + 2 * s;
            hud.drawPlain(c, "button_set01", CompanionHud.BUTTONS.get(button), bx, y + 7 * s, b, b, active ? 255 : 150);
        }
        if (name != null && !name.isEmpty()) {  // an empty slot stays a blank plate, as in the game
            String v = name;
            float room = w - (button != null ? h : 0) - 40 * s;
            float size = fitText(v, room, h * 0.46f, active ? hudBold() : hudFont());
            float cx = button == null ? x + w / 2f : "B".equals(button) ? x + h + (w - h) / 2f - 6 * s : x + (w - h) / 2f + 6 * s;
            label(c, v, cx, y + h / 2f, size, active ? hudBold() : hudFont(), active ? 0xFFF6ECE8 : 0xFF8E8E8C, 0, 3 * s);
        }
    }

    /** The game's menu backdrop: large soft black and grey checks (save recap, minimap). */
    private void drawChecker(Canvas c, int w, int h) {
        float cell = 120 * s;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF151517);
        for (int j = 0; j * cell < h; j++)
            for (int i = (j & 1); i * cell < w; i += 2) c.drawRect(i * cell, j * cell, (i + 1) * cell, (j + 1) * cell, paint);
    }

    private void drawBattleHud(Canvas c) {
        int w = getWidth(), h = getHeight();
        ox = (w - BASE_W * s) / 2f;
        oy = (h - BASE_H * s) / 2f;
        CompanionState st = state;
        // Background: the game's cloudy gauge texture, dark red, with a vignette (its system windows).
        c.drawColor(0xFF0A0A0C);
        drawChecker(c, w, h);
        Bitmap cloud = hud.bitmap("atb_uv");
        if (cloud != null) {
            paint.setShader(null);
            paint.setColorFilter(new LightingColorFilter(0x2E2E34, 0));
            paint.setAlpha(110);
            float tile = 640 * s;
            for (float ty = 0; ty < h; ty += tile)
                for (float tx = 0; tx < w; tx += tile) {
                    tmp.set(tx, ty, tx + tile, ty + tile);
                    c.drawBitmap(cloud, null, tmp, paint);
                }
            paint.setColorFilter(null);
            paint.setAlpha(255);
        }
        paint.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.72f, new int[] { 0x00000000, 0x00000000, 0xEE000000 },
            new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Enemy (left).
        if (!st.enemy.isEmpty()) {
            label(c, st.enemy, X(40), Y(54), fitText(st.enemy, 390 * s, 40 * s, hudFont()), hudFont(), 0xFFF6F0EC, -1, 4 * s);
            float ef = st.enemyHp >= 0 && st.enemyHpMax > 0 ? st.enemyHp / (float) st.enemyHpMax : 0f;
            hudGauge(c, X(30), Y(78), 560 * s, 46 * s, ef, 0xFF5FD0B8, 0, TINT_BRONZE);
            // The HUD's banner over a staggered enemy (it has no stagger meter or percent).
            if (st.staggered) gameText(c, false, "STAGGER", X(40), Y(140), 22 * s, 0xFFF2F2F4, -1);
            // Vulnerabilities as the Libra screen lists them (only what the player has revealed; '?' otherwise).
            // 3 x 2 grid above the enemy gauge, right of the name (fire ice thunder / wind phys magic).
            for (int i = 0; i < 6; i++) {
                String v = st.vuln[i];
                if (v == null) continue;
                String e = CompanionState.VULN_ORDER[i];
                boolean known = !v.equals("?"), marked = known && !v.equals("normal");
                int col = !marked ? 0xFF8A8A88 : v.equals("weak") ? 0xFFFF8A5C : 0xFF8CC8FF;
                int[] r = CompanionHud.ELEMENTS.get(e);
                float ex = 430 + (i % 3) * 170, top = 6 + (i / 3) * 34;
                if (r != null) hud.draw(c, "battle_parts01", r, X(ex), Y(top), 28 * s, 28 * s,
                    (marked ? elementColour(e) : 0x8A8A88) & 0xFFFFFF, 255);
                else gameText(c, false, e.equals("phys") ? "PHY" : "MAG", X(ex), Y(top + 15), 10 * s,
                    marked ? 0xFFE6E6E4 : 0xFF8A8A88, -1);
                gameText(c, false, vulnShort(v), X(ex + (r != null ? 36 : 62)), Y(top + 15), 11 * s, col, -1);
            }
        }
        // Lightning (right): HP and EP.
        gameText(c, false, "HP", X(1205), Y(48), 20 * s, 0xFF4DBF3A, 1);
        if (st.hp >= 0) gameText(c, false, String.valueOf(st.hp), X(1138), Y(48), 28 * s, 0xFFF6F0EC, 1);
        hudGauge(c, X(720), Y(78), 490 * s, 46 * s, st.hp >= 0 && st.hpMax > 0 ? st.hp / (float) st.hpMax : 0f, 0xFF4DBF3A, 0, TINT_BRONZE);
        if (st.ep >= 0) {
            gameText(c, false, "EP", X(735), Y(140), 17 * s, 0xFFA99CF0, -1);
            // Pips only with a known maximum; otherwise the number alone (as the HUD's EP orb).
            int pips = st.epMax > 0 ? Math.max(1, Math.round(st.epMax)) : 0;
            for (int i = 0; i < pips; i++) {
                float cx = X(806 + i * 34), cy = Y(140);
                paint.setStyle(Paint.Style.FILL);
                float part = Math.max(0f, Math.min(1f, st.ep - i));  // EP is fractional (kills add e.g. 0.05)
                paint.setColor(0xFF8F86C8);
                if (part >= 0.999f) c.drawCircle(cx, cy, 12 * s, paint);
                else if (part > 0.01f) {
                    tmp.set(cx - 12 * s, cy - 12 * s, cx + 12 * s, cy + 12 * s);
                    c.drawArc(tmp, -90, 360 * part, true, paint);
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * s);
                paint.setColor(0xFFA99CF0);
                c.drawCircle(cx, cy, 12 * s, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            String epText = st.epMax > 0 ? String.format(Locale.US, "%.2f/%d", st.ep, Math.round(st.epMax))
                : (st.ep == Math.floor(st.ep) ? String.valueOf((int) st.ep) : String.format(Locale.US, "%.1f", st.ep));
            gameText(c, false, epText, st.epMax > 0 ? X(1205) : X(790), Y(140), st.epMax > 0 ? 18 * s : 22 * s, 0xFFD2C8FA,
                st.epMax > 0 ? 1 : -1);
        }
        hud.slice3(c, "ws_pnl01", CompanionHud.TRUSS, 30, X(20), Y(150), (BASE_W - 40) * s, 40 * s, TINT_BRONZE_DIM, 128);

        // Schema rows.
        int active = pending >= 0 ? pending : st.active;
        for (int i = 0; i < 3; i++) {
            CompanionState.Schema sc = st.schemata[i];
            boolean on = i == active;
            float y = ROW_Y + i * (ROW_H + ROW_GAP);
            cards[i].set(X(14), Y(y - 4), X(BASE_W - 14), Y(y + ROW_H - 4));
            if (on) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x38303034);
                c.drawRoundRect(cards[i], 16 * s, 16 * s, paint);
                glow.setStyle(Paint.Style.STROKE);
                glow.setStrokeWidth(8 * s);
                glow.setColor(0xFFD6D6E0);
                glow.setMaskFilter(new BlurMaskFilter(12 * s, BlurMaskFilter.Blur.NORMAL));
                c.drawRoundRect(cards[i], 16 * s, 16 * s, glow);
                glow.setMaskFilter(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * s);
                paint.setColor(0xFFE2DFD8);  // light steel, as the HUD's metal
                c.drawRoundRect(cards[i], 16 * s, 16 * s, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            // Name plate.
            hudPlate(c, X(30), Y(y + 14), 600 * s, 78 * s, false, null, null);  // dark iron, as the HUD's schema plate
            String name = sc.name.isEmpty() ? "Schema " + (i + 1) : sc.name;
            // As the HUD's schema plate: rounded bold, crimson letters with a pale rim.
            float ns = fitText(name, 540 * s, 40 * s, hudBold());
            label(c, name, X(330), Y(y + 53), ns, hudBold(), on ? SLOT_NAME[i] : SLOT_NAME_DIM[i], 0, 4 * s);
            if (!sc.garb.isEmpty() && !sc.garb.equals(sc.name)) label(c, sc.garb, X(46), Y(y + 124), 24 * s, hudFont(), on ? 0xFFDCD8D0 : 0xFF86827C, -1, 2 * s);
            // ATB.
            gameText(c, false, "ATB", X(46), Y(y + 160), 22 * s, on ? 0xFF5FD0B8 : 0xFF468C7E, -1);
            boolean full = sc.atbMax > 0 && sc.atb >= sc.atbMax;
            int numCol = full ? 0xFFFFE9A8 : on ? 0xFFF4F4F2 : 0xFF9A9A98;
            if (full) gameText(c, false, "FULL", X(620), Y(y + 160), 24 * s, numCol, 1);
            else if (sc.atb >= 0) gameText(c, false, String.format(Locale.US, "%03d/%03d", sc.atb, sc.atbMax), X(620), Y(y + 160), 24 * s, numCol, 1);
            float fill = sc.atb >= 0 && sc.atbMax > 0 ? sc.atb / (float) sc.atbMax : 0f;
            hudGauge(c, X(30), Y(y + 184), 600 * s, 66 * s, fill, on ? SLOT_ATB[i] : darken(SLOT_ATB[i]),
                sc.atbMax > 0 ? Math.min(30, sc.atbMax / 10) : 10, on ? TINT_BRONZE : TINT_BRONZE_DIM);
            // Ability cross around the command dial.
            float cx = 930, cy = y + ROW_H / 2f - 4;
            plateSlot = i;
            hud.draw(c, "btl_cmd_en", CompanionHud.DIAL, X(cx - 48), Y(cy - 48), 96 * s, 96 * s, on ? 0xE0DDD6 : 0x77746E, 255);
            float pw = 222, ph = 60;
            hudPlate(c, X(cx - pw / 2), Y(y + 6), pw * s, ph * s, on, sc.abilities[0], "Y");
            hudPlate(c, X(cx - 52 - pw), Y(cy - ph / 2), pw * s, ph * s, on, sc.abilities[1], "X");
            hudPlate(c, X(cx + 52), Y(cy - ph / 2), pw * s, ph * s, on, sc.abilities[2], "B");
            hudPlate(c, X(cx - pw / 2), Y(y + ROW_H - ph - 14), pw * s, ph * s, on, sc.abilities[3], "A");
            if (!on) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x46000000);
                c.drawRect(0, Y(y - 4), w, Y(y + ROW_H - 4), paint);
            }
        }
        // Hint, as the HUD shows LB / RB.
        hud.drawPlain(c, "button_set01", CompanionHud.BUTTONS.get("LB"), X(30), Y(1032), 54 * s, 36 * s, 255);
        hud.drawPlain(c, "button_set01", CompanionHud.BUTTONS.get("RB"), X(92), Y(1032), 54 * s, 36 * s, 255);
        label(c, "or tap a schema to switch", X(160), Y(1050), 20 * s, hudFont(), 0xFFB8B8B6, -1, 2 * s);
    }

    /** The Libra screen's words, short enough to sit under an icon. */
    private static String vulnShort(String v) {
        switch (v) {
            case "weak": return "WEAK";
            case "normal": return "-";
            case "halved": return "HALF";
            case "resist": return "RES";
            case "immune": return "NULL";
            case "absorb": return "ABS";
            default: return "?";
        }
    }

    private static int elementColour(String e) {
        switch (e) {
            case "fire": return 0xFFFF7850;
            case "thunder": return 0xFFFFE65A;
            case "ice": return 0xFF82C8FF;
            case "wind": return 0xFF8CF0B4;
            default: return 0xFFE0E0E0;
        }
    }

    /** Fills the screen with a bundled picture, centre-cropped (the game's map-screen art). */
    private boolean drawCover(Canvas c, String piece, int w, int h, int dimAlpha) {
        Bitmap b = hud != null ? hud.bitmap(piece) : null;
        if (b == null) return false;
        float sc = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int sw = Math.round(w / sc), sh = Math.round(h / sc);
        int sx = (b.getWidth() - sw) / 2, sy = (b.getHeight() - sh) / 2;
        src.set(sx, sy, sx + sw, sy + sh);
        tmp.set(0, 0, w, h);
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setAlpha(255);
        c.drawBitmap(b, src, tmp, paint);
        if (dimAlpha > 0) {
            paint.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.7f,
                new int[] { (dimAlpha / 3) << 24, dimAlpha << 24 }, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
        return true;
    }

    /** The worn metal behind the game's own map screen (mapbg), else the plain backdrop. */
    private void drawMapBackground(Canvas c, int w, int h) {
        if (!drawCover(c, "mapbg", w, h, 0x70)) drawBackdrop(c, w, h);
    }

    /** Region (the game's name plate look) with the place beneath, as the game names locations. */
    /** Width of a game plate whose inner panel fits textW: the frame's end caps scale with height. */
    private float plateWidth(float textW, float ph) {
        int[] r = CompanionHud.PLATE;
        float cap = 16f * ph / (r[3] - r[1]);
        return textW + 2 * cap + 20 * s;
    }

    /** Region with the place beneath, both in the plate's serif (the owner: the lines must match). */
    private void drawPlacePlate(Canvas c, String region, String place) {
        float ph = 92 * s;
        float maxText = 470 * s;
        float big = fitText(region, maxText, 34 * s, hudFont());
        text.setTextSize(big);
        float rw = text.measureText(region);
        float small = fitText(place, maxText, 23 * s, hudFont());
        text.setTextSize(small);
        float pw = plateWidth(Math.max(rw, text.measureText(place)), ph);
        float x = 42 * s, y = 42 * s;
        hudPlate(c, x, y, pw, ph, true, null, null);
        label(c, region, x + pw / 2f, y + 34 * s, big, hudFont(), NAME_PINK, 0, 3 * s);
        label(c, place, x + pw / 2f, y + 67 * s, small, hudFont(), 0xFFE0C890, 0, 2 * s);
    }

    /** Where no area map is streamed (the Ark, between regions): the game's illustrated world map. */
    private void drawWorldMap(Canvas c) {
        int w = getWidth(), h = getHeight();
        hud = CompanionHud.fresh(hud, getContext());
        if (!drawCover(c, "wmap", w, h, 0x60)) {
            drawFanart(c);
            return;
        }
        if (!state.region.isEmpty() && hud.ok) {
            if (!state.place.isEmpty()) drawPlacePlate(c, state.region, state.place);
            else drawPlacePlate(c, state.region, "Nova Chrysalia");
            drawClockPlate(c);
            drawQuestLine(c);
            return;
        }
        String name = "Nova Chrysalia";
        text.setTypeface(hudFont());
        text.setTextSize(40 * s);
        float pw = text.measureText(name) + 72 * s, ph = 66 * s;
        if (hud.ok) {
            hudPlate(c, 42 * s, 42 * s, pw, ph, true, null, null);
            label(c, name, 42 * s + pw / 2f, 42 * s + ph / 2f, 40 * s, hudFont(), NAME_PINK, 0, 3 * s);
        }
        drawClockPlate(c);
        drawQuestLine(c);
    }

    private void drawBackdrop(Canvas c, int w, int h) {
        paint.setShader(new RadialGradient(w / 2f, h * 0.45f, Math.max(w, h) * 0.75f,
            new int[] { 0xFF2A1116, 0xFF120709, 0xFF050203 }, new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // Faint engraved lines, like the HUD's metalwork.
        paint.setColor(0x14E9D3A0);
        paint.setStrokeWidth(1.5f * s);
        for (float y = 18 * s; y < h; y += 54 * s) c.drawLine(0, y, w, y, paint);
    }

    private void drawHp(Canvas c, float l, float t, float r, float b) {
        CompanionState st = state;
        float labelW = 70 * s;
        text.setTypeface(hudFont());
        text.setTextSize(30 * s);
        text.setColor(HP_GREEN);
        text.setTextAlign(Paint.Align.LEFT);
        float midY = (t + b) / 2f;
        c.drawText("HP", l + 8 * s, midY + 11 * s, text);
        float numW = 230 * s;
        String num = st.hp >= 0 ? (st.hpMax > 0 ? st.hp + " / " + st.hpMax : String.valueOf(st.hp)) : "----";
        text.setColor(TEXT);
        text.setTextSize(40 * s);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(hudFont());
        c.drawText(num, r, midY + 14 * s, text);
        float fill = st.hp >= 0 && st.hpMax > 0 ? Math.min(1f, st.hp / (float) st.hpMax) : 0f;
        tmp.set(l + labelW, midY - 15 * s, r - numW - 20 * s, midY + 15 * s);
        drawGauge(c, tmp, fill, HP_GREEN, HP_GREEN_LO, 0);
    }

    /** A gauge in a bronze frame; segments > 0 draws the ATB's notched look. */
    private void drawGauge(Canvas c, RectF r, float fill, int hi, int lo, int segments) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF0B0506);
        c.drawRect(r, paint);
        if (fill > 0f) {
            float right = r.left + (r.width() * fill);
            paint.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[] { lighten(hi), hi, lo },
                new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(r.left, r.top, right, r.bottom, paint);
            paint.setShader(null);
        }
        if (segments > 0) {
            paint.setColor(0xFF0B0506);
            float step = r.width() / segments;
            for (int i = 1; i < segments; i++) {
                float x = r.left + i * step;
                c.drawRect(x - 1.2f * s, r.top, x + 1.2f * s, r.bottom, paint);
            }
        }
        bronzeStroke(c, r, 0, 3 * s);
    }

    private void drawSchema(Canvas c, RectF r, CompanionState.Schema sc, boolean active, int index) {
        float radius = 14 * s;
        // Glow behind the active card.
        if (active) {
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(10 * s);
            glow.setColor(0xCCFF5A78);
            glow.setMaskFilter(new BlurMaskFilter(22 * s, BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(r, radius, radius, glow);
            glow.setMaskFilter(null);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, r.top, 0, r.bottom,
            active ? new int[] { 0xFF3B1018, 0xFF1A0609 } : new int[] { 0xFF1E1314, 0xFF0D0809 }, null,
            Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);
        bronzeStroke(c, r, radius, (active ? 5 : 3) * s);

        float pad = 18 * s;
        float x0 = r.left + pad, x1 = r.right - pad;
        float y = r.top + pad;

        // Name plate (as the HUD's "The Savior" plate).
        tmp.set(x0, y, x1, y + 72 * s);
        plate(c, tmp, active);
        text.setTypeface(hudFont());
        text.setTextAlign(Paint.Align.CENTER);
        String name = sc.name.isEmpty() ? "Schema " + (index + 1) : sc.name;
        float size = fitText(name, tmp.width() - 30 * s, 36 * s, hudFont());
        text.setTextSize(size);
        float ty = tmp.centerY() + size * 0.36f;
        if (active) {
            text.setColor(0xFFFF2F55);
            text.setMaskFilter(new BlurMaskFilter(8 * s, BlurMaskFilter.Blur.NORMAL));
            c.drawText(name, tmp.centerX(), ty, text);
            text.setMaskFilter(null);
        }
        text.setColor(active ? NAME_PINK : 0xFFB0606C);
        c.drawText(name, tmp.centerX(), ty, text);
        y = tmp.bottom + 14 * s;

        // Garb.
        if (!sc.garb.isEmpty() && !sc.garb.equals(sc.name)) {
            text.setTypeface(hudFont());
            text.setTextSize(24 * s);
            text.setColor(active ? 0xFFE0C890 : 0xFF8E7D60);
            c.drawText(sc.garb, r.centerX(), y + 20 * s, text);
            y += 36 * s;
        }

        // ATB.
        text.setTypeface(hudFont());
        text.setTextSize(22 * s);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(ATB_LABEL);
        c.drawText("ATB", x0, y + 24 * s, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(active ? TEXT : TEXT_DIM);
        text.setTextSize(28 * s);
        String atb = sc.atb >= 0 ? String.format(Locale.US, "%03d/%03d", sc.atb, Math.max(sc.atbMax, 0)) : "---/---";
        c.drawText(atb, x1, y + 26 * s, text);
        y += 38 * s;
        tmp.set(x0, y, x1, y + 26 * s);
        float fill = sc.atb >= 0 && sc.atbMax > 0 ? Math.min(1f, sc.atb / (float) sc.atbMax) : 0f;
        drawGauge(c, tmp, fill, active ? ATB_RED : 0xFF9A3038, ATB_RED_LO, Math.max(1, Math.min(24, sc.atbMax / 5)));
        if (sc.atbMax > 0 && sc.atb >= sc.atbMax) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(18 * s);
            text.setColor(0xFFFFE9A8);
            c.drawText("FULL", tmp.centerX(), tmp.centerY() + 6 * s, text);
        }
        y = tmp.bottom + 26 * s;

        // Abilities: Y, X, B, A.
        float rowH = Math.min(96 * s, (r.bottom - pad - y - 3 * 14 * s) / 4f);
        for (int a = 0; a < 4; a++) {
            tmp.set(x0, y, x1, y + rowH);
            drawAbility(c, tmp, a, sc.abilities[a], sc.costs[a], active);
            y += rowH + 14 * s;
        }

        if (!active) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            c.drawRoundRect(r, radius, radius, paint);
        }
    }

    private void drawAbility(Canvas c, RectF r, int button, String name, int cost, boolean active) {
        plate(c, r, active);
        float d = r.height() * 0.62f;
        float cx = r.left + r.height() * 0.55f, cy = r.centerY();
        // Face-button glyph.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx - d * 0.15f, cy - d * 0.2f, d * 0.7f,
            new int[] { lighten(BUTTON_COLORS[button]), BUTTON_COLORS[button], darken(BUTTON_COLORS[button]) },
            new float[] { 0f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, d / 2f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * s);
        paint.setColor(0xFFF0E8D8);
        c.drawCircle(cx, cy, d / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
        text.setTypeface(hudFont());
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(d * 0.6f);
        text.setColor(0xFF1A1010);
        c.drawText(BUTTON_LETTERS[button], cx, cy + d * 0.21f, text);

        float nameLeft = cx + d / 2f + 14 * s;
        float right = r.right - 14 * s;
        if (cost >= 0) {
            text.setTypeface(hudFont());
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(22 * s);
            text.setColor(active ? 0xFFFFB0B8 : 0xFF9A6A70);
            c.drawText(String.valueOf(cost), right, cy + 8 * s, text);
            right -= 46 * s;
        }
        String label = name.isEmpty() ? "—" : name;
        float size = fitText(label, right - nameLeft, 30 * s, hudFont());
        text.setTypeface(hudFont());
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(active ? TEXT : TEXT_DIM);
        c.drawText(label, nameLeft, cy + size * 0.36f, text);
    }

    /** The HUD's dark crimson plate with a bronze edge. */
    private void plate(Canvas c, RectF r, boolean active) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, r.top, 0, r.bottom,
            active ? new int[] { PLATE_HI, 0xFF3A0C14, PLATE_LO } : new int[] { 0xFF3A2226, 0xFF24171A, 0xFF140C0E },
            new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(r, paint);
        paint.setShader(null);
        // Inner bevel.
        paint.setColor(0x33FFFFFF);
        c.drawRect(r.left, r.top, r.right, r.top + 2 * s, paint);
        bronzeStroke(c, r, 0, 2.5f * s);
    }

    private void bronzeStroke(Canvas c, RectF r, float radius, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
            new int[] { BRONZE_HI, BRONZE, BRONZE_LO, BRONZE }, new float[] { 0f, 0.35f, 0.7f, 1f },
            Shader.TileMode.CLAMP));
        if (radius > 0) c.drawRoundRect(r, radius, radius, paint);
        else c.drawRect(r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private float fitText(String value, float width, float max, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(max);
        float measured = text.measureText(value);
        return measured <= width || measured == 0 ? max : Math.max(max * 0.55f, max * width / measured);
    }

    private static int lighten(int c) {
        return Color.argb(Color.alpha(c), Math.min(255, Color.red(c) + 60), Math.min(255, Color.green(c) + 60),
            Math.min(255, Color.blue(c) + 60));
    }

    private static int darken(int c) {
        return Color.argb(Color.alpha(c), Color.red(c) / 2, Color.green(c) / 2, Color.blue(c) / 2);
    }

    // ---------------------------------------------------------------------------------------
    // Field map (filled in once the native side reports the area and position)
    // ---------------------------------------------------------------------------------------

    /** The player arrow in the game's minimap icon sheet (naviicon, original pixels). */
    private static final int[] PLAYER_ARROW = { 0, 1, 13, 17 };

    /** Map modes (tap the map to switch; remembered): the whole area north up, or following Lightning. */
    static final String PREF_MAP_MODE = "companion_map";
    /** Follow mode: world units across the round map (the game's minimap shows about 60). */
    // the owner: closer than 240. Default world units across the disc (pinch changes it).
    private static final float FOLLOW_SPAN = 150f;
    static final String PREF_FOLLOW_SPAN = "companion_follow_span";
    private float followSpan = Float.NaN;

    private float followSpan() {
        if (Float.isNaN(followSpan)) followSpan = RuntimeActivity.renderPrefs(getContext()).getFloat(PREF_FOLLOW_SPAN, FOLLOW_SPAN);
        return followSpan;
    }

    /** Pinch on the follow map (drawing thread): factor > 1 zooms in; true when it changed. */
    boolean zoom(float factor, boolean done) {
        if (!wantsAnimation()) return false;
        float span = Math.max(60f, Math.min(420f, followSpan() / factor));
        boolean changed = Math.abs(span - followSpan) > 0.01f;
        followSpan = span;
        if (done) RuntimeActivity.renderPrefs(getContext()).edit().putFloat(PREF_FOLLOW_SPAN, span).apply();
        return changed;
    }

    // The follow map's animated pose (drawing thread): eased towards the newest sample every frame,
    // so turning and walking glide at the display rate instead of stepping at the poll rate.
    private float poseX = Float.NaN, poseZ, poseHeading, poseCam;      // newest sample
    private float dispX = Float.NaN, dispZ, dispHeading, dispCam;      // what is drawn
    private long lastAnim;
    private static final float TAU_TURN_MS = 110f, TAU_MOVE_MS = 70f;

    /** A pose sample at the display rate (nativeCompanionPose). */
    void setPose(float x, float z, float heading, float cam) {
        poseX = x;
        poseZ = z;
        poseHeading = heading;
        poseCam = cam;
    }

    /** The scene drawn now (fades between scenes key on it). */
    CompanionState.Scene scene() {
        return state.scene();
    }

    /** True while the follow map is shown: the surface then draws at the display rate. */
    boolean wantsAnimation() {
        return state.scene() == CompanionState.Scene.MAP && followMode();
    }

    private static float angleDiff(float to, float from) {
        float d = (to - from) % (float) (2 * Math.PI);
        if (d > Math.PI) d -= 2 * Math.PI;
        if (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    /** Eases the drawn pose towards the newest sample; true when the picture moved visibly. */
    boolean animate(long nowMs) {
        if (Float.isNaN(poseX)) return false;
        float dt = lastAnim == 0 ? 16f : Math.min(100f, nowMs - lastAnim);
        lastAnim = nowMs;
        if (Float.isNaN(dispX) || Math.hypot(poseX - dispX, poseZ - dispZ) > 40f) {  // first sample, warp
            dispX = poseX; dispZ = poseZ; dispHeading = poseHeading; dispCam = poseCam;
            return true;
        }
        float kt = 1f - (float) Math.exp(-dt / TAU_TURN_MS), km = 1f - (float) Math.exp(-dt / TAU_MOVE_MS);
        float dx = (poseX - dispX) * km, dz = (poseZ - dispZ) * km;
        float dh = angleDiff(poseHeading, dispHeading) * kt, dc = angleDiff(poseCam, dispCam) * kt;
        dispX += dx; dispZ += dz; dispHeading += dh; dispCam += dc;
        // Visible = more than ~0.1 screen pixel of movement or ~0.05 degrees of turn.
        return Math.abs(dx) + Math.abs(dz) > 0.02f || Math.abs(dh) + Math.abs(dc) > 0.001f;
    }

    private boolean followMode() {
        return "follow".equals(RuntimeActivity.renderPrefs(getContext()).getString(PREF_MAP_MODE, "area"));
    }

    private void drawMap(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (maps == null) maps = new CompanionMaps(getContext());
        hud = CompanionHud.fresh(hud, getContext());
        // The tile id names its map exactly; overlapping rectangles (Yusnaan inside the Dead Dunes'
        // bounds) made the "largest map holding the tile" guess pick the wrong one.
        CompanionMaps.NaviMap m = state.naviMap > 0 ? maps.byId(state.naviMap, state.naviX, state.naviZ) : null;
        if (m == null) m = maps.find(state.naviX, state.naviZ);
        Bitmap map = m != null ? maps.bitmap(m, 4096) : null;
        if (map == null) {
            drawWorldMap(c);
            return;
        }
        boolean located = !Float.isNaN(state.worldX) && !Float.isNaN(state.worldZ)
            && m.contains(state.worldX, state.worldZ);
        float heading = Float.isNaN(state.heading) ? 0f : state.heading;
        if (located && followMode()) {
            if (Float.isNaN(dispX)) { dispX = state.worldX; dispZ = state.worldZ; dispHeading = heading;
                dispCam = Float.isNaN(state.camHeading) ? heading : state.camHeading; }
            // Drawn at up to 30 fps in software on a little core: everything that does not turn
            // (background, ring, plates) comes from two cached layers; only the map, its markers and
            // the north mark are drawn per frame.
            c.drawBitmap(followUnderlay(w, h), 0, 0, null);
            drawFollowMap(c, m, map, dispCam, dispHeading);
            drawRing(c);
            c.drawBitmap(followOverlay(m, w, h), 0, 0, null);
            return;
        }
        drawMapBackground(c, w, h);
        drawAreaMap(c, m, map, located, heading);
        drawRegionPlate(c, m);
        drawClockPlate(c);
        drawQuestLine(c);
    }

    /** Day and game time, top right, on the game's plate in the game's font (as the HUD clock). */
    private void drawClockPlate(Canvas c) {
        if (state.clock.isEmpty() || hud == null || !hud.ok) return;
        int w = getWidth();
        String day = state.day >= 0 ? "DAY " + state.day : "";
        float dh = 18 * s, chh = 26 * s;
        float dw = day.isEmpty() ? 0 : hud.textWidth(false, day, dh), cw = hud.textWidth(false, state.clock, chh);
        float pw = 30 * s + dw + (day.isEmpty() ? 0 : 24 * s) + cw + 30 * s, ph = 66 * s;
        float x = w - 42 * s - pw, y = 42 * s;
        hudPlate(c, x, y, pw, ph, true, null, null);
        if (!day.isEmpty()) gameText(c, false, day, x + 30 * s, y + ph / 2f, dh, 0xFFE9D3A0, -1);
        gameText(c, false, state.clock, x + pw - 30 * s, y + ph / 2f, chh, 0xFFF6F0EC, 1);
    }

    /** North up: about 1100 map pixels tall around Lightning, or the whole map without a position. */
    private void drawAreaMap(Canvas c, CompanionMaps.NaviMap m, Bitmap map, boolean located, float heading) {
        int w = getWidth(), h = getHeight();
        RectF frame = mapFrame;
        frame.set(18 * s, 18 * s, w - 18 * s, h - 18 * s);
        float scale, cx, cy;
        if (located) {
            scale = frame.height() / 1100f;
            cx = (state.worldX - m.originX) * m.pxPerUnit;
            cy = (state.worldZ - m.originZ) * m.pxPerUnit;
        } else {
            scale = Math.min(frame.width() / m.width, frame.height() / m.height);
            cx = m.width / 2f;
            cy = m.height / 2f;
        }
        c.save();
        c.clipRect(frame);
        float left = frame.centerX() - cx * scale, top = frame.centerY() - cy * scale;
        tmp.set(left, top, left + m.width * scale, top + m.height * scale);
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setColor(Color.WHITE);
        c.drawBitmap(map, null, tmp, paint);
        drawMarkers(c, m, frame.centerX(), frame.centerY(), cx, cy, scale, 0f, Float.MAX_VALUE);
        if (located) drawMarker(c, frame.centerX(), frame.centerY(), heading);
        c.restore();
        bronzeStroke(c, frame, 14 * s, 4 * s);
    }

    /**
     * Like the game's minimap: round, zoomed in, turned so the way Lightning faces is up, in the
     * game's bronze minimap ring (nvmap_waku2), with a north mark on the ring.
     */
    private void drawFollowMap(Canvas c, CompanionMaps.NaviMap m, Bitmap map, float heading, float facing) {
        int w = getWidth(), h = getHeight();
        float r = followR(), ccx = followCx(), ccy = followCy();  // the ring (1.10 r) stays on screen
        float scale = 2f * r / (followSpan() * m.pxPerUnit);
        float px = (dispX - m.originX) * m.pxPerUnit, py = (dispZ - m.originZ) * m.pxPerUnit;
        Path circle = new Path();
        circle.addCircle(ccx, ccy, r, Path.Direction.CW);
        c.save();
        c.clipPath(circle);
        c.save();
        c.translate(ccx, ccy);
        c.rotate((float) -Math.toDegrees(heading));
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
        if (m.tile2x <= 0) {  // no 2x tiles: the whole map, enlarged
            tmp.set(-px * scale, -py * scale, (m.width - px) * scale, (m.height - py) * scale);
            c.drawBitmap(map, null, tmp, paint);
        }
        // Sharper 2x tiles (Real-ESRGAN, built with the APK) over the area in view.
        if (m.tile2x > 0) {
            int t = m.tile2x;
            float reach = r / scale;  // the circle's radius in map pixels
            for (int row = (int) Math.floor((py - reach) / t); row <= (int) Math.floor((py + reach) / t); row++) {
                for (int col = (int) Math.floor((px - reach) / t); col <= (int) Math.floor((px + reach) / t); col++) {
                    Bitmap tb = maps.tile(m, row, col);
                    if (tb == null) continue;
                    tmp.set((col * t - px) * scale, (row * t - py) * scale, ((col + 1) * t - px) * scale, ((row + 1) * t - py) * scale);
                    c.drawBitmap(tb, null, tmp, paint);
                }
            }
        }
        c.restore();
        drawMarkers(c, m, ccx, ccy, px, py, scale, -heading, r - 18 * s);
        drawMarker(c, ccx, ccy, angleDiff(facing, heading));  // her facing relative to the camera
        c.restore();
    }

    /** Follow-mode geometry: ring radius and centre (also used by the cached layers). */
    // The game's minimap ring stays still around the turning map (the owner: a turning ring distracts; no N mark).
    private static final float RING_SCALE = 1.10f;
    private float followR() { return Math.min(getWidth(), getHeight()) / 2f - 92 * s; }
    private float followCx() { return getWidth() / 2f; }
    private float followCy() { return getHeight() / 2f + 6 * s; }

    private Bitmap underlay, overlay;
    private String overlayKey = "";

    /** The map-screen background with the dark disc the map turns on (drawn once per size). */
    private Bitmap followUnderlay(int w, int h) {
        if (underlay != null && underlay.getWidth() == w && underlay.getHeight() == h) return underlay;
        underlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas u = new Canvas(underlay);
        drawMapBackground(u, w, h);
        float r = followR(), ccx = followCx(), ccy = followCy();
        paint.setShader(new RadialGradient(ccx, ccy, r, new int[] { 0xFF14262A, 0xFF0A1416 }, null, Shader.TileMode.CLAMP));
        u.drawCircle(ccx, ccy, r, paint);
        paint.setShader(null);
        return underlay;
    }

    /** The game's minimap ring, turned so its top point marks north (or a bronze circle without the pieces). */
    private void drawRing(Canvas c) {
        float r = followR(), ccx = followCx(), ccy = followCy();
        Bitmap ring = hud != null ? hud.bitmap("nvmap_waku2") : null;
        if (ring != null) {
            float rr = r * RING_SCALE;
            c.save();
            tmp.set(ccx - rr, ccy - rr, ccx + rr, ccy + rr);
            paint.setColorFilter(new LightingColorFilter(0xC8C5BE, 0));
            paint.setAlpha(255);
            c.drawBitmap(ring, null, tmp, paint);
            paint.setColorFilter(null);
            c.restore();
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(10 * s);
        paint.setShader(new LinearGradient(0, ccy - r, 0, ccy + r, new int[] { BRONZE_HI, BRONZE, BRONZE_LO },
            null, Shader.TileMode.CLAMP));
        c.drawCircle(ccx, ccy, r + 5 * s, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    /** The plates (region / place, day and time, objective): redrawn when their text changes. */
    private Bitmap followOverlay(CompanionMaps.NaviMap m, int w, int h) {
        String key = w + "x" + h + "|" + state.region + "|" + state.place + "|" + state.clock + "|" + state.day + "|"
            + state.quest + "|" + m.id;
        if (overlay != null && key.equals(overlayKey)) return overlay;
        if (overlay == null || overlay.getWidth() != w || overlay.getHeight() != h) {
            overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        overlay.eraseColor(0);
        Canvas o = new Canvas(overlay);
        drawRegionPlate(o, m);
        drawClockPlate(o);
        drawQuestLine(o);
        overlayKey = key;
        return overlay;
    }

    /**
     * Quest objectives and places (state "mk": type:x:z). (ox, oy) = screen point of map pixel
     * (cx, cy); rot = map rotation (radians); limit = radius beyond which markers are pinned to the
     * edge (follow mode: off-screen objectives point the way, like the game's minimap).
     */
    private void drawMarkers(Canvas c, CompanionMaps.NaviMap m, float ox, float oy, float cx, float cy, float scale,
                             float rot, float limit) {
        if (state.markerTypes.length == 0) return;
        double cs = Math.cos(rot), sn = Math.sin(rot);
        for (int i = 0; i < state.markerTypes.length; i++) {
            String type = state.markerTypes[i];
            float dx = ((state.markerX[i] - m.originX) * m.pxPerUnit - cx) * scale;
            float dy = ((state.markerZ[i] - m.originZ) * m.pxPerUnit - cy) * scale;
            float rx = (float) (dx * cs - dy * sn), ry = (float) (dx * sn + dy * cs);
            boolean quest = type.equals("quest") || type.equals("mainquest");
            float d = (float) Math.hypot(rx, ry);
            if (d > limit) {
                if (!quest) continue;  // only objectives stay on the edge
                rx *= limit / d;
                ry *= limit / d;
            }
            drawMarkerIcon(c, type, ox + rx, oy + ry);
        }
    }

    /** The game's minimap icon for a marker type (CompanionHud.markerIcon), or a coloured badge. */
    private void drawMarkerIcon(Canvas c, String type, float x, float y) {
        float size = (type.equals("mainquest") ? 46 : 38) * s;
        if (hud != null && hud.drawMarker(c, type, x - size / 2, y - size / 2, size)) return;
        int col;
        String glyph;
        switch (type) {
            case "mainquest": col = 0xFFFFC83C; glyph = "!"; break;
            case "quest": col = 0xFFFF8A3C; glyph = "!"; break;
            case "shop": col = 0xFF4FA0FF; glyph = "$"; break;
            case "inn": col = 0xFF5CCB6A; glyph = "Z"; break;
            case "warp": col = 0xFF5FE0E0; glyph = "W"; break;
            case "save": col = 0xFFF0F0F0; glyph = "S"; break;
            case "npc": col = 0xFF8CE070; glyph = ""; break;
            case "enemy": col = 0xFFE8323C; glyph = ""; break;  // the minimap's red enemy dot
            default: col = 0xFFC0C0C0; glyph = ""; break;
        }
        float rr = glyph.isEmpty() ? 7 * s : size / 2.6f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC000000);
        c.drawCircle(x, y, rr + 3 * s, paint);
        paint.setColor(col);
        c.drawCircle(x, y, rr, paint);
        if (!glyph.isEmpty()) {
            text.setTypeface(hudFont());
            text.setTextSize(rr * 1.3f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xFF1A1010);
            c.drawText(glyph, x, y + rr * 0.45f, text);
        }
    }

    private void drawRegionPlate(Canvas c, CompanionMaps.NaviMap m) {
        String name = !state.region.isEmpty() ? state.region : CompanionMaps.regionName(m.id);
        if (name.isEmpty()) return;
        if (!state.place.isEmpty() && hud != null && hud.ok) {
            drawPlacePlate(c, name, state.place);
            return;
        }
        text.setTypeface(hudFont());
        text.setTextSize(40 * s);
        float tw = text.measureText(name);
        float ph = 66 * s, pw = plateWidth(tw, ph);
        if (hud != null && hud.ok) {
            hudPlate(c, 42 * s, 42 * s, pw, ph, true, null, null);
            label(c, name, 42 * s + pw / 2f, 42 * s + ph / 2f, 40 * s, hudFont(), NAME_PINK, 0, 3 * s);
            return;
        }
        tmp.set(42 * s, 42 * s, 42 * s + pw, 42 * s + ph);
        plate(c, tmp, true);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(NAME_PINK);
        c.drawText(name, tmp.centerX(), tmp.centerY() + 14 * s, text);
    }

    /** The current objective (state "quest"), on a plate along the bottom. */
    private void drawQuestLine(Canvas c) {
        if (state.quest.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        // The game writes "Midnight to 6 AM" and the objective as two parts (a line break, read as
        // two spaces): the time window as a gold label, then the objective.
        String when = "", what = state.quest;
        int split = state.quest.indexOf("  ");
        if (split > 0) {
            when = state.quest.substring(0, split).trim();
            what = state.quest.substring(split).trim();
        }
        float ph = 64 * s;
        float cap = 16f * ph / (CompanionHud.PLATE[3] - CompanionHud.PLATE[1]);
        if (hud != null && hud.ok) hudPlate(c, 42 * s, h - 106 * s, w - 84 * s, ph, true, null, null);
        else plate(c, new RectF(42 * s, h - 106 * s, w - 42 * s, h - 42 * s), true);
        tmp.set(42 * s, h - 106 * s, w - 42 * s, h - 42 * s);
        float x = tmp.left + cap + 4 * s, right = tmp.right - cap - 8 * s, cy = tmp.centerY();
        drawMarkerIcon(c, "mainquest", x + 18 * s, cy);
        x += 46 * s;
        if (!when.isEmpty()) {
            float ws = fitText(when, (right - x) * 0.4f, 23 * s, hudFont());
            label(c, when, x, cy, ws, hudFont(), 0xFFE9D3A0, -1, 2 * s);
            text.setTypeface(hudFont());
            text.setTextSize(ws);
            x += text.measureText(when) + 18 * s;
        }
        float size = fitText(what, right - x, 26 * s, hudFont());
        label(c, what, x, cy, size, hudFont(), TEXT, -1, 2 * s);
    }

    /** Lightning's marker: a pink arrow pointing where she faces (heading in radians, 0 = up). */
    private void drawMarker(Canvas c, float x, float y, float heading) {
        // The game's own minimap arrow (naviicon), with a soft shadow, when bundled.
        if (hud != null && hud.bitmap("naviicon") != null) {
            float aw = 30 * s, ah = aw * 16f / 13f;
            c.save();
            c.translate(x, y);
            c.rotate((float) Math.toDegrees(heading));
            hud.draw(c, "naviicon", PLAYER_ARROW, -aw * 0.62f, -ah * 0.62f + 2 * s, aw * 1.24f, ah * 1.24f, 0x000000, 150);
            hud.drawPlain(c, "naviicon", PLAYER_ARROW, -aw / 2f, -ah / 2f, aw, ah, 255);
            c.restore();
            return;
        }
        Path p = new Path();
        float r = 22 * s;
        p.moveTo(0, -r);
        p.lineTo(r * 0.7f, r * 0.8f);
        p.lineTo(0, r * 0.4f);
        p.lineTo(-r * 0.7f, r * 0.8f);
        p.close();
        c.save();
        c.translate(x, y);
        c.rotate((float) Math.toDegrees(heading));
        glow.setStyle(Paint.Style.FILL);
        glow.setColor(0xAAFF5A78);
        glow.setMaskFilter(new BlurMaskFilter(14 * s, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(p, glow);
        glow.setMaskFilter(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(NAME_PINK);
        c.drawPath(p, paint);
        c.restore();
    }

    // ---------------------------------------------------------------------------------------
    // Fan art (title, cutscenes, menus): the picture ES-DE's companion shows for the game
    // ---------------------------------------------------------------------------------------

    private void drawFanart(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (!fanartTried) {
            fanartTried = true;
            fanart = loadFanart(w, h);
        }
        if (fanart == null) {
            drawBackdrop(c, w, h);
            // The game's own logo (gui/resident/system FF13_font) when it is bundled.
            hud = CompanionHud.fresh(hud, getContext());
            Bitmap logo = hud.bitmap("FF13_font");
            if (logo != null) {
                float lw = w * 0.82f, lh = lw * logo.getHeight() / logo.getWidth();
                tmp.set((w - lw) / 2f, (h - lh) / 2f, (w + lw) / 2f, (h + lh) / 2f);
                paint.setColorFilter(null);
                paint.setAlpha(255);
                c.drawBitmap(logo, null, tmp, paint);
                return;
            }
            text.setTypeface(hudFont());
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(64 * s);
            text.setColor(0xFFE0C890);
            c.drawText("LIGHTNING RETURNS", w / 2f, h / 2f, text);
            text.setTextSize(30 * s);
            text.setColor(TEXT_DIM);
            c.drawText("FINAL FANTASY XIII", w / 2f, h / 2f + 56 * s, text);
            return;
        }
        // Centre crop to fill, as ES-DE's companion does.
        float scale = Math.max(w / (float) fanart.getWidth(), h / (float) fanart.getHeight());
        int sw = Math.round(w / scale), sh = Math.round(h / scale);
        int sx = (fanart.getWidth() - sw) / 2, sy = (fanart.getHeight() - sh) / 2;
        src.set(sx, sy, sx + sw, sy + sh);
        tmp.set(0, 0, w, h);
        paint.setShader(null);
        c.drawBitmap(fanart, src, tmp, paint);
    }

    /** ES-DE's downloaded media: fan art first, then screenshots and covers, for any system folder. */
    private static Bitmap loadFanart(int w, int h) {
        File media = new File(Environment.getExternalStorageDirectory(), "ES-DE/downloaded_media");
        String[] kinds = { "fanart", "screenshots", "titlescreens", "covers" };
        File[] systems = media.listFiles(File::isDirectory);
        if (systems == null) return null;
        for (String kind : kinds) {
            for (File system : systems) {
                File[] files = new File(system, kind).listFiles((dir, n) -> {
                    String l = n.toLowerCase(Locale.ROOT);
                    return l.startsWith("lightning returns") && (l.endsWith(".jpg") || l.endsWith(".png") || l.endsWith(".webp"));
                });
                if (files == null) continue;
                for (File f : files) {
                    Bitmap b = decode(f, w, h);
                    if (b != null) return b;
                }
            }
        }
        return null;
    }

    private static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------

    /** A tap on the bottom screen (drawing thread). True when the picture must be redrawn. */
    boolean tap(float x, float y) {
        if (state.scene() == CompanionState.Scene.MAP) {
            // Tap the map: whole area <-> following Lightning.
            RuntimeActivity.renderPrefs(getContext()).edit()
                .putString(PREF_MAP_MODE, followMode() ? "area" : "follow").apply();
            return true;
        }
        if (state.scene() != CompanionState.Scene.BATTLE) return false;
        int active = pending >= 0 ? pending : state.active;
        for (int i = 0; i < 3; i++) {
            if (!cards[i].contains(x, y)) continue;
            if (i == active || active < 0) return false;
            // With three schemata every other one is one press away: RB = next, LB = previous.
            int button = i == (active + 1) % 3 ? RB : LB;
            RuntimeActivity.nativeCompanionPress(button, PRESS_MS);
            pending = i;
            pendingUntil = SystemClock.uptimeMillis() + 800;
            return true;
        }
        return false;
    }
}
