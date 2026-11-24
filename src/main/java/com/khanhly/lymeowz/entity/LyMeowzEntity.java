package com.khanhly.lymeowz.entity;

import com.khanhly.lymeowz.chat.Brain;
import com.khanhly.lymeowz.chat.Lines;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.compat.LyMeowzBase;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.world.CompanionStore;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Survival;
import com.khanhly.lymeowz.survival.Tools;
import com.khanhly.lymeowz.survival.task.SmeltTask;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * LyMeowz – cô bạn đồng hành AI.
 *
 * <ul>
 *   <li>FOLLOW: tự do đi chơi trong bán kính {@code followRadius} (mặc định 100 block) quanh người chơi.</li>
 *   <li>Đi quá xa / khác chiều không gian → tự tìm và dịch chuyển tới người chơi.</li>
 *   <li>Tự giữ chunk quanh mình bằng ticket tự hết hạn khi đang di chuyển.</li>
 *   <li>Người chơi thoát game / đi ngủ / ra lệnh → tự về nhà (mặc định là giường của người chơi).</li>
 *   <li>Không bao giờ chết hẳn: khi kiệt sức cô ấy "ngất" và về nhà nghỉ.</li>
 * </ul>
 * Mọi dữ liệu lâu dài nằm trong {@link CompanionStore}, không nằm trong NBT.
 */
public class LyMeowzEntity extends LyMeowzBase {

    public enum Mode { FOLLOW, STAY, HOME }


    // Trạng thái AI tạm thời
    @Nullable private BlockPos roamTarget;
    private int roamTimer;
    private Vec3 lastStuckCheck = Vec3.ZERO;
    private int stuckCount;
    private boolean searching;
    private boolean searchAnnounced;
    private boolean ownerWasOnline;
    /** Vài giây sau khi chủ vào game: im lặng đi tìm (lời chào do tracker nói). */
    private int ownerJoinGrace;
    private boolean travelingHome;
    private boolean arrivedHomeSaid;
    private boolean sleepHandled;
    private boolean nightSaid;
    private int chatterCooldown = 2400;
    private int lastSpeakTick = -1000;

    public final Brain brain = new Brain(this);
    public final Survival survival = new Survival(this);
    /** Túi đồ – nạp lười từ CompanionStore. */
    @Nullable private LyInventory inventory;
    private float hunger = -1;
    private int hungerTimer;
    private int blocksBroken;
    /** Vị trí của chủ mỗi giây trong 3 giây gần nhất (để biết chủ đang đi hay đứng). */
    private final Vec3[] ownerTrail = new Vec3[4];
    private int idleStep;
    private static final boolean DEBUG = Boolean.getBoolean("lymeowz.debug");

    public LyMeowzEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        if (this.getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
        }
        this.setCustomName(Component.literal(LyConfig.get().name).withStyle(ChatFormatting.LIGHT_PURPLE));
        this.setCustomNameVisible(true);
        for (EquipmentSlot slot : EquipmentSlot.values()) this.setDropChance(slot, 0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.25, true));
        this.goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Player.class));
        addMonsterTargetGoal(2, e -> !(e instanceof Creeper) && e.distanceToSqr(this) < 14 * 14);
    }

    // ------------------------------------------------------------------ data / owner

    /**
     * Luôn tra lại (chỉ là một lần tra HashMap): từ 1.21.9 một entity ở chunk xa có thể chỉ bị "ẩn" rồi hiện lại
     * với cùng đối tượng Java, nên không được giữ bản sao – nếu cô ấy đã bị thay bằng bản mới thì phải biết ngay.
     */
    @Nullable
    public CompanionStore.Data data() {
        return CompanionStore.byCompanion(getUUID());
    }

    @Nullable
    public UUID getOwnerUuid() {
        CompanionStore.Data d = data();
        return d == null ? null : UUID.fromString(d.owner);
    }

    public Mode getMode() {
        CompanionStore.Data d = data();
        if (d == null) return Mode.FOLLOW;
        try { return Mode.valueOf(d.mode); } catch (IllegalArgumentException e) { return Mode.FOLLOW; }
    }

    private MinecraftServer server() {
        return ((ServerLevel) level()).getServer();
    }

    private CompanionStore.Spot here() {
        return new CompanionStore.Spot(Compat.dimId(level().dimension()), getBlockX(), getBlockY(), getBlockZ());
    }

    public void setOwner(ServerPlayer player) {
        CompanionStore.assign(player.getUUID(), getUUID(), here());
        rememberOwnerSpawn(player);
    }

    public boolean isOwner(Player player) {
        CompanionStore.Data d = data();
        return d != null && d.owner.equals(player.getUUID().toString());
    }

    @Nullable
    public ServerPlayer getOwner() {
        UUID id = getOwnerUuid();
        if (id == null || !(level() instanceof ServerLevel)) return null;
        ServerPlayer p = server().getPlayerList().getPlayer(id);
        return p != null ? p : com.khanhly.lymeowz.command.SurvivalTest.fakeOwner(id);
    }

    public void setMode(Mode mode) {
        CompanionStore.Data d = data();
        if (d != null) { d.mode = mode.name(); CompanionStore.markDirty(); }
        this.roamTarget = null;
        this.searching = false;
        this.arrivedHomeSaid = false;
        this.getNavigation().stop();
    }

    public void setHome(ServerLevel level, BlockPos pos) {
        CompanionStore.Data d = data();
        if (d == null) return;
        d.home = new CompanionStore.Spot(Compat.dimId(level.dimension()), pos.getX(), pos.getY(), pos.getZ());
        CompanionStore.markDirty();
    }

    @Nullable
    public BlockPos getHomePos() {
        CompanionStore.Data d = data();
        return d == null || d.home == null ? null : new BlockPos(d.home.x, d.home.y, d.home.z);
    }

    // ------------------------------------------------------------------ talking

    /** Nói một câu cho chủ và người chơi xung quanh nghe ({@code %s} = tên cô ấy). */
    public void say(String text) {
        sayRaw(text.replace("%s", LyConfig.get().name));
    }

    public void say(String[] lines) {
        say(Lines.pick(lines, getRandom()));
    }

    /** Nói nguyên văn. */
    public void sayRaw(String line) {
        if (!(level() instanceof ServerLevel sl)) return;
        Component msg = Component.literal("<")
                .append(Component.literal(LyConfig.get().name).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal("> " + line));
        ServerPlayer owner = getOwner();
        if (owner != null) owner.sendSystemMessage(msg);
        for (ServerPlayer p : sl.players()) {
            if (p != owner && p.distanceToSqr(this) < 32 * 32) p.sendSystemMessage(msg);
        }
        lastSpeakTick = this.tickCount;
        if (com.khanhly.lymeowz.command.SurvivalTest.running()) com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[survival] <{}> {}", LyConfig.get().name, line);
    }

    private void sayRare(String[] lines, int cooldownTicks) {
        if (this.tickCount - lastSpeakTick > cooldownTicks) say(lines);
    }

    public void hearts() {
        if (level() instanceof ServerLevel sl) Compat.hearts(sl, getX(), getY() + 2.0, getZ());
    }

    // ------------------------------------------------------------------ main AI loop

    @Override
    protected void lyServerTick(ServerLevel level) {
        LyConfig cfg = LyConfig.get();
        CompanionStore.Data d = data();
        if (d == null && CompanionStore.isRetired(getUUID())) { discard(); return; }
        ServerPlayer owner = getOwner();

        if (d != null) tickHunger(level);
        if (this.tickCount % 100 == 0) { flushInventory(); equipArmor(); }
        if (this.tickCount % 20 == 0) {
            if (d != null) {
                if (d.last == null || !d.last.same(Compat.dimId(level.dimension()), getBlockX(), getBlockY(), getBlockZ())) {
                    d.last = here();
                    CompanionStore.markDirty();
                }
            }
            if (owner != null) rememberOwnerSpawn(owner);
        }
        // Tự giữ chunk khi đang theo người chơi hoặc đang trên đường về nhà.
        // Gia hạn dày (mỗi 5 tick): ở 1.21.5–1.21.8 gia hạn thưa khiến chunk đôi khi bị hạ cấp
        // ngay khi cô ấy vừa bước sang chunk mới.
        if (this.tickCount % 5 == 0) {
            boolean keep = (owner != null && getMode() == Mode.FOLLOW) || travelingHome;
            if (keep) Compat.keepChunk(level, blockPosition(), cfg.chunkLoadRadius);
            if (DEBUG && tickCount % 20 == 0) com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[debug] tick={} pos={} keep={} home={}",
                    tickCount, blockPosition().toShortString(), keep, travelingHome);
        }

        if (d == null) return; // chưa có chủ: đứng chơi một mình

        if (owner != null && !ownerWasOnline) ownerJoinGrace = 100;
        ownerWasOnline = owner != null;
        if (ownerJoinGrace > 0) ownerJoinGrace--;

        if (owner == null) { // chủ offline → về nhà đợi
            survival.interrupt(level);
            survival.tickMaintenance(level);
            tickGoHome(level);
            return;
        }

        if (cfg.goHomeWhenOwnerSleeps && owner.isSleeping()) {
            if (!sleepHandled) { sleepHandled = true; arrivedHomeSaid = true; say(Lines.OWNER_SLEEP); survival.interrupt(level); }
            tickGoHome(level);
            return;
        }
        sleepHandled = false;

        Mode mode = getMode();
        switch (mode) {
            case STAY -> {
                survival.tickMaintenance(level);
                getNavigation().stop();
                getLookControl().setLookAt(owner, 30f, 30f);
            }
            case HOME -> { survival.tickMaintenance(level); tickGoHome(level); }
            case FOLLOW -> tickFollow(level, owner);
        }

        if (mode != Mode.HOME) defendOwner(owner);
        tickMood(level, owner);
    }

    // ------------------------------------------------------------------ FOLLOW

    private void tickFollow(ServerLevel level, ServerPlayer owner) {
        LyConfig cfg = LyConfig.get();
        travelingHome = false;

        boolean otherLevel = owner.level() != level;
        double dist = otherLevel ? Double.MAX_VALUE : distanceTo(owner);

        // 1) Quá xa hoặc khác chiều → tự tìm người chơi bằng dịch chuyển.
        if (otherLevel || dist > cfg.teleportDistance) {
            if (!owner.isSpectator() && teleportToOwner(owner)) {
                if (ownerJoinGrace == 0 && tickCount - lastSpeakTick > 200) say(Lines.FOUND);
            }
            return;
        }

        if (getTarget() != null && getTarget().isAlive()) return; // đang đánh nhau

        // 2) Ngoài bán kính tự do → chạy về phía người chơi.
        if (dist > cfg.followRadius) {
            if (!searching) {
                searching = true;
                searchAnnounced = ownerJoinGrace == 0;
                if (searchAnnounced) say(Lines.SEARCHING);
            }
            roamTarget = null;
            getNavigation().moveTo(owner, 1.35);
            if (checkStuck()) teleportToOwner(owner);
            return;
        }
        if (searching && dist < 10) {
            searching = false;
            if (searchAnnounced) say(Lines.FOUND);
        }

        // 3) Trong bán kính: chủ đang đi → đi theo; chủ đứng lại → làm việc quanh chủ; hết việc → đứng nghỉ.
        int work = cfg.workRadius;
        if (Compat.isNight(level)) work = Math.min(work, 12);   // ban đêm ở gần chủ
        boolean moving = ownerMoving(owner);
        if (dist > work || (moving && dist > 6)) {
            survival.interrupt(level);
            if (getNavigation().isDone() || tickCount % 20 == 0) getNavigation().moveTo(owner, dist > 16 ? 1.3 : 1.1);
            if (checkStuck() && dist > 16) teleportToOwner(owner);
            return;
        }
        if (moving) {
            survival.interrupt(level);
            if (dist > 4) { if (getNavigation().isDone()) getNavigation().moveTo(owner, 1.0); }
            else getNavigation().stop();
            return;
        }
        survival.tick(level, owner.blockPosition(), work);
        if (!survival.busy()) idle(owner, dist);
    }

    /** Rảnh: đứng gần chủ, nhìn chủ / nhìn quanh, thỉnh thoảng nhích vài bước. */
    private void idle(ServerPlayer owner, double dist) {
        if (dist > 5) {
            if (getNavigation().isDone()) getNavigation().moveTo(owner, 1.0);
            return;
        }
        if (dist < 3.5 && !getNavigation().isDone()) getNavigation().stop();
        if (--idleStep <= 0) {
            idleStep = 200 + getRandom().nextInt(400);
            if (getRandom().nextInt(3) == 0) {
                BlockPos p = owner.blockPosition().offset(getRandom().nextIntBetweenInclusive(-3, 3), 0, getRandom().nextIntBetweenInclusive(-3, 3));
                getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.7);
            }
        }
    }

    /** Chủ có đi được hơn 4 block trong ~3 giây vừa rồi không. */
    private boolean ownerMoving(ServerPlayer owner) {
        if (tickCount % 20 == 0) {
            System.arraycopy(ownerTrail, 0, ownerTrail, 1, ownerTrail.length - 1);
            ownerTrail[0] = owner.position();
        }
        Vec3 old = ownerTrail[ownerTrail.length - 1];
        return old != null && ownerTrail[0] != null && old.distanceToSqr(ownerTrail[0]) > 4 * 4;
    }

    private void defendOwner(ServerPlayer owner) {
        if (tickCount % 10 != 0) return;
        LivingEntity current = getTarget();
        if (current != null && current.isAlive()) return;
        LivingEntity threat = owner.getLastHurtByMob();
        if (!(threat instanceof Enemy) || !threat.isAlive()) threat = owner.getLastHurtMob();
        if (threat instanceof Enemy && threat.isAlive() && !(threat instanceof Creeper)
                && threat.level() == level() && threat.distanceToSqr(this) < 24 * 24) {
            setTarget(threat);
            sayRare(Lines.FIGHT, 600);
        }
    }

    private void tickMood(ServerLevel level, ServerPlayer owner) {
        if (owner.level() != level) return;
        double d2 = owner.distanceToSqr(this);
        if (level.dimensionType().hasSkyLight()) {
            if (Compat.isNight(level)) {
                if (!nightSaid && d2 < 32 * 32) { nightSaid = true; say(Lines.NIGHT); }
            } else {
                nightSaid = false;
            }
        }
        if (LyConfig.get().idleChatter && --chatterCooldown <= 0) {
            chatterCooldown = 3600 + getRandom().nextInt(4800);
            if (d2 < 24 * 24 && getTarget() == null) say(Lines.IDLE);
        }
    }

    // ------------------------------------------------------------------ HOME

    private void tickGoHome(ServerLevel level) {
        HomeSpot home = resolveHome(level);
        if (home == null) { getNavigation().stop(); return; }

        if (home.level != level) {
            travelingHome = true;
            Compat.keepChunk(home.level, home.pos, 2);
            teleportNear(home.level, home.pos, 0, 2);
            return;
        }
        double dh = Vec3.atBottomCenterOf(home.pos).distanceTo(position());
        if (dh > 512) { // quá xa để đi bộ
            travelingHome = true;
            Compat.keepChunk(level, home.pos, 2);
            teleportNear(level, home.pos, 0, 2);
            return;
        }
        if (dh > 3) {
            travelingHome = true;
            arrivedHomeSaid = false;
            if (getNavigation().isDone() || tickCount % 60 == 0) {
                // Đi từng chặng ~24 block về phía nhà.
                Vec3 dir = Vec3.atBottomCenterOf(home.pos).subtract(position());
                double step = Math.min(dh, 24);
                Vec3 hop = position().add(dir.normalize().scale(step));
                BlockPos hopPos = BlockPos.containing(hop);
                int y = dh <= 24 ? home.pos.getY()
                        : (level.isLoaded(hopPos) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hopPos.getX(), hopPos.getZ()) : home.pos.getY());
                if (!getNavigation().moveTo(hop.x, y, hop.z, 1.1)) stuckCount++;
            }
            if (checkStuck() || stuckCount > 6) {
                stuckCount = 0;
                teleportNear(level, home.pos, 0, 2);
            }
            return;
        }
        // Tới nhà rồi.
        travelingHome = false;
        if (!arrivedHomeSaid) {
            arrivedHomeSaid = true;
            ServerPlayer owner = getOwner();
            if (owner != null && !owner.isSleeping()) say(Lines.ARRIVED_HOME);
        }
        if (getNavigation().isDone() && getRandom().nextInt(200) == 0) {
            BlockPos p = home.pos.offset(getRandom().nextIntBetweenInclusive(-4, 4), 0, getRandom().nextIntBetweenInclusive(-4, 4));
            getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.7);
        }
    }

    private record HomeSpot(ServerLevel level, BlockPos pos) {}

    @Nullable
    private HomeSpot fromSpot(MinecraftServer server, @Nullable CompanionStore.Spot s) {
        if (s == null) return null;
        var key = Compat.dim(s.dim);
        ServerLevel l = key == null ? null : server.getLevel(key);
        return l == null ? null : new HomeSpot(l, new BlockPos(s.x, s.y, s.z));
    }

    @Nullable
    private HomeSpot resolveHome(ServerLevel current) {
        MinecraftServer server = current.getServer();
        CompanionStore.Data d = data();
        if (d != null) {
            HomeSpot h = fromSpot(server, d.home);
            if (h == null) h = fromSpot(server, d.ownerSpawn);
            if (h != null) return h;
        }
        ServerLevel ow = server.overworld();
        return new HomeSpot(ow, Compat.worldSpawn(ow));
    }

    private void rememberOwnerSpawn(ServerPlayer owner) {
        CompanionStore.Data d = data();
        GlobalPos spawn = Compat.respawn(owner);
        if (d == null || spawn == null) return;
        String dim = Compat.dimId(spawn.dimension());
        BlockPos p = spawn.pos();
        if (d.ownerSpawn == null || !d.ownerSpawn.same(dim, p.getX(), p.getY(), p.getZ())) {
            d.ownerSpawn = new CompanionStore.Spot(dim, p.getX(), p.getY(), p.getZ());
            CompanionStore.markDirty();
        }
    }

    // ------------------------------------------------------------------ teleport / stuck

    public boolean teleportToOwner(ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel ol)) return false;
        roamTarget = null;
        searching = false;
        if (teleportNear(ol, owner.blockPosition(), 1, 3)) return true;
        // Chỗ chủ đứng chật quá (hang hẹp, Nether...) → đứng ngay cạnh chủ.
        if (owner.onGround() || owner.isInWater()) { doTeleport(ol, owner.blockPosition()); return true; }
        return false;
    }

    /** Tìm chỗ an toàn quanh {@code center} rồi dịch chuyển tới (kể cả khác chiều không gian). */
    public boolean teleportNear(ServerLevel target, BlockPos center, int minR, int maxR) {
        // 1) Thử ngẫu nhiên vài chỗ gần.
        for (int i = 0; i < 24; i++) {
            int dx = getRandom().nextIntBetweenInclusive(-maxR, maxR);
            int dz = getRandom().nextIntBetweenInclusive(-maxR, maxR);
            if (Math.abs(dx) < minR && Math.abs(dz) < minR) continue;
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos p = center.offset(dx, dy, dz);
                if (isSafeSpot(target, p)) { doTeleport(target, p); return true; }
            }
        }
        // 2) Quét có hệ thống, từ gần ra xa, cả lên lẫn xuống.
        int r = maxR + 3;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -6; dy <= 6; dy++) {
            double d = dx * dx + dz * dz + dy * dy * 2.0;
            if (d >= bestD) continue;
            BlockPos p = center.offset(dx, dy, dz);
            if (isSafeSpot(target, p)) { best = p; bestD = d; }
        }
        if (best != null) { doTeleport(target, best); return true; }
        // 3) Thế giới không có trần (không phải Nether): lên mặt đất ngay trên điểm đó.
        if (!target.dimensionType().hasCeiling()) {
            int y = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
            BlockPos top = new BlockPos(center.getX(), y, center.getZ());
            if (isSafeSpot(target, top)) { doTeleport(target, top); return true; }
        }
        return false;
    }

    private boolean isSafeSpot(ServerLevel l, BlockPos p) {
        BlockPos below = p.below();
        if (!l.getBlockState(below).isFaceSturdy(l, below, Direction.UP)) return false;
        if (!l.getFluidState(p).isEmpty() || !l.getFluidState(p.above()).isEmpty()) return false;
        return l.noCollision(this, getType().getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(p)));
    }

    private void doTeleport(ServerLevel target, BlockPos p) {
        getNavigation().stop();
        stuckCount = 0;
        resetFallDistance();
        Vec3 pos = Vec3.atBottomCenterOf(p);
        // Giữ chunk ở điểm đến để cô ấy không bị lưu xuống đĩa ngay khi vừa tới (1.21.9+ không còn spawn chunks).
        Compat.keepChunk(target, p, 2);
        if (target == level()) {
            teleportTo(pos.x, pos.y, pos.z);
        } else {
            // Đổi chiều không gian: Minecraft tạo bản sao giữ nguyên UUID → dữ liệu trong CompanionStore vẫn khớp.
            Compat.changeDimension(this, target, pos, getYRot(), getXRot());
        }
        lastStuckCheck = pos;
    }

    /** Mỗi 2 giây kiểm tra: đang muốn đi mà gần như không nhúc nhích → coi như bị kẹt. */
    private boolean checkStuck() {
        if (tickCount % 40 != 0) return false;
        Vec3 now = position();
        boolean moving = !getNavigation().isDone();
        if (moving && now.distanceToSqr(lastStuckCheck) < 0.5) stuckCount++;
        else stuckCount = 0;
        lastStuckCheck = now;
        if (stuckCount >= 3) { stuckCount = 0; return true; }
        return false;
    }

    // ------------------------------------------------------------------ interaction

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        ServerPlayer sp = (ServerPlayer) player;
        ItemStack stack = player.getItemInHand(hand);

        if (data() == null) {
            CompanionStore.Data existing = CompanionStore.byOwner(player.getUUID());
            if (existing != null) {
                say(Lines.STRANGER);
                return InteractionResult.SUCCESS;
            }
            setOwner(sp);
            say(Lines.FIRST_MEET);
            hearts();
            return InteractionResult.SUCCESS;
        }
        if (!isOwner(player)) {
            sayRare(Lines.STRANGER, 60);
            return InteractionResult.SUCCESS;
        }

        // Shift + chuột phải tay không: mở túi đồ của cô ấy.
        if (player.isShiftKeyDown() && stack.isEmpty()) {
            openInventory(sp);
            return InteractionResult.SUCCESS;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null && getHunger() < 20) {
            addHunger(food.nutrition());
            heal(2);
            useOne(stack, player);
            say(Lines.FED);
            hearts();
            return InteractionResult.SUCCESS;
        }
        if (Compat.isFlower(stack)) {
            useOne(stack, player);
            say(Lines.GIFT_FLOWER);
            hearts();
            return InteractionResult.SUCCESS;
        }
        if (!stack.isEmpty()) {
            // Đưa đồ cho cô ấy: cất vào túi.
            ItemStack left = inv().put(stack.copy());
            int given = stack.getCount() - left.getCount();
            if (given > 0) {
                if (!player.getAbilities().instabuild) stack.shrink(given);
                say(Lines.THANKS_GIFT);
            } else {
                say("Mugyu~ túi mình đầy mất rồi...");
            }
            return InteractionResult.SUCCESS;
        }
        if (getMode() == Mode.FOLLOW) { setMode(Mode.STAY); say(Lines.STAY); }
        else { setMode(Mode.FOLLOW); say(Lines.FOLLOW); }
        return InteractionResult.SUCCESS;
    }

    private static void useOne(ItemStack stack, Player player) {
        if (!player.getAbilities().instabuild) stack.shrink(1);
    }

    // ------------------------------------------------------------------ damage / never die

    @Override
    protected boolean lyAllowHurt(ServerLevel level, DamageSource source, float amount) {
        if (data() == null || source.is(DamageTypes.GENERIC_KILL)) return true;
        Entity attacker = source.getEntity();
        if (attacker instanceof Player p && isOwner(p)) {
            sayRare(Lines.HURT_BY_OWNER, 100);
            return false;
        }
        if (getHealth() - amount <= 1.0f) {
            faint(level);
            return false;
        }
        return true;
    }

    private void faint(ServerLevel level) {
        setHealth(getMaxHealth() * 0.5f);
        clearFire();
        removeAllEffects();
        setTarget(null);
        resetFallDistance();
        say(Lines.FAINT);
        setMode(Mode.HOME);
        HomeSpot home = resolveHome(level);
        if (home != null) teleportNear(home.level, home.pos, 0, 2);
    }

    @Override
    public void remove(RemovalReason reason) {
        flushInventory();
        if (level() instanceof ServerLevel sl) survival.interrupt(sl);
        if (DEBUG) com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[debug] remove {} at {}", reason, blockPosition().toShortString());
        super.remove(reason);
        if (reason.shouldDestroy() && level() instanceof ServerLevel) {
            CompanionStore.Data d = CompanionStore.byCompanion(getUUID());
            if (d != null) CompanionStore.remove(d);
            CompanionStore.forgetRetired(getUUID());
        }
    }

    @Override public boolean removeWhenFarAway(double distanceSquared) { return false; }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof Player p && isOwner(p)) return false;
        if (target instanceof LyMeowzEntity) return false;
        return super.canAttack(target);
    }


    // ------------------------------------------------------------------ túi đồ

    public LyInventory inv() {
        if (inventory == null) {
            inventory = new LyInventory();
            CompanionStore.Data d = data();
            if (d != null && d.inventory != null) inventory.fromJson(level().registryAccess(), d.inventory);
        }
        return inventory;
    }

    /** Ghi túi đồ vào CompanionStore (đồ đang cầm tay được Minecraft tự lưu cùng entity). */
    public void flushInventory() {
        if (inventory == null || !(level() instanceof ServerLevel)) return;
        CompanionStore.Data d = data();
        if (d == null) return;
        if (inventory.takeDirty() || d.inventory == null) {
            d.inventory = inventory.toJson(level().registryAccess(), ItemStack.EMPTY);
            CompanionStore.markDirty();
        }
    }

    /** Bản sao túi đồ cộng đồ đang cầm (để đếm). */
    public LyInventory inventoryWithHand() {
        LyInventory c = com.khanhly.lymeowz.survival.Crafting.copy(inv());
        if (!getMainHandItem().isEmpty()) c.put(getMainHandItem().copy());
        return c;
    }

    public int countAll(Predicate<ItemStack> p) {
        return inv().count(p) + (p.test(getMainHandItem()) && !getMainHandItem().isEmpty() ? getMainHandItem().getCount() : 0);
    }

    /** Cất đồ đang cầm vào túi. */
    public void stowHand() {
        ItemStack hand = getMainHandItem();
        if (hand.isEmpty()) return;
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemStack left = inv().put(hand);
        if (!left.isEmpty() && level() instanceof ServerLevel sl) spawnAtLocation(sl, left);
    }

    private void holdSlot(int slot) {
        if (slot < 0) return;
        stowHand();
        setItemSlot(EquipmentSlot.MAINHAND, inv().takeSlot(slot));
    }

    /** Cầm dụng cụ tốt nhất để phá block này (rìu cho gỗ, cuốc cho đá...). */
    public void holdBestFor(BlockState state) {
        double current = Tools.scoreFor(getMainHandItem(), state);
        int slot = inv().bestSlot(s -> Tools.scoreFor(s, state));
        if (slot >= 0 && Tools.scoreFor(inv().getItem(slot), state) > current) holdSlot(slot);
        else if (current <= 0) stowHand(); // tay không còn hơn cầm kiếm đi chặt cây
    }

    public ItemStack bestToolFor(BlockState state) {
        ItemStack best = getMainHandItem();
        double bestScore = Tools.scoreFor(best, state);
        int slot = inv().bestSlot(s -> Tools.scoreFor(s, state));
        if (slot >= 0 && Tools.scoreFor(inv().getItem(slot), state) > bestScore) best = inv().getItem(slot);
        return best;
    }

    public void holdBestWeapon() {
        double current = Tools.weaponScore(getMainHandItem());
        int slot = inv().bestSlot(Tools::weaponScore);
        if (slot >= 0 && Tools.weaponScore(inv().getItem(slot)) > current) holdSlot(slot);
    }

    /** Tự mặc giáp có trong túi vào ô còn trống. */
    private void equipArmor() {
        if (inventory == null && data() == null) return;
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            Equippable eq = s.get(DataComponents.EQUIPPABLE);
            if (eq == null) continue;
            EquipmentSlot slot = eq.slot();
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND || !getItemBySlot(slot).isEmpty()) continue;
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            setItemSlot(slot, inv().takeSlot(i));
        }
    }

    public void openInventory(ServerPlayer player) {
        stowHand();
        LyInventory container = inv();
        player.openMenu(new SimpleMenuProvider((id, playerInv, p) -> ChestMenu.threeRows(id, playerInv, container),
                Component.literal("Túi đồ của " + LyConfig.get().name)));
    }

    /** Đưa hết tài nguyên cho chủ (giữ lại dụng cụ, lò, bàn và 4 phần ăn). */
    public int giveResourcesTo(ServerPlayer owner) {
        if (!(level() instanceof ServerLevel sl)) return 0;
        stowHand();
        int keptFood = 0, given = 0;
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty()) continue;
            boolean tool = Tools.isKind(s, Tools.Kind.PICKAXE) || Tools.isKind(s, Tools.Kind.AXE) || Tools.isKind(s, Tools.Kind.SWORD);
            boolean gear = s.is(Items.FURNACE) || s.is(Items.CRAFTING_TABLE) || s.get(DataComponents.EQUIPPABLE) != null;
            if (tool || gear) continue;
            if (Tools.isGoodFood(s) && keptFood < 4) { keptFood += s.getCount(); continue; }
            ItemStack out = inv().takeSlot(i);
            given += out.getCount();
            ItemEntity e = new ItemEntity(sl, getX(), getEyeY() - 0.3, getZ(), out);
            Vec3 dir = owner.position().subtract(position()).normalize().scale(0.3);
            e.setDeltaMovement(dir.x, 0.25, dir.z);
            e.setThrower(this);
            e.setPickUpDelay(40);
            sl.addFreshEntity(e);
        }
        if (given > 0) swingArm();
        return given;
    }

    // ------------------------------------------------------------------ hành động như người chơi

    public void swingArm() {
        swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
    }

    public void walkTo(BlockPos pos, double speed) {
        var path = getNavigation().createPath(pos, 1);
        if (path != null) getNavigation().moveTo(path, speed);
    }

    public void placeBlock(ServerLevel level, BlockPos pos, BlockState state) {
        getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        var sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 2f, sound.getPitch() * 0.8f);
        swingArm();
    }

    /** Đặt block xuống một ô trống cạnh mình (không chắn chỗ chủ đứng). */
    @Nullable
    public BlockPos placeNear(ServerLevel level, BlockState state) {
        BlockPos feet = blockPosition();
        ServerPlayer owner = getOwner();
        for (int r = 1; r <= 2; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos p = feet.offset(dx, dy, dz);
                if (!level.getBlockState(p).canBeReplaced() || !level.getFluidState(p).isEmpty()) continue;
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                if (owner != null && (owner.blockPosition().equals(p) || owner.blockPosition().above().equals(p))) continue;
                if (isProtected(p) && p.distSqr(feet) > 4) continue;
                placeBlock(level, p, state);
                return p;
            }
        }
        return null;
    }

    @Nullable
    public BlockPos findBlockNear(ServerLevel level, Block block, int r) {
        BlockPos feet = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-r, -2, -r), feet.offset(r, 2, r))) {
            if (level.getBlockState(p).is(block)) return p.immutable();
        }
        return null;
    }

    /** Gần nhà (trong protectRadius) thì không chặt/đào/săn – không phá đồ của chủ. */
    public boolean isProtected(BlockPos p) {
        CompanionStore.Data d = data();
        if (d == null) return false;
        int r = LyConfig.get().protectRadius;
        for (CompanionStore.Spot s : new CompanionStore.Spot[]{d.home, d.ownerSpawn}) {
            if (s == null || !s.dim.equals(Compat.dimId(level().dimension()))) continue;
            double dx = s.x - p.getX(), dz = s.z - p.getZ();
            if (dx * dx + dz * dz <= (double) r * r) return true;
        }
        return false;
    }

    public void onBlockBroken(BlockState state) {
        if (++blocksBroken % 16 == 0) addHunger(-1);
    }

    // ------------------------------------------------------------------ đói

    public int getHunger() {
        if (hunger < 0) {
            CompanionStore.Data d = data();
            hunger = d == null ? 20 : d.food;
        }
        return (int) Math.ceil(hunger);
    }

    public void addHunger(float delta) {
        getHunger();
        hunger = Math.max(0, Math.min(20, hunger + delta));
        CompanionStore.Data d = data();
        if (d != null) { d.food = hunger; CompanionStore.markDirty(); }
    }

    /** Đói dần theo thời gian; no thì tự hồi máu; đói lả thì mất máu (không chết). */
    private void tickHunger(ServerLevel level) {
        if (++hungerTimer % 20 != 0) return;
        getHunger();
        if (hungerTimer % (20 * 40) == 0) addHunger(-1);       // mỗi 40 giây
        if (hungerTimer % 80 == 0) {
            if (hunger >= 18 && getHealth() < getMaxHealth()) { heal(1); addHunger(-0.5f); }
            else if (hunger <= 0 && getHealth() > 2) {
                hurtServer(level, damageSources().starve(), 1);
                sayWorkLine("starve", Lines.HUNGRY);
            }
        }
    }

    // ------------------------------------------------------------------ lời nói khi làm việc

    private final java.util.Map<String, Integer> workSaid = new java.util.HashMap<>();

    /** Nói câu về công việc, mỗi loại tối đa 1 lần / 3 phút. */
    public void sayWork(String key, String line) {
        Integer last = workSaid.get(key);
        if (last != null && tickCount - last < 20 * 180) return;
        workSaid.put(key, tickCount);
        say(line);
    }

    private void sayWorkLine(String key, String[] lines) {
        sayWork(key, Lines.pick(lines, getRandom()));
    }

    public void announceCrafted(net.minecraft.world.item.Item item) {
        if (item == Items.STICK || item == Items.OAK_PLANKS) return;
        say("Làm xong " + com.khanhly.lymeowz.survival.ItemNames.of(item) + " rồi nè~");
    }

    // ------------------------------------------------------------------ events from tracker

    /** Gọi khi chủ vừa vào game và tìm thấy cô ấy. */
    public void onOwnerReturned(ServerPlayer owner) {
        if (getMode() == Mode.HOME) setMode(Mode.FOLLOW);
        say(Lines.WELCOME_BACK);
        hearts();
        // Chạy ra đón chủ trước rồi mới đi chơi tiếp.
        if (getMode() == Mode.FOLLOW && owner.level() == level()) {
            roamTarget = owner.blockPosition();
            roamTimer = 300;
            getNavigation().moveTo(owner, 1.3);
        }
    }
}
