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
    public enum PlaceKind { HOME, TREES, STONE, ORE, ANIMALS, DANGER, WATER, VIEW }

    public enum Event {
        GIFT, FED, PRAISED, INSULTED, HIT_BY_PLAYER, HURT_BY_MOB, KILLED_MOB, FAINTED, FOUND_PLACE,
        TASK_SUCCESS, TASK_FAIL, CRAFTED, ATE, OWNER_RETURNED, OWNER_LEFT, CHATTED, LONELY, NIGHT_FELL
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
        d.lifeTicks += 20;
        Personality p = p();
        boolean night = Compat.isNight(level) && level.dimensionType().hasSkyLight();
        double ownerDist = owner == null || owner.level() != level ? 999 : owner.distanceTo(ly);

        // Năng lượng: làm việc thì mệt, nghỉ thì hồi; ban đêm hồi nhanh hơn.
        d.energy = clamp(d.energy + (working ? -0.12f : (night ? 0.4f : 0.2f)));
        // Giao tiếp: ở gần chủ thì ấm lòng, xa lâu thì cô đơn.
        d.social = clamp(d.social + (ownerDist < 12 ? 0.15f : -0.08f * (0.5f + p.social)));
        // Chán: làm mãi một việc / đứng không thì chán dần; khám phá thì hết chán.
        d.boredom = clamp(d.boredom + switch (activity) {
            case "khám phá" -> -1.2f;
            case "nghỉ" -> 0.12f;
            default -> 0.05f;
        });
        // Tò mò: tự tăng theo tính cách, khám phá thì được thỏa mãn.
        d.curiosity = clamp(d.curiosity + ("khám phá".equals(activity) ? -0.6f : 0.04f + 0.08f * p.curious));
        // Sợ: tự nguôi dần; đêm tối ngoài trời, máu thấp, quái ở gần thì sợ thêm.
        float fearDelta = -1.0f - p.brave;
        if (night && level.canSeeSky(ly.blockPosition())) fearDelta += 0.4f * (1 - p.brave);
        if (ly.getHealth() < 8) fearDelta += 1.5f;
        List<net.minecraft.world.entity.Mob> hostiles = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                ly.getBoundingBox().inflate(10), m -> m instanceof Enemy && m.isAlive());
        if (!hostiles.isEmpty()) fearDelta += 1.5f * hostiles.size() * (1.2f - p.brave);
        if (ownerDist < 6) fearDelta -= 0.5f; // có chủ bên cạnh thì yên tâm hơn
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
            case GIFT -> { d.joy += 18; d.social += 10; relate(who, 8); }
            case FED -> { d.joy += 10; relate(who, 5); }
            case PRAISED -> { d.joy += 12; d.social += 8; relate(who, 4); }
            case INSULTED -> { d.joy -= 15 + 10 * p.kind; relate(who, -8); }
            case HIT_BY_PLAYER -> { d.joy -= 20; d.fear += 15 * (1 - p.brave); relate(who, -12); }
            case HURT_BY_MOB -> { d.fear += 25 * (1.2f - p.brave); d.joy -= 5; remember(PlaceKind.DANGER, where, 1); }
            case KILLED_MOB -> { d.fear -= 20; d.joy += 6 + 8 * p.fighter; }
            case FAINTED -> { d.fear += 40; d.joy -= 25; remember(PlaceKind.DANGER, where, 3); }
            case FOUND_PLACE -> { d.joy += 6; d.curiosity -= 25; d.boredom -= 15; }
            case TASK_SUCCESS -> { d.joy += 2 + 3 * p.diligent; d.boredom -= 2; }
            case TASK_FAIL -> { d.joy -= 3; d.boredom += 3; }
            case CRAFTED -> { d.joy += 8; rollDay(); if (what != null && d.craftedToday.size() < 12) d.craftedToday.add(what); }
            case ATE -> { d.joy += 4; }
            case OWNER_RETURNED -> { d.joy += 20; d.social += 30; relate(who, 2); }
            case OWNER_LEFT -> { d.social -= 10; d.joy -= 5; }
            case CHATTED -> { d.social += 6; d.boredom -= 4; relate(who, 0.5f); }
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

    private void relate(@Nullable Player who, float delta) {
        if (who == null) return;
        MindData.Relation r = d.relations.computeIfAbsent(who.getUUID().toString(), k -> new MindData.Relation());
        r.name = who.getName().getString();
        r.affinity = Math.max(-100, Math.min(100, r.affinity + delta));
        if (delta > 0) r.good++; else if (delta < 0) r.bad++;
        r.lastSeen = d.lifeTicks;
    }

    public float affinity(UUID player) {
        MindData.Relation r = d.relations.get(player.toString());
        if (r == null) return ly.isOwnerUuid(player) ? 30 : 0;
        return r.affinity + (ly.isOwnerUuid(player) ? 30 : 0);
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
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, ly.getBoundingBox().inflate(16), Animal::isAlive);
        if (animals.size() >= 3) remember(PlaceKind.ANIMALS, animals.get(0).blockPosition(), animals.size());
        // Chỗ cao nhìn xa.
        if (c.getY() > level.getSeaLevel() + 40 && level.canSeeSky(c)) remember(PlaceKind.VIEW, c, 1);
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
            ly.onDiscovered(kind, pos);
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
