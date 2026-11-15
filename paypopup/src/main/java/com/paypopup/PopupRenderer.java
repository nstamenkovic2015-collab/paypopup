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
 *
 * The amount also counts up from $0 like a slot machine.
 */
public final class PopupRenderer {

    // ---------------------------------------------------------------- tiers
    private static final String[] TITLES = {
            "PAYMENT RECEIVED", "NICE!", "BIG PAYMENT!", "HUGE!!",
            "INSANE!!!", "LEGENDARY!!!!", "GOD TIER PAYMENT"
    };
    private static final int[] TIER_COLORS = {
            0x55FF55, 0x55FFFF, 0xFFD700, 0xFF8C00, 0xFF55FF, -1, -1   // -1 = rainbow
    };
    private static final int[] COIN_COUNT   = {12, 20, 35, 50, 80, 120, 180};
    private static final float[] SHAKE      = {0, 0, 0, 2f, 4f, 7f, 12f};

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
        int fireworkEvery = tier == 6 ? 5 : 9;
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

        PARTICLES.removeIf(Particle::tick);

        if (age > SLIDE_IN_TICKS + holdTicks + SLIDE_OUT_TICKS) {
            current = null;
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

        // ---------- Screen flash (tier 4+) ----------
        if (tier >= 4 && sinceLand >= 0 && sinceLand < 8) {
            int flash = (int) ((1 - sinceLand / 8f) * (tier == 4 ? 120 : 180));
            g.fill(0, 0, sw, sh, argb(flash, 0xFFFFFF));
        }

        // ---------- Lightning strobe (tier 6) ----------
        if (tier == 6 && sinceLand > 0 && t < outStart && RANDOM.nextInt(40) == 0) {
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
        if (tier == 6 && t < outStart) shake = Math.max(shake, 2f);   // god tier never stops shaking
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
            float fade = Math.min(1f, life / (float) maxLife * 2.5f);
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
        float speed = tier == 6 ? 0.04f : 0.02f;
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
