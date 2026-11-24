package com.khanhly.lymeowz.mind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mọi thứ cô ấy "nhớ", lưu cùng dữ liệu trong {@code <world>/data/lymeowz.json}.
 */
public class MindData {
    public Personality personality;

    /** Nhu cầu & cảm xúc, 0–100 (đói lấy từ thanh no riêng). */
    public float fear = 0, joy = 60, boredom = 20, curiosity = 40, energy = 100, social = 70;

    /** Những nơi đã thấy: nhà, rừng, mỏ, đàn thú, chỗ nguy hiểm... */
    public List<Place> places = new ArrayList<>();
    /** Người chơi nào tốt / xấu với cô ấy. */
    public Map<String, Relation> relations = new HashMap<>();
    /** Nhật ký những chuyện đáng nhớ gần đây. */
    public List<Episode> episodes = new ArrayList<>();
    /** Học: kết quả từng loại việc ở từng vùng (vùng 64×64 block). */
    public Map<String, Stat> learned = new HashMap<>();
    /** Các vùng 32×32 đã đi qua (để khám phá chỗ mới). */
    public Set<Long> explored = new HashSet<>();
    public long lifeTicks;
    /** Việc đã làm xong trong ngày (Minecraft) hiện tại: việc → số lần. */
    public Map<String, Integer> today = new HashMap<>();
    public long todayIndex = -1;
    /** Đồ đã chế được trong ngày. */
    public List<String> craftedToday = new ArrayList<>();

    public static class Place {
        public String kind;   // xem Mind.PlaceKind
        public String dim;
        public int x, y, z;
        public float strength = 1;
        public long seen;
        public int count;     // số cây / số quặng / số con...

        public Place() {}
        public Place(String kind, String dim, int x, int y, int z, long seen, int count) {
            this.kind = kind; this.dim = dim; this.x = x; this.y = y; this.z = z; this.seen = seen; this.count = count;
        }
    }

    public static class Relation {
        public String name;
        /** -100 (ghét) … +100 (rất thân). */
        public float affinity;
        public int good, bad;
        public long lastSeen;
        /** Thân dần theo thời gian ở cạnh nhau (có trần) – phải làm gì đó tử tế mới thân hơn. */
        public float together;
        /** Giới hạn độ thân tăng được mỗi ngày (Minecraft): làm thân cần thời gian. */
        public long gainDay = -1;
        public float gainToday;
        /** Lần cuối bị người này đánh (lifeTicks). */
        public long lastHit = -100000;
    }

    public static class Episode {
        public long time;
        public String type;   // xem Mind.Event
        public String what;   // đối tượng (tên quái, món đồ...)
        public String who;    // tên người chơi (nếu có)
        public String dim;
        public int x, z;
    }

    public static class Stat {
        public float success, fail;
        public float reward;
    }
}
