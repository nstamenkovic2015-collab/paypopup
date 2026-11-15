package com.paypopup;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Draws the animated payment popup. The more money, the crazier it gets.
 *
 *   Tier  Amount   Effect (variants are picked at random every time)
 *   ----  ------   ---------------------------------------------------------------
 *    0    any      sparkle trail, landing pulse, coin/gem/star burst, floating "+$"
 *    1    $100K+   + dollar bills fly out
 *    2    $1M+     + coin fountains, spinning light rays
 *    3    $5M+     MONEY RAIN       (bills / coins / diamonds)
 *    4    $10M+    + glowing screen edges, fireworks
 *    5    $100M+   + white flash, confetti
 *    6    $500M+   TORNADO          (storm + lightning / fire tornado / money tornado)
 *    7    $1B+     LEGENDARY        (fireworks / meteor shower / lightning storm)
 *    8    $10B+    GOD TIER         (all three at once + strobe + dragon roar)
 *    9    $25B+    GALAXY COLLISION (Milky Way vs a pink / green / orange galaxy)
 *   10    $50B+    BLACK HOLE       (classic / quasar with jets / purple void)
 *   11    $100B+   MULTIVERSE COLLAPSE: two universes collide, a black hole forms,
 *                  eats every planet, then collapses into a BIG BANG
 *
 * Every popup also gets one of 5 colour themes: gold, emerald, diamond, amethyst, redstone.
 */
public final class PopupRenderer {

    // =====================================================================
    //  Tier table
    // =====================================================================
    private static final double[] MIN_AMOUNT = {
            0, 100_000d, 1_000_000d, 5_000_000d, 10_000_000d, 100_000_000d, 500_000_000d,
            1_000_000_000d, 10_000_000_000d, 25_000_000_000d, 50_000_000_000d, 100_000_000_000d
    };
    private static final String[][] TITLES = {
            {"PAYMENT RECEIVED", "PAYMENT RECEIVED", "PAYMENT RECEIVED"},
            {"NICE!", "SWEET!", "NICE ONE!"},
            {"BIG PAYMENT!", "BIG MONEY!", "BALLER!"},
            {"MONEY RAIN!", "COIN STORM!", "DIAMOND RAIN!"},
            {"HUGE!!", "MASSIVE!!", "HUGE!!"},
            {"INSANE!!!", "UNREAL!!!", "INSANE!!!"},
            {"TORNADO PAYMENT!!", "FIRE TORNADO!!", "MONEY TORNADO!!"},
            {"LEGENDARY!!!!", "METEOR SHOWER!!!!", "THUNDER STRIKE!!!!"},
            {"GOD TIER PAYMENT", "GOD TIER PAYMENT", "GOD TIER PAYMENT"},
            {"GALACTIC PAYMENT", "GALACTIC PAYMENT", "GALACTIC PAYMENT"},
            {"BLACK HOLE PAYMENT", "QUASAR PAYMENT", "VOID PAYMENT"},
            {"MULTIVERSE COLLAPSE", "MULTIVERSE COLLAPSE", "MULTIVERSE COLLAPSE"},
    };
    private static final int[] COIN_COUNT = {14, 22, 35, 40, 55, 80, 90, 120, 180, 200, 220, 260};
    private static final float[] SHAKE = {0, 0, 1f, 1f, 2f, 4f, 6f, 7f, 12f, 14f, 18f, 22f};

    /** Colour themes: {main, light, dark}. */
    private static final int[][] THEMES = {
            {0xFFD700, 0xFFF5A0, 0xC8A000},   // gold
            {0x2EE86B, 0xA8FFC4, 0x0F9E3F},   // emerald
            {0x5FF3FF, 0xD6FDFF, 0x1CA7B8},   // diamond
            {0xC77DFF, 0xEBD4FF, 0x7B3FBF},   // amethyst
            {0xFF4040, 0xFFB0B0, 0xA01010},   // redstone
    };

    // Particle kinds
    private static final int SPARK = 0, COIN = 1, GEM = 2, STAR = 3, CONFETTI = 4, BILL = 5,
            TEXT = 6, METEOR = 7, EMBER = 8;

    private static final int SLIDE_IN_TICKS = 10;
    private static final int SLIDE_OUT_TICKS = 8;
    private static final int COUNT_UP_TICKS = 20;
    private static final int MAX_PARTICLES = 1800;

    // =====================================================================
    //  State
    // =====================================================================
    private static final Deque<PayParser.Payment> QUEUE = new ArrayDeque<>();
    private static final List<Particle> PARTICLES = new ArrayList<>();
    private static final List<Particle> PENDING = new ArrayList<>();
    private static final List<Bolt> BOLTS = new ArrayList<>();
    private static final List<Planet> PLANETS = new ArrayList<>();
    private static final Random RANDOM = new Random();

    private static PayParser.Payment current;
    private static int tier, variant, theme;
    private static int age, holdTicks;
    private static float popupY;
    private static int screenW = 320, screenH = 240;

    private PopupRenderer() {}

    public static void show(PayParser.Payment payment) {
        QUEUE.add(payment);
    }

    public static int tierOf(double amount) {
        for (int i = MIN_AMOUNT.length - 1; i > 0; i--) {
            if (amount >= MIN_AMOUNT[i]) return i;
        }
        return 0;
    }

    private static void start(PayParser.Payment p) {
        current = p;
        tier = tierOf(p.amount());
        variant = RANDOM.nextInt(3);
        theme = RANDOM.nextInt(THEMES.length);
        age = 0;
        holdTicks = Math.round((PayPopupConfig.get().displaySeconds + Math.min(tier, 8) * 0.5f) * 20);
        if (tier == 6) holdTicks = Math.max(holdTicks, 170);
        if (tier == 9) holdTicks = Math.max(holdTicks, 200);
        if (tier == 10) holdTicks = Math.max(holdTicks, 190);
        if (tier == 11) holdTicks = Math.max(holdTicks, 330);
        PLANETS.clear();
        BOLTS.clear();
        bhActive = false;
        playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f + Math.min(tier, 8) * 0.08f);
    }

    // =====================================================================
    //  TICK - 20 times a second
    // =====================================================================
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        screenW = mc.getWindow().getGuiScaledWidth();
        screenH = mc.getWindow().getGuiScaledHeight();
        int sw = screenW, sh = screenH;

        if (current == null) {
            if (QUEUE.isEmpty()) {
                bhActive = false;
                PLANETS.clear();
                BOLTS.removeIf(b -> --b.life <= 0);
                tickParticles();
                return;
            }
            start(QUEUE.poll());
        }

        age++;
        float cx = sw / 2f;
        float cy = popupY + 26 * boxScale();
        boolean holding = age >= SLIDE_IN_TICKS && age < SLIDE_IN_TICKS + holdTicks;
        int sinceLand = age - SLIDE_IN_TICKS;
        int[] th = THEMES[theme];

        // ---- sparkle trail while the box slides in ----
        if (sinceLand < 0) {
            for (int i = 0; i < 2 + tier / 3; i++) {
                Particle p = new Particle(SPARK, cx + (RANDOM.nextFloat() - 0.5f) * 170, cy - 22,
                        (RANDOM.nextFloat() - 0.5f) * 0.6f, -0.3f, 14 + RANDOM.nextInt(8), th[1], 2);
                p.gravity = 0.02f;
                add(p);
            }
        }

        // ---- the moment it lands ----
        if (sinceLand == 0) {
            burst(cx, cy, COIN_COUNT[tier]);
            playLandSound();
            if (tier <= 2) floatingText(cx, cy + 34, "+" + PayParser.format(current.amount()), 0x55FF55);
        }
        if (tier >= 2 && tier <= 8 && sinceLand == 6) burst(cx, cy, COIN_COUNT[tier] / 2);
        if (tier >= 4 && tier <= 8 && holding && age % 3 == 0) burst(cx, cy, Math.min(tier, 8) - 2);

        // ---- confetti ----
        if (holding && ((tier >= 5 && tier <= 8) || tier == 10)) {
            int pieces = tier == 5 || tier == 6 ? 2 : tier == 7 ? 4 : tier == 8 ? 7 : 3;
            for (int i = 0; i < pieces; i++) confetti(sw);
        }

        // ---- tier specials ----
        switch (tier) {
            case 1 -> {
                if (sinceLand == 0 || sinceLand == 6) billBurst(cx, cy, 8);
            }
            case 2 -> {
                if (sinceLand == 0) billBurst(cx, cy, 10);
                if (sinceLand >= 0 && sinceLand < 30) fountain(cx, cy);
            }
            case 3 -> tickRain(holding, sinceLand, sw, 4);
            case 4 -> {
                tickRain(holding, sinceLand, sw, 2);
                sideFireworks(sinceLand, cx, cy);
            }
            case 5 -> sideFireworks(sinceLand, cx, cy);
            case 6 -> tickTornado(holding, sinceLand, sw, sh);
            case 7 -> tickLegendary(holding, sinceLand, sw, sh, variant);
            case 8 -> {
                tickLegendary(holding, sinceLand, sw, sh, 0);
                tickLegendary(holding, sinceLand, sw, sh, 1);
                tickLegendary(holding, sinceLand, sw, sh, 2);
            }
            case 9 -> tickGalaxy(sinceLand, sw, sh);
            case 10 -> tickBlackHole(sinceLand, holding, sw, sh);
            case 11 -> tickMultiverse(sinceLand, holding, sw, sh);
            default -> { }
        }

        BOLTS.removeIf(b -> --b.life <= 0);
        tickParticles();

        if (age > SLIDE_IN_TICKS + holdTicks + SLIDE_OUT_TICKS) {
            current = null;
            bhActive = false;
            PLANETS.clear();
        }
    }

    private static void tickParticles() {
        PARTICLES.removeIf(Particle::tick);
        PARTICLES.addAll(PENDING);
        PENDING.clear();
    }

    // =====================================================================
    //  RENDER - every frame
    // =====================================================================
    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        if (current == null) {
            drawBolts(g);
            drawParticles(g, font, partial);
            return;
        }

        float t = age + partial;
        float sinceLand = t - SLIDE_IN_TICKS;
        float outStart = SLIDE_IN_TICKS + holdTicks;
        float scale = boxScale();
        String title = TITLES[tier][variant];
        int boxW = Math.max(180 + Math.min(tier, 8) * 14, (int) (font.width(title) * 1.45f) + 24);
        int boxH = 56 + Math.min(tier, 8) * 4;
        int[] th = THEMES[theme];

        // ---------- position & fade ----------
        float y;
        float alpha;
        if (t < SLIDE_IN_TICKS) {
            float p = t / SLIDE_IN_TICKS;
            y = Mth.lerp(easeOutBack(p), -boxH * scale - 20, 14);
            alpha = p;
        } else if (t < outStart) {
            y = 14;
            alpha = 1;
        } else {
            float p = Math.min(1, (t - outStart) / SLIDE_OUT_TICKS);
            y = Mth.lerp(easeInCubic(p), 14, -boxH * scale - 20);
            alpha = 1 - p;
        }
        popupY = y;
        int a = Mth.clamp((int) (alpha * 255), 0, 255);

        // ---------- big scenes behind everything ----------
        if (tier == 6) renderTornado(g, t, sinceLand, alpha, sw, sh);
        if (tier == 9) renderGalaxies(g, t, sinceLand, alpha, sw, sh);
        if (tier == 10) renderBlackHole(g, t, sw, sh);
        if (tier == 11) renderMultiverse(g, t, sinceLand, alpha, sw, sh);
        drawBolts(g);

        // ---------- landing flash ----------
        if (tier >= 5 && sinceLand >= 0 && sinceLand < 8) {
            int flash = (int) ((1 - sinceLand / 8f) * (tier == 5 ? 120 : 180));
            g.fill(0, 0, sw, sh, argb(flash, 0xFFFFFF));
        }

        // ---------- lightning strobe (god tier) ----------
        if (tier == 8 && sinceLand > 0 && t < outStart && RANDOM.nextInt(40) == 0) {
            g.fill(0, 0, sw, sh, argb(140, 0xE0E8FF));
        }

        // ---------- glowing screen edges ----------
        if (tier >= 4 && a > 5) {
            int edgeColor = color(t, 0);
            float pulse = 0.6f + 0.4f * Mth.sin(t * 0.3f);
            int layers = 4 + Math.min(tier, 8) * 2;
            for (int i = 0; i < layers; i++) {
                int ea = (int) ((1 - i / (float) layers) * 90 * pulse * alpha);
                int c = argb(ea, edgeColor);
                int o = i * 2;
                g.fill(o, o, sw - o, o + 2, c);
                g.fill(o, sh - o - 2, sw - o, sh - o, c);
                g.fill(o, o + 2, o + 2, sh - o - 2, c);
                g.fill(sw - o - 2, o + 2, sw - o, sh - o - 2, c);
            }
        }

        if (a < 5) {
            drawParticles(g, font, partial);
            return;
        }

        // ---------- screen shake ----------
        float shake = 0;
        if (sinceLand >= 0 && sinceLand < 25) shake = SHAKE[tier] * (1 - sinceLand / 25f);
        if (tier >= 8 && t < outStart) shake = Math.max(shake, 2f);
        if (tier == 6 && sinceLand > 0 && t < outStart) shake = Math.max(shake, 1.2f);
        if (tier == 9) {
            float sm = sinceLand - galaxyMergeTick();
            if (sm >= 0 && sm < 35) shake = Math.max(shake, 16f * (1 - sm / 35f));
        }
        if (tier == 11) {
            float sc = sinceLand - mvCollide();
            if (sc >= 0 && sc < 45) shake = Math.max(shake, 24f * (1 - sc / 45f));
            float sb = sinceLand - mvBang();
            if (sb >= 0 && sb < 30) shake = Math.max(shake, 20f * (1 - sb / 30f));
        }
        if ((tier == 10 || tier == 11) && bhActive) shake = Math.max(shake, 1.5f + bhRadius * 0.04f);
        float sx = (RANDOM.nextFloat() - 0.5f) * shake * 2;
        float sy = (RANDOM.nextFloat() - 0.5f) * shake * 2;

        g.pose().pushMatrix();
        g.pose().translate(sw / 2f + sx, y + sy);
        g.pose().scale(scale, scale);

        // ---------- spinning light rays ----------
        if (tier >= 2 && tier <= 8) {
            int rays = 8 + tier * 2;
            int rayLen = 90 + tier * 25;
            g.pose().pushMatrix();
            g.pose().translate(0, boxH / 2f);
            for (int i = 0; i < rays; i++) {
                g.pose().pushMatrix();
                g.pose().rotate(t * 0.03f + (float) (i * Math.PI * 2 / rays));
                g.fill(0, -2, rayLen, 2, argb((int) (a * 0.35f), color(t, i * 20)));
                g.pose().popMatrix();
            }
            g.pose().popMatrix();
        }

        int left = -boxW / 2;
        int right = boxW / 2;
        int border = color(t, 0);

        // ---------- landing pulse ring ----------
        if (sinceLand >= 0 && sinceLand < 12) {
            int grow = (int) (sinceLand * (2.5f + tier * 0.4f));
            int pa = (int) ((1 - sinceLand / 12f) * 220);
            outline(g, left - 3 - grow, -3 - grow, right + 3 + grow, boxH + 3 + grow, argb(pa, border));
        }

        // ---------- box ----------
        int thick = 2 + Math.min(tier, 8) / 2;
        g.fill(left - thick, -thick, right + thick, boxH + thick, argb(a, border));
        g.fill(left, 0, right, boxH, argb(Math.min(a, 235), 0x14141F));
        g.fill(left, 0, right, 3, argb(a, border));
        g.fill(left, boxH - 3, right, boxH, argb(a, border));
        // corner gems
        g.fill(left - thick - 2, -thick - 2, left - thick + 3, -thick + 3, argb(a, th[1]));
        g.fill(right + thick - 3, -thick - 2, right + thick + 2, -thick + 3, argb(a, th[1]));

        // shine sweep
        float shine = ((t * (6 + tier * 2)) % (boxW + 60)) - 30;
        int sl = Mth.clamp(left + (int) shine, left, right);
        int sr = Mth.clamp(left + (int) shine + 10, left, right);
        g.fill(sl, 3, sr, boxH - 3, argb(a / 7, 0xFFFFFF));

        // ---------- title ----------
        float titleScale = 1f + (tier >= 5 ? 0.15f * Mth.sin(t * 0.4f) + 0.2f : 0);
        g.pose().pushMatrix();
        g.pose().translate(0, 10);
        g.pose().scale(titleScale, titleScale);
        g.centeredText(font, title, 0, -4, argb(a, tier >= 7 ? color(t, 40) : th[1]));
        g.pose().popMatrix();

        // ---------- amount: counts up, then pops ----------
        float countP = Mth.clamp(sinceLand / COUNT_UP_TICKS, 0, 1);
        double shown = current.amount() * easeOutCubic(countP);
        float pop = 1f;
        float sincePop = sinceLand - COUNT_UP_TICKS;
        if (sincePop >= 0 && sincePop < 8) {
            pop = 1f + (0.5f + Math.min(tier, 8) * 0.1f) * Mth.sin(sincePop / 8f * (float) Math.PI);
        }
        float amountScale = (1.6f + Math.min(tier, 8) * 0.15f) * pop;
        g.pose().pushMatrix();
        g.pose().translate(0, boxH / 2f + 2);
        g.pose().scale(amountScale, amountScale);
        g.centeredText(font, PayParser.format(shown), 0, -4, argb(a, tier >= 7 ? color(t, 120) : 0x55FF55));
        g.pose().popMatrix();

        // ---------- sender ----------
        g.centeredText(font, "from " + current.sender(), 0, boxH - 13, argb(a, 0xCCCCCC));

        g.pose().popMatrix();

        drawParticles(g, font, partial);
    }

    // =====================================================================
    //  Small-tier effects
    // =====================================================================
    private static void burst(float x, float y, int count) {
        int[] th = THEMES[theme];
        int kind = variant == 0 ? COIN : variant == 1 ? GEM : STAR;
        for (int i = 0; i < count; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            float speed = 1.5f + RANDOM.nextFloat() * (3f + Math.min(tier, 8));
            Particle p = new Particle(kind, x, y, (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed - 2.5f, 25 + RANDOM.nextInt(20), th[0],
                    2 + (tier >= 5 ? 1 : 0));
            p.gravity = 0.3f;
            add(p);
        }
    }

    private static void billBurst(float x, float y, int count) {
        for (int i = 0; i < count; i++) {
            float dir = RANDOM.nextBoolean() ? 1 : -1;
            Particle p = new Particle(BILL, x + dir * 60, y, dir * (2 + RANDOM.nextFloat() * 3),
                    -2 - RANDOM.nextFloat() * 3, 60 + RANDOM.nextInt(30), 0x2E9E48, 2);
            p.gravity = 0.08f;
            p.drag = 0.97f;
            p.spin = RANDOM.nextFloat() * 6;
            add(p);
        }
    }

    private static void fountain(float x, float y) {
        int[] th = THEMES[theme];
        for (int side = -1; side <= 1; side += 2) {
            Particle p = new Particle(COIN, x + side * 85, y + 20, side * (0.5f + RANDOM.nextFloat()),
                    -4 - RANDOM.nextFloat() * 2.5f, 35, th[0], 2);
            p.gravity = 0.22f;
            add(p);
        }
    }

    private static void floatingText(float x, float y, String text, int color) {
        Particle p = new Particle(TEXT, x, y, 0, -0.7f, 40, color, 1);
        p.gravity = 0;
        p.drag = 1;
        p.text = text;
        add(p);
    }

    private static void confetti(int sw) {
        Particle p = new Particle(CONFETTI, RANDOM.nextFloat() * sw, -5, (RANDOM.nextFloat() - 0.5f) * 1.5f,
                1 + RANDOM.nextFloat() * 2, 120, hsv(RANDOM.nextFloat(), 0.8f, 1f), 2);
        p.gravity = 0.03f;
        p.drag = 0.99f;
        p.spin = RANDOM.nextFloat() * 10;
        add(p);
    }

    private static void sideFireworks(int sinceLand, float cx, float cy) {
        if (sinceLand == 12) {
            firework(cx - 130, cy, 35);
            firework(cx + 130, cy, 35);
            playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1f);
        }
        if (sinceLand == 26 && tier >= 5) {
            firework(cx - 70, cy + 50, 30);
            firework(cx + 70, cy + 50, 30);
            playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 1.1f);
        }
    }

    private static void firework(float x, float y, int count) {
        float hue = RANDOM.nextFloat();
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            float speed = 2.5f + RANDOM.nextFloat() * 1.5f;
            Particle p = new Particle(SPARK, x, y, (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed,
                    20 + RANDOM.nextInt(15), hsv(hue + RANDOM.nextFloat() * 0.1f, 0.9f, 1f), 2);
            p.gravity = 0.06f;
            p.drag = 0.93f;
            add(p);
        }
    }

    // ---------------------------------------------------------------- rain
    private static void tickRain(boolean holding, int sinceLand, int sw, int density) {
        if (!holding) return;
        float ramp = Mth.clamp(sinceLand / 20f, 0.3f, 1f);
        int n = Math.max(1, (int) (density * ramp));
        for (int i = 0; i < n; i++) {
            int kind = variant == 0 ? BILL : variant == 1 ? COIN : GEM;
            int c = variant == 0 ? 0x2E9E48 : variant == 1 ? 0xFFD700 : 0x5FF3FF;
            Particle p = new Particle(kind, RANDOM.nextFloat() * sw, -6, (RANDOM.nextFloat() - 0.5f),
                    1.5f + RANDOM.nextFloat() * 2f, 400, c, 2);
            p.gravity = 0.02f;
            p.drag = 0.99f;
            p.spin = RANDOM.nextFloat() * 6;
            add(p);
        }
        if (RANDOM.nextInt(12) == 0) {
            Particle p = new Particle(TEXT, RANDOM.nextFloat() * sw, -8, 0, 1.2f, 400, 0x55FF55, 1);
            p.gravity = 0.02f;
            p.drag = 1;
            p.text = "$";
            add(p);
        }
        if (sinceLand % 6 == 0) {
            playSound(variant == 2 ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.EXPERIENCE_ORB_PICKUP,
                    1.1f + RANDOM.nextFloat() * 0.8f);
        }
    }

    // =====================================================================
    //  TORNADO (tier 6)
    // =====================================================================
    private static final float[][] TORNADO = makeTornado(750);

    private static float tornadoAxisX(float t, float h, int sw) {
        return sw / 2f + Mth.sin(t * 0.018f) * sw * 0.3f + Mth.sin(t * 0.07f + h * 4) * h * 20;
    }

    private static void tickTornado(boolean holding, int sinceLand, int sw, int sh) {
        if (sinceLand == 0 || (holding && sinceLand % 70 == 0)) playSound(SoundEvents.ELYTRA_FLYING, 0.7f);
        if (!holding) return;

        float ground = sh - 6;
        float height = sh * 0.8f;
        float base = Math.min(sw, sh);

        // debris flung out of the top of the funnel
        for (int i = 0; i < 3; i++) {
            float h = 0.75f + RANDOM.nextFloat() * 0.25f;
            float rad = 4 + h * h * base * 0.3f;
            float dir = RANDOM.nextBoolean() ? 1 : -1;
            float x = tornadoAxisX(age, h, sw) + dir * rad;
            float y = ground - h * height;
            int kind;
            int c;
            if (variant == 1) {
                kind = EMBER;
                c = RANDOM.nextBoolean() ? 0xFF8A1A : 0xFFD24A;
            } else if (variant == 2) {
                kind = RANDOM.nextBoolean() ? BILL : COIN;
                c = kind == BILL ? 0x2E9E48 : 0xFFD700;
            } else {
                kind = RANDOM.nextInt(4) == 0 ? COIN : SPARK;
                c = kind == COIN ? THEMES[theme][0] : 0xA8ADB5;
            }
            Particle p = new Particle(kind, x, y, dir * (2 + RANDOM.nextFloat() * 3), -1 + RANDOM.nextFloat() * 2,
                    60 + RANDOM.nextInt(30), c, 2);
            p.gravity = 0.12f;
            p.drag = 0.98f;
            p.spin = RANDOM.nextFloat() * 6;
            add(p);
        }

        if (variant == 0 && sinceLand % 28 == 5) {
            float bx = 20 + RANDOM.nextFloat() * (sw - 40);
            BOLTS.add(new Bolt(bx, 0, bx + (RANDOM.nextFloat() - 0.5f) * 60, ground, 0xCFE3FF));
            playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8f + RANDOM.nextFloat() * 0.4f);
        }
        if (variant == 1 && sinceLand % 16 == 0) {
            playSound(RANDOM.nextBoolean() ? SoundEvents.BLAZE_SHOOT : SoundEvents.FIRECHARGE_USE, 0.7f);
        }
        if (variant == 2 && sinceLand % 6 == 0) {
            playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f + RANDOM.nextFloat());
        }
    }

    private static void renderTornado(GuiGraphicsExtractor g, float t, float sinceLand, float alpha, int sw, int sh) {
        if (sinceLand < 0) return;
        float strength = Mth.clamp(sinceLand / 25f, 0, 1) * alpha;
        if (strength < 0.02f) return;

        int sky = variant == 0 ? 0x1A1D24 : variant == 1 ? 0x3A0A00 : 0x0A2A12;
        g.fill(0, 0, sw, sh, argb((int) ((variant == 0 ? 120 : 60) * strength), sky));

        float ground = sh - 6;
        float height = sh * 0.8f * (0.3f + 0.7f * strength);
        float base = Math.min(sw, sh);

        // dust cloud at the bottom
        int dust = variant == 1 ? 0xFF6A00 : variant == 2 ? 0x3FBF5A : 0x9A8F80;
        float bx = tornadoAxisX(t, 0, sw);
        fillCircle(g, bx, ground, base * 0.24f * strength, 0.25f, argb((int) (45 * strength), dust));
        fillCircle(g, bx, ground, base * 0.14f * strength, 0.3f, argb((int) (70 * strength), dust));

        // funnel: back half first, then front half
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < TORNADO.length; i++) {
                float[] p = TORNADO[i];
                float h = p[0];
                float ang = p[1] + t * 0.28f * p[2] * (1.7f - h);
                float sin = Mth.sin(ang);
                if ((sin >= 0) != (pass == 1)) continue;
                float rad = (4 + h * h * base * 0.3f) * strength;
                int px = (int) (tornadoAxisX(t, h, sw) + Mth.cos(ang) * rad);
                int py = (int) (ground - h * height + sin * rad * 0.18f);
                float shade = pass == 1 ? 1f : 0.5f;
                int a = (int) (255 * strength * shade * (0.5f + 0.5f * p[3]));

                if (variant == 1) {
                    int c = h < 0.25f ? 0xFFF2A0 : h < 0.55f ? 0xFFA22A : h < 0.8f ? 0xFF5A14 : 0xC0200A;
                    int s = p[3] > 0.7f ? 2 : 1;
                    g.fill(px, py, px + s, py + s, argb(a, c));
                } else if (variant == 2) {
                    if (i % 3 == 0) {
                        g.fill(px - 2, py - 1, px + 2, py + 1, argb(a, 0x2E9E48));
                    } else if (i % 3 == 1) {
                        g.fill(px - 1, py - 1, px + 1, py + 1, argb(a, 0xFFD700));
                    } else {
                        g.fill(px, py, px + 1, py + 1, argb(a, 0xC9CED6));
                    }
                } else {
                    int c = p[3] > 0.66f ? 0xC9CED6 : p[3] > 0.33f ? 0x8A8F99 : 0x5B5F66;
                    g.fill(px, py, px + 2, py + 2, argb(a, c));
                }
            }
        }
    }

    // =====================================================================
    //  LEGENDARY / GOD TIER (tiers 7-8)
    // =====================================================================
    private static void tickLegendary(boolean holding, int sinceLand, int sw, int sh, int mode) {
        if (!holding) return;
        boolean god = tier == 8;

        if (mode == 0) {                                   // fireworks everywhere
            if (sinceLand % (god ? 6 : 8) == 0) {
                firework(30 + RANDOM.nextFloat() * (sw - 60), 30 + RANDOM.nextFloat() * (sh * 0.6f), 45 + tier * 5);
                playSound(RANDOM.nextBoolean() ? SoundEvents.FIREWORK_ROCKET_BLAST : SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
                        0.8f + RANDOM.nextFloat() * 0.4f);
                if (RANDOM.nextInt(3) == 0) playSound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1f);
            }
        } else if (mode == 1) {                            // meteor shower
            if (sinceLand % (god ? 5 : 3) == 0) {
                float dir = RANDOM.nextBoolean() ? 1 : -1;
                float x = dir > 0 ? RANDOM.nextFloat() * sw * 0.6f : sw * 0.4f + RANDOM.nextFloat() * sw * 0.6f;
                Particle p = new Particle(METEOR, x, -10, dir * (3 + RANDOM.nextFloat() * 2.5f), 4.5f + RANDOM.nextFloat() * 2.5f,
                        200, 0xFFA040, 3);
                p.gravity = 0.04f;
                p.drag = 1f;
                add(p);
                if (RANDOM.nextInt(4) == 0) playSound(SoundEvents.FIRECHARGE_USE, 0.6f + RANDOM.nextFloat() * 0.3f);
            }
        } else {                                           // lightning storm
            if (sinceLand % (god ? 12 : 7) == 0) {
                float x = 20 + RANDOM.nextFloat() * (sw - 40);
                float endX = x + (RANDOM.nextFloat() - 0.5f) * 80;
                BOLTS.add(new Bolt(x, 0, endX, sh - 4, RANDOM.nextBoolean() ? 0xCFE3FF : 0xE6C8FF));
                for (int i = 0; i < 18; i++) {
                    Particle p = new Particle(SPARK, endX, sh - 6, (RANDOM.nextFloat() - 0.5f) * 6, -RANDOM.nextFloat() * 5,
                            15 + RANDOM.nextInt(10), 0xE8F0FF, 2);
                    p.gravity = 0.25f;
                    add(p);
                }
                playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7f + RANDOM.nextFloat() * 0.5f);
                if (RANDOM.nextBoolean()) playSound(SoundEvents.LIGHTNING_BOLT_IMPACT, 1f);
            }
        }
    }

    // =====================================================================
    //  GALAXY COLLISION (tier 9)
    // =====================================================================
    private static final float[][] MILKY_WAY = makeGalaxy(520, 2, 11);
    private static final float[][] OTHER_GALAXY = makeGalaxy(380, 3, 23);
    private static final float[][] MINI_GALAXY = makeGalaxy(110, 2, 5);
    private static final float[][] BG_STARS = makeDiskPoints(170, 7, false);
    private static final int[][] OTHER_COLORS = {{0xFFE0F0, 0xFF7AC8}, {0xE0FFE8, 0x6BFF9E}, {0xFFF0D8, 0xFFA14A}};

    private static int galaxyMergeTick() {
        return (int) (holdTicks * 0.55f);
    }

    private static void tickGalaxy(int sinceLand, int sw, int sh) {
        int merge = galaxyMergeTick();
        if (sinceLand == 0) playSound(SoundEvents.BEACON_ACTIVATE, 0.7f);
        if (sinceLand > 0 && sinceLand < merge && sinceLand % 25 == 0) {
            playSound(SoundEvents.BEACON_AMBIENT, 0.6f + sinceLand / (float) merge);
        }
        if (sinceLand == merge) {
            starBurst(sw / 2f, sh * 0.58f, 260, new int[]{OTHER_COLORS[variant][1], 0xCFE3FF, 0xFFFFFF});
            playSound(SoundEvents.END_PORTAL_SPAWN, 0.8f);
            playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f);
            playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f);
        }
    }

    private static void renderGalaxies(GuiGraphicsExtractor g, float t, float sinceLand, float alpha, int sw, int sh) {
        if (sinceLand < 0) return;
        float fadeIn = Mth.clamp(sinceLand / 15f, 0, 1) * alpha;
        if (fadeIn <= 0.01f) return;

        g.fill(0, 0, sw, sh, argb((int) (225 * fadeIn), 0x05030F));
        drawBackgroundStars(g, t, fadeIn, sw, sh);

        int[] oc = OTHER_COLORS[variant];
        float merge = galaxyMergeTick();
        float p = Mth.clamp(sinceLand / merge, 0, 1);
        float fall = p * p;
        float base = Math.min(sw, sh);
        float meetX = sw / 2f, meetY = sh * 0.58f;
        float rot = t * 0.025f;

        if (p < 1) {
            float ax = Mth.lerp(fall, -0.3f * sw, meetX - base * 0.04f);
            float ay = Mth.lerp(fall, sh * 0.85f, meetY + base * 0.02f);
            float bx = Mth.lerp(fall, 1.3f * sw, meetX + base * 0.04f);
            float by = Mth.lerp(fall, sh * 0.25f, meetY - base * 0.02f);
            float dx = bx - ax, dy = by - ay;
            float dist = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            float tidal = (float) Math.pow(Mth.clamp(1 - dist / (sw * 0.9f), 0, 1), 2) * 0.9f;

            drawGalaxy(g, bx, by, base * 0.22f, -rot * 1.3f + 1.2f, 0.55f, OTHER_GALAXY, oc[0], oc[1],
                    -dx / dist, -dy / dist, tidal, fadeIn);
            drawGalaxy(g, ax, ay, base * 0.30f, rot, 0.42f, MILKY_WAY, 0xFFF1C9, 0x9FC3FF,
                    dx / dist, dy / dist, tidal, fadeIn);
        } else {
            float sinceMerge = sinceLand - merge;
            float grow = 1f + Math.min(0.5f, sinceMerge * 0.006f);
            float chaos = Math.max(0, 0.6f - sinceMerge * 0.006f);
            drawGalaxy(g, meetX, meetY, base * 0.30f * grow, rot * 1.6f, 0.45f, OTHER_GALAXY, oc[0], oc[1],
                    1, -0.3f, chaos, fadeIn);
            drawGalaxy(g, meetX, meetY, base * 0.34f * grow, rot * 1.2f + 2f, 0.40f, MILKY_WAY, 0xFFF6DA, 0xB8CCFF,
                    -1, 0.3f, chaos, fadeIn);
            shockRings(g, meetX, meetY, sinceMerge, fadeIn, new int[]{0xFFFFFF, oc[1]});
            if (sinceMerge < 12) g.fill(0, 0, sw, sh, argb((int) ((1 - sinceMerge / 12f) * 235), 0xFFFFFF));
        }
    }

    private static void drawGalaxy(GuiGraphicsExtractor g, float cx, float cy, float size, float rot, float tilt,
                                   float[][] stars, int coreColor, int armColor,
                                   float pullX, float pullY, float tidal, float alpha) {
        fillCircle(g, cx, cy, size * 0.22f, tilt, argb((int) (35 * alpha), coreColor));
        fillCircle(g, cx, cy, size * 0.13f, tilt, argb((int) (70 * alpha), coreColor));
        fillCircle(g, cx, cy, size * 0.06f, tilt, argb((int) (170 * alpha), 0xFFFFFF));

        for (float[] s : stars) {
            float r = s[0];
            float ang = s[1] + rot * (1.5f - r);
            float x = Mth.cos(ang) * r * size;
            float y = Mth.sin(ang) * r * size * tilt;
            float stretch = r * r * tidal * size * 1.4f;
            x += pullX * stretch;
            y += pullY * stretch;
            int c = r < 0.18f ? coreColor : (s[2] > 0.92f ? 0xFFFFFF : armColor);
            int a = (int) (alpha * 255 * (0.35f + 0.65f * s[2]));
            int px = (int) (cx + x), py = (int) (cy + y);
            int dot = s[2] > 0.85f ? 2 : 1;
            g.fill(px, py, px + dot, py + dot, argb(a, c));
        }
    }

    private static void drawBackgroundStars(GuiGraphicsExtractor g, float t, float alpha, int sw, int sh) {
        for (float[] s : BG_STARS) {
            float tw = 0.5f + 0.5f * Mth.sin(t * 0.15f + s[2] * 20);
            int px = (int) ((s[0] * 0.5f + 0.5f) * sw), py = (int) ((s[1] * 0.5f + 0.5f) * sh);
            g.fill(px, py, px + 1, py + 1, argb((int) (alpha * 255 * tw), 0xFFFFFF));
        }
    }

    // =====================================================================
    //  BLACK HOLE (tier 10, also used by tier 11)
    // =====================================================================
    private static boolean bhActive;
    private static float bhX, bhY, bhRadius, bhTarget = 1;
    private static int bhPalette;
    private static boolean bhJets;
    private static final float[][] DISK = makeAccretionDisk(420);
    private static final int[][] DISK_PALETTES = {
            {0xFFFBEA, 0xFFD27F, 0xFF8A2A, 0xC2340E},   // classic
            {0xF0FBFF, 0xA8DCFF, 0x4FA3FF, 0x2440C2},   // quasar blue
            {0xFFF0FF, 0xE2A8FF, 0xB05CFF, 0x5A1A9E},   // void purple
    };

    private static void tickBlackHole(int sinceLand, boolean holding, int sw, int sh) {
        float target = Math.min(sw, sh) * 0.11f;
        bhTarget = target;
        bhPalette = variant;
        bhJets = variant == 1;
        bhX = sw / 2f;
        bhY = sh * 0.6f;
        bhActive = true;
        int outStart = holdTicks;
        if (sinceLand < 0) {
            bhRadius = 0;
        } else if (sinceLand < 30) {
            bhRadius = target * easeOutBack(sinceLand / 30f);
        } else if (sinceLand < outStart) {
            bhRadius = target * (1f + 0.04f * Mth.sin(sinceLand * 0.2f));
        } else {
            float p = Math.min(1, (sinceLand - outStart) / (float) SLIDE_OUT_TICKS);
            bhRadius = target * (1 - easeInCubic(p));
        }

        if (sinceLand == 0) {
            playSound(SoundEvents.WARDEN_SONIC_BOOM, 0.6f);
            playSound(SoundEvents.WITHER_SPAWN, 0.5f);
        }
        if (holding && sinceLand % 20 == 0) playSound(SoundEvents.WARDEN_HEARTBEAT, 0.5f);
        if (sinceLand == outStart) playSound(SoundEvents.END_PORTAL_SPAWN, 1.4f);
        if (holding) spawnDebris(sw, sh, 4);
    }

    private static void spawnDebris(int sw, int sh, int count) {
        int[] pal = DISK_PALETTES[bhPalette];
        for (int i = 0; i < count; i++) {
            float ex, ey;
            switch (RANDOM.nextInt(4)) {
                case 0 -> { ex = RANDOM.nextFloat() * sw; ey = -4; }
                case 1 -> { ex = RANDOM.nextFloat() * sw; ey = sh + 4; }
                case 2 -> { ex = -4; ey = RANDOM.nextFloat() * sh; }
                default -> { ex = sw + 4; ey = RANDOM.nextFloat() * sh; }
            }
            float dx = bhX - ex, dy = bhY - ey;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            Particle p = new Particle(SPARK, ex, ey, -dy / d * 3f + dx / d * 0.5f, dx / d * 3f + dy / d * 0.5f,
                    200, pal[1 + RANDOM.nextInt(2)], 2);
            p.gravity = 0;
            add(p);
        }
    }

    private static void renderBlackHole(GuiGraphicsExtractor g, float t, int sw, int sh) {
        if (!bhActive || bhRadius < 1) return;
        float strength = Mth.clamp(bhRadius / bhTarget, 0, 1);
        float r = bhRadius;
        int[] pal = DISK_PALETTES[bhPalette];

        if (tier == 10) {
            g.fill(0, 0, sw, sh, argb((int) (190 * strength), 0x000000));
            drawBackgroundStars(g, t, strength * 0.8f, sw, sh);
        }

        fillCircle(g, bhX, bhY, r * 3.2f, 0.45f, argb((int) (18 * strength), pal[2]));
        fillCircle(g, bhX, bhY, r * 2.2f, 0.55f, argb((int) (30 * strength), pal[1]));

        drawDisk(g, t, r, strength, false, pal);

        // quasar jets shooting out of the poles
        if (bhJets) {
            for (int dir = -1; dir <= 1; dir += 2) {
                for (int i = 0; i < sh; i += 2) {
                    float f = i / (sh * 0.55f);
                    if (f > 1) break;
                    int w = (int) (r * 0.22f * (1 - f * 0.6f)) + 1;
                    int ja = (int) (210 * strength * (1 - f) * (0.7f + 0.3f * Mth.sin(t * 0.8f + i * 0.1f)));
                    int jy = (int) (bhY + dir * (r + i));
                    g.fill((int) bhX - w, jy, (int) bhX + w, jy + 2, argb(ja, (i / 2) % 3 == 0 ? pal[0] : pal[1]));
                }
            }
        }

        // photon ring
        int ringDots = 220;
        for (int i = 0; i < ringDots; i++) {
            double ang = i * Math.PI * 2 / ringDots;
            float rr = r * 1.18f;
            int px = (int) (bhX + Math.cos(ang) * rr);
            int py = (int) (bhY + Math.sin(ang) * rr);
            float bright = 0.6f + 0.4f * (float) Math.sin(ang * 3 + t * 0.2f);
            g.fill(px, py, px + 2, py + 2, argb((int) (230 * strength * bright), pal[1]));
        }

        fillCircle(g, bhX, bhY, r, 1f, 0xFF000000);   // event horizon

        drawDisk(g, t, r, strength, true, pal);
    }

    private static void drawDisk(GuiGraphicsExtractor g, float t, float r, float strength, boolean front, int[] pal) {
        for (float[] d : DISK) {
            float rf = d[0];
            float ang = d[1] + t * 0.12f / (float) Math.sqrt(rf);
            float sin = Mth.sin(ang);
            if ((sin >= 0) != front) continue;
            float x = Mth.cos(ang) * r * rf;
            float y = sin * r * rf * 0.26f;
            float heat = 1 - (rf - 1.5f) / 1.9f;
            int c = heat > 0.8f ? pal[0] : heat > 0.55f ? pal[1] : heat > 0.3f ? pal[2] : pal[3];
            float beam = 0.55f + 0.45f * Mth.cos(ang);
            int a = (int) (255 * strength * beam * (0.4f + 0.6f * d[2]));
            int px = (int) (bhX + x), py = (int) (bhY + y);
            int dot = d[2] > 0.7f ? 2 : 1;
            g.fill(px, py, px + dot, py + dot, argb(a, c));
        }
    }

    // =====================================================================
    //  MULTIVERSE COLLAPSE (tier 11)
    // =====================================================================
    private static final float[][] UNI_STARS = makeDiskPoints(220, 31, true);
    private static final float[][] MINI_POS = {{-0.45f, -0.2f}, {0.3f, -0.45f}, {0.05f, 0.15f}, {-0.25f, 0.5f}, {0.5f, 0.3f}};
    private static final int[][][] UNIVERSE_COLORS = {
            {{0x9FC3FF, 0x6BE4FF}, {0xFFC56B, 0xFF6B6B}},
            {{0x7BFFB0, 0x3FD6A0}, {0xD08CFF, 0xFF7AE0}},
            {{0xFFFFFF, 0xBFD8FF}, {0xFF9AD5, 0xFFD36B}},
    };
    private static final int[][] PLANET_TYPES = {
            // {main colour, highlight, ring colour (0 = no ring)}
            {0x2D6CDF, 0x5FD36B, 0},          // earth
            {0xC1440E, 0xE8784A, 0},          // mars
            {0xD8A866, 0xF1D3A0, 0},          // jupiter
            {0xE3C27A, 0xF8E6B5, 0xCDB27A},   // saturn
            {0xA8E6FF, 0xE8F8FF, 0},          // ice world
            {0xFF5A1F, 0xFFD24A, 0},          // lava world
            {0x9B59D0, 0xD4A8FF, 0xC9A0FF},   // purple gas giant
            {0x3FBF8A, 0xA8FFD8, 0},          // jungle world
    };

    private static int mvCollide() { return (int) (holdTicks * 0.30f); }
    private static int mvForm() { return mvCollide() + 25; }
    private static int mvCollapse() { return (int) (holdTicks * 0.86f); }
    private static int mvBang() { return (int) (holdTicks * 0.95f); }

    private static void tickMultiverse(int sinceLand, boolean holding, int sw, int sh) {
        int collide = mvCollide();
        float meetX = sw / 2f, meetY = sh * 0.58f;

        if (sinceLand == 0) {
            playSound(SoundEvents.BEACON_ACTIVATE, 0.5f);
            playSound(SoundEvents.END_PORTAL_SPAWN, 0.5f);
        }
        if (sinceLand > 0 && sinceLand < collide && sinceLand % 18 == 0) {
            playSound(SoundEvents.BEACON_AMBIENT, 0.5f + sinceLand / (float) collide);
        }

        // ---- THE COLLISION ----
        if (sinceLand == collide) {
            int[][] uc = UNIVERSE_COLORS[variant];
            starBurst(meetX, meetY, 420, new int[]{uc[0][0], uc[0][1], uc[1][0], uc[1][1], 0xFFFFFF});
            for (int i = 0; i < 12; i++) {
                double ang = RANDOM.nextDouble() * Math.PI * 2;
                float speed = 3 + RANDOM.nextFloat() * 4;
                float tang = 1 + RANDOM.nextFloat();
                float ox = (float) Math.cos(ang), oy = (float) Math.sin(ang);
                int[] type = PLANET_TYPES[RANDOM.nextInt(PLANET_TYPES.length)];
                Planet p = new Planet(meetX + ox * 10, meetY + oy * 10,
                        ox * speed - oy * tang, oy * speed * 0.7f + ox * tang,
                        4 + RANDOM.nextFloat() * 7, type);
                PLANETS.add(p);
            }
            playSound(SoundEvents.END_PORTAL_SPAWN, 0.6f);
            playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f);
            playSound(SoundEvents.ENDER_DRAGON_DEATH, 1.0f);
            playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f);
        }

        // ---- a black hole forms from the wreckage ----
        float target = Math.min(sw, sh) * 0.13f;
        bhTarget = target;
        bhPalette = variant;
        bhJets = false;
        bhX = meetX;
        bhY = meetY;
        int form = mvForm();
        if (sinceLand >= form && sinceLand < mvBang()) {
            bhActive = true;
            int f = sinceLand - form;
            if (f < 40) {
                bhRadius = target * easeOutBack(f / 40f);
            } else if (sinceLand < mvCollapse()) {
                bhRadius = target * (1 + 0.05f * Mth.sin(f * 0.2f));
            } else {
                float p = Mth.clamp((sinceLand - mvCollapse()) / (float) (mvBang() - mvCollapse()), 0, 1);
                bhRadius = target * (1 - easeInCubic(p));
            }
            if (f == 0) {
                playSound(SoundEvents.WARDEN_SONIC_BOOM, 0.5f);
                playSound(SoundEvents.WITHER_SPAWN, 0.4f);
            }
            if (f % 20 == 0) playSound(SoundEvents.WARDEN_HEARTBEAT, 0.45f);
            if (holding) spawnDebris(sw, sh, 3);
        } else {
            bhActive = false;
        }
        if (sinceLand == mvCollapse()) playSound(SoundEvents.END_PORTAL_SPAWN, 1.5f);

        // ---- BIG BANG ----
        if (sinceLand == mvBang()) {
            PLANETS.clear();
            int[] colors = new int[12];
            for (int i = 0; i < colors.length; i++) colors[i] = hsv(i / 12f, 0.7f, 1f);
            starBurst(meetX, meetY, 500, colors);
            playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7f);
            playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
            playSound(SoundEvents.TOTEM_USE, 1.0f);
        }

        tickPlanets();
    }

    private static void tickPlanets() {
        PLANETS.removeIf(p -> {
            p.prevX = p.x;
            p.prevY = p.y;
            p.x += p.vx;
            p.y += p.vy;
            if (bhActive && bhRadius > 1) {
                float dx = bhX - p.x, dy = bhY - p.y;
                float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                if (d < bhRadius * 0.9f) {
                    // EATEN - a puff of planet bits that also fall in
                    for (int i = 0; i < 25; i++) {
                        Particle bit = new Particle(SPARK, p.x, p.y, (RANDOM.nextFloat() - 0.5f) * 3,
                                (RANDOM.nextFloat() - 0.5f) * 3, 200, RANDOM.nextBoolean() ? p.type[0] : p.type[1], 2);
                        bit.gravity = 0;
                        add(bit);
                    }
                    playSound(SoundEvents.ENDERMAN_TELEPORT, 0.5f + RANDOM.nextFloat() * 0.4f);
                    return true;
                }
                float pull = Math.min(0.6f, 300f * bhRadius / (d * d));
                p.vx += dx / d * pull;
                p.vy += dy / d * pull;
                p.vx *= 0.99f;
                p.vy *= 0.99f;
            } else {
                p.vx *= 0.95f;
                p.vy *= 0.95f;
            }
            return false;
        });
    }

    private static void renderMultiverse(GuiGraphicsExtractor g, float t, float sinceLand, float alpha, int sw, int sh) {
        if (sinceLand < 0) return;
        float fadeIn = Mth.clamp(sinceLand / 15f, 0, 1) * alpha;
        if (fadeIn <= 0.01f) return;

        g.fill(0, 0, sw, sh, argb((int) (235 * fadeIn), 0x03020A));
        drawBackgroundStars(g, t, fadeIn, sw, sh);

        int[][] uc = UNIVERSE_COLORS[variant];
        float collide = mvCollide();
        float base = Math.min(sw, sh);
        float uniR = base * 0.30f;
        float meetX = sw / 2f, meetY = sh * 0.58f;

        if (sinceLand < collide) {
            // two universes drifting toward each other
            float p = sinceLand / collide;
            float fall = p * p;
            float ax = Mth.lerp(fall, -uniR * 1.2f, meetX - uniR * 0.55f);
            float bx = Mth.lerp(fall, sw + uniR * 1.2f, meetX + uniR * 0.55f);
            float ay = meetY + Mth.sin(t * 0.1f) * 4;
            float by = meetY + Mth.cos(t * 0.1f) * 4;
            drawUniverse(g, ax, ay, uniR, uc[0], t, fadeIn, 0);
            drawUniverse(g, bx, by, uniR, uc[1], t, fadeIn, 1);

            // energy arcs crackling between them as they get close
            if (p > 0.55f) {
                int arcs = 1 + (int) ((p - 0.55f) * 8);
                for (int i = 0; i < arcs; i++) {
                    float y0 = meetY + (RANDOM.nextFloat() - 0.5f) * uniR;
                    jagged(g, ax + uniR * 0.8f, y0, bx - uniR * 0.8f, y0 + (RANDOM.nextFloat() - 0.5f) * 20,
                            argb((int) (220 * fadeIn), RANDOM.nextBoolean() ? uc[0][1] : uc[1][1]));
                }
            }
        } else {
            float since = sinceLand - collide;
            float consume = Mth.clamp((sinceLand - mvForm()) / (float) (mvCollapse() - mvForm()), 0, 1);
            float size = uniR * (1.3f + since * 0.004f) * (1 - consume * 0.9f);
            float remain = fadeIn * (1 - consume * 0.7f);
            if (size > 4 && sinceLand < mvBang()) {
                drawUniverse(g, meetX, meetY, size, uc[0], t * 1.8f, remain * 0.8f, 0);
                drawUniverse(g, meetX, meetY, size * 0.9f, uc[1], -t * 1.5f, remain * 0.8f, 1);
            }

            shockRings(g, meetX, meetY, since, fadeIn, new int[]{0xFFFFFF, uc[0][1], uc[1][1]});
            if (since < 25) g.fill(0, 0, sw, sh, argb((int) ((1 - since / 25f) * 255), 0xFFFFFF));

            renderBlackHole(g, t, sw, sh);
            renderPlanets(g);

            float sinceBang = sinceLand - mvBang();
            if (sinceBang >= 0) {
                shockRings(g, meetX, meetY, sinceBang, fadeIn, new int[]{0xFFFFFF, 0xFFE08A, 0xFF8AE0});
                if (sinceBang < 30) g.fill(0, 0, sw, sh, argb((int) ((1 - sinceBang / 30f) * 255), 0xFFFFFF));
            }
        }
    }

    private static void drawUniverse(GuiGraphicsExtractor g, float cx, float cy, float r, int[] cols, float t,
                                     float alpha, int which) {
        if (alpha <= 0.01f) return;
        fillCircle(g, cx, cy, r * 1.1f, 1f, argb((int) (22 * alpha), cols[0]));
        fillCircle(g, cx, cy, r, 1f, argb((int) (90 * alpha), 0x0B0A22));
        fillCircle(g, cx, cy, r * 0.97f, 1f, argb((int) (28 * alpha), cols[1]));

        for (float[] s : UNI_STARS) {
            float tw = 0.4f + 0.6f * Mth.sin(t * 0.2f + s[2] * 30);
            int px = (int) (cx + s[0] * r), py = (int) (cy + s[1] * r);
            g.fill(px, py, px + 1, py + 1, argb((int) (255 * alpha * tw), 0xFFFFFF));
        }

        for (int i = 0; i < MINI_POS.length; i++) {
            float gx = cx + MINI_POS[i][0] * r, gy = cy + MINI_POS[i][1] * r;
            drawGalaxy(g, gx, gy, r * 0.2f, t * 0.04f + i * 1.3f + which, 0.5f, MINI_GALAXY,
                    0xFFF4D6, cols[i % 2], 0, 0, 0, alpha);
        }

        int dots = 180;
        for (int i = 0; i < dots; i++) {
            double ang = i * Math.PI * 2 / dots;
            float shimmer = 0.5f + 0.5f * (float) Math.sin(ang * 5 + t * 0.2f);
            int px = (int) (cx + Math.cos(ang) * r), py = (int) (cy + Math.sin(ang) * r);
            g.fill(px, py, px + 2, py + 2, argb((int) (220 * alpha * (0.4f + 0.6f * shimmer)),
                    shimmer > 0.5f ? cols[0] : cols[1]));
        }
    }

    private static void renderPlanets(GuiGraphicsExtractor g) {
        for (Planet p : PLANETS) {
            boolean ring = p.type[2] != 0;
            if (bhActive && bhRadius > 1) {
                float dx = bhX - p.x, dy = bhY - p.y;
                float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                if (d < bhRadius * 4) {
                    // spaghettification: stretched into a trail toward the hole
                    float k = 1 - d / (bhRadius * 4);
                    for (int i = 5; i >= 1; i--) {
                        float f = i / 5f * k * 0.6f;
                        fillCircle(g, p.x + dx * f, p.y + dy * f, p.radius * (1 - i * 0.15f), 1f,
                                argb(200 - i * 30, p.type[0]));
                    }
                }
            }
            if (ring) drawPlanetRing(g, p, false);
            fillCircle(g, p.x, p.y, p.radius, 1f, argb(255, p.type[0]));
            fillCircle(g, p.x - p.radius * 0.3f, p.y - p.radius * 0.3f, p.radius * 0.45f, 1f, argb(140, p.type[1]));
            if (ring) drawPlanetRing(g, p, true);
        }
    }

    private static void drawPlanetRing(GuiGraphicsExtractor g, Planet p, boolean front) {
        int dots = 60;
        for (int i = 0; i < dots; i++) {
            double ang = i * Math.PI * 2 / dots;
            float sin = (float) Math.sin(ang);
            if ((sin >= 0) != front) continue;
            int px = (int) (p.x + Math.cos(ang) * p.radius * 1.9f);
            int py = (int) (p.y + sin * p.radius * 0.5f);
            g.fill(px, py, px + 1, py + 1, argb(230, p.type[2]));
        }
    }

    private static final class Planet {
        float x, y, prevX, prevY, vx, vy;
        final float radius;
        final int[] type;

        Planet(float x, float y, float vx, float vy, float radius, int[] type) {
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.type = type;
        }
    }

    // =====================================================================
    //  Shared space helpers
    // =====================================================================
    private static void starBurst(float x, float y, int count, int[] colors) {
        for (int i = 0; i < count; i++) {
            double ang = RANDOM.nextDouble() * Math.PI * 2;
            float speed = 1f + RANDOM.nextFloat() * 9f;
            Particle p = new Particle(SPARK, x, y, (float) Math.cos(ang) * speed, (float) Math.sin(ang) * speed * 0.6f,
                    30 + RANDOM.nextInt(40), colors[RANDOM.nextInt(colors.length)], 2);
            p.gravity = 0;
            p.drag = 0.95f;
            add(p);
        }
    }

    private static void shockRings(GuiGraphicsExtractor g, float cx, float cy, float since, float alpha, int[] colors) {
        for (int ring = 0; ring < colors.length; ring++) {
            float rs = since - ring * 6;
            if (rs < 0 || rs > 40) continue;
            float radius = rs * (7 + ring * 2);
            int ra = (int) ((1 - rs / 40f) * 230 * alpha);
            int dots = 160;
            for (int i = 0; i < dots; i++) {
                double ang = i * Math.PI * 2 / dots;
                int px = (int) (cx + Math.cos(ang) * radius);
                int py = (int) (cy + Math.sin(ang) * radius * 0.6f);
                g.fill(px, py, px + 2, py + 2, argb(ra, colors[ring]));
            }
        }
    }

    // =====================================================================
    //  Lightning bolts
    // =====================================================================
    private static final class Bolt {
        final float[] xs, ys;
        int life;
        final int maxLife = 7;
        final int color;

        Bolt(float x0, float y0, float x1, float y1, int color) {
            int n = 14;
            xs = new float[n + 1];
            ys = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float f = i / (float) n;
                float jitter = (i == 0 || i == n) ? 0 : (RANDOM.nextFloat() - 0.5f) * 26;
                xs[i] = Mth.lerp(f, x0, x1) + jitter;
                ys[i] = Mth.lerp(f, y0, y1);
            }
            this.life = maxLife;
            this.color = color;
        }
    }

    private static void drawBolts(GuiGraphicsExtractor g) {
        for (Bolt b : BOLTS) {
            float f = b.life / (float) b.maxLife;
            for (int i = 0; i < b.xs.length - 1; i++) {
                line(g, b.xs[i] - 1, b.ys[i], b.xs[i + 1] - 1, b.ys[i + 1], 4, argb((int) (70 * f), b.color));
                line(g, b.xs[i], b.ys[i], b.xs[i + 1], b.ys[i + 1], 2, argb((int) (255 * f), 0xFFFFFF));
            }
        }
    }

    private static void jagged(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
        int n = 10;
        float px = x0, py = y0;
        for (int i = 1; i <= n; i++) {
            float f = i / (float) n;
            float nx = Mth.lerp(f, x0, x1);
            float ny = Mth.lerp(f, y0, y1) + (i == n ? 0 : (RANDOM.nextFloat() - 0.5f) * 16);
            line(g, px, py, nx, ny, 2, color);
            px = nx;
            py = ny;
        }
    }

    private static void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int size, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        int steps = (int) (Math.max(Math.abs(dx), Math.abs(dy)) / 2) + 1;
        for (int i = 0; i <= steps; i++) {
            float f = i / (float) steps;
            int x = (int) (x0 + dx * f), y = (int) (y0 + dy * f);
            g.fill(x, y, x + size, y + size, color);
        }
    }

    // =====================================================================
    //  Particles
    // =====================================================================
    private static void add(Particle p) {
        if (PARTICLES.size() + PENDING.size() < MAX_PARTICLES) PENDING.add(p);
    }

    private static void drawParticles(GuiGraphicsExtractor g, Font font, float partial) {
        for (Particle p : PARTICLES) p.draw(g, font, partial);
    }

    private static final class Particle {
        final int kind;
        float x, y, prevX, prevY, vx, vy;
        float gravity = 0.3f, drag = 0.96f, spin;
        int life;
        final int maxLife, color, size;
        String text;

        Particle(int kind, float x, float y, float vx, float vy, int life, int color, int size) {
            this.kind = kind;
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.vx = vx;
            this.vy = vy;
            this.life = this.maxLife = life;
            this.color = color;
            this.size = size;
        }

        boolean tick() {
            prevX = x;
            prevY = y;
            x += vx;
            y += vy;

            if (bhActive && bhRadius > 1) {
                float dx = bhX - x, dy = bhY - y;
                float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                if (d < bhRadius * 0.95f) return true;
                float pull = Math.min(2.5f, 900f * bhRadius / (d * d));
                vx += dx / d * pull;
                vy += dy / d * pull;
                vx *= 0.985f;
                vy *= 0.985f;
                return --life <= -400;
            }

            vy += gravity;
            vx *= drag;
            if (kind == CONFETTI || kind == BILL) {
                spin += 0.3f;
                vx += Mth.sin(spin) * 0.08f;
            }
            if (kind == METEOR && y > screenH - 6) {
                for (int i = 0; i < 12; i++) {
                    Particle s = new Particle(SPARK, x, screenH - 6, (RANDOM.nextFloat() - 0.5f) * 5,
                            -RANDOM.nextFloat() * 4, 15 + RANDOM.nextInt(10), RANDOM.nextBoolean() ? 0xFFA040 : 0xFFE08A, 2);
                    s.gravity = 0.25f;
                    add(s);
                }
                if (RANDOM.nextInt(3) == 0) playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 0.5f + RANDOM.nextFloat() * 0.2f);
                return true;
            }
            if (y > screenH + 20 && vy > 0) return true;   // fell off the screen
            return --life <= 0;
        }

        void draw(GuiGraphicsExtractor g, Font font, float partial) {
            int px = (int) Mth.lerp(partial, prevX, x);
            int py = (int) Mth.lerp(partial, prevY, y);
            float fade = bhActive ? 1f : Math.max(0f, Math.min(1f, life / (float) maxLife * 2.5f));
            int a = (int) (255 * fade);
            if (a < 4) return;

            switch (kind) {
                case CONFETTI -> {
                    int w = Math.max(1, (int) Math.abs(Mth.cos(spin) * 3));
                    g.fill(px - w, py - 2, px + w, py + 2, argb(a, color));
                }
                case BILL -> {
                    int w = Math.max(1, (int) Math.abs(Mth.cos(spin) * 4));
                    g.fill(px - w, py - 2, px + w, py + 2, argb(a, 0x2E9E48));
                    if (w > 1) g.fill(px - w + 1, py - 1, px + w - 1, py + 1, argb(a, 0x8BE39B));
                }
                case COIN -> {
                    g.fill(px - size, py - size, px + size, py + size, argb(a, darker(color)));
                    g.fill(px - size + 1, py - size + 1, px + size - 1, py + size - 1, argb(a, color));
                    g.fill(px - 1, py - 1, px, py, argb(a, 0xFFFFFF));
                }
                case GEM -> {
                    g.fill(px - 1, py - 3, px + 1, py - 1, argb(a, color));
                    g.fill(px - 3, py - 1, px + 3, py + 1, argb(a, color));
                    g.fill(px - 1, py + 1, px + 1, py + 3, argb(a, darker(color)));
                    g.fill(px - 1, py - 1, px, py, argb(a, 0xFFFFFF));
                }
                case STAR -> {
                    int s = (life % 6 < 3) ? 3 : 2;
                    g.fill(px - s, py, px + s + 1, py + 1, argb(a, color));
                    g.fill(px, py - s, px + 1, py + s + 1, argb(a, color));
                    g.fill(px, py, px + 1, py + 1, argb(a, 0xFFFFFF));
                }
                case TEXT -> g.centeredText(font, text, px, py, argb(a, color));
                case METEOR -> {
                    for (int k = 12; k >= 1; k--) {
                        int tx = (int) (px - vx * k * 0.6f), ty = (int) (py - vy * k * 0.6f);
                        int ta = (int) (a * (1 - k / 13f) * 0.8f);
                        int s = k < 4 ? 3 : k < 8 ? 2 : 1;
                        int c = k < 3 ? 0xFFF6C8 : k < 7 ? 0xFFA040 : 0xD2401A;
                        g.fill(tx, ty, tx + s, ty + s, argb(ta, c));
                    }
                    g.fill(px - 1, py - 1, px + 3, py + 3, argb(a, 0xFFFFFF));
                }
                case EMBER -> {
                    int c = (life % 4 < 2) ? color : 0xFFF2A0;
                    g.fill(px, py, px + 2, py + 2, argb(a, c));
                }
                default -> {
                    int s = (life % 4 < 2) ? size + 1 : size;
                    g.fill(px - s / 2, py - s / 2, px + s / 2 + 1, py + s / 2 + 1, argb(a, color));
                }
            }
        }
    }

    // =====================================================================
    //  Sounds
    // =====================================================================
    private static void playLandSound() {
        switch (tier) {
            case 0, 1 -> playSound(SoundEvents.PLAYER_LEVELUP, 1.2f);
            case 2 -> {
                playSound(SoundEvents.PLAYER_LEVELUP, 1.0f);
                playSound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f);
            }
            case 3 -> {
                playSound(SoundEvents.PLAYER_LEVELUP, 0.9f);
                playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f);
            }
            case 4 -> {
                playSound(SoundEvents.PLAYER_LEVELUP, 0.9f);
                playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 1.0f);
            }
            case 5, 6 -> {
                playSound(SoundEvents.TOTEM_USE, 1.0f);
                playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f);
            }
            case 7 -> {
                playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
                playSound(SoundEvents.TOTEM_USE, 1.0f);
            }
            case 8 -> {
                playSound(SoundEvents.ENDER_DRAGON_GROWL, 1.0f);
                playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
                playSound(SoundEvents.TOTEM_USE, 0.8f);
            }
            default -> playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.9f);
        }
    }

    private static void playSound(SoundEvent sound, float pitch) {
        if (!PayPopupConfig.get().playSound) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    // =====================================================================
    //  Builders (pre-made random shapes)
    // =====================================================================
    /** Spiral galaxy: {radius 0..1, angle, brightness}. */
    private static float[][] makeGalaxy(int count, int arms, long seed) {
        Random r = new Random(seed);
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            float rad;
            float ang;
            if (i < count / 6) {
                rad = (float) Math.abs(r.nextGaussian()) * 0.12f;
                ang = r.nextFloat() * (float) (Math.PI * 2);
            } else {
                rad = (float) Math.pow(r.nextFloat(), 0.7) * 0.95f + 0.05f;
                int arm = i % arms;
                ang = (float) (arm * Math.PI * 2 / arms) + rad * 4.2f + (float) r.nextGaussian() * 0.28f;
            }
            out[i][0] = Math.min(rad, 1.05f);
            out[i][1] = ang;
            out[i][2] = r.nextFloat();
        }
        return out;
    }

    /** Points {x, y, phase}; x/y in -1..1 (inside a circle if round). */
    private static float[][] makeDiskPoints(int count, long seed, boolean round) {
        Random r = new Random(seed);
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            float x, y;
            do {
                x = r.nextFloat() * 2 - 1;
                y = r.nextFloat() * 2 - 1;
            } while (round && x * x + y * y > 0.9f);
            out[i][0] = x;
            out[i][1] = y;
            out[i][2] = r.nextFloat();
        }
        return out;
    }

    /** Accretion disk: {radius factor 1.5..3.4, angle, brightness}. */
    private static float[][] makeAccretionDisk(int count) {
        Random r = new Random(99);
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            out[i][0] = 1.5f + (float) Math.pow(r.nextFloat(), 1.6) * 1.9f;
            out[i][1] = r.nextFloat() * (float) (Math.PI * 2);
            out[i][2] = r.nextFloat();
        }
        return out;
    }

    /** Tornado funnel: {height 0..1, angle, speed, brightness}. */
    private static float[][] makeTornado(int count) {
        Random r = new Random(4);
        float[][] out = new float[count][4];
        for (int i = 0; i < count; i++) {
            out[i][0] = (float) Math.pow(r.nextFloat(), 0.8);
            out[i][1] = r.nextFloat() * (float) (Math.PI * 2);
            out[i][2] = 0.8f + r.nextFloat() * 0.4f;
            out[i][3] = r.nextFloat();
        }
        return out;
    }

    // =====================================================================
    //  Helpers
    // =====================================================================
    private static float boxScale() {
        return PayPopupConfig.get().scale * (1f + Math.min(tier, 8) * 0.07f);
    }

    /** Main colour for this popup: theme colour, tornado colour, or a moving rainbow for 1B+. */
    private static int color(float t, float offset) {
        if (tier <= 5) return THEMES[theme][0];
        if (tier == 6) return variant == 0 ? 0xC9CED6 : variant == 1 ? 0xFF8A1A : 0x3FBF5A;
        float speed = tier >= 8 ? 0.04f : 0.02f;
        return hsv((t * speed + offset / 360f) % 1f, 0.85f, 1f);
    }

    private static void outline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    private static void fillCircle(GuiGraphicsExtractor g, float cx, float cy, float r, float squishY, int color) {
        if (r < 0.5f) return;
        int ry = Math.max(1, (int) (r * squishY));
        for (int dy = -ry; dy <= ry; dy++) {
            float n = dy / (float) ry;
            int half = (int) (r * Math.sqrt(Math.max(0, 1 - n * n)));
            g.fill((int) cx - half, (int) cy + dy, (int) cx + half, (int) cy + dy + 1, color);
        }
    }

    private static int argb(int alpha, int rgb) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int darker(int rgb) {
        int r = ((rgb >> 16) & 0xFF) * 7 / 10, gr = ((rgb >> 8) & 0xFF) * 7 / 10, b = (rgb & 0xFF) * 7 / 10;
        return (r << 16) | (gr << 8) | b;
    }

    private static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s), q = v * (1 - f * s), u = v * (1 - (1 - f) * s);
        float r, gr, b;
        switch (i % 6) {
            case 0 -> { r = v; gr = u; b = p; }
            case 1 -> { r = q; gr = v; b = p; }
            case 2 -> { r = p; gr = v; b = u; }
            case 3 -> { r = p; gr = q; b = v; }
            case 4 -> { r = u; gr = p; b = v; }
            default -> { r = v; gr = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (gr * 255) << 8) | (int) (b * 255);
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    private static float easeInCubic(float x) {
        return x * x * x;
    }

    private static float easeOutCubic(float x) {
        return 1 - (float) Math.pow(1 - x, 3);
    }
}
