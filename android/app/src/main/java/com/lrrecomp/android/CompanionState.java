package com.lrrecomp.android;

import java.util.HashMap;
import java.util.Map;

/**
 * What the game shows now, as reported by native code (src/lr_companion.inc): one line of
 * key=value pairs separated by ';'. Unknown keys are ignored, missing ones keep their defaults,
 * so the native side can grow without breaking the bottom screen.
 */
final class CompanionState {
    enum Scene { FANART, BATTLE, MAP }

    /** Frame-rate mode at app+788: 0 title / system, 1 field, battle and menus, 2+ events, -1 unknown. */
    int mode = -1;
    long frames;
    boolean battle;
    /** Index of the active schema (0-2), -1 unknown. */
    int active = -1;
    final Schema[] schemata = { new Schema(), new Schema(), new Schema() };
    int hp = -1, hpMax = -1;
    /** EP (Lightning's ability points) and the cap, -1 = unknown. */
    float ep = -1f, epMax = -1f;
    /** The targeted enemy: name, HP, staggered, and its six vulnerabilities as the Libra screen lists them. */
    String enemy = "";
    int enemyHp = -1, enemyHpMax = -1;
    boolean staggered;
    /** Fire, ice, thunder, wind, physical, magic: weak | normal | halved | resist | immune | absorb | ? (unrevealed); null = no data. */
    static final String[] VULN_ORDER = { "fire", "ice", "thunder", "wind", "phys", "magic" };
    String[] vuln = new String[6];
    /** Field: world origin of the newest streamed map tile ("navi=ID:X:Z,..."), NaN = none. */
    float naviX = Float.NaN, naviZ = Float.NaN;
    /** The navimap the newest streamed tile belongs to (the tile id's top 16 bits), -1 = none. */
    int naviMap = -1;
    /** The game's loading screen is up ("loading=1"). */
    boolean loading;
    /** Lightning's world position (X, Z) and heading in radians (0 = map up), NaN = unknown. */
    float worldX = Float.NaN, worldZ = Float.NaN, heading = Float.NaN;
    /** Where the field camera looks (same convention); the follow map turns with it. */
    float camHeading = Float.NaN;
    /** Markers the game's minimap shows ("mk=type:x:z,..."): quest, mainquest, shop, inn, warp, save, npc, other. */
    String[] markerTypes = new String[0];
    float[] markerX = new float[0], markerZ = new float[0];
    /** The current objective's text ("quest="), empty = none. */
    String quest = "";
    /** The game clock ("clock=HH:MM") and day number ("day="), empty / -1 = unknown. */
    String clock = "";
    /** Region and place as the game names them ("region=Luxerion;place=North Station Plaza"). */
    String region = "", place = "";
    int day = -1;

    static final class Schema {
        String name = "";
        String garb = "";
        int atb = -1, atbMax = -1;
        /** Abilities on Y, X, B, A (the game's order on the HUD: top, left, right, bottom). */
        final String[] abilities = { "", "", "", "" };
        final int[] costs = { -1, -1, -1, -1 };
    }

    Scene scene() {
        if (battle) return Scene.BATTLE;
        // In the world only when the game says where (region and place known, Lightning placed, no
        // loading screen): the area map, or the world map where no area map streams (the Ark). The
        // title, menus before a load and load screens report mode 1 too: fan art there.
        if (mode == 1 && !loading && !region.isEmpty() && !Float.isNaN(worldX)) return Scene.MAP;
        return Scene.FANART;
    }

    /** What the picture depends on: the line without the frame counter, the position rounded to
     * a quarter world unit (half a map pixel) and the heading to ~3 degrees. Equal = no redraw. */
    private String sig = "";

    String signature() { return sig; }

    private static String signatureOf(Map<String, String> kv) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(kv).entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (k.equals("frames")) continue;
            if (k.equals("worldx") || k.equals("worldz") || k.equals("heading")) {
                float f = floatOf(v);
                if (!Float.isNaN(f)) v = String.valueOf(Math.round(f * (k.equals("heading") ? 20f : 4f)));
            }
            b.append(k).append('=').append(v).append(';');
        }
        return b.toString();
    }

    static CompanionState parse(String line) {
        CompanionState s = new CompanionState();
        if (line == null) return s;
        Map<String, String> kv = new HashMap<>();
        for (String part : line.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) kv.put(part.substring(0, eq), part.substring(eq + 1));
        }
        s.sig = signatureOf(kv);
        s.mode = intOf(kv.get("mode"), -1);
        s.frames = longOf(kv.get("frames"), 0);
        s.battle = intOf(kv.get("battle"), 0) != 0;
        s.active = intOf(kv.get("active"), -1);
        s.hp = intOf(kv.get("hp"), -1);
        s.ep = floatOf(kv.get("ep"));
        s.epMax = floatOf(kv.get("epmax"));
        if (Float.isNaN(s.ep)) s.ep = -1f;
        if (Float.isNaN(s.epMax)) s.epMax = -1f;
        s.enemy = str(kv.get("ename"));
        s.enemyHp = intOf(kv.get("ehp"), -1);
        s.enemyHpMax = intOf(kv.get("ehpmax"), -1);
        s.staggered = "1".equals(kv.get("stagger"));
        for (String item : str(kv.get("vuln")).split(",")) {  // vuln=fire:weak,ice:normal,thunder:?,...
            int c = item.indexOf(':');
            if (c <= 0) continue;
            for (int i = 0; i < 6; i++) if (VULN_ORDER[i].equals(item.substring(0, c))) s.vuln[i] = item.substring(c + 1);
        }
        s.hpMax = intOf(kv.get("hpmax"), -1);
        String navi = kv.get("navi");
        if (navi != null && !navi.isEmpty()) {
            String[] first = navi.split(",")[0].split(":");
            if (first.length == 3) {
                s.naviX = floatOf(first[1]);
                s.naviZ = floatOf(first[2]);
                try { s.naviMap = (int) (Long.parseLong(first[0], 16) >>> 16); } catch (NumberFormatException ignored) { }
            }
        }
        String mk = kv.get("mk");
        if (mk != null && !mk.isEmpty()) {
            String[] items = mk.split(",");
            java.util.ArrayList<String> types = new java.util.ArrayList<>();
            java.util.ArrayList<Float> xs = new java.util.ArrayList<>(), zs = new java.util.ArrayList<>();
            for (String item : items) {
                String[] f = item.split(":");
                if (f.length != 3) continue;
                float x = floatOf(f[1]), z = floatOf(f[2]);
                if (Float.isNaN(x) || Float.isNaN(z)) continue;
                types.add(f[0]);
                xs.add(x);
                zs.add(z);
            }
            s.markerTypes = types.toArray(new String[0]);
            s.markerX = new float[xs.size()];
            s.markerZ = new float[zs.size()];
            for (int i = 0; i < xs.size(); i++) { s.markerX[i] = xs.get(i); s.markerZ[i] = zs.get(i); }
        }
        s.quest = str(kv.get("quest"));
        s.clock = str(kv.get("clock"));
        s.region = str(kv.get("region"));
        s.loading = intOf(kv.get("loading"), 0) != 0;
        s.place = str(kv.get("place"));
        s.day = intOf(kv.get("day"), -1);
        s.worldX = floatOf(kv.get("worldx"));
        s.worldZ = floatOf(kv.get("worldz"));
        s.heading = floatOf(kv.get("heading"));
        s.camHeading = floatOf(kv.get("camheading"));
        for (int i = 0; i < 3; i++) {
            Schema c = s.schemata[i];
            String p = "s" + i;
            c.name = str(kv.get(p + "name"));
            c.garb = str(kv.get(p + "garb"));
            c.atb = intOf(kv.get(p + "atb"), -1);
            c.atbMax = intOf(kv.get(p + "atbmax"), -1);
            for (int a = 0; a < 4; a++) {
                c.abilities[a] = str(kv.get(p + "ab" + a));
                c.costs[a] = intOf(kv.get(p + "cost" + a), -1);
            }
        }
        // The command window shows the active schema's abilities (ab0..ab3 = Y, X, B, A).
        if (s.active >= 0 && s.active < 3) {
            for (int k = 0; k < 4; k++) {
                String ab = kv.get("ab" + k);
                if (ab != null && s.schemata[s.active].abilities[k].isEmpty()) s.schemata[s.active].abilities[k] = ab;
            }
        }
        return s;
    }

    /** A made-up battle to look at the layout without a battle (debug extra LR_COMPANION_DEMO=battle). */
    static String demoBattle(long t) {
        int atb0 = (int) (t / 40 % 101);
        return "v=1;mode=1;battle=1;active=" + (t / 6000 % 3) + ";hp=2140;hpmax=2500;ep=2.45;epmax=5"
            + ";ename=Anubys;ehp=6800;ehpmax=10000;stagger=1"
            + ";vuln=fire:weak,ice:normal,thunder:?,wind:?,phys:halved,magic:weak"
            + ";s0name=The Savior;s0garb=Equilibrium;s0atb=" + atb0 + ";s0atbmax=100"
            + ";s0ab0=Thunder;s0ab1=Guard;s0ab2=Attack;s0ab3=Fire;s0cost0=15;s0cost1=5;s0cost2=10;s0cost3=15"
            + ";s1name=Dark Muse;s1garb=Dark Muse;s1atb=62;s1atbmax=120"
            + ";s1ab0=Blizzara;s1ab1=Aerora;s1ab2=Heavy Slash;s1ab3=Ruin;s1cost0=25;s1cost1=25;s1cost2=20;s1cost3=10"
            + ";s2name=Red Mage;s2garb=Red Mage;s2atb=100;s2atbmax=100"
            + ";s2ab0=Dispel;s2ab1=Heavy Guard;s2ab2=Flamestrike;s2ab3=Deprotect;s2cost0=20;s2cost1=10;s2cost2=20;s2cost3=20";
    }

    private static String str(String v) { return v == null ? "" : v; }

    private static int intOf(String v, int fallback) {
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static long longOf(String v, long fallback) {
        if (v == null) return fallback;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static float floatOf(String v) {
        if (v == null) return Float.NaN;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return Float.NaN; }
    }
}
