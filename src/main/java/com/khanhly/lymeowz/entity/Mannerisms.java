package com.khanhly.lymeowz.entity;

import com.khanhly.lymeowz.mind.MindData;
import com.khanhly.lymeowz.mind.Personality;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Cử chỉ vụn vặt như một người chơi thật đang cầm chuột:
 * <ul>
 *   <li>Đảo mắt (xoay camera) lúc nhanh lúc chậm, nhìn người, thú, đồ rơi, đường chân trời, bầu trời.</li>
 *   <li>Đang đi thì thỉnh thoảng nhảy, chạy thì nhảy liên tục, đột nhiên dừng lại ngó một cái rồi đi tiếp.</li>
 *   <li>Rảnh thì làm mấy trò ngây ngô: đứng ngó quanh, xoay vòng tại chỗ, nhảy lò cò, ngồi xuống đứng lên,
 *       ngước nhìn trời, nhìn chằm chằm xuống chân, đấm gió, lắc đầu, đứng xem con gà đi qua...</li>
 *   <li>Biết mình đang bị nhìn: người lạ nhìn thì quay đi, bạn nhìn thì nhìn lại / vẫy tay, bạn thân thì ngồi xuống đứng lên.</li>
 * </ul>
 * Tần suất và kiểu trò phụ thuộc tính cách (vui vẻ, tò mò, hướng nội) và tâm trạng (vui, chán, sợ, buồn).
 */
public class Mannerisms {
    private enum Quirk {
        NONE(false), LOOK_AROUND(true), SPIN(true), JUMPS(false), CROUCHES(true), LOOK_SKY(true), LOOK_DOWN(true),
        PUNCH_AIR(true), WATCH(true), WIGGLE(false), STOP_AND_STARE(true), DANCE(true);
        final boolean still;
        Quirk(boolean still) { this.still = still; }
    }

    /** Đang làm việc cần nhìn vào tay (không đảo mắt lung tung). */
    private static final Set<String> FOCUSED = Set.of("chặt cây", "đào đá", "đào mỏ", "xây nhà", "sửa nhà", "làm obsidian",
            "dựng cổng Nether", "làm ruộng", "thu hoạch", "làm chuồng", "cất đồ", "nung", "chế tạo", "câu cá", "hái quả");
    /** Việc rảnh rỗi: được làm trò. */
    private static final Set<String> IDLE = Set.of("nghỉ", "trò chuyện", "");

    private final LyMeowzEntity ly;
    // Ánh nhìn.
    @Nullable private Vec3 gazePoint;
    @Nullable private Entity gazeEntity;
    private int gazeTicks;
    private float yawSpeed = 10, pitchSpeed = 10;
    // Trò đang làm.
    private Quirk quirk = Quirk.NONE;
    private int qTicks, qStep, qNext;
    private float angle;
    @Nullable private Entity qEntity;
    private int nextQuirk = 200;
    private int hopCooldown = 40;
    // Bị nhìn.
    private int watched;
    private long reactAgain;

    public Mannerisms(LyMeowzEntity ly) {
        this.ly = ly;
    }

    /** Đang làm một trò bắt phải đứng yên. */
    public boolean holding() {
        return quirk.still;
    }

    public void cancel() {
        if (quirk == Quirk.CROUCHES || quirk == Quirk.DANCE || quirk == Quirk.LOOK_DOWN) ly.setPose(Pose.STANDING);
        quirk = Quirk.NONE;
    }

    private RandomSource r() {
        return ly.getRandom();
    }

    public void tick(ServerLevel level) {
        if (ly.isSleeping() || ly.isPassenger() || ly.isInWater()) { if (quirk != Quirk.NONE) cancel(); return; }
        LivingEntity target = ly.getTarget();
        if (target != null && target.isAlive()) { if (quirk != Quirk.NONE) cancel(); return; }
        String task = ly.survival.currentName();
        boolean working = FOCUSED.contains(task);
        var req = ly.request();
        boolean hurry = (req != null && !"WAIT".equals(req.type) && !"CAMP".equals(req.type))
                || "chạy trốn".equals(task) || "ăn".equals(task);
        boolean idle = !hurry && (IDLE.contains(task) || !ly.survival.busy());
        boolean moving = !ly.getNavigation().isDone();
        if ("buồn".equals(task) || "thư giãn".equals(task)) return; // việc đó tự lo ánh nhìn

        if (!working) reactToWatchers(level);
        if (quirk != Quirk.NONE) {
            runQuirk(level, idle);
            return;
        }
        // Đang đi: thỉnh thoảng nhảy; đang chạy thì nhảy liên tục như người chơi chạy-nhảy.
        if (moving && ly.onGround() && --hopCooldown <= 0) {
            Personality p = ly.mind().p();
            if (ly.isSprinting() && r().nextFloat() < 0.6f) ly.getJumpControl().jump();
            else if (r().nextFloat() < 0.05f + 0.15f * p.cheerful) ly.getJumpControl().jump();
            hopCooldown = ly.isSprinting() ? 12 + r().nextInt(8) : 40 + r().nextInt(160);
        }
        if (--nextQuirk <= 0) {
            if (hurry) { nextQuirk = 200 + r().nextInt(400); if (moving && r().nextBoolean()) start(Quirk.JUMPS, 30); }
            else pickQuirk(level, idle, moving, working);
        }
        if (!working) gaze(level, idle, moving);
    }

    // ------------------------------------------------------------------ ánh nhìn

    private void gaze(ServerLevel level, boolean idle, boolean moving) {
        if (gazeTicks-- <= 0) {
            gazeEntity = null;
            gazePoint = null;
            // Đang đi: phần lớn nhìn đường, thỉnh thoảng liếc sang một bên.
            if (moving && r().nextFloat() < 0.8f) { gazeTicks = 20 + r().nextInt(60); return; }
            pickGaze(level, moving);
            gazeTicks = moving ? 8 + r().nextInt(18) : 15 + r().nextInt(idle ? 70 : 40);
            // Tốc độ xoay "chuột": chậm rãi, vừa, hoặc giật nhanh.
            float roll = r().nextFloat();
            yawSpeed = roll < 0.35f ? 3 + r().nextInt(5) : roll < 0.8f ? 10 + r().nextInt(12) : 35 + r().nextInt(30);
            pitchSpeed = Math.max(4, yawSpeed * (0.5f + r().nextFloat() * 0.5f));
        }
        if (gazeEntity != null) {
            if (!gazeEntity.isAlive() || gazeEntity.distanceToSqr(ly) > 24 * 24) { gazeEntity = null; gazeTicks = 0; return; }
            ly.getLookControl().setLookAt(gazeEntity.getX(), gazeEntity.getEyeY(), gazeEntity.getZ(), yawSpeed, pitchSpeed);
        } else if (gazePoint != null) {
            ly.getLookControl().setLookAt(gazePoint.x, gazePoint.y, gazePoint.z, yawSpeed, pitchSpeed);
        }
    }

    private void pickGaze(ServerLevel level, boolean moving) {
        Personality p = ly.mind().p();
        float roll = r().nextFloat();
        // Người chơi ở gần (người hướng ngoại / thân thì nhìn nhiều hơn).
        ServerPlayer pl = ly.nearestPlayer(level, 12);
        if (pl != null && roll < 0.2f + 0.25f * p.social + (ly.affinity(pl) > 40 ? 0.15f : 0)) { gazeEntity = pl; return; }
        roll = r().nextFloat();
        if (roll < 0.25f + 0.25f * p.curious) {
            List<Entity> near = level.getEntities(ly, ly.getBoundingBox().inflate(10, 4, 10),
                    e -> (e instanceof Animal || e instanceof ItemEntity || e instanceof LivingEntity) && e.isAlive());
            if (!near.isEmpty()) { gazeEntity = near.get(r().nextInt(near.size())); return; }
        }
        // Một điểm bâng quơ: đường chân trời, mặt đất trước mặt, hoặc bầu trời.
        float yaw = ly.getYHeadRot() + (moving ? r().nextIntBetweenInclusive(-70, 70) : r().nextIntBetweenInclusive(-160, 160));
        float pitch = r().nextFloat() < 0.12f ? -40 - r().nextInt(30) : r().nextIntBetweenInclusive(-15, 30);
        gazePoint = ly.getEyePosition().add(Vec3.directionFromRotation(pitch, yaw).scale(6));
    }

    // ------------------------------------------------------------------ bị nhìn

    private void reactToWatchers(ServerLevel level) {
        if (ly.tickCount % 2 != 0) return;
        ServerPlayer watcher = null;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(ly) > 14 * 14) continue;
            Vec3 toHer = ly.getEyePosition().subtract(p.getEyePosition());
            double len = toHer.length();
            if (len < 0.5 || p.getViewVector(1f).dot(toHer.scale(1 / len)) < 0.985) continue;
            var hit = level.clip(new ClipContext(p.getEyePosition(), ly.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK) continue;
            watcher = p;
            break;
        }
        if (watcher == null) { watched = Math.max(0, watched - 2); return; }
        watched += 2;
        long now = level.getGameTime();
        if (watched < 40 || now < reactAgain || quirk != Quirk.NONE) return;
        reactAgain = now + 20 * (20 + r().nextInt(40));
        watched = 0;
        float a = ly.affinity(watcher);
        qEntity = watcher;
        if (a < 21) {
            // Người lạ nhìn chằm chằm: liếc lại rồi quay phắt đi.
            start(Quirk.STOP_AND_STARE, 14 + r().nextInt(10));
            qNext = 1; // bước 1 = quay đi
        } else if (a < 61) {
            start(r().nextBoolean() ? Quirk.WIGGLE : Quirk.STOP_AND_STARE, 24 + r().nextInt(20));
            if (r().nextBoolean()) ly.swingArm(); // vẫy tay
        } else {
            start(r().nextFloat() < 0.6f ? Quirk.CROUCHES : Quirk.DANCE, 30 + r().nextInt(20));
        }
    }

    // ------------------------------------------------------------------ trò ngây ngô

    private void pickQuirk(ServerLevel level, boolean idle, boolean moving, boolean working) {
        Personality p = ly.mind().p();
        MindData m = ly.mind().data();
        float pace = 1.3f - 0.5f * p.cheerful - 0.3f * (m.boredom / 100f);
        if (working) {
            // Đang làm: chỉ liếc nhanh một cái hoặc nhảy một phát rồi làm tiếp.
            nextQuirk = (int) ((200 + r().nextInt(500)) * pace);
            if (r().nextFloat() < 0.5f) start(Quirk.WIGGLE, 8);
            else if (ly.onGround()) ly.getJumpControl().jump();
            return;
        }
        if (moving && !idle) {
            nextQuirk = (int) ((140 + r().nextInt(320)) * pace);
            // Đang đi đâu đó: dừng lại ngó một chút, hoặc nhảy vài cái.
            if (r().nextFloat() < 0.5f) start(Quirk.STOP_AND_STARE, 18 + r().nextInt(30));
            else start(Quirk.JUMPS, 30 + r().nextInt(30));
            return;
        }
        nextQuirk = (int) ((40 + r().nextInt(130)) * pace);
        boolean sad = ly.bond.isSad() || m.joy < 30;
        boolean scared = m.fear > 50;
        boolean bored = m.boredom > 50;
        ServerPlayer friend = ly.nearestPlayer(level, 10);
        boolean friendNear = friend != null && ly.affinity(friend) >= 41;

        // Bảng trọng số.
        float[] w = new float[Quirk.values().length];
        w[Quirk.LOOK_AROUND.ordinal()] = 3 + 2 * p.curious + (scared ? 3 : 0);
        w[Quirk.SPIN.ordinal()] = sad ? 0 : 0.6f + 1.2f * p.cheerful + (bored ? 1.5f : 0);
        w[Quirk.JUMPS.ordinal()] = sad ? 0 : 1 + 2 * p.cheerful + (bored ? 1 : 0);
        w[Quirk.CROUCHES.ordinal()] = sad ? 0 : (friendNear ? 2 : 0.3f) + p.cheerful * 0.5f;
        w[Quirk.LOOK_SKY.ordinal()] = 1 + p.curious + (level.isRaining() ? 1 : 0) + (Math.floorMod(level.getOverworldClockTime(), 24000L) > 11500 ? 1.5f : 0);
        w[Quirk.LOOK_DOWN.ordinal()] = 0.5f + (sad ? 4 : 0) + (1 - p.social);
        w[Quirk.PUNCH_AIR.ordinal()] = sad ? 0 : 0.6f + (bored ? 2 : 0) + p.fighter;
        w[Quirk.WATCH.ordinal()] = 1 + 2.5f * p.curious;
        w[Quirk.WIGGLE.ordinal()] = 0.8f + (scared ? 1.5f : 0);
        w[Quirk.DANCE.ordinal()] = sad ? 0 : (friendNear && m.joy > 70 ? 2 : 0.2f * p.cheerful);
        float sum = 0;
        for (float x : w) sum += x;
        float pick = r().nextFloat() * sum;
        Quirk q = Quirk.LOOK_AROUND;
        for (Quirk c : Quirk.values()) { pick -= w[c.ordinal()]; if (pick <= 0 && w[c.ordinal()] > 0) { q = c; break; } }
        qEntity = friendNear ? friend : null;
        switch (q) {
            case LOOK_AROUND -> start(q, 50 + r().nextInt(60));
            case SPIN -> { start(q, 18 + r().nextInt(30)); angle = ly.getYHeadRot(); qNext = r().nextBoolean() ? 1 : -1; }
            case JUMPS -> start(q, 25 + r().nextInt(40));
            case CROUCHES -> start(q, 20 + r().nextInt(25));
            case LOOK_SKY -> { start(q, 40 + r().nextInt(60)); angle = ly.getYHeadRot() + r().nextIntBetweenInclusive(-60, 60); }
            case LOOK_DOWN -> start(q, 30 + r().nextInt(50));
            case PUNCH_AIR -> start(q, 15 + r().nextInt(20));
            case WATCH -> {
                qEntity = level.getEntities(ly, ly.getBoundingBox().inflate(12, 4, 12), e -> e instanceof Animal || e instanceof ItemEntity)
                        .stream().findAny().orElse(null);
                start(qEntity != null ? q : Quirk.LOOK_AROUND, 50 + r().nextInt(70));
            }
            case WIGGLE -> start(q, 16 + r().nextInt(12));
            case DANCE -> start(q, 40 + r().nextInt(30));
            default -> {}
        }
    }

    private void start(Quirk q, int ticks) {
        quirk = q;
        qTicks = ticks;
        qStep = 0;
        qNext = 0;
        if (q.still) ly.getNavigation().stop();
        if (com.khanhly.lymeowz.command.SurvivalTest.running())
            com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[survival]       ~ {} ({} tick, đang {})", q, ticks, ly.survival.currentName());
    }

    private void runQuirk(ServerLevel level, boolean idle) {
        if (quirk.still && !idle && quirk != Quirk.STOP_AND_STARE && quirk != Quirk.CROUCHES && quirk != Quirk.WIGGLE) { cancel(); return; }
        if (quirk.still) ly.getNavigation().stop();
        qStep++;
        Vec3 eye = ly.getEyePosition();
        switch (quirk) {
            case LOOK_AROUND -> {
                // Vài điểm, mỗi điểm một tốc độ khác nhau.
                if (qStep == 1 || qStep % (12 + r().nextInt(4)) == 0 && r().nextFloat() < 0.4f) {
                    float yaw = ly.getYHeadRot() + r().nextIntBetweenInclusive(-150, 150);
                    gazePoint = eye.add(Vec3.directionFromRotation(r().nextIntBetweenInclusive(-25, 20), yaw).scale(6));
                    yawSpeed = r().nextFloat() < 0.3f ? 45 : 6 + r().nextInt(14);
                    pitchSpeed = yawSpeed * 0.7f;
                }
                if (gazePoint != null) ly.getLookControl().setLookAt(gazePoint.x, gazePoint.y, gazePoint.z, yawSpeed, pitchSpeed);
            }
            case SPIN -> {
                angle += qNext * (16 + r().nextInt(10));
                Vec3 to = eye.add(Vec3.directionFromRotation(0, angle).scale(5));
                ly.getLookControl().setLookAt(to.x, to.y, to.z, 60, 30);
                ly.setYBodyRot(angle);
            }
            case JUMPS -> {
                if (ly.onGround() && qStep % 10 == 0) ly.getJumpControl().jump();
                if (qEntity != null) ly.getLookControl().setLookAt(qEntity, 20, 20);
            }
            case CROUCHES -> {
                ly.setPose((qStep / 3) % 2 == 0 ? Pose.CROUCHING : Pose.STANDING);
                if (qEntity != null) ly.getLookControl().setLookAt(qEntity, 30, 30);
            }
            case DANCE -> {
                // Ngồi xuống đứng lên + nhảy + xoay đầu qua lại: "nhảy múa" kiểu người chơi.
                ly.setPose((qStep / 4) % 2 == 0 ? Pose.CROUCHING : Pose.STANDING);
                if (ly.onGround() && qStep % 16 == 8) ly.getJumpControl().jump();
                float yaw = ly.getYRot() + ((qStep / 6) % 2 == 0 ? 35 : -35);
                Vec3 to = eye.add(Vec3.directionFromRotation(-5, yaw).scale(5));
                ly.getLookControl().setLookAt(to.x, to.y, to.z, 30, 20);
                if (qStep % 12 == 0) ly.swingArm();
            }
            case LOOK_SKY -> {
                Vec3 to = eye.add(Vec3.directionFromRotation(-65 - (qStep % 40) * 0.2f, angle + Mth.sin(qStep * 0.05f) * 20).scale(6));
                ly.getLookControl().setLookAt(to.x, to.y, to.z, 3, 3);
            }
            case LOOK_DOWN -> {
                Vec3 look = ly.getLookAngle();
                ly.getLookControl().setLookAt(ly.getX() + look.x * 0.3, ly.getY() - 0.5, ly.getZ() + look.z * 0.3, 4, 6);
                if (qStep == qTicks / 2 && r().nextFloat() < 0.4f) ly.swingArm(); // gõ gõ xuống đất
            }
            case PUNCH_AIR -> {
                if (qStep % 4 == 0) ly.swingArm();
                if (qStep == 1) gazePoint = eye.add(ly.getLookAngle().scale(2)).add(0, -0.6, 0);
                if (gazePoint != null) ly.getLookControl().setLookAt(gazePoint.x, gazePoint.y, gazePoint.z, 30, 30);
            }
            case WATCH -> {
                if (qEntity == null || !qEntity.isAlive()) { qTicks = 0; break; }
                ly.getLookControl().setLookAt(qEntity, 8, 8);
                // Tò mò thì lò dò đi theo vài bước.
                if (qStep == 30 && ly.mind().p().curious > 0.5f && qEntity.distanceTo(ly) > 3) {
                    ly.getNavigation().moveTo(qEntity, 0.6);
                    quirk = Quirk.JUMPS; // thôi giữ yên, cho đi
                    qTicks = 40;
                    qStep = 1;
                }
            }
            case WIGGLE -> {
                float yaw = ly.getYRot() + ((qStep / 4) % 2 == 0 ? 40 : -40);
                Vec3 to = eye.add(Vec3.directionFromRotation(0, yaw).scale(5));
                ly.getLookControl().setLookAt(to.x, to.y, to.z, 50, 20);
            }
            case STOP_AND_STARE -> {
                if (qEntity != null && qEntity.isAlive()) {
                    if (qNext == 1 && qStep > qTicks / 2) {
                        // Ngại: quay phắt đi.
                        Vec3 away = ly.position().subtract(qEntity.position()).normalize().scale(4).add(eye);
                        ly.getLookControl().setLookAt(away.x, away.y, away.z, 60, 30);
                    } else ly.getLookControl().setLookAt(qEntity, qNext == 1 ? 40 : 12, 12);
                } else {
                    if (qStep == 1) {
                        float yaw = ly.getYHeadRot() + r().nextIntBetweenInclusive(-120, 120);
                        gazePoint = eye.add(Vec3.directionFromRotation(r().nextIntBetweenInclusive(-20, 15), yaw).scale(6));
                    }
                    if (gazePoint != null) ly.getLookControl().setLookAt(gazePoint.x, gazePoint.y, gazePoint.z, 14, 10);
                }
            }
            default -> {}
        }
        if (--qTicks <= 0) {
            Quirk was = quirk;
            cancel();
            qNext = 0;
            qEntity = null;
            gazeTicks = was == Quirk.LOOK_AROUND ? 10 : 0;
        }
    }
}
