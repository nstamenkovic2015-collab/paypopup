package com.paypopup;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Draws the animated "PAYMENT RECEIVED" popup.
 *
 * Animation steps:
 *   1. Slide in    - drops down from the top with a little bounce
 *   2. Hold        - amount "pops" bigger, coins burst out, border glows
 *   3. Slide out   - shoots back up and fades away
 * If several payments arrive at once they wait in a queue and show one after another.
 */
public final class PopupRenderer {

    private static final int SLIDE_IN_TICKS = 10;
    private static final int SLIDE_OUT_TICKS = 8;

    private static final Deque<PayParser.Payment> QUEUE = new ArrayDeque<>();
    private static final List<Coin> COINS = new ArrayList<>();
    private static final Random RANDOM = new Random();

    private static PayParser.Payment current;
    private static int age;          // ticks since the current popup started
    private static int holdTicks;    // how long this one stays

    private PopupRenderer() {}

    /** Adds a payment to the queue. */
    public static void show(PayParser.Payment payment) {
        QUEUE.add(payment);
    }

    /** Called 20 times a second. Moves the animation forward. */
    public static void tick() {
        if (current == null) {
            if (QUEUE.isEmpty()) return;
            current = QUEUE.poll();
            age = 0;
            holdTicks = Math.round(PayPopupConfig.get().displaySeconds * 20);
            PayPopupClient.playPopupSound(current.amount());
        }

        age++;

        // Burst of coins right when the popup lands
        if (age == SLIDE_IN_TICKS) spawnCoins(18 + tier(current.amount()) * 10);

        COINS.removeIf(Coin::tick);

        if (age > SLIDE_IN_TICKS + holdTicks + SLIDE_OUT_TICKS) {
            current = null;
            COINS.clear();
        }
    }

    /** Draws the popup on the HUD every frame. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        if (current == null) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float t = age + partial;
        float scale = PayPopupConfig.get().scale;

        int screenW = g.guiWidth();
        int boxW = 180;
        int boxH = 52;

        // ---------- Work out position and fade ----------
        float y;
        float alpha;
        float outStart = SLIDE_IN_TICKS + holdTicks;
        if (t < SLIDE_IN_TICKS) {
            float p = t / SLIDE_IN_TICKS;
            y = Mth.lerp(easeOutBack(p), -boxH - 10, 12);
            alpha = p;
        } else if (t < outStart) {
            y = 12;
            alpha = 1;
        } else {
            float p = Math.min(1, (t - outStart) / SLIDE_OUT_TICKS);
            y = Mth.lerp(easeInCubic(p), 12, -boxH - 10);
            alpha = 1 - p;
        }

        int a = Mth.clamp((int) (alpha * 255), 0, 255);
        if (a < 5) return;

        g.pose().pushMatrix();
        g.pose().translate(screenW / 2f, y);
        g.pose().scale(scale, scale);

        int left = -boxW / 2;
        int right = boxW / 2;

        // ---------- Box ----------
        int border = tierColor(current.amount(), t);
        g.fill(left - 2, -2, right + 2, boxH + 2, withAlpha(border, a));          // glowing border
        g.fill(left, 0, right, boxH, withAlpha(0x1A1A2E, Math.min(a, 230)));      // dark background
        g.fill(left, 0, right, 3, withAlpha(border, a));                          // top stripe

        // Shine sweeping across the box
        float shine = ((t * 6) % (boxW + 60)) - 30;
        g.fill(left + (int) shine, 3, left + (int) shine + 8, boxH, withAlpha(0xFFFFFF, a / 8));

        // ---------- Title ----------
        Component title = Component.translatable("paypopup.title");
        g.drawCenteredString(font, title, 0, 7, withAlpha(0xFFD700, a));

        // ---------- Amount (with a "pop" effect) ----------
        float pop = 1f;
        float sinceLand = t - SLIDE_IN_TICKS;
        if (sinceLand >= 0 && sinceLand < 8) {
            pop = 1f + 0.6f * (float) Math.sin(sinceLand / 8f * Math.PI);
        }
        String amount = PayParser.format(current.amount());
        g.pose().pushMatrix();
        g.pose().translate(0, 24);
        g.pose().scale(1.6f * pop, 1.6f * pop);
        g.drawCenteredString(font, amount, 0, -4, withAlpha(0x55FF55, a));
        g.pose().popMatrix();

        // ---------- Sender ----------
        Component from = Component.translatable("paypopup.from", current.sender());
        g.drawCenteredString(font, from, 0, 38, withAlpha(0xCCCCCC, a));

        // ---------- Coins ----------
        for (Coin c : COINS) c.draw(g, partial, a);

        g.pose().popMatrix();
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /** 0 = under 1M, 1 = 1M+, 2 = 1B+ (gets fancier the more money you get). */
    private static int tier(double amount) {
        if (amount >= 1_000_000_000d) return 2;
        if (amount >= 1_000_000d) return 1;
        return 0;
    }

    /** Border colour: green, then gold, then rainbow for huge payments. */
    private static int tierColor(double amount, float t) {
        return switch (tier(amount)) {
            case 2 -> java.awt.Color.HSBtoRGB((t % 60) / 60f, 0.8f, 1f) & 0xFFFFFF;
            case 1 -> 0xFFAA00;
            default -> 0x55FF55;
        };
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    private static float easeInCubic(float x) {
        return x * x * x;
    }

    private static void spawnCoins(int count) {
        for (int i = 0; i < count; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            float speed = 1.5f + RANDOM.nextFloat() * 3f;
            COINS.add(new Coin(0, 24,
                    (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed - 2f,
                    20 + RANDOM.nextInt(15)));
        }
    }

    /** A little gold coin that flies out and falls with gravity. */
    private static final class Coin {
        float x, y, prevX, prevY, vx, vy;
        int life;
        final int maxLife;

        Coin(float x, float y, float vx, float vy, int life) {
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.vx = vx;
            this.vy = vy;
            this.life = this.maxLife = life;
        }

        /** Returns true when the coin should be removed. */
        boolean tick() {
            prevX = x;
            prevY = y;
            x += vx;
            y += vy;
            vy += 0.35f;     // gravity
            vx *= 0.96f;     // air drag
            return --life <= 0;
        }

        void draw(GuiGraphics g, float partial, int alpha) {
            int px = (int) Mth.lerp(partial, prevX, x);
            int py = (int) Mth.lerp(partial, prevY, y);
            int a = (int) (alpha * Math.min(1f, life / (float) maxLife * 2f));
            g.fill(px - 2, py - 2, px + 2, py + 2, withAlpha(0xFFD700, a));
            g.fill(px - 1, py - 1, px + 1, py + 1, withAlpha(0xFFF5A0, a));
        }
    }
}
