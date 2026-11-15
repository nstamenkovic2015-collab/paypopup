package com.paypopup;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Main class of the mod. Fabric runs onInitializeClient() when the game starts.
 *
 * What it does:
 *   - Listens to every chat/system message
 *   - If it's a "someone paid you" message, shows the animated popup
 *   - Adds the /paypopup command (test, toggle, min)
 */
public class PayPopupClient implements ClientModInitializer {

    public static final String MOD_ID = "paypopup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        PayPopupConfig.load();

        // 1) Watch server messages (most servers send /pay messages as system messages)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleMessage(message));

        // 2) Draw the popup on top of the normal HUD
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "popup"),
                PopupRenderer::render);

        // 3) Move the animation forward every tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> PopupRenderer.tick());

        // 4) /paypopup commands
        registerCommands();

        LOGGER.info("Pay Popup loaded! Get that bag.");
    }

    private static void handleMessage(Component message) {
        PayPopupConfig config = PayPopupConfig.get();
        if (!config.enabled) return;

        PayParser.Payment payment = PayParser.parse(message.getString());
        if (payment == null) return;
        if (payment.amount() < config.minimumAmount) return;

        LOGGER.debug("Payment detected: {} from {}", payment.rawAmount(), payment.sender());
        PopupRenderer.show(payment);
    }

    /** Bigger payments get a more hype sound. */
    public static void playPopupSound(double amount) {
        if (!PayPopupConfig.get().playSound) return;
        var sound = amount >= 1_000_000_000d
                ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
                : SoundEvents.PLAYER_LEVELUP;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f));
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("paypopup")

                        // /paypopup test  -> fake payment so you can see the animation
                        .then(literal("test").executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.translatable("paypopup.command.test"));
                            PopupRenderer.show(new PayParser.Payment("TestPlayer", 2_500_000, "2.5M"));
                            return 1;
                        }))

                        // /paypopup test <amount>
                        .then(literal("test").then(argument("amount", DoubleArgumentType.doubleArg(1))
                                .executes(ctx -> {
                                    double amt = DoubleArgumentType.getDouble(ctx, "amount");
                                    PopupRenderer.show(new PayParser.Payment("TestPlayer", amt, PayParser.format(amt)));
                                    return 1;
                                })))

                        // /paypopup toggle  -> turn on/off
                        .then(literal("toggle").executes(ctx -> {
                            PayPopupConfig config = PayPopupConfig.get();
                            config.enabled = !config.enabled;
                            PayPopupConfig.save();
                            ctx.getSource().sendFeedback(Component.translatable(
                                    config.enabled ? "paypopup.command.toggle.on" : "paypopup.command.toggle.off"));
                            return 1;
                        }))

                        // /paypopup min <amount>  -> only show payments above this
                        .then(literal("min").then(argument("amount", DoubleArgumentType.doubleArg(0))
                                .executes(ctx -> {
                                    double amt = DoubleArgumentType.getDouble(ctx, "amount");
                                    PayPopupConfig.get().minimumAmount = amt;
                                    PayPopupConfig.save();
                                    ctx.getSource().sendFeedback(Component.literal(
                                            "Only showing payments of " + PayParser.format(amt) + " or more"));
                                    return 1;
                                })))
                ));
    }
}
