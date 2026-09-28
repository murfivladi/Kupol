package dev.vlad.core.util;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/** Анимированный градиентный текст: кадры с переливом цветов по буквам. */
public final class Gradient {

    private Gradient() {
    }

    /**
     * Кадры из секции конфига: text, colors (["#5B8CFF", ...]), bold, frames.
     * Цвета замыкаются в круг, поэтому анимация бесшовная.
     */
    public static List<String> frames(ConfigurationSection section, Logger log) {
        if (section == null) {
            return Collections.singletonList("");
        }
        String text = section.getString("text", "VladCore");
        boolean bold = section.getBoolean("bold", true);
        int count = Math.max(1, section.getInt("frames", 40));
        List<Color> colors = new ArrayList<>();
        for (String h : section.getStringList("colors")) {
            try {
                colors.add(Color.decode(h));
            } catch (NumberFormatException e) {
                log.warning("Неверный цвет градиента: " + h);
            }
        }
        if (colors.isEmpty()) {
            colors.add(Color.WHITE);
        }
        colors.add(colors.get(0));

        List<String> result = new ArrayList<>();
        int[] cps = text.codePoints().toArray();
        int letters = Math.max(1, cps.length);
        for (int f = 0; f < count; f++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cps.length; i++) {
                double t = ((double) i / letters + (double) f / count) % 1.0;
                sb.append(ChatColor.of(lerp(colors, t)));
                if (bold) {
                    sb.append(ChatColor.BOLD);
                }
                sb.appendCodePoint(cps[i]);
            }
            result.add(sb.toString());
        }
        return result;
    }

    /**
     * Статичный градиент по буквам. formats — коды оформления (§l, §o...), которые
     * повторяются после каждого цвета (цвет в Minecraft сбрасывает оформление).
     */
    public static String apply(String text, List<Color> stops, String formats) {
        if (stops.size() == 1) {
            stops = new ArrayList<>(stops);
            stops.add(stops.get(0));
        }
        int[] cps = text.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            double t = cps.length == 1 ? 0 : (double) i / (cps.length - 1);
            sb.append(ChatColor.of(lerp(stops, t))).append(formats).appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    private static Color lerp(List<Color> stops, double t) {
        double scaled = t * (stops.size() - 1);
        int idx = Math.min((int) scaled, stops.size() - 2);
        double local = scaled - idx;
        Color a = stops.get(idx);
        Color b = stops.get(idx + 1);
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * local),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * local),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * local));
    }
}
