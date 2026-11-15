package com.paypopup;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads chat messages and works out if someone just paid you.
 *
 * Handles messages like:
 *   "Steve paid you $1.5M."
 *   "You received $250,000 from Alex"
 *   "Notch has sent you $3B"
 */
public final class PayParser {

    /** A detected payment: who sent it and how much. */
    public record Payment(String sender, double amount, String rawAmount) {}

    // Player names are 3-16 letters, numbers or underscores. Some servers add a rank like [VIP] first.
    private static final String NAME = "(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_.]{3,16})";
    private static final String MONEY = "\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[KkMmBbTt]?)";

    private static final Pattern[] PATTERNS = {
            // "Steve paid you $1.5M"
            Pattern.compile(NAME + "\\s+paid you\\s+" + MONEY, Pattern.CASE_INSENSITIVE),
            // "Steve has sent you $1.5M" / "Steve sent you $1.5M"
            Pattern.compile(NAME + "\\s+(?:has\\s+)?sent you\\s+" + MONEY, Pattern.CASE_INSENSITIVE),
            // "You received $1.5M from Steve"
            Pattern.compile("you (?:have )?received\\s+" + MONEY + "\\s+from\\s+" + NAME, Pattern.CASE_INSENSITIVE),
    };

    private PayParser() {}

    /** Returns the payment in this message, or null if it isn't one. */
    public static Payment parse(String message) {
        if (message == null || message.isEmpty()) return null;

        // Strip leftover legacy colour codes like "§a"
        String clean = message.replaceAll("§.", "").trim();

        for (int i = 0; i < PATTERNS.length; i++) {
            Matcher m = PATTERNS[i].matcher(clean);
            if (!m.find()) continue;

            // In the "received ... from" pattern the order is flipped
            String sender = (i == 2) ? m.group(2) : m.group(1);
            String amountText = (i == 2) ? m.group(1) : m.group(2);

            double amount = parseAmount(amountText);
            if (amount <= 0) continue;

            return new Payment(sender, amount, amountText.replace(" ", ""));
        }
        return null;
    }

    /** Turns "1.5M" into 1500000, "250,000" into 250000, and so on. */
    public static double parseAmount(String text) {
        String t = text.replace(",", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return 0;

        double multiplier = 1;
        char last = t.charAt(t.length() - 1);
        switch (last) {
            case 'K' -> multiplier = 1_000d;
            case 'M' -> multiplier = 1_000_000d;
            case 'B' -> multiplier = 1_000_000_000d;
            case 'T' -> multiplier = 1_000_000_000_000d;
            default -> { }
        }
        if (multiplier != 1) t = t.substring(0, t.length() - 1);

        try {
            return Double.parseDouble(t) * multiplier;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Turns 1500000 back into "$1.5M" for the popup. */
    public static String format(double amount) {
        String[] suffix = {"", "K", "M", "B", "T"};
        int i = 0;
        double v = amount;
        while (v >= 1000 && i < suffix.length - 1) {
            v /= 1000;
            i++;
        }
        String number = (v % 1 == 0) ? String.format(Locale.ROOT, "%.0f", v)
                                     : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return "$" + number + suffix[i];
    }
}
