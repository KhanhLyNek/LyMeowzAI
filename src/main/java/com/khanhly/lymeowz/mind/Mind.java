package com.khanhly.lymeowz.mind;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Tâm trí" của LyMeowz: nhu cầu & cảm xúc (kiểu The Sims), trí nhớ (nơi chốn, người chơi, chuyện đã xảy ra)
 * và một chút học tăng cường (việc nào ở đâu hay thành công). Không cần mạng hay mô hình ngôn ngữ.
 */
public class Mind {
    public enum PlaceKind { HOME, TREES, STONE, ORE, ANIMALS, DANGER, WATER, VIEW, CAVE, FLOWERS, LAVA }

    public enum Event {
        GIFT, FED, PRAISED, INSULTED, HIT_BY_PLAYER, HURT_BY_MOB, KILLED_MOB, FAINTED, FOUND_PLACE,
        TASK_SUCCESS, TASK_FAIL, CRAFTED, ATE, OWNER_RETURNED, OWNER_LEFT, CHATTED, LONELY, NIGHT_FELL, NAMED, SAVED, BUILT
    }

    private final LyMeowzEntity ly;
    private final MindData d;
    private int perceptionTimer;

    public Mind(LyMeowzEntity ly, MindData data) {
        this.ly = ly;
        this.d = data;
        if (d.personality == null) d.personality = Personality.random(ly.getUUID());
    }

    public MindData data() { return d; }
    public Personality p() { return d.personality; }

    // ------------------------------------------------------------------ nhu cầu & cảm xúc theo thời gian

    /** Gọi mỗi giây. */
    public void tickSecond(ServerLevel level, @Nullable ServerPlayer owner, boolean working, String activity) {
        if (owner != null && d.lifeTicks % 1200 == 0 && owner.level() == level && owner.distanceTo(ly) < 16) together(owner);
        d.lifeTicks += 20;
        Personality p = p();
        boolean night = Compat.isNight(level) && level.dimensionType().hasSkyLight();
        double ownerDist = owner == null || owner.level() != level ? 999 : owner.distanceTo(ly);

        // Năng lượng: làm việc thì mệt, nghỉ thì hồi; ban đêm hồi nhanh hơn.
        d.energy = clamp(d.energy + (working ? -0.12f : (night ? 0.4f : 0.2f)));
        // Giao tiếp: ở gần chủ thì ấm lòng, xa lâu thì cô đơn.
        d.social = clamp(d.social + (ownerDist < 16 ? 0.15f : -0.05f * (0.5f + p.social)));
        // Chán: làm mãi một việc / đứng không thì chán dần; khám phá thì hết chán.
        d.boredom = clamp(d.boredom + switch (activity) {
            case "khám phá", "thư giãn" -> -1.2f;
            case "ngủ" -> -0.05f;
            case "nghỉ" -> 0.12f;
            default -> 0.05f;
        });
        // Tò mò: tự tăng theo tính cách, khám phá thì được thỏa mãn.
        d.curiosity = clamp(d.curiosity + ("khám phá".equals(activity) ? -0.6f : "ngủ".equals(activity) ? 0 : 0.04f + 0.08f * p.curious));
        // Sợ: tự nguôi dần; đêm tối ngoài trời, máu thấp, quái ở gần thì sợ thêm.
        float fearDelta = -1.0f - p.brave;
        if (night && level.canSeeSky(ly.blockPosition())) fearDelta += 0.4f * (1 - p.brave);
        if (ly.getHealth() < 8) fearDelta += 1.5f;
        List<net.minecraft.world.entity.Mob> hostiles = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                ly.getBoundingBox().inflate(10), m -> m instanceof Enemy && m.isAlive());
        if (!hostiles.isEmpty()) fearDelta += 1.5f * hostiles.size() * (1.2f - p.brave);
        if (ownerDist < 6 && owner != null && affinity(owner.getUUID()) > 40) fearDelta -= 0.5f; // có bạn bên cạnh thì yên tâm hơn
        d.fear = clamp(d.fear + fearDelta);
        // Vui: trôi về mức nền theo tính cách; đói, sợ, cô đơn thì buồn.
        float base = 35 + 40 * p.cheerful;
        float joyDelta = (base - d.joy) * 0.01f;
        if (ly.getHunger() <= 6) joyDelta -= 0.3f;
        if (d.fear > 60) joyDelta -= 0.3f;
        if (d.social < 25) joyDelta -= 0.2f;
        d.joy = clamp(d.joy + joyDelta);

        if (d.social < 15 && d.lifeTicks % 6000 == 0) event(Event.LONELY, null, null, null);

        // Nhớ những vùng đã đi qua.
        d.explored.add(cellKey(ly.blockPosition()));
        if (++perceptionTimer % 10 == 0) perceive(level);
    }

    // ------------------------------------------------------------------ sự kiện

    /** Một chuyện xảy ra: đổi cảm xúc, đổi quan hệ, ghi vào nhật ký. */
    public void event(Event e, @Nullable Player who, @Nullable String what, @Nullable BlockPos where) {
        Personality p = p();
        switch (e) {
            case GIFT -> { d.joy += 12; d.social += 8; }
            case FED -> { d.joy += 10; relate(who, 3, true); }
            case PRAISED -> { d.joy += 8; d.social += 5; relate(who, 1.5f, true); }
            case INSULTED -> { d.joy -= 15 + 10 * p.kind; relate(who, -4, false); }
            case HIT_BY_PLAYER -> { d.joy -= 20; d.fear += 15 * (1 - p.brave); relate(who, -15, false); markHit(who); }
            case HURT_BY_MOB -> { d.fear += 25 * (1.2f - p.brave); d.joy -= 5; remember(PlaceKind.DANGER, where, 1); }
            case KILLED_MOB -> { d.fear -= 20; d.joy += 6 + 8 * p.fighter; }
            case FAINTED -> { d.fear += 40; d.joy -= 25; remember(PlaceKind.DANGER, where, 3); }
            case FOUND_PLACE -> { d.joy += 6; d.curiosity -= 25; d.boredom -= 15; }
            case TASK_SUCCESS -> { d.joy += 2 + 3 * p.diligent; d.boredom -= 2; }
            case TASK_FAIL -> { d.joy -= 3; d.boredom += 3; }
            case CRAFTED -> { d.joy += 8; rollDay(); if (what != null && d.craftedToday.size() < 12) d.craftedToday.add(what); }
            case ATE -> { d.joy += 4; }
            case OWNER_RETURNED -> { d.joy += 10; d.social += 15; }
            case OWNER_LEFT -> { d.social -= 5; }
            case CHATTED -> { d.social += 6; d.boredom -= 4; relate(who, 0.3f, true); }
            case NAMED -> { d.joy += 15; relate(who, 8, false); }
            case SAVED -> { d.fear -= 30; d.joy += 10; relate(who, 5, false); if (who != null) ly.bond.onSaved(who); }
            case BUILT -> { d.joy += 15; d.boredom -= 20; rollDay(); if (what != null) d.craftedToday.add(what); }
            case LONELY, NIGHT_FELL -> {}
        }
        d.joy = clamp(d.joy); d.fear = clamp(d.fear); d.social = clamp(d.social);
        d.curiosity = clamp(d.curiosity); d.boredom = clamp(d.boredom);
        boolean minorFail = e == Event.TASK_FAIL && (what == null || !(what.equals("chặt cây") || what.equals("đào đá") || what.equals("đi săn")));
        if (e != Event.CHATTED && e != Event.TASK_SUCCESS && e != Event.ATE && !minorFail) {
            MindData.Episode ep = new MindData.Episode();
            ep.time = d.lifeTicks;
            ep.type = e.name();
            ep.what = what;
            ep.who = who == null ? null : who.getName().getString();
            BlockPos at = where != null ? where : ly.blockPosition();
            ep.dim = Compat.dimId(ly.level().dimension());
            ep.x = at.getX();
            ep.z = at.getZ();
            d.episodes.add(ep);
            while (d.episodes.size() > 40) d.episodes.remove(0);
        }
    }

    /** Giới hạn độ thân tăng mỗi ngày từ quà / lời khen / trò chuyện. */
    public static final float DAILY_GAIN = 10;
    /** Ở cạnh nhau lâu chỉ giúp thân tới mức này; muốn thân hơn phải làm gì đó. */
    public static final float TOGETHER_CAP = 30;

    /** Được tặng quà có giá trị {@code value} (1 = đồ thường, 12 = kim cương...). */
    public void gift(Player who, int value) {
        relate(who, Math.max(1, Math.min(6, value)), true);
    }

    private MindData.Relation relation(Player who) {
        MindData.Relation r = d.relations.computeIfAbsent(who.getUUID().toString(), k -> new MindData.Relation());
        r.name = who.getName().getString();
        return r;
    }

    private void markHit(@Nullable Player who) {
        if (who != null) relation(who).lastHit = d.lifeTicks;
    }

    /** Đổi độ thân với một người. {@code capped}: tính vào giới hạn mỗi ngày (quà, khen...). */
    private void relate(@Nullable Player who, float delta, boolean capped) {
        if (who == null) return;
        MindData.Relation r = relation(who);
        if (delta > 0 && capped) {
            long day = ly.level().getGameTime() / 24000L;
            if (r.gainDay != day) { r.gainDay = day; r.gainToday = 0; }
            delta = Math.min(delta, DAILY_GAIN - r.gainToday);
            if (delta <= 0) return;
            r.gainToday += delta;
        }
        r.affinity = Math.max(-100, Math.min(100, r.affinity + delta));
        r.peak = Math.max(r.peak, r.affinity);
        if (delta > 0) r.good++; else if (delta < 0) r.bad++;
        r.lastSeen = d.lifeTicks;
    }

    /** Đổi độ thân trực tiếp (sự kiện): {@code capped} = tính vào giới hạn mỗi ngày. */
    public void bond(Player who, float delta, boolean capped) {
        relate(who, delta, capped);
    }

    @Nullable
    public MindData.Relation relationOf(UUID player) {
        return d.relations.get(player.toString());
    }

    public MindData.Relation relationOf(Player p) {
        return relation(p);
    }

    /** Gọi mỗi phút cho người đang ở gần: quen dần nhau, nhưng có trần. */
    public void together(Player who) {
        MindData.Relation r = relation(who);
        r.lastSeen = d.lifeTicks;
        if (d.lifeTicks - r.lastHit < 20 * 60 * 5) return; // vừa bị đánh thì không thấy thân hơn
        if (r.together >= TOGETHER_CAP || r.affinity >= TOGETHER_CAP) return;
        float g = 0.3f + 0.3f * p().social;
        r.together += g;
        r.affinity = Math.min(100, r.affinity + g);
        r.peak = Math.max(r.peak, r.affinity);
    }

    public float affinity(UUID player) {
        MindData.Relation r = d.relations.get(player.toString());
        return r == null ? 0 : r.affinity;
    }

    /** Đã từng gặp người này chưa. */
    public boolean knows(UUID player) {
        return d.relations.containsKey(player.toString());
    }

    // ------------------------------------------------------------------ nhìn & nhớ nơi chốn

    /** Nhìn quanh (bán kính 16) và ghi nhớ rừng, mỏ đá, quặng, đàn thú, nước. */
    private void perceive(ServerLevel level) {
        BlockPos c = ly.blockPosition();
        int trees = 0, stone = 0, ore = 0, water = 0;
        BlockPos treeAt = null, stoneAt = null, oreAt = null, waterAt = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx += 2) for (int dz = -16; dz <= 16; dz += 2) {
            int x = c.getX() + dx, z = c.getZ() + dz;
            m.set(x, c.getY(), z);
            if (!level.isLoaded(m)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            for (int y = Math.max(top - 6, c.getY() - 4); y <= Math.min(top, c.getY() + 8); y++) {
                m.set(x, y, z);
                BlockState st = level.getBlockState(m);
                if (st.is(BlockTags.LEAVES) && st.hasProperty(LeavesBlock.PERSISTENT) && !st.getValue(LeavesBlock.PERSISTENT)) {
                    trees++; if (treeAt == null) treeAt = m.immutable(); break;
                }
                if (st.is(BlockItemTags.COAL_ORES.block()) || st.is(BlockTags.IRON_ORES)) {
                    if (exposed(level, m)) { ore++; oreAt = m.immutable(); }
                } else if (st.is(BlockTags.BASE_STONE_OVERWORLD) && exposed(level, m)) {
                    stone++; if (stoneAt == null) stoneAt = m.immutable();
                } else if (!st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                    water++; if (waterAt == null) waterAt = m.immutable();
                }
            }
        }
        if (trees >= 8) remember(PlaceKind.TREES, treeAt, trees / 4);
        if (stone >= 10) remember(PlaceKind.STONE, stoneAt, stone);
        if (ore >= 1) remember(PlaceKind.ORE, oreAt, ore);
        if (water >= 12) remember(PlaceKind.WATER, waterAt, water);
        BlockPos cave = findCave(level, c);
        if (cave != null) remember(PlaceKind.CAVE, cave, 1);
        int flowers = 0;
        BlockPos flowerAt = null;
        for (BlockPos f : BlockPos.betweenClosed(c.offset(-8, -2, -8), c.offset(8, 2, 8))) {
            if (level.getBlockState(f).is(BlockTags.FLOWERS)) { flowers++; if (flowerAt == null) flowerAt = f.immutable(); }
        }
        if (flowers >= 8) remember(PlaceKind.FLOWERS, flowerAt, flowers);
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, ly.getBoundingBox().inflate(16), Animal::isAlive);
        if (animals.size() >= 3) remember(PlaceKind.ANIMALS, animals.get(0).blockPosition(), animals.size());
        // Chỗ cao nhìn xa.
        if (c.getY() > level.getSeaLevel() + 40 && level.canSeeSky(c)) remember(PlaceKind.VIEW, c, 1);
    }

    /** Cửa hang: một khoảng tối rộng ngay dưới mặt đất, thông ra ngoài. */
    @Nullable
    private static BlockPos findCave(ServerLevel level, BlockPos c) {
        for (int dx = -12; dx <= 12; dx += 3) for (int dz = -12; dz <= 12; dz += 3) {
            int x = c.getX() + dx, z = c.getZ() + dz;
            if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int air = 0;
            for (int y = top - 1; y > top - 10; y--) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState st = level.getBlockState(p);
                if (st.isAir() && !level.canSeeSky(p)) air++;
                else if (!st.isAir() && air > 0) break;
            }
            if (air >= 4) {
                // Phải có lối vào: ô không khí nhìn thấy trời ngay cạnh.
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos side = new BlockPos(x, top - 1, z).relative(d, 2);
                    if (level.getBlockState(side).isAir() && level.getBlockState(side.below()).isAir()) return new BlockPos(x, top, z);
                }
            }
        }
        return null;
    }

    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction dir : Direction.values()) if (level.getBlockState(p.relative(dir)).isAir()) return true;
        return false;
    }

    /** Ghi nhớ một nơi; gộp với nơi cùng loại trong vòng 24 block. @return true nếu là nơi mới. */
    public boolean remember(PlaceKind kind, @Nullable BlockPos pos, int count) {
        if (pos == null) return false;
        String dim = Compat.dimId(ly.level().dimension());
        for (MindData.Place pl : d.places) {
            if (!pl.kind.equals(kind.name()) || !pl.dim.equals(dim)) continue;
            double dx = pl.x - pos.getX(), dz = pl.z - pos.getZ();
            if (dx * dx + dz * dz < 24 * 24) {
                pl.seen = d.lifeTicks;
                pl.count = Math.max(pl.count, count);
                pl.strength = Math.min(5, pl.strength + 0.2f);
                return false;
            }
        }
        d.places.add(new MindData.Place(kind.name(), dim, pos.getX(), pos.getY(), pos.getZ(), d.lifeTicks, count));
        // Không nhớ quá nhiều: quên những nơi mờ nhạt nhất.
        while (d.places.size() > 60) {
            d.places.stream().filter(pl -> !pl.kind.equals("HOME"))
                    .min(Comparator.comparingDouble(pl -> pl.strength - (d.lifeTicks - pl.seen) / 72000.0))
                    .ifPresent(d.places::remove);
        }
        if (kind != PlaceKind.DANGER && kind != PlaceKind.HOME) {
            event(Event.FOUND_PLACE, null, kind.name(), pos);
        }
        return true;
    }

    /** Nơi đã nhớ gần nhất thuộc loại này (cùng chiều không gian). */
    @Nullable
    public MindData.Place nearest(PlaceKind kind, BlockPos from) {
        String dim = Compat.dimId(ly.level().dimension());
        return d.places.stream().filter(pl -> pl.kind.equals(kind.name()) && pl.dim.equals(dim))
                .min(Comparator.comparingDouble(pl -> sq(pl.x - from.getX()) + sq(pl.z - from.getZ()))).orElse(null);
    }

    /** Mức "nguy hiểm" đã nhớ quanh một điểm (0 = an toàn). */
    public float dangerNear(BlockPos pos) {
        String dim = Compat.dimId(ly.level().dimension());
        float danger = 0;
        for (MindData.Place pl : d.places) {
            if (!pl.kind.equals("DANGER") || !pl.dim.equals(dim)) continue;
            double dist = Math.sqrt(sq(pl.x - pos.getX()) + sq(pl.z - pos.getZ()));
            if (dist < 24) danger += pl.strength * (1 - (float) dist / 24);
        }
        return danger;
    }

    public static long cellKey(BlockPos p) {
        return ((long) (p.getX() >> 5) << 32) | ((p.getZ() >> 5) & 0xffffffffL);
    }

    public boolean explored(BlockPos p) {
        return d.explored.contains(cellKey(p));
    }

    // ------------------------------------------------------------------ học

    private static String region(BlockPos p) {
        return (p.getX() >> 6) + "," + (p.getZ() >> 6);
    }

    /** Ghi kết quả một việc ở một vùng. Kinh nghiệm cũ phai dần để còn thử lại. */
    public void learn(String action, BlockPos where, boolean success) {
        for (String key : new String[]{action, action + "@" + region(where)}) {
            MindData.Stat s = d.learned.computeIfAbsent(key, k -> new MindData.Stat());
            s.success *= 0.95f;
            s.fail *= 0.95f;
            if (success) s.success += 1; else s.fail += 1;
        }
        event(success ? Event.TASK_SUCCESS : Event.TASK_FAIL, null, action, where);
        if (success) { rollDay(); d.today.merge(action, 1, Integer::sum); }
    }

    /** Sang ngày mới (trong game) thì xóa nhật ký trong ngày. */
    public void rollDay() {
        long day = ly.level().getGameTime() / 24000L;
        if (d.today == null) d.today = new java.util.HashMap<>();
        if (d.craftedToday == null) d.craftedToday = new java.util.ArrayList<>();
        if (d.todayIndex != day) { d.todayIndex = day; d.today.clear(); d.craftedToday.clear(); }
    }

    /** Ước lượng khả năng thành công (0–1) của một việc ở vùng này, dựa trên kinh nghiệm. */
    public float expectation(String action, BlockPos where) {
        MindData.Stat here = d.learned.get(action + "@" + region(where));
        MindData.Stat all = d.learned.get(action);
        float s = 1, f = 1; // tiên nghiệm 50/50
        if (all != null) { s += all.success * 0.3f; f += all.fail * 0.3f; }
        if (here != null) { s += here.success; f += here.fail; }
        return s / (s + f);
    }

    // ------------------------------------------------------------------ tiện ích

    public String dominantFeeling() {
        float hungerNeed = (20 - ly.getHunger()) * 5;
        float[] v = {d.fear, d.joy, d.boredom, d.curiosity, hungerNeed, 100 - d.energy, 100 - d.social};
        String[] k = {"FEAR", "JOY", "BORED", "CURIOUS", "HUNGRY", "TIRED", "LONELY"};
        int best = 1;
        for (int i = 0; i < v.length; i++) if (v[i] > v[best] + (i == 1 ? 0 : 10)) best = i;
        if (best == 1 && d.joy < 35) return "SAD";
        return k[best];
    }

    private static double sq(double v) { return v * v; }

    private static float clamp(float v) { return Mth.clamp(v, 0, 100); }
}
