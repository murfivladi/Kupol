package dev.evoday.gate.captcha;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Random;

// картинка капчи: фон из шума, буквы с градиентом, наклоном и тенью, кривые, волна и зерно
public final class CaptchaImage {

    public static final int MAX_LENGTH = 6;
    private static final Random RANDOM = new SecureRandom();
    private static final Font FONT = loadFont();

    private CaptchaImage() {
    }

    private static Font loadFont() {
        try (InputStream in = CaptchaImage.class.getResourceAsStream("/fonts/Bungee.ttf")) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            // сразу пробуем получить контур - на кривых jre падает именно тут
            font.deriveFont(100f).createGlyphVector(new BufferedImage(1, 1, 1).createGraphics().getFontRenderContext(), "A");
            return font;
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean usesTtf() {
        return FONT != null;
    }

    public static String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CaptchaFont.ALPHABET.charAt(RANDOM.nextInt(CaptchaFont.ALPHABET.length())));
        }
        return sb.toString();
    }

    public static BufferedImage render(String code, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        float hue = RANDOM.nextFloat();
        background(img, hue);

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // буквы тёмные и насыщенные, фон светлый - чтобы читалось даже после палитры карт
        float textHue = (hue + 0.4f + RANDOM.nextFloat() * 0.2f) % 1f;
        double slot = width * 0.9 / code.length();
        double size = Math.min(height * 0.62, slot * 1.25);
        clutter(g, width, height, size, hue, textHue);
        double x = width * 0.05;
        for (int i = 0; i < code.length(); i++) {
            Shape glyph = glyph(g, code.charAt(i), size);
            Rectangle2D b = glyph.getBounds2D();
            AffineTransform at = new AffineTransform();
            double cx = x + slot / 2;
            double cy = height / 2.0 + (RANDOM.nextDouble() - 0.5) * height * 0.18;
            at.translate(cx, cy);
            at.rotate((RANDOM.nextDouble() - 0.5) * 0.4);
            at.shear((RANDOM.nextDouble() - 0.5) * 0.25, 0);
            double scale = 0.9 + RANDOM.nextDouble() * 0.2;
            at.scale(scale, scale);
            at.translate(-b.getCenterX(), -b.getCenterY());
            Shape shape = at.createTransformedShape(glyph);

            // тень со сдвигом
            g.setColor(new Color(0, 0, 0, 110));
            g.fill(AffineTransform.getTranslateInstance(size * 0.04, size * 0.05).createTransformedShape(shape));

            Rectangle2D sb = shape.getBounds2D();
            float h1 = (textHue + (RANDOM.nextFloat() - 0.5f) * 0.12f + 1f) % 1f;
            float h2 = (h1 + 0.08f + RANDOM.nextFloat() * 0.1f) % 1f;
            g.setPaint(new LinearGradientPaint(
                    (float) sb.getMinX(), (float) sb.getMinY(), (float) sb.getMaxX(), (float) sb.getMaxY(),
                    new float[]{0f, 1f},
                    new Color[]{Color.getHSBColor(h1, 0.85f, 0.55f), Color.getHSBColor(h2, 0.9f, 0.35f)}));
            g.fill(shape);
            if (RANDOM.nextBoolean()) {
                g.setColor(new Color(255, 255, 255, 170));
                g.setStroke(new BasicStroke((float) (size * 0.025)));
                g.draw(shape);
            }
            x += slot;
        }

        // кривые через весь текст, того же цвета и градиента что буквы - так их не отфильтровать по цвету
        for (int i = 0; i < 4; i++) {
            float h1 = (textHue + (RANDOM.nextFloat() - 0.5f) * 0.12f + 1f) % 1f;
            g.setPaint(new LinearGradientPaint(0, 0, width, height, new float[]{0f, 1f}, new Color[]{
                    Color.getHSBColor(h1, 0.85f, 0.5f), Color.getHSBColor((h1 + 0.1f) % 1f, 0.9f, 0.35f)}));
            g.setStroke(new BasicStroke((float) (height * (0.008 + RANDOM.nextDouble() * 0.012)),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(curve(width, height));
        }
        // тонкие разрезы цветом фона прямо через буквы - буквы не вырезать по сплошному контуру
        for (int i = 0; i < 3; i++) {
            g.setColor(Color.getHSBColor(hue, 0.25f, 0.95f));
            g.setStroke(new BasicStroke((float) (height * (0.006 + RANDOM.nextDouble() * 0.008)),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(curve(width, height));
        }
        g.dispose();

        invertCircles(img, 1);

        img = wave(img);
        grain(img);
        return img;
    }

    private static CubicCurve2D curve(int width, int height) {
        float y1 = height * (0.15f + RANDOM.nextFloat() * 0.7f);
        float y2 = height * (0.15f + RANDOM.nextFloat() * 0.7f);
        return new CubicCurve2D.Float(-10, y1,
                width * 0.33f, height * (RANDOM.nextFloat() * 1.4f - 0.2f),
                width * 0.66f, height * (RANDOM.nextFloat() * 1.4f - 0.2f),
                width + 10, y2);
    }

    // мусор на фоне: пятна, полосы и бледные буквы-обманки тем же шрифтом
    private static void clutter(Graphics2D g, int width, int height, double size, float hue, float textHue) {
        for (int i = 0; i < 14; i++) {
            float h = RANDOM.nextBoolean() ? hue : (hue + 0.5f) % 1f;
            g.setColor(Color.getHSBColor((h + (RANDOM.nextFloat() - 0.5f) * 0.2f + 1f) % 1f,
                    0.2f + RANDOM.nextFloat() * 0.3f, 0.75f + RANDOM.nextFloat() * 0.2f));
            double r = height * (0.08 + RANDOM.nextDouble() * 0.25);
            g.fill(new java.awt.geom.Ellipse2D.Double(RANDOM.nextDouble() * width - r, RANDOM.nextDouble() * height - r,
                    r * 2, r * (1 + RANDOM.nextDouble())));
        }
        for (int i = 0; i < 6; i++) {
            g.setColor(Color.getHSBColor(hue, 0.3f, 0.7f + RANDOM.nextFloat() * 0.25f));
            g.setStroke(new BasicStroke((float) (height * (0.03 + RANDOM.nextDouble() * 0.05))));
            double y = RANDOM.nextDouble() * height;
            g.draw(new java.awt.geom.Line2D.Double(0, y, width, y + (RANDOM.nextDouble() - 0.5) * height));
        }
        // обманки бледнее и мельче настоящих, чтобы человек их отличал
        for (int i = 0; i < 6; i++) {
            char c = CaptchaFont.ALPHABET.charAt(RANDOM.nextInt(CaptchaFont.ALPHABET.length()));
            Shape shape = glyph(g, c, size * (0.3 + RANDOM.nextDouble() * 0.15));
            Rectangle2D b = shape.getBounds2D();
            AffineTransform at = AffineTransform.getTranslateInstance(RANDOM.nextDouble() * width, RANDOM.nextDouble() * height);
            at.rotate((RANDOM.nextDouble() - 0.5) * 1.5);
            at.translate(-b.getCenterX(), -b.getCenterY());
            g.setColor(Color.getHSBColor((textHue + (RANDOM.nextFloat() - 0.5f) * 0.3f + 1f) % 1f, 0.22f, 0.8f));
            g.fill(at.createTransformedShape(shape));
        }
    }

    // пара кругов, внутри которых цвета перевёрнуты
    private static void invertCircles(BufferedImage img, int count) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int i = 0; i < count; i++) {
            int cx = RANDOM.nextInt(w);
            // у верхнего или нижнего края - задевает буквы, но не закрывает их целиком
            int cy = RANDOM.nextBoolean() ? RANDOM.nextInt(h / 4) : h - 1 - RANDOM.nextInt(h / 4);
            int r = (int) (h * (0.08 + RANDOM.nextDouble() * 0.06));
            for (int y = Math.max(0, cy - r); y < Math.min(h, cy + r); y++) {
                for (int x = Math.max(0, cx - r); x < Math.min(w, cx + r); x++) {
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                        img.setRGB(x, y, ~img.getRGB(x, y) & 0xffffff);
                    }
                }
            }
        }
    }

    private static Shape glyph(Graphics2D g, char c, double size) {
        if (FONT != null) {
            GlyphVector gv = FONT.deriveFont((float) size).createGlyphVector(g.getFontRenderContext(), String.valueOf(c));
            return gv.getOutline();
        }
        // запасной вариант - пиксельный шрифт, собранный в фигуру
        Area area = new Area();
        double px = size / CaptchaFont.HEIGHT;
        for (int y = 0; y < CaptchaFont.HEIGHT; y++) {
            for (int x = 0; x < CaptchaFont.WIDTH; x++) {
                if (CaptchaFont.pixel(c, x, y)) {
                    area.add(new Area(new Rectangle2D.Double(x * px, y * px, px * 1.02, px * 1.02)));
                }
            }
        }
        return area;
    }

    // мягкий фон: сумма нескольких октав сглаженного шума, светлые пастельные цвета
    private static void background(BufferedImage img, float hue) {
        int w = img.getWidth();
        int h = img.getHeight();
        long seed = RANDOM.nextLong();
        Color a = Color.getHSBColor(hue, 0.25f, 0.97f);
        Color b = Color.getHSBColor((hue + 0.15f) % 1f, 0.35f, 0.85f);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double n = 0;
                double amp = 0.5;
                double freq = 1.0 / 64;
                for (int o = 0; o < 4; o++) {
                    n += amp * noise(seed + o, x * freq, y * freq);
                    amp *= 0.5;
                    freq *= 2;
                }
                double t = Math.max(0, Math.min(1, n / 0.94));
                img.setRGB(x, y, mix(a, b, t));
            }
        }
    }

    private static double noise(long seed, double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        double sx = fx * fx * (3 - 2 * fx);
        double sy = fy * fy * (3 - 2 * fy);
        double top = lerp(lattice(seed, x0, y0), lattice(seed, x0 + 1, y0), sx);
        double bottom = lerp(lattice(seed, x0, y0 + 1), lattice(seed, x0 + 1, y0 + 1), sx);
        return lerp(top, bottom, sy);
    }

    private static double lattice(long seed, int x, int y) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (h >>> 11) / (double) (1L << 53);
    }

    // волна по x и y - буквы слегка изгибаются
    private static BufferedImage wave(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double ax = h * 0.035;
        double ay = h * 0.03;
        double px = RANDOM.nextDouble() * Math.PI * 2;
        double py = RANDOM.nextDouble() * Math.PI * 2;
        double lx = w / (1.5 + RANDOM.nextDouble());
        double ly = h / (1.0 + RANDOM.nextDouble());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = (int) Math.round(x + ax * Math.sin(y / ly * Math.PI * 2 + py));
                int sy = (int) Math.round(y + ay * Math.sin(x / lx * Math.PI * 2 + px));
                sx = Math.max(0, Math.min(w - 1, sx));
                sy = Math.max(0, Math.min(h - 1, sy));
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
    }

    private static void grain(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (RANDOM.nextFloat() > 0.35f) {
                    continue;
                }
                int rgb = img.getRGB(x, y);
                int d = (int) (RANDOM.nextGaussian() * 12);
                int r = clamp(((rgb >> 16) & 0xff) + d);
                int gr = clamp(((rgb >> 8) & 0xff) + d);
                int bl = clamp((rgb & 0xff) + d);
                img.setRGB(x, y, (r << 16) | (gr << 8) | bl);
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int mix(Color a, Color b, double t) {
        int r = (int) lerp(a.getRed(), b.getRed(), t);
        int g = (int) lerp(a.getGreen(), b.getGreen(), t);
        int bl = (int) lerp(a.getBlue(), b.getBlue(), t);
        return (r << 16) | (g << 8) | bl;
    }
}
