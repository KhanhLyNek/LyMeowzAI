package com.khanhly.lymeowz.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Đo thời gian chạy từng phần của cô ấy (chỉ khi bật: /lymeowz perf hoặc lúc chạy thử). Tắt thì gần như không tốn gì.
 */
public final class Perf {
    private Perf() {}

    public static boolean on;
    private static final Map<String, long[]> DATA = new LinkedHashMap<>(); // tổng ns, số lần, lâu nhất
    private static long sinceTick;

    public static long t() {
        return on ? System.nanoTime() : 0L;
    }

    public static void add(String key, long start) {
        if (!on || start == 0L) return;
        long d = System.nanoTime() - start;
        long[] a = DATA.computeIfAbsent(key, k -> new long[3]);
        a[0] += d;
        a[1]++;
        if (d > a[2]) a[2] = d;
    }

    public static void start(long serverTick) {
        DATA.clear();
        sinceTick = serverTick;
        on = true;
    }

    /** Bảng kết quả: mỗi phần trung bình bao nhiêu µs mỗi tick server, lần lâu nhất bao nhiêu ms. */
    public static List<String> report(long serverTick) {
        long ticks = Math.max(1, serverTick - sinceTick);
        List<String> out = new ArrayList<>();
        out.add(String.format("Đo trong %d tick (%.1f giây):", ticks, ticks / 20.0));
        DATA.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(18)
                .forEach(e -> out.add(String.format("  %-28s %7.1f µs/tick  (%d lần, lâu nhất %.2f ms)",
                        e.getKey(), e.getValue()[0] / 1000.0 / ticks, e.getValue()[1], e.getValue()[2] / 1e6)));
        return out;
    }
}
