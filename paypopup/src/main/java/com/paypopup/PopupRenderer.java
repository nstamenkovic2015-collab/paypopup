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
 * Draws the animated "PAYMENT RECEIVED" popup.
 *
 * The more money you get, the crazier it gets:
 *
 *   Tier 0  under $100K   PAYMENT RECEIVED   green, a few coins
 *   Tier 1  $100K+        NICE!              aqua, more coins
 *   Tier 2  $1M+          BIG PAYMENT!       gold, spinning light rays, sparkle sound
 *   Tier 3  $10M+         HUGE!!             orange, screen edges glow, small shake, firework
 *   Tier 4  $100M+        INSANE!!!          purple, white flash, confetti rain, totem sound
 *   Tier 5  $1B+          LEGENDARY!!!!      rainbow, fireworks all over the screen, big shake
 *   Tier 6  $10B+         GOD TIER PAYMENT   everything maxed + lightning strobe + dragon roar
 *   Tier 7  $25B+         GALACTIC PAYMENT   space takes over the screen, the Milky Way and another
 *                                            galaxy fly in, stretch each other and COLLIDE
 *   Tier 8  $50B+         BLACK HOLE PAYMENT a black hole opens in the middle of the screen,
 *                                            with a glowing accretion disk, and eats everything
 *
 * The amount also counts up from $0 like a slot machine.
 */
public final class PopupRenderer {

    // ---------------------------------------------------------------- tiers
    private static final String[] TITLES = {
            "PAYMENT RECEIVED", "NICE!", "BIG PAYMENT!", "HUGE!!",
            "INSANE!!!", "LEGENDARY!!!!", "GOD TIER PAYMENT",
            "GALACTIC PAYMENT", "BLACK HOLE PAYMENT"
    };
    private static final int[] TIER_COLORS = {
            0x55FF55, 0x55FFFF, 0xFFD700, 0xFF8C00, 0xFF55FF, -1, -1, -1, -1   // -1 = rainbow
    };
    private static final int[] COIN_COUNT   = {12, 20, 35, 50, 80, 120, 180, 220, 260};
    private static final float[] SHAKE      = {0, 0, 0, 2f, 4f, 7f, 12f, 14f, 18f};

    private static final int SLIDE_IN_TICKS = 10;
    private static final int SLIDE_OUT_TICKS = 8;
    private static final int COUNT_UP_TICKS = 20;

    // ---------------------------------------------------------------- state
    private static final Deque<PayParser.Payment> QUEUE = new ArrayDeque<>();
    private static final List<Particle> PARTICLES = new ArrayList<>();
    private static final Random RANDOM = new Random();

    private static PayParser.Payment current;
    private static int tier;
    private static int age;
    private static int holdTicks;
    private static float popupY;    // last drawn position, used to spawn coins at the box

    private PopupRenderer() {}

    public static void show(PayParser.Payment payment) {
        QUEUE.add(payment);
    }

    public static int tierOf(double amount) {
        if (amount >= 50_000_000_000d) return 8;
        if (amount >= 25_000_000_000d) return 7;
        if (amount >= 10_000_000_000d) return 6;
        if (amount >= 1_000_000_000d) return 5;
        if (amount >= 100_000_000d) return 4;
        if (amount >= 10_000_000d) return 3;
        if (amount >= 1_000_000d) return 2;
        if (amount >= 100_000d) return 1;
        return 0;
    }

    // =====================================================================
    //  TICK (20 times a second) - timing, sounds, spawning particles
    // =====================================================================
    public static void tick() {
        if (current == null) {
            if (QUEUE.isEmpty()) {
                bhActive = false;
                PARTICLES.removeIf(Particle::tick);
                return;
            }
            current = QUEUE.poll();
            tier = tierOf(current.amount());
            age = 0;
            // bigger payments stay on screen longer
            holdTicks = Math.round((PayPopupConfig.get().displaySeconds + tier * 0.6f) * 20);
            playIntroSound();
        }

        age++;
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float cx = sw / 2f;
        float cy = popupY + 26 * boxScale();
        boolean holding = age >= SLIDE_IN_TICKS && age < SLIDE_IN_TICKS + holdTicks;
        int sinceLand = age - SLIDE_IN_TICKS;

        // ---- The moment the box lands ----
        if (sinceLand == 0) {
            spawnCoinBurst(cx, cy, COIN_COUNT[tier]);
            playLandSound();
        }

        // ---- Second coin wave for tier 2+ ----
        if (tier >= 2 && sinceLand == 6) spawnCoinBurst(cx, cy, COIN_COUNT[tier] / 2);

        // ---- Coins keep trickling out for tier 3+ ----
        if (tier >= 3 && holding && age % 3 == 0) spawnCoinBurst(cx, cy, tier - 1);

        // ---- Confetti rain from the top for tier 4+ ----
        if (tier >= 4 && holding) {
            int pieces = tier == 4 ? 2 : tier == 5 ? 4 : 7;
            for (int i = 0; i < pieces; i++) spawnConfetti(sw);
        }

        // ---- Fireworks all over the screen for tier 5+ ----
        int fireworkEvery = tier >= 6 ? 5 : 9;
        if (tier >= 5 && holding && sinceLand % fireworkEvery == 0) {
            float fx = 30 + RANDOM.nextFloat() * (sw - 60);
            float fy = 30 + RANDOM.nextFloat() * (sh * 0.6f);
            spawnFirework(fx, fy, 40 + tier * 6);
            playSound(RANDOM.nextBoolean() ? SoundEvents.FIREWORK_ROCKET_BLAST : SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
                    0.8f + RANDOM.nextFloat() * 0.4f);
            if (RANDOM.nextInt(3) == 0) playSound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1f);
        }

        // ---- Tier 3/4: one big firework next to the box ----
        if ((tier == 3 || tier == 4) && sinceLand == 12) {
            spawnFirework(cx - 120, cy, 35);
            spawnFirework(cx + 120, cy, 35);
            playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1f);
        }

        // ---- Galaxy collision / black hole (tier 7+) ----
        tickCosmic(sinceLand, holding, sw, sh);

        PARTICLES.removeIf(Particle::tick);

        if (age > SLIDE_IN_TICKS + holdTicks + SLIDE_OUT_TICKS) {
            current = null;
            bhActive = false;
        }
    }

    // =====================================================================
    //  RENDER (every frame)
    // =====================================================================
    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        if (current == null) {
            drawParticles(g, partial);   // let leftover confetti finish falling
            return;
        }

        float t = age + partial;
        float sinceLand = t - SLIDE_IN_TICKS;
        float outStart = SLIDE_IN_TICKS + holdTicks;
        float scale = boxScale();
        int boxW = 180 + tier * 14;
        int boxH = 56 + tier * 4;

        // ---------- Position & fade ----------
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

        // ---------- Space scenes behind everything (tier 7+) ----------
        if (tier == 7) renderGalaxies(g, t, sinceLand, alpha, sw, sh);
        if (tier == 8) renderBlackHole(g, t, sw, sh);

        // ---------- Screen flash (tier 4+) ----------
        if (tier >= 4 && sinceLand >= 0 && sinceLand < 8) {
            int flash = (int) ((1 - sinceLand / 8f) * (tier == 4 ? 120 : 180));
            g.fill(0, 0, sw, sh, argb(flash, 0xFFFFFF));
        }

        // ---------- Lightning strobe (tier 6) ----------
        if (tier >= 6 && sinceLand > 0 && t < outStart && RANDOM.nextInt(40) == 0) {
            g.fill(0, 0, sw, sh, argb(140, 0xE0E8FF));
        }

        // ---------- Glowing screen edges (tier 3+) ----------
        if (tier >= 3 && a > 5) {
            int edgeColor = color(t, 0);
            float pulse = 0.6f + 0.4f * Mth.sin(t * 0.3f);
            int layers = 4 + tier * 2;
            for (int i = 0; i < layers; i++) {
                int ea = (int) ((1 - i / (float) layers) * 90 * pulse * alpha);
                int c = argb(ea, edgeColor);
                int o = i * 2;
                g.fill(o, o, sw - o, o + 2, c);                 // top
                g.fill(o, sh - o - 2, sw - o, sh - o, c);       // bottom
                g.fill(o, o + 2, o + 2, sh - o - 2, c);         // left
                g.fill(sw - o - 2, o + 2, sw - o, sh - o - 2, c); // right
            }
        }

        if (a < 5) {
            drawParticles(g, partial);
            return;
        }

        // ---------- Screen shake ----------
        float shake = 0;
        if (sinceLand >= 0 && sinceLand < 25) shake = SHAKE[tier] * (1 - sinceLand / 25f);
        if (tier >= 6 && t < outStart) shake = Math.max(shake, 2f);   // god tier never stops shaking
        if (tier == 7) {
            float sinceMerge = sinceLand - mergeTick();
            if (sinceMerge >= 0 && sinceMerge < 35) shake = Math.max(shake, 16f * (1 - sinceMerge / 35f));
        }
        if (tier == 8 && bhActive) shake = Math.max(shake, 1.5f + bhRadius * 0.04f);
        float sx = (RANDOM.nextFloat() - 0.5f) * shake * 2;
        float sy = (RANDOM.nextFloat() - 0.5f) * shake * 2;

        g.pose().pushMatrix();
        g.pose().translate(sw / 2f + sx, y + sy);
        g.pose().scale(scale, scale);

        // ---------- Spinning light rays behind the box (tier 2+) ----------
        if (tier >= 2) {
            int rays = 8 + tier * 2;
            int rayLen = 90 + tier * 25;
            g.pose().pushMatrix();
            g.pose().translate(0, boxH / 2f);
            for (int i = 0; i < rays; i++) {
                g.pose().pushMatrix();
                g.pose().rotate(t * 0.03f + (float) (i * Math.PI * 2 / rays));
                int rc = argb((int) (a * 0.35f), color(t, i * 20));
                g.fill(0, -2, rayLen, 2, rc);
                g.pose().popMatrix();
            }
            g.pose().popMatrix();
        }

        int left = -boxW / 2;
        int right = boxW / 2;
        int border = color(t, 0);

        // ---------- Box ----------
        int thick = 2 + tier / 2;
        g.fill(left - thick, -thick, right + thick, boxH + thick, argb(a, border));
        g.fill(left, 0, right, boxH, argb(Math.min(a, 235), 0x14141F));
        g.fill(left, 0, right, 3, argb(a, border));
        g.fill(left, boxH - 3, right, boxH, argb(a, border));

        // Shine sweeping across (faster for bigger tiers)
        float shine = ((t * (6 + tier * 2)) % (boxW + 60)) - 30;
        int sl = Mth.clamp(left + (int) shine, left, right);
        int sr = Mth.clamp(left + (int) shine + 10, left, right);
        g.fill(sl, 3, sr, boxH - 3, argb(a / 7, 0xFFFFFF));

        // ---------- Title (wobbles on high tiers) ----------
        float titleScale = 1f + (tier >= 4 ? 0.15f * Mth.sin(t * 0.4f) + 0.2f : 0);
        g.pose().pushMatrix();
        g.pose().translate(0, 10);
        g.pose().scale(titleScale, titleScale);
        g.centeredText(font, TITLES[tier], 0, -4, argb(a, tier >= 5 ? color(t, 40) : 0xFFD700));
        g.pose().popMatrix();

        // ---------- Amount: counts up, then "pops" ----------
        float countP = Mth.clamp(sinceLand / COUNT_UP_TICKS, 0, 1);
        double shown = current.amount() * easeOutCubic(countP);
        float pop = 1f;
        float sincePop = sinceLand - COUNT_UP_TICKS;
        if (sincePop >= 0 && sincePop < 8) pop = 1f + (0.5f + tier * 0.1f) * Mth.sin(sincePop / 8f * (float) Math.PI);
        float amountScale = (1.6f + tier * 0.15f) * pop;
        g.pose().pushMatrix();
        g.pose().translate(0, boxH / 2f + 2);
        g.pose().scale(amountScale, amountScale);
        int amountColor = tier >= 5 ? color(t, 120) : 0x55FF55;
        g.centeredText(font, PayParser.format(shown), 0, -4, argb(a, amountColor));
        g.pose().popMatrix();

        // ---------- Sender ----------
        g.centeredText(font, "from " + current.sender(), 0, boxH - 13, argb(a, 0xCCCCCC));

        g.pose().popMatrix();

        // ---------- Particles on top of everything ----------
        drawParticles(g, partial);
    }


    // =====================================================================
    //  SPACE SCENES (tier 7 galaxy collision, tier 8 black hole)
    // =====================================================================

    // Black hole state (read by particles so they get sucked in)
    private static boolean bhActive;
    private static float bhX, bhY, bhRadius;

    // Pre-made star positions so the galaxies look the same every frame
    private static final float[][] MILKY_WAY = makeGalaxy(520, 2, 11);
    private static final float[][] OTHER_GALAXY = makeGalaxy(380, 3, 23);
    private static final float[][] BG_STARS = makeBackgroundStars(170);
    private static final float[][] DISK = makeDisk(420);

    private static int mergeTick() {
        return (int) (holdTicks * 0.55f);
    }

    private static void tickCosmic(int sinceLand, boolean holding, int sw, int sh) {
        if (tier == 7) {
            if (sinceLand == 0) playSound(SoundEvents.BEACON_ACTIVATE, 0.7f);
            if (holding && sinceLand > 0 && sinceLand < mergeTick() && sinceLand % 25 == 0) {
                playSound(SoundEvents.BEACON_AMBIENT, 0.6f + sinceLand / (float) mergeTick());
            }
            if (sinceLand == mergeTick()) {
                // THE COLLISION
                float cx = sw / 2f, cy = sh * 0.58f;
                for (int i = 0; i < 260; i++) {
                    double ang = RANDOM.nextDouble() * Math.PI * 2;
                    float speed = 1f + RANDOM.nextFloat() * 9f;
                    int c = RANDOM.nextInt(3) == 0 ? 0xFFB3E6 : (RANDOM.nextBoolean() ? 0xCFE3FF : 0xFFFFFF);
                    Particle p = new Particle(cx, cy, (float) Math.cos(ang) * speed,
                            (float) Math.sin(ang) * speed * 0.6f, 30 + RANDOM.nextInt(40), c, 2);
                    p.gravity = 0;
                    p.drag = 0.95f;
                    p.sparkle = true;
                    PARTICLES.add(p);
                }
                playSound(SoundEvents.END_PORTAL_SPAWN, 0.8f);
                playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f);
                playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f);
            }
        }

        if (tier == 8) {
            float target = Math.min(sw, sh) * 0.11f;
            int outStart = holdTicks;
            bhX = sw / 2f;
            bhY = sh * 0.6f;
            bhActive = true;
            if (sinceLand < 0) {
                bhRadius = 0;
            } else if (sinceLand < 30) {
                bhRadius = target * easeOutBack(sinceLand / 30f);
            } else if (sinceLand < outStart) {
                bhRadius = target * (1f + 0.04f * Mth.sin(sinceLand * 0.2f));   // pulses
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

            // debris flying in from the edges and spiraling into the hole
            if (holding) {
                for (int i = 0; i < 4; i++) {
                    float ex, ey;
                    switch (RANDOM.nextInt(4)) {
                        case 0 -> { ex = RANDOM.nextFloat() * sw; ey = -4; }
                        case 1 -> { ex = RANDOM.nextFloat() * sw; ey = sh + 4; }
                        case 2 -> { ex = -4; ey = RANDOM.nextFloat() * sh; }
                        default -> { ex = sw + 4; ey = RANDOM.nextFloat() * sh; }
                    }
                    float dx = bhX - ex, dy = bhY - ey;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    // sideways velocity makes them orbit/spiral instead of falling straight in
                    Particle p = new Particle(ex, ey, -dy / d * 3f + dx / d * 0.5f, dx / d * 3f + dy / d * 0.5f,
                            200, hsv(0.05f + RANDOM.nextFloat() * 0.12f, 0.8f, 1f), 2);
                    p.gravity = 0;
                    p.sparkle = true;
                    PARTICLES.add(p);
                }
            }
        }
    }

    // ------------------------------------------------------------ galaxies
    private static void renderGalaxies(GuiGraphicsExtractor g, float t, float sinceLand, float alpha, int sw, int sh) {
        if (sinceLand < 0) return;
        float fadeIn = Mth.clamp(sinceLand / 15f, 0, 1) * alpha;
        if (fadeIn <= 0.01f) return;

        // deep space background + twinkling stars
        g.fill(0, 0, sw, sh, argb((int) (225 * fadeIn), 0x05030F));
        drawBackgroundStars(g, t, fadeIn, sw, sh);

        float merge = mergeTick();
        float p = Mth.clamp(sinceLand / merge, 0, 1);
        float fall = p * p;                       // speeds up as gravity takes over
        float base = Math.min(sw, sh);
        float meetX = sw / 2f, meetY = sh * 0.58f;
        float rot = t * 0.025f;

        if (p < 1) {
            // Milky Way from bottom-left, other galaxy from top-right
            float ax = Mth.lerp(fall, -0.3f * sw, meetX - base * 0.04f);
            float ay = Mth.lerp(fall, sh * 0.85f, meetY + base * 0.02f);
            float bx = Mth.lerp(fall, 1.3f * sw, meetX + base * 0.04f);
            float by = Mth.lerp(fall, sh * 0.25f, meetY - base * 0.02f);
            float dx = bx - ax, dy = by - ay;
            float dist = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            float tidal = (float) Math.pow(Mth.clamp(1 - dist / (sw * 0.9f), 0, 1), 2) * 0.9f;

            drawGalaxy(g, bx, by, base * 0.22f, -rot * 1.3f + 1.2f, 0.55f, OTHER_GALAXY,
                    0xFFE0F0, 0xFF7AC8, -dx / dist, -dy / dist, tidal, fadeIn);
            drawGalaxy(g, ax, ay, base * 0.30f, rot, 0.42f, MILKY_WAY,
                    0xFFF1C9, 0x9FC3FF, dx / dist, dy / dist, tidal, fadeIn);
        } else {
            // MERGED: both galaxies swirl together into one giant messy galaxy
            float sinceMerge = sinceLand - merge;
            float grow = 1f + Math.min(0.5f, sinceMerge * 0.006f);
            float chaos = Math.max(0, 0.6f - sinceMerge * 0.006f);
            drawGalaxy(g, meetX, meetY, base * 0.30f * grow, rot * 1.6f, 0.45f, OTHER_GALAXY,
                    0xFFE0F0, 0xFF7AC8, 1, -0.3f, chaos, fadeIn);
            drawGalaxy(g, meetX, meetY, base * 0.34f * grow, rot * 1.2f + 2f, 0.40f, MILKY_WAY,
                    0xFFF6DA, 0xB8CCFF, -1, 0.3f, chaos, fadeIn);

            // expanding shockwave rings
            for (int ring = 0; ring < 2; ring++) {
                float rs = sinceMerge - ring * 6;
                if (rs < 0 || rs > 40) continue;
                float radius = rs * (7 + ring * 2);
                int ra = (int) ((1 - rs / 40f) * 230 * fadeIn);
                int dots = 160;
                for (int i = 0; i < dots; i++) {
                    double ang = i * Math.PI * 2 / dots;
                    int px = (int) (meetX + Math.cos(ang) * radius);
                    int py = (int) (meetY + Math.sin(ang) * radius * 0.6f);
                    g.fill(px, py, px + 2, py + 2, argb(ra, ring == 0 ? 0xFFFFFF : 0xFFB3E6));
                }
            }

            // blinding flash at impact
            if (sinceMerge < 12) {
                g.fill(0, 0, sw, sh, argb((int) ((1 - sinceMerge / 12f) * 235), 0xFFFFFF));
            }
        }
    }

    private static void drawGalaxy(GuiGraphicsExtractor g, float cx, float cy, float size, float rot, float tilt,
                                   float[][] stars, int coreColor, int armColor,
                                   float pullX, float pullY, float tidal, float alpha) {
        // soft glowing core
        fillCircle(g, cx, cy, size * 0.22f, tilt, argb((int) (35 * alpha), coreColor));
        fillCircle(g, cx, cy, size * 0.13f, tilt, argb((int) (70 * alpha), coreColor));
        fillCircle(g, cx, cy, size * 0.06f, tilt, argb((int) (170 * alpha), 0xFFFFFF));

        for (float[] s : stars) {
            float r = s[0];
            // inner stars orbit faster than outer ones, which makes the arms wind up
            float ang = s[1] + rot * (1.5f - r);
            float x = (float) Math.cos(ang) * r * size;
            float y = (float) Math.sin(ang) * r * size * tilt;
            // tidal stretch: the other galaxy's gravity pulls the outer stars out into a tail
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
            int px = (int) (s[0] * sw), py = (int) (s[1] * sh);
            g.fill(px, py, px + 1, py + 1, argb((int) (alpha * 255 * tw), 0xFFFFFF));
        }
    }

    // ---------------------------------------------------------- black hole
    private static void renderBlackHole(GuiGraphicsExtractor g, float t, int sw, int sh) {
        if (!bhActive || bhRadius < 1) return;
        float target = Math.min(sw, sh) * 0.11f;
        float strength = Mth.clamp(bhRadius / target, 0, 1);
        float R = bhRadius;

        // the screen goes dark around it
        g.fill(0, 0, sw, sh, argb((int) (190 * strength), 0x000000));
        drawBackgroundStars(g, t, strength * 0.8f, sw, sh);

        // warm glow
        fillCircle(g, bhX, bhY, R * 3.2f, 0.45f, argb((int) (18 * strength), 0xFF7A1A));
        fillCircle(g, bhX, bhY, R * 2.2f, 0.55f, argb((int) (30 * strength), 0xFFA040));

        // back half of the accretion disk (behind the hole)
        drawDisk(g, t, R, strength, false);

        // photon ring: light bent all the way around the hole
        int ringDots = 220;
        for (int i = 0; i < ringDots; i++) {
            double ang = i * Math.PI * 2 / ringDots;
            float rr = R * 1.18f;
            int px = (int) (bhX + Math.cos(ang) * rr);
            int py = (int) (bhY + Math.sin(ang) * rr);
            float bright = 0.6f + 0.4f * (float) Math.sin(ang * 3 + t * 0.2f);
            g.fill(px, py, px + 2, py + 2, argb((int) (230 * strength * bright), 0xFFE2A8));
        }

        // event horizon: pure black
        fillCircle(g, bhX, bhY, R, 1f, 0xFF000000);

        // front half of the disk (passes in front of the hole)
        drawDisk(g, t, R, strength, true);
    }

    private static void drawDisk(GuiGraphicsExtractor g, float t, float R, float strength, boolean front) {
        for (float[] d : DISK) {
            float rf = d[0];                                     // 1.5 .. 3.4 times the hole radius
            float ang = d[1] + t * 0.12f / (float) Math.sqrt(rf); // inner parts spin faster
            float sin = (float) Math.sin(ang);
            if ((sin >= 0) != front) continue;
            float x = (float) Math.cos(ang) * R * rf;
            float y = sin * R * rf * 0.26f;
            float heat = 1 - (rf - 1.5f) / 1.9f;                  // hotter near the hole
            int c = heat > 0.8f ? 0xFFFBEA : heat > 0.55f ? 0xFFD27F : heat > 0.3f ? 0xFF8A2A : 0xC2340E;
            // the side spinning toward you looks brighter (doppler beaming)
            float beam = 0.55f + 0.45f * (float) Math.cos(ang);
            int a = (int) (255 * strength * beam * (0.4f + 0.6f * d[2]));
            int px = (int) (bhX + x), py = (int) (bhY + y);
            int dot = d[2] > 0.7f ? 2 : 1;
            g.fill(px, py, px + dot, py + dot, argb(a, c));
        }
    }

    // ------------------------------------------------------------ builders
    private static void fillCircle(GuiGraphicsExtractor g, float cx, float cy, float r, float squishY, int color) {
        int ry = Math.max(1, (int) (r * squishY));
        for (int dy = -ry; dy <= ry; dy++) {
            float n = dy / (float) ry;
            int half = (int) (r * Math.sqrt(Math.max(0, 1 - n * n)));
            g.fill((int) cx - half, (int) cy + dy, (int) cx + half, (int) cy + dy + 1, color);
        }
    }

    /** Spiral galaxy: {radius 0..1, angle, brightness}. */
    private static float[][] makeGalaxy(int count, int arms, long seed) {
        Random r = new Random(seed);
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            float rad;
            float ang;
            if (i < count / 6) {
                // dense bright core
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

    private static float[][] makeBackgroundStars(int count) {
        Random r = new Random(7);
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            out[i][0] = r.nextFloat();
            out[i][1] = r.nextFloat();
            out[i][2] = r.nextFloat();
        }
        return out;
    }

    /** Accretion disk particles: {radius factor, angle, brightness}. */
    private static float[][] makeDisk(int count) {
        Random r = new Random(99);
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            out[i][0] = 1.5f + (float) Math.pow(r.nextFloat(), 1.6) * 1.9f;
            out[i][1] = r.nextFloat() * (float) (Math.PI * 2);
            out[i][2] = r.nextFloat();
        }
        return out;
    }

    // =====================================================================
    //  Sounds
    // =====================================================================
    private static void playIntroSound() {
        playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f + tier * 0.1f);
    }

    private static void playLandSound() {
        switch (tier) {
            case 0, 1 -> playSound(SoundEvents.PLAYER_LEVELUP, 1.2f);
            case 2 -> {
                playSound(SoundEvents.PLAYER_LEVELUP, 1.0f);
                playSound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f);
            }
            case 3 -> {
                playSound(SoundEvents.PLAYER_LEVELUP, 0.9f);
                playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 1.0f);
            }
            case 4 -> {
                playSound(SoundEvents.TOTEM_USE, 1.0f);
                playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f);
            }
            case 5 -> {
                playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
                playSound(SoundEvents.TOTEM_USE, 1.0f);
            }
            default -> {
                playSound(SoundEvents.ENDER_DRAGON_GROWL, 1.0f);
                playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
                playSound(SoundEvents.TOTEM_USE, 0.8f);
            }
        }
    }

    private static void playSound(SoundEvent sound, float pitch) {
        if (!PayPopupConfig.get().playSound) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    // =====================================================================
    //  Particles
    // =====================================================================
    private static void spawnCoinBurst(float x, float y, int count) {
        for (int i = 0; i < count; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            float speed = 1.5f + RANDOM.nextFloat() * (3f + tier);
            Particle p = new Particle(x, y,
                    (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed - 2.5f,
                    25 + RANDOM.nextInt(20), 0xFFD700, 2 + (tier >= 4 ? 1 : 0));
            p.gravity = 0.3f;
            p.coin = true;
            PARTICLES.add(p);
        }
    }

    private static void spawnConfetti(int screenW) {
        Particle p = new Particle(RANDOM.nextFloat() * screenW, -5,
                (RANDOM.nextFloat() - 0.5f) * 1.5f, 1 + RANDOM.nextFloat() * 2,
                80 + RANDOM.nextInt(40), hsv(RANDOM.nextFloat(), 0.8f, 1f), 2);
        p.gravity = 0.03f;
        p.drag = 0.99f;
        p.confetti = true;
        p.spin = RANDOM.nextFloat() * 10;
        PARTICLES.add(p);
    }

    private static void spawnFirework(float x, float y, int count) {
        float hue = RANDOM.nextFloat();
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            float speed = 2.5f + RANDOM.nextFloat() * 1.5f;
            Particle p = new Particle(x, y,
                    (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed,
                    20 + RANDOM.nextInt(15), hsv(hue + RANDOM.nextFloat() * 0.1f, 0.9f, 1f), 2);
            p.gravity = 0.06f;
            p.drag = 0.93f;
            p.sparkle = true;
            PARTICLES.add(p);
        }
    }

    private static void drawParticles(GuiGraphicsExtractor g, float partial) {
        for (Particle p : PARTICLES) p.draw(g, partial);
    }

    private static final class Particle {
        float x, y, prevX, prevY, vx, vy;
        float gravity = 0.3f, drag = 0.96f, spin;
        int life;
        final int maxLife, color, size;
        boolean coin, confetti, sparkle;

        Particle(float x, float y, float vx, float vy, int life, int color, int size) {
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
                // the black hole pulls everything in
                float dx = bhX - x, dy = bhY - y;
                float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                if (d < bhRadius * 0.95f) return true;          // eaten!
                float pull = Math.min(2.5f, 900f * bhRadius / (d * d));
                vx += dx / d * pull;
                vy += dy / d * pull;
                vx *= 0.985f;
                vy *= 0.985f;
                return --life <= -200;                          // lives until it falls in
            }
            vy += gravity;
            vx *= drag;
            if (confetti) {
                spin += 0.3f;
                vx += Mth.sin(spin) * 0.08f;   // flutters side to side
            }
            return --life <= 0;
        }

        void draw(GuiGraphicsExtractor g, float partial) {
            int px = (int) Mth.lerp(partial, prevX, x);
            int py = (int) Mth.lerp(partial, prevY, y);
            float fade = bhActive ? 1f : Math.max(0f, Math.min(1f, life / (float) maxLife * 2.5f));
            int a = (int) (255 * fade);

            if (confetti) {
                // flips like a piece of paper
                int w = Math.max(1, (int) Math.abs(Mth.cos(spin) * 3));
                g.fill(px - w, py - 2, px + w, py + 2, argb(a, color));
            } else if (coin) {
                g.fill(px - size, py - size, px + size, py + size, argb(a, 0xC8A000));
                g.fill(px - size + 1, py - size + 1, px + size - 1, py + size - 1, argb(a, color));
                g.fill(px - 1, py - 1, px, py, argb(a, 0xFFFBD0));
            } else {
                int s = (sparkle && life % 4 < 2) ? size + 1 : size;
                g.fill(px - s / 2, py - s / 2, px + s / 2 + 1, py + s / 2 + 1, argb(a, color));
            }
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================
    private static float boxScale() {
        return PayPopupConfig.get().scale * (1f + tier * 0.07f);
    }

    /** Tier colour, or a moving rainbow for tier 5+. */
    private static int color(float t, float offset) {
        int c = TIER_COLORS[tier];
        if (c != -1) return c;
        float speed = tier >= 6 ? 0.04f : 0.02f;
        return hsv((t * speed + offset / 360f) % 1f, 0.85f, 1f);
    }

    private static int argb(int alpha, int rgb) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    /** Hue/saturation/value (0..1) to RGB. */
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
