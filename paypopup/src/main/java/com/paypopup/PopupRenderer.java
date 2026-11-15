package com.paypopup;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 10 TRILLION (10T) payment event.
 *
 * A 60-second full-screen show drawn only inside the game window (HUD layer):
 *  - intro flash + glitch
 *  - 6 different popup animations (slide, zoom, drop-spin, glitch-in, typewriter, heartbeat)
 *  - in-window screen shake, particles (coins, sparks, confetti, glyphs, glitch shards)
 *  - glitch effects (RGB split, slices, scanlines, noise blocks)
 *  - live 60 -> 0 countdown
 *  - finale: implosion -> big bang -> final card -> fade out
 *  - stops by itself after exactly 60 seconds (wall clock), even if the game lags
 *
 * It only uses Minecraft's own GUI drawing and UI sound APIs. No OS calls, no files,
 * no processes.
 *
 * Hooks (see README in the chat):
 *   PopupRenderer.show(...)       -> if (TenTrillionEvent.tryStart(payment)) return;
 *   END_CLIENT_TICK               -> TenTrillionEvent.tick();
 *   HudElementRegistry.addLast    -> TenTrillionEvent::render
 */
public final class TenTrillionEvent {

    // ------------------------------------------------------------------ config
    public static final double THRESHOLD = 10_000_000_000_000.0; // 10T
    private static final long DURATION_NANOS = 60_000_000_000L;   // 60 s
    private static final long NANOS_PER_TICK = 50_000_000L;       // 20 tps
    private static final int TOTAL_TICKS = 1200;
    private static final int INTRO_END = 60;          // 0-3 s
    private static final int POPUP_LEN = 150;         // 7.5 s each
    private static final int FINALE_START = 1000;     // last 10 s
    private static final int BANG_TICK = 1100;        // 55 s
    private static final int FADE_START = 1170;       // 58.5 s
    private static final int MAX_PARTICLES = 1400;

    // ------------------------------------------------------------------ particle kinds
    private static final int COIN = 0, SPARK = 1, CONFETTI = 2, GLYPH = 3, SHARD = 4;

    // ------------------------------------------------------------------ colours
    private static final int GOLD = 0xFFD54F, GOLD_DARK = 0xB8860B, WHITE = 0xFFFFFF;
    private static final int CYAN = 0x00F0FF, MAGENTA = 0xFF2BD6, RED = 0xFF3344, GREEN = 0x39FF88;
    private static final int[] PARTY = {GOLD, CYAN, MAGENTA, GREEN, 0xFF8A00, 0x8A7CFF, WHITE};

    // ------------------------------------------------------------------ state
    private static boolean active;
    private static PayParser.Payment payment;
    private static long startNanos;
    private static int age;            // virtual tick, derived from wall clock
    private static float shake;        // px
    private static int glitchTicks;
    private static float flash;        // 0..1 white flash
    private static int lastSecond = -1;
    private static float secondPulse;
    private static int extraPayments;  // more 10T payments during the show

    private static final List<P> PARTICLES = new ArrayList<>();
    private static final List<P> PENDING = new ArrayList<>();
    private static final List<Ring> RINGS = new ArrayList<>();
    private static final Random R = new Random();
    private static int screenW = 427, screenH = 240;

    private TenTrillionEvent() {}

    // =================================================================== public API

    /** Call from PopupRenderer.show(). Returns true if the 10T event took the payment. */
    public static boolean tryStart(PayParser.Payment p) {
        if (p == null || p.amount() < THRESHOLD) return false;
        if (active) {                       // already running: just celebrate more
            extraPayments++;
            PENDING.add(glyph(screenW / 2f, screenH * 0.75f, "+" + PayParser.format(p.amount()), GOLD));
            sound(SoundEvents.PLAYER_LEVELUP, 1.4f);
            return true;
        }
        payment = p;
        active = true;
        startNanos = System.nanoTime();
        age = 0;
        shake = 0;
        glitchTicks = 0;
        flash = 1f;
        lastSecond = -1;
        extraPayments = 0;
        PARTICLES.clear();
        PENDING.clear();
        RINGS.clear();
        updateScreenSize();
        onTick(0);
        return true;
    }

    public static boolean isActive() {
        return active;
    }

    /** Ends the event immediately and frees everything. */
    public static void stop() {
        active = false;
        payment = null;
        PARTICLES.clear();
        PENDING.clear();
        RINGS.clear();
        shake = 0;
        flash = 0;
        glitchTicks = 0;
    }

    /** Call every client tick. */
    public static void tick() {
        if (!active) return;
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed >= DURATION_NANOS) {           // hard 60 s stop
            stop();
            return;
        }
        updateScreenSize();

        // run every scheduled moment we passed (keeps sync with the countdown if the game lags)
        int target = (int) Math.min(TOTAL_TICKS - 1, elapsed / NANOS_PER_TICK);
        while (age < target) {
            age++;
            onTick(age);
        }

        // physics once per real tick
        PARTICLES.addAll(PENDING);
        PENDING.clear();
        boolean implode = age >= FINALE_START && age < BANG_TICK;
        float cx = screenW / 2f, cy = screenH / 2f;
        for (Iterator<P> it = PARTICLES.iterator(); it.hasNext(); ) {
            P p = it.next();
            if (implode) {
                float dx = cx - p.x, dy = cy - p.y;
                float d = Math.max(8f, (float) Math.sqrt(dx * dx + dy * dy));
                float pull = 0.9f * (age - FINALE_START) / (float) (BANG_TICK - FINALE_START) + 0.15f;
                p.vx += dx / d * pull - dy / d * 0.35f;  // pull + swirl
                p.vy += dy / d * pull + dx / d * 0.35f;
                if (d < 10f) p.life = 0;
            }
            if (!p.tick()) it.remove();
        }
        while (PARTICLES.size() > MAX_PARTICLES) PARTICLES.remove(0);

        for (Iterator<Ring> it = RINGS.iterator(); it.hasNext(); ) {
            Ring r = it.next();
            r.r += r.vr;
            if (++r.age >= r.maxAge) it.remove();
        }

        shake *= 0.86f;
        flash *= 0.84f;
        secondPulse *= 0.8f;
        if (glitchTicks > 0) glitchTicks--;
    }

    /** HUD element: TenTrillionEvent::render */
    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        updateScreenSize();
        float pt = delta.getGameTimeDeltaPartialTick(false);
        float t = age + pt;
        int w = screenW, h = screenH;
        float cx = w / 2f, cy = h / 2f;

        float fadeOut = age >= FADE_START ? 1f - Mth.clamp((t - FADE_START) / (TOTAL_TICKS - FADE_START), 0f, 1f) : 1f;
        float fadeIn = Mth.clamp(t / 20f, 0f, 1f);
        float a = fadeIn * fadeOut;

        // ---- background (not shaken, so the edges never show the world)
        drawBackground(g, w, h, t, a);

        // ---- everything else shakes
        float sx = (R.nextFloat() - 0.5f) * 2f * shake;
        float sy = (R.nextFloat() - 0.5f) * 2f * shake;
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);

        drawRays(g, cx, cy, t, a);
        drawRings(g, pt, a);
        drawParticles(g, font, pt, a);
        drawCore(g, font, cx, cy, t, a);
        drawPopup(g, font, cx, cy, t, a);
        drawFinalCard(g, font, cx, cy, t, a);
        drawCountdown(g, font, cx, t, a);

        g.pose().popMatrix();

        // ---- full-screen glitch + flash on top
        if (glitchTicks > 0) drawGlitch(g, font, w, h, a);
        drawScanlines(g, w, h, a);
        if (flash > 0.02f) g.fill(0, 0, w, h, argb((int) (flash * 255 * fadeOut), WHITE));
    }

    // =================================================================== timeline

    private static void onTick(int t) {
        float cx = screenW / 2f, cy = screenH / 2f;

        // --- intro
        if (t == 0) {
            sound(SoundEvents.WARDEN_SONIC_BOOM, 0.8f);
            sound(SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0f);
            glitchTicks = 25;
            shake = 14f;
            ring(cx, cy, 0, 9f, 40, WHITE, 3);
        }
        if (t == 12) { sound(SoundEvents.END_PORTAL_SPAWN, 1.0f); burst(cx, cy, 160, 7f); }
        if (t == 30) { sound(SoundEvents.TOTEM_USE, 1.0f); coinFountain(cx, screenH, 80); shake = 8f; }
        if (t == 45) { sound(SoundEvents.WARDEN_EMERGE, 1.2f); glitchTicks = 12; }

        // --- popups
        if (t >= INTRO_END && t < FINALE_START && (t - INTRO_END) % POPUP_LEN == 0) {
            int idx = (t - INTRO_END) / POPUP_LEN;
            popupStart(idx % POPUPS.length, cx, cy);
        }

        // --- ambient show during the main phase
        if (t > INTRO_END && t < FINALE_START) {
            if (t % 4 == 0) coinRain(3);
            if (t % 6 == 0) confettiRain(4);
            if (t % 28 == 0) {
                float fx = screenW * (0.15f + R.nextFloat() * 0.7f);
                float fy = screenH * (0.15f + R.nextFloat() * 0.35f);
                firework(fx, fy, PARTY[R.nextInt(PARTY.length)]);
                sound(R.nextBoolean() ? SoundEvents.FIREWORK_ROCKET_BLAST : SoundEvents.FIREWORK_ROCKET_TWINKLE,
                        0.8f + R.nextFloat() * 0.4f);
            }
            if (t % 90 == 45) {                      // random glitch hit
                glitchTicks = 8 + R.nextInt(10);
                shake = Math.max(shake, 5f);
                sound(SoundEvents.ENDERMAN_TELEPORT, 0.5f + R.nextFloat() * 0.3f);
                for (int i = 0; i < 30; i++) PENDING.add(shard(R.nextFloat() * screenW, R.nextFloat() * screenH));
            }
            if (t % 200 == 100) {
                sound(SoundEvents.LIGHTNING_BOLT_IMPACT, 1.0f);
                ring(cx, cy, 10, 6f, 45, CYAN, 2);
                shake = Math.max(shake, 9f);
            }
        }

        // --- finale part 1: implosion
        if (t == FINALE_START) {
            sound(SoundEvents.BEACON_DEACTIVATE, 0.6f);
            sound(SoundEvents.WARDEN_ROAR, 0.8f);
            glitchTicks = 20;
        }
        if (t > FINALE_START && t < BANG_TICK) {
            float k = (t - FINALE_START) / (float) (BANG_TICK - FINALE_START);
            shake = Math.max(shake, 2f + k * 10f);
            if (t % 2 == 0) edgeInflow(6);
            if (t % 10 == 0) ring(cx, cy, Math.max(screenW, screenH) * 0.7f, -6f, 30, k > 0.6f ? RED : MAGENTA, 2);
            if (t % 20 == 0) sound(SoundEvents.WARDEN_HEARTBEAT, 0.8f + k * 0.8f);
            if (t % 25 == 0) glitchTicks = 6;
        }

        // --- finale part 2: big bang
        if (t == BANG_TICK) {
            flash = 1f;
            shake = 26f;
            glitchTicks = 18;
            sound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7f);
            sound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.6f);
            sound(SoundEvents.TOTEM_USE, 0.8f);
            for (int i = 0; i < 5; i++) ring(cx, cy, i * 6f, 7f + i * 3f, 50, PARTY[i], 3);
            burst(cx, cy, 420, 13f);
            coinExplosion(cx, cy, 160);
            for (int i = 0; i < 6; i++) firework(screenW * (0.1f + i * 0.16f), screenH * 0.3f, PARTY[i]);
        }
        if (t == BANG_TICK + 15) sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
        if (t > BANG_TICK && t < FADE_START && t % 5 == 0) confettiRain(12);
        if (t == BANG_TICK + 40) { sound(SoundEvents.PLAYER_LEVELUP, 0.7f); coinFountain(cx, screenH, 70); }
        if (t == FADE_START) sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f);
    }

    // =================================================================== popups

    private record Popup(String title, String sub, int style, int accent, SoundEvent sound) {}

    // style: 0 slide, 1 zoom, 2 drop-spin, 3 glitch-in, 4 typewriter, 5 heartbeat
    private static final Popup[] POPUPS = {
            new Popup("10 TRILLION RECEIVED", "from %s", 0, GOLD, SoundEvents.TOTEM_USE),
            new Popup("+%f", "deposited into your balance", 1, GREEN, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST),
            new Popup("BANK OVERLOAD", "the vault cannot contain it", 2, RED, SoundEvents.ANVIL_LAND),
            new Popup("WEALTH.EXE", "has stopped being normal", 3, CYAN, SoundEvents.GLASS_BREAK),
            new Popup("LEGENDARY PAYMENT", "%s just changed everything", 4, MAGENTA, SoundEvents.BEACON_ACTIVATE),
            new Popup("WELCOME TO THE 10T CLUB", "population: you", 5, GOLD, SoundEvents.ENDER_DRAGON_GROWL),
    };

    private static void popupStart(int idx, float cx, float cy) {
        Popup p = POPUPS[idx];
        sound(p.sound(), idx == 5 ? 1.2f : 1.0f);
        shake = Math.max(shake, 10f);
        ring(cx, cy, 20, 5f, 35, p.accent(), 2);
        burst(cx, cy, 90, 6f);
        if (p.style() == 3) glitchTicks = 20;
        if (p.style() == 1) coinExplosion(cx, cy, 60);
    }

    private static String fill(String s) {
        String sender = payment == null ? "???" : payment.sender();
        String full = payment == null ? "10,000,000,000,000" : String.format("%,.0f", payment.amount());
        return s.replace("%s", sender).replace("%f", full);
    }

    private static void drawPopup(GuiGraphicsExtractor g, Font font, float cx, float cy, float t, float a) {
        if (t < INTRO_END || t >= FINALE_START) return;
        int idx = (int) ((t - INTRO_END) / POPUP_LEN);
        float local = (t - INTRO_END) - idx * POPUP_LEN;
        Popup p = POPUPS[idx % POPUPS.length];
        String title = fill(p.title());
        String sub = fill(p.sub());

        float in = Mth.clamp(local / 18f, 0f, 1f);
        float out = Mth.clamp((POPUP_LEN - local) / 14f, 0f, 1f);
        float x = cx, y = cy, scale = 1f, rot = 0f, alpha = a;
        boolean rgbSplit = false;

        switch (p.style()) {
            case 0 -> { // slide in from left, out to right
                x = Mth.lerp(easeOutCubic(in), -screenW * 0.6f, cx);
                if (out < 1f) x = Mth.lerp(easeInCubic(1f - out), cx, screenW * 1.6f);
            }
            case 1 -> { // zoom pop
                scale = easeOutBack(in) * (out < 1f ? easeOutCubic(out) : 1f);
            }
            case 2 -> { // drop from top with bounce + spin
                y = Mth.lerp(bounce(in), -60f, cy);
                rot = (1f - easeOutCubic(in)) * -0.8f;
                if (out < 1f) { y = Mth.lerp(easeInCubic(1f - out), cy, screenH + 80f); rot = (1f - out) * 0.6f; }
            }
            case 3 -> { // glitch in
                if (local < 24) {
                    x += (R.nextFloat() - 0.5f) * 40f * (1f - local / 24f);
                    y += (R.nextFloat() - 0.5f) * 10f * (1f - local / 24f);
                    alpha *= R.nextFloat() < 0.3f ? 0.2f : 1f;
                }
                rgbSplit = true;
                alpha *= out;
            }
            case 4 -> { // typewriter
                int chars = Math.min(title.length(), (int) (local / 2.5f));
                title = title.substring(0, chars) + ((int) (t / 4) % 2 == 0 && chars < title.length() ? "_" : "");
                scale = 0.85f + 0.15f * easeOutCubic(in);
                alpha *= out;
            }
            case 5 -> { // heartbeat
                float beat = (float) Math.pow(Math.max(0, Mth.sin(t * 0.35f)), 8);
                scale = easeOutBack(in) * (1f + beat * 0.18f) * (out < 1f ? out : 1f);
                rgbSplit = beat > 0.5f;
            }
            default -> {}
        }
        if (glitchTicks > 0) rgbSplit = true;
        if (alpha <= 0.02f || scale <= 0.02f) return;

        float big = Math.min(3.2f, (screenW * 0.8f) / Math.max(1, font.width(title)));
        int boxW = (int) (Math.max(font.width(title) * big, font.width(sub) * 1.3f) + 40);
        int boxH = (int) (9 * big + 34);

        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().rotate(rot);
        g.pose().scale(scale, scale);

        // glow + box
        for (int i = 4; i >= 1; i--) {
            int pad = i * 4;
            g.fill(-boxW / 2 - pad, -boxH / 2 - pad, boxW / 2 + pad, boxH / 2 + pad, argb((int) (alpha * 28 / i), p.accent()));
        }
        g.fill(-boxW / 2, -boxH / 2, boxW / 2, boxH / 2, argb((int) (alpha * 215), 0x0B0618));
        int border = hsv((t * 0.01f) % 1f, 0.7f, 1f);
        outline(g, -boxW / 2, -boxH / 2, boxW, boxH, argb((int) (alpha * 255), idx % 2 == 0 ? p.accent() : border));
        outline(g, -boxW / 2 + 3, -boxH / 2 + 3, boxW - 6, boxH - 6, argb((int) (alpha * 120), p.accent()));

        float ty = -boxH / 2f + 10 + 4.5f * big;
        if (rgbSplit) {
            bigText(g, font, title, -2, ty, big, argb((int) (alpha * 200), RED));
            bigText(g, font, title, 2, ty, big, argb((int) (alpha * 200), CYAN));
        }
        bigText(g, font, title, 0, ty, big, argb((int) (alpha * 255), p.accent()));
        bigText(g, font, sub, 0, boxH / 2f - 12, 1.3f, argb((int) (alpha * 230), WHITE));

        g.pose().popMatrix();
    }

    // =================================================================== countdown

    private static void drawCountdown(GuiGraphicsExtractor g, Font font, float cx, float t, float a) {
        long elapsed = System.nanoTime() - startNanos;
        int secondsLeft = (int) Math.max(0, Math.ceil((DURATION_NANOS - elapsed) / 1e9));
        if (secondsLeft != lastSecond) {
            lastSecond = secondsLeft;
            secondPulse = 1f;
            if (secondsLeft <= 10 && secondsLeft > 0) {
                sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f + (10 - secondsLeft) * 0.12f);
                shake = Math.max(shake, 3f);
            }
        }
        boolean urgent = secondsLeft <= 10;
        int col = urgent ? RED : GOLD;
        float s = 2.6f + secondPulse * (urgent ? 1.2f : 0.5f);
        String txt = String.format("00:%02d", secondsLeft);

        int bw = 110, bh = 44, top = 8;
        g.fill((int) cx - bw / 2, top, (int) cx + bw / 2, top + bh, argb((int) (a * 170), 0x000000));
        outline(g, (int) cx - bw / 2, top, bw, bh, argb((int) (a * 255), col));
        bigText(g, font, "EVENT ENDS IN", cx, top + 7, 0.8f, argb((int) (a * 200), WHITE));
        if (urgent && glitchTicks > 0) bigText(g, font, txt, cx + 2, top + 24, s, argb((int) (a * 180), CYAN));
        bigText(g, font, txt, cx, top + 24, s, argb((int) (a * 255), col));

        // progress bar
        float prog = Mth.clamp(1f - elapsed / (float) DURATION_NANOS, 0f, 1f);
        int bx = (int) cx - bw / 2 + 6, by = top + bh - 6, bwInner = bw - 12;
        g.fill(bx, by, bx + bwInner, by + 3, argb((int) (a * 120), 0x333333));
        g.fill(bx, by, bx + (int) (bwInner * prog), by + 3, argb((int) (a * 255), col));
    }

    // =================================================================== finale visuals

    private static void drawCore(GuiGraphicsExtractor g, Font font, float cx, float cy, float t, float a) {
        if (t < FINALE_START || t >= BANG_TICK) return;
        float k = (t - FINALE_START) / (BANG_TICK - FINALE_START);
        float r = 6f + k * k * 46f + Mth.sin(t * 0.8f) * 2f * k;
        for (int i = 5; i >= 1; i--) fillCircle(g, cx, cy, r + i * 6f * k, argb((int) (a * 25 * k), MAGENTA));
        fillCircle(g, cx, cy, r, argb((int) (a * 255), 0x120018));
        fillCircle(g, cx, cy, r * 0.75f, argb((int) (a * 255 * k), hsv((t * 0.02f) % 1f, 0.6f, 1f)));
        fillCircle(g, cx, cy, r * 0.45f, argb((int) (a * 255), WHITE));
        bigText(g, font, "10T", cx, cy + 1, 0.8f + k * 2.2f, argb((int) (a * 255), 0x120018));
    }

    private static void drawFinalCard(GuiGraphicsExtractor g, Font font, float cx, float cy, float t, float a) {
        if (t < BANG_TICK + 6) return;
        float in = Mth.clamp((t - BANG_TICK - 6) / 22f, 0f, 1f);
        float s = easeOutBack(in);
        if (s <= 0.02f) return;
        String amount = payment == null ? "10,000,000,000,000" : String.format("%,.0f", payment.amount());
        String sender = payment == null ? "" : "from " + payment.sender();
        if (extraPayments > 0) sender += "  (+" + extraPayments + " more!)";

        float big = Math.min(3.4f, (screenW * 0.85f) / Math.max(1, font.width(amount)));
        int bw = (int) (font.width(amount) * big + 50), bh = (int) (9 * big + 70);

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(s, s);
        g.pose().rotate(Mth.sin(t * 0.05f) * 0.03f);

        g.fill(-bw / 2, -bh / 2, bw / 2, bh / 2, argb((int) (a * 225), 0x07040F));
        for (int i = 0; i < 3; i++) {
            int c = hsv(((t * 0.015f) + i * 0.33f) % 1f, 0.75f, 1f);
            outline(g, -bw / 2 - i * 3, -bh / 2 - i * 3, bw + i * 6, bh + i * 6, argb((int) (a * (255 - i * 70)), c));
        }
        bigText(g, font, "LEGENDARY PAYMENT", 0, -bh / 2f + 14, 1.6f, argb((int) (a * 255), GOLD));
        int gold = hsv(0.12f + Mth.sin(t * 0.2f) * 0.03f, 0.75f, 1f);
        bigText(g, font, amount, 2, 3, big, argb((int) (a * 160), GOLD_DARK));
        bigText(g, font, amount, 0, 1, big, argb((int) (a * 255), gold));
        bigText(g, font, sender, 0, bh / 2f - 14, 1.1f, argb((int) (a * 230), WHITE));
        g.pose().popMatrix();
    }

    // =================================================================== layers

    private static void drawBackground(GuiGraphicsExtractor g, int w, int h, float t, float a) {
        int bands = 24;
        float finale = t >= FINALE_START ? Mth.clamp((t - FINALE_START) / 80f, 0f, 1f) : 0f;
        for (int i = 0; i < bands; i++) {
            float f = i / (float) (bands - 1);
            float hue = (0.72f + f * 0.12f + Mth.sin(t * 0.01f + f * 3f) * 0.04f) % 1f;
            int c = hsv(hue, 0.85f, 0.18f + 0.12f * (1f - f) - finale * 0.12f);
            int y1 = (int) (h * i / (float) bands), y2 = (int) (h * (i + 1) / (float) bands) + 1;
            g.fill(0, y1, w, y2, argb((int) (a * 205), c));
        }
        // vignette
        for (int i = 0; i < 6; i++) {
            int pad = i * 6;
            int al = (int) (a * 18);
            g.fill(0, 0, w, pad + 6, argb(al, 0));
            g.fill(0, h - pad - 6, w, h, argb(al, 0));
        }
    }

    private static void drawRays(GuiGraphicsExtractor g, float cx, float cy, float t, float a) {
        if (t < 10 || (t >= FINALE_START && t < BANG_TICK)) return;
        int n = 18;
        float len = Math.max(screenW, screenH);
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(t * 0.012f);
        for (int i = 0; i < n; i++) {
            g.pose().pushMatrix();
            g.pose().rotate((float) (i * Math.PI * 2 / n));
            int c = i % 2 == 0 ? GOLD : MAGENTA;
            g.fill(0, -3, (int) len, 3, argb((int) (a * 22), c));
            g.fill(0, -1, (int) len, 1, argb((int) (a * 30), WHITE));
            g.pose().popMatrix();
        }
        g.pose().popMatrix();
    }

    private static void drawRings(GuiGraphicsExtractor g, float pt, float a) {
        for (Ring r : RINGS) {
            float rad = r.r + r.vr * pt;
            if (rad <= 1) continue;
            float life = 1f - r.age / (float) r.maxAge;
            int col = argb((int) (a * 255 * life), r.color);
            int seg = Math.max(24, (int) (rad * 0.6f));
            float px = r.x + rad, py = r.y;
            for (int i = 1; i <= seg; i++) {
                double ang = i * Math.PI * 2 / seg;
                float nx = r.x + (float) Math.cos(ang) * rad, ny = r.y + (float) Math.sin(ang) * rad;
                line(g, px, py, nx, ny, r.thick, col);
                px = nx; py = ny;
            }
        }
    }

    private static void drawParticles(GuiGraphicsExtractor g, Font font, float pt, float a) {
        for (P p : PARTICLES) p.draw(g, font, pt, a);
    }

    private static void drawGlitch(GuiGraphicsExtractor g, Font font, int w, int h, float a) {
        int slices = 6 + R.nextInt(8);
        for (int i = 0; i < slices; i++) {
            int y = R.nextInt(h), sh = 2 + R.nextInt(14), off = R.nextInt(60) - 30;
            int c = R.nextInt(3) == 0 ? RED : (R.nextBoolean() ? CYAN : MAGENTA);
            g.fill(off, y, w + off, y + sh, argb((int) (a * (40 + R.nextInt(70))), c));
        }
        for (int i = 0; i < 14; i++) {        // noise blocks
            int bx = R.nextInt(w), by = R.nextInt(h), bs = 3 + R.nextInt(18);
            g.fill(bx, by, bx + bs * 2, by + bs, argb((int) (a * 120), R.nextBoolean() ? WHITE : 0x000000));
        }
        if (R.nextInt(4) == 0) {               // corrupted text fragment
            String[] junk = {"0x10T", "$$$$$$", "OVERFLOW", "10^13", "ERR:RICH", "#####"};
            g.text(font, junk[R.nextInt(junk.length)], R.nextInt(Math.max(1, w - 40)), R.nextInt(h), argb((int) (a * 220), GREEN));
        }
    }

    private static void drawScanlines(GuiGraphicsExtractor g, int w, int h, float a) {
        int al = (int) (a * (glitchTicks > 0 ? 40 : 16));
        if (al <= 0) return;
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, argb(al, 0));
    }

    // =================================================================== spawners

    private static void burst(float x, float y, int n, float speed) {
        for (int i = 0; i < n; i++) {
            double ang = R.nextDouble() * Math.PI * 2;
            float sp = speed * (0.3f + R.nextFloat());
            P p = new P(SPARK, x, y, (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp,
                    25 + R.nextInt(30), PARTY[R.nextInt(PARTY.length)], 1 + R.nextInt(2));
            p.gravity = 0.06f;
            p.drag = 0.95f;
            PENDING.add(p);
        }
    }

    private static void firework(float x, float y, int color) {
        int n = 70;
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n;
            float sp = 2.5f + R.nextFloat() * 1.5f;
            P p = new P(SPARK, x, y, (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp, 35 + R.nextInt(20),
                    R.nextInt(4) == 0 ? WHITE : color, 2);
            p.gravity = 0.04f;
            p.drag = 0.96f;
            PENDING.add(p);
        }
    }

    private static void coinRain(int n) {
        for (int i = 0; i < n; i++) {
            P p = new P(COIN, R.nextFloat() * screenW, -10, (R.nextFloat() - 0.5f) * 1.2f, 1f + R.nextFloat() * 2f,
                    200, GOLD, 3 + R.nextInt(4));
            p.gravity = 0.05f;
            p.drag = 0.995f;
            p.vrot = 0.1f + R.nextFloat() * 0.3f;
            PENDING.add(p);
        }
    }

    private static void confettiRain(int n) {
        for (int i = 0; i < n; i++) {
            P p = new P(CONFETTI, R.nextFloat() * screenW, -8, (R.nextFloat() - 0.5f) * 2f, 0.8f + R.nextFloat() * 1.5f,
                    180, PARTY[R.nextInt(PARTY.length)], 2 + R.nextInt(3));
            p.gravity = 0.02f;
            p.drag = 0.99f;
            p.vrot = (R.nextFloat() - 0.5f) * 0.5f;
            PENDING.add(p);
        }
    }

    private static void coinFountain(float x, float y, int n) {
        for (int i = 0; i < n; i++) {
            P p = new P(COIN, x + (R.nextFloat() - 0.5f) * 40, y, (R.nextFloat() - 0.5f) * 7f, -6f - R.nextFloat() * 6f,
                    110, GOLD, 3 + R.nextInt(4));
            p.gravity = 0.22f;
            p.drag = 0.99f;
            p.vrot = 0.2f + R.nextFloat() * 0.3f;
            PENDING.add(p);
        }
    }

    private static void coinExplosion(float x, float y, int n) {
        for (int i = 0; i < n; i++) {
            double ang = R.nextDouble() * Math.PI * 2;
            float sp = 3f + R.nextFloat() * 8f;
            P p = new P(COIN, x, y, (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp, 90 + R.nextInt(40),
                    GOLD, 3 + R.nextInt(5));
            p.gravity = 0.12f;
            p.drag = 0.97f;
            p.vrot = 0.3f;
            PENDING.add(p);
        }
        for (int i = 0; i < 12; i++) {
            double ang = R.nextDouble() * Math.PI * 2;
            PENDING.add(glyph(x, y, R.nextBoolean() ? "$" : "10T", GOLD, (float) Math.cos(ang) * 3f, (float) Math.sin(ang) * 3f));
        }
    }

    private static void edgeInflow(int n) {
        for (int i = 0; i < n; i++) {
            float x, y;
            if (R.nextBoolean()) { x = R.nextBoolean() ? -5 : screenW + 5; y = R.nextFloat() * screenH; }
            else { x = R.nextFloat() * screenW; y = R.nextBoolean() ? -5 : screenH + 5; }
            P p = new P(R.nextInt(3) == 0 ? COIN : SPARK, x, y, 0, 0, 120, PARTY[R.nextInt(PARTY.length)], 2 + R.nextInt(2));
            p.drag = 0.92f;
            p.vrot = 0.3f;
            PENDING.add(p);
        }
    }

    private static P shard(float x, float y) {
        P p = new P(SHARD, x, y, (R.nextFloat() - 0.5f) * 6f, (R.nextFloat() - 0.5f) * 1f, 10 + R.nextInt(15),
                R.nextBoolean() ? CYAN : MAGENTA, 3 + R.nextInt(8));
        p.drag = 0.9f;
        return p;
    }

    private static P glyph(float x, float y, String text, int color) {
        return glyph(x, y, text, color, (R.nextFloat() - 0.5f) * 1f, -1.2f);
    }

    private static P glyph(float x, float y, String text, int color, float vx, float vy) {
        P p = new P(GLYPH, x, y, vx, vy, 70, color, 2);
        p.text = text;
        p.drag = 0.98f;
        return p;
    }

    private static void ring(float x, float y, float r, float vr, int life, int color, int thick) {
        RINGS.add(new Ring(x, y, r, vr, life, color, thick));
    }

    // =================================================================== particle + ring

    private static final class P {
        final int kind, maxLife, color, size;
        float x, y, px, py, vx, vy, gravity, drag = 1f, rot, vrot;
        int life;
        String text;

        P(int kind, float x, float y, float vx, float vy, int life, int color, int size) {
            this.kind = kind;
            this.x = this.px = x;
            this.y = this.py = y;
            this.vx = vx;
            this.vy = vy;
            this.life = this.maxLife = life;
            this.color = color;
            this.size = size;
            this.rot = R.nextFloat() * 6.28f;
        }

        boolean tick() {
            px = x; py = y;
            vy += gravity;
            vx *= drag; vy *= drag;
            x += vx; y += vy;
            rot += vrot;
            if (y > screenH + 40 || x < -60 || x > screenW + 60) return false;
            return --life > 0;
        }

        void draw(GuiGraphicsExtractor g, Font font, float pt, float a) {
            float dx = Mth.lerp(pt, px, x), dy = Mth.lerp(pt, py, y);
            float lifeF = life / (float) maxLife;
            int alpha = (int) (255 * a * Math.min(1f, lifeF * 4f));
            if (alpha <= 4) return;
            switch (kind) {
                case COIN -> {
                    float squash = Math.abs(Mth.cos(rot));
                    int wpx = Math.max(1, (int) (size * squash));
                    for (int row = -size; row <= size; row++) {
                        float f = row / (float) size;
                        int half = (int) (wpx * Math.sqrt(Math.max(0, 1 - f * f)));
                        int col = row < -size / 2 ? 0xFFF3B0 : (row > size / 2 ? GOLD_DARK : GOLD);
                        g.fill((int) dx - half, (int) dy + row, (int) dx + half + 1, (int) dy + row + 1, argb(alpha, col));
                    }
                }
                case SPARK -> {
                    line(g, px, py, dx, dy, size, argb(alpha / 2, color));
                    g.fill((int) dx, (int) dy, (int) dx + size + 1, (int) dy + size + 1, argb(alpha, color));
                }
                case CONFETTI -> {
                    g.pose().pushMatrix();
                    g.pose().translate(dx, dy);
                    g.pose().rotate(rot);
                    g.pose().scale(1f, Math.max(0.2f, Math.abs(Mth.sin(rot * 1.3f))));
                    g.fill(-size, -size / 2 - 1, size, size / 2 + 1, argb(alpha, color));
                    g.pose().popMatrix();
                }
                case GLYPH -> bigText(g, font, text, dx, dy, 1.6f, argb(alpha, color));
                case SHARD -> {
                    if (R.nextInt(3) != 0)
                        g.fill((int) dx, (int) dy, (int) dx + size * 3, (int) dy + size, argb(alpha / 2, color));
                }
                default -> {}
            }
        }
    }

    private static final class Ring {
        final float x, y, vr;
        final int maxAge, color, thick;
        float r;
        int age;

        Ring(float x, float y, float r, float vr, int life, int color, int thick) {
            this.x = x; this.y = y; this.r = r; this.vr = vr;
            this.maxAge = life; this.color = color; this.thick = thick;
        }
    }

    // =================================================================== helpers

    private static void updateScreenSize() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;
        screenW = mc.getWindow().getGuiScaledWidth();
        screenH = mc.getWindow().getGuiScaledHeight();
    }

    private static void sound(SoundEvent s, float pitch) {
        if (!PayPopupConfig.get().playSound) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, pitch));
    }

    private static void bigText(GuiGraphicsExtractor g, Font font, String s, float x, float y, float scale, int color) {
        if (s == null || s.isEmpty()) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.centeredText(font, s, 0, -4, color);
        g.pose().popMatrix();
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void fillCircle(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        if (r < 0.5f) return;
        int n = Math.max(1, (int) r);
        for (int i = -n; i <= n; i++) {
            float f = i / (float) n;
            int half = (int) (r * Math.sqrt(Math.max(0f, 1f - f * f)));
            g.fill((int) cx - half, (int) cy + i, (int) cx + half, (int) cy + i + 1, color);
        }
    }

    private static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int thick, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float step = Math.max(1f, thick * 0.9f);
        int n = (int) (Math.max(Math.abs(dx), Math.abs(dy)) / step) + 1;
        for (int i = 0; i <= n; i++) {
            float f = i / (float) n;
            int x = (int) (x1 + dx * f), y = (int) (y1 + dy * f);
            g.fill(x, y, x + thick, y + thick, color);
        }
    }

    private static int argb(int alpha, int rgb) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int hsv(float h, float s, float v) {
        v = Mth.clamp(v, 0f, 1f);
        h = (h % 1f + 1f) % 1f * 6f;
        int i = (int) h;
        float f = h - i, p = v * (1 - s), q = v * (1 - s * f), u = v * (1 - s * (1 - f));
        float r, gr, b;
        switch (i) {
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
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1f, 3) + c1 * (float) Math.pow(x - 1f, 2);
    }

    private static float easeOutCubic(float x) {
        return 1f - (float) Math.pow(1f - x, 3);
    }

    private static float easeInCubic(float x) {
        return x * x * x;
    }

    private static float bounce(float x) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (x < 1f / d1) return n1 * x * x;
        if (x < 2f / d1) { x -= 1.5f / d1; return n1 * x * x + 0.75f; }
        if (x < 2.5f / d1) { x -= 2.25f / d1; return n1 * x * x + 0.9375f; }
        x -= 2.625f / d1;
        return n1 * x * x + 0.984375f;
    }
}
