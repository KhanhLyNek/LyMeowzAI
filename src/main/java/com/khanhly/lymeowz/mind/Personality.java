package com.khanhly.lymeowz.mind;

import java.util.Random;
import java.util.UUID;

/**
 * Tính cách: các trọng số cố định (0–1) khiến mỗi LyMeowz khác nhau. Sinh ngẫu nhiên một lần từ UUID của cô ấy
 * rồi lưu lại, nên cùng một cô thì tính cách không đổi.
 */
public class Personality {
    /** Dũng cảm (0 = rất nhút nhát). */
    public float brave;
    /** Chăm chỉ. */
    public float diligent;
    /** Tò mò, thích khám phá. */
    public float curious;
    /** Thích ở gần người khác. */
    public float social;
    /** Hiền lành, tốt bụng. */
    public float kind;
    /** Thích xây dựng, trồng trọt, chăm chút nhà cửa. */
    public float builder;
    /** Thích chiến đấu, săn bắn. */
    public float fighter;
    /** Nói nhiều. */
    public float talkative;
    /** Lạc quan, vui vẻ. */
    public float cheerful;
    /** Lý tưởng sống – điều cô ấy coi trọng nhất. */
    public String ideal;

    public static Personality random(UUID seed) {
        Random r = new Random(seed.getMostSignificantBits() ^ seed.getLeastSignificantBits());
        Personality p = new Personality();
        p.brave = trait(r, 0.4f);
        p.diligent = trait(r, 0.6f);
        p.curious = trait(r, 0.6f);
        p.social = trait(r, 0.7f);
        p.kind = trait(r, 0.7f);
        p.builder = trait(r, 0.5f);
        p.fighter = trait(r, 0.35f);
        p.talkative = trait(r, 0.6f);
        p.cheerful = trait(r, 0.7f);
        p.ideal = pickIdeal(p);
        return p;
    }

    /** Phân bố quanh một giá trị "đặc trưng của LyMeowz", nhưng mỗi cô một khác. */
    private static float trait(Random r, float center) {
        float v = center + (float) r.nextGaussian() * 0.2f;
        return Math.max(0.05f, Math.min(0.95f, v));
    }

    private static String pickIdeal(Personality p) {
        float[] scores = {p.kind + p.social, p.curious * 1.6f, p.builder * 1.7f, p.fighter + p.brave, p.diligent * 1.5f};
        String[] ideals = {"FRIENDSHIP", "ADVENTURE", "HOME", "PROTECT", "WORK"};
        int best = 0;
        for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
        return ideals[best];
    }

    /** Mô tả ngắn để kể cho người chơi nghe. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        add(b, brave < 0.3f, "hơi nhút nhát");
        add(b, brave > 0.7f, "gan dạ");
        add(b, curious > 0.7f, "rất tò mò");
        add(b, builder > 0.65f, "thích xây xửa trồng cây");
        add(b, fighter > 0.6f, "thích đánh quái");
        add(b, kind > 0.7f, "hiền");
        add(b, diligent > 0.7f, "chăm làm");
        add(b, diligent < 0.3f, "hơi lười");
        add(b, talkative > 0.7f, "nói nhiều");
        if (b.length() == 0) b.append("bình thường thôi");
        return b.toString();
    }

    private static void add(StringBuilder b, boolean cond, String s) {
        if (!cond) return;
        if (b.length() > 0) b.append(", ");
        b.append(s);
    }

    public String idealText() {
        return switch (ideal == null ? "" : ideal) {
            case "FRIENDSHIP" -> "ở bên bạn bè";
            case "ADVENTURE" -> "đi khám phá thế giới";
            case "HOME" -> "xây một tổ ấm thật đẹp";
            case "PROTECT" -> "bảo vệ người mình quý";
            case "WORK" -> "làm thật nhiều việc có ích";
            default -> "sống vui vẻ";
        };
    }
}
