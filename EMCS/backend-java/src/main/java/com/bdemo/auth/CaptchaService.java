package com.bdemo.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CaptchaService {
    private final String prefix;
    private final StringRedisTemplate redis;

    public CaptchaService(StringRedisTemplate redis, @org.springframework.beans.factory.annotation.Value("${b-demo.cache-prefix}") String prefix) {
        this.prefix = prefix + "captcha_codes:";
        this.redis = redis;
    }

    public Captcha create() {
        int left = ThreadLocalRandom.current().nextInt(1, 10);
        int right = ThreadLocalRandom.current().nextInt(1, 10);
        String uuid = UUID.randomUUID().toString();
        redis.opsForValue().set(prefix + uuid, Integer.toString(left + right), Duration.ofMinutes(2));
        return new Captcha(uuid, render(left + " + " + right + " = ?"));
    }

    public boolean consume(String uuid, String answer) {
        if (uuid == null || answer == null) {
            return false;
        }
        String key = prefix + uuid;
        String expected = redis.opsForValue().getAndDelete(key);
        return expected != null && expected.equals(answer.trim());
    }

    private String render(String expression) {
        BufferedImage image = new BufferedImage(140, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(245, 247, 250));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(new Color(48, 65, 86));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        graphics.drawString(expression, 14, 27);
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "gif", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成验证码", exception);
        }
    }

    public record Captcha(String uuid, String base64Image) {}
}
