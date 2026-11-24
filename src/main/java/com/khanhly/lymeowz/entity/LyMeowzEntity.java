package com.khanhly.lymeowz.entity;

import com.khanhly.lymeowz.chat.Brain;
import com.khanhly.lymeowz.chat.Lines;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.compat.LyMeowzBase;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.world.CompanionStore;
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

    /** Dữ liệu lưu trữ – null nếu cô ấy chưa có chủ. */
    @Nullable private CompanionStore.Data data;
    private boolean dataBound;

    // Trạng thái AI tạm thời
    @Nullable private BlockPos roamTarget;
    private int roamTimer;
    private Vec3 lastStuckCheck = Vec3.ZERO;
    private int stuckCount;
    private boolean searching;
    private boolean travelingHome;
    private boolean arrivedHomeSaid;
    private boolean sleepHandled;
    private boolean nightSaid;
    private int chatterCooldown = 2400;
    private int lastSpeakTick = -1000;

    public final Brain brain = new Brain(this);

    public LyMeowzEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        if (this.getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
        this.setCustomName(Component.literal(LyConfig.get().name).withStyle(ChatFormatting.LIGHT_PURPLE));
        this.setCustomNameVisible(true);
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        this.setDropChance(EquipmentSlot.MAINHAND, 0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.ARMOR, 6.0)
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

    @Nullable
    public CompanionStore.Data data() {
        if (!dataBound) {
            dataBound = true;
            data = CompanionStore.byCompanion(getUUID());
        }
        return data;
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
        data = CompanionStore.assign(player.getUUID(), getUUID(), here());
        dataBound = true;
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
        return server().getPlayerList().getPlayer(id);
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

        if (this.tickCount % 20 == 0) {
            if (getHealth() < getMaxHealth()) heal(1.0f);
            if (d != null) {
                if (d.last == null || !d.last.same(Compat.dimId(level.dimension()), getBlockX(), getBlockY(), getBlockZ())) {
                    d.last = here();
                    CompanionStore.markDirty();
                }
            }
            if (owner != null) rememberOwnerSpawn(owner);
            // Tự giữ chunk khi đang theo người chơi hoặc đang trên đường về nhà.
            boolean keep = (owner != null && getMode() == Mode.FOLLOW) || travelingHome;
            if (keep) Compat.keepChunk(level, chunkPosition(), cfg.chunkLoadRadius);
        }

        if (d == null) return; // chưa có chủ: đứng chơi một mình

        if (owner == null) { // chủ offline → về nhà đợi
            tickGoHome(level);
            return;
        }

        if (cfg.goHomeWhenOwnerSleeps && owner.isSleeping()) {
            if (!sleepHandled) { sleepHandled = true; arrivedHomeSaid = true; say(Lines.OWNER_SLEEP); }
            tickGoHome(level);
            return;
        }
        sleepHandled = false;

        Mode mode = getMode();
        switch (mode) {
            case STAY -> {
                getNavigation().stop();
                getLookControl().setLookAt(owner, 30f, 30f);
            }
            case HOME -> tickGoHome(level);
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
                if (tickCount - lastSpeakTick > 200) say(Lines.FOUND);
            }
            return;
        }

        if (getTarget() != null && getTarget().isAlive()) return; // đang đánh nhau

        // 2) Ngoài bán kính tự do → chạy về phía người chơi.
        if (dist > cfg.followRadius) {
            if (!searching) { searching = true; say(Lines.SEARCHING); }
            roamTarget = null;
            getNavigation().moveTo(owner, 1.35);
            if (checkStuck()) teleportToOwner(owner);
            return;
        }
        if (searching && dist < 10) { searching = false; say(Lines.FOUND); }

        // 3) Trong bán kính → tự do đi chơi.
        roamTimer--;
        boolean reached = roamTarget != null && roamTarget.closerToCenterThan(position(), 2.5);
        if (roamTarget == null || roamTimer <= 0 || reached || getNavigation().isDone()) {
            if (reached || roamTarget == null || roamTimer <= 0) pickRoamTarget(level, owner);
            if (roamTarget != null) {
                double speed = dist > 24 ? 1.2 : 1.0;
                if (!getNavigation().moveTo(roamTarget.getX() + 0.5, roamTarget.getY(), roamTarget.getZ() + 0.5, speed)) {
                    roamTarget = null;
                    roamTimer = 0;
                }
            }
        }
        if (checkStuck()) {
            roamTarget = null;
            if (dist > 16) teleportToOwner(owner);
        }
    }

    /** Chọn chỗ đi chơi tiếp theo, luôn nằm trong bán kính followRadius quanh người chơi. */
    private void pickRoamTarget(ServerLevel level, ServerPlayer owner) {
        int radius = LyConfig.get().followRadius;
        boolean underground = !level.canSeeSky(owner.blockPosition().above());
        float r = getRandom().nextFloat();
        roamTimer = 160 + getRandom().nextInt(340);

        // Hay quay về cạnh người chơi, nhất là khi ở trong hang hoặc ban đêm.
        if (underground || Compat.isNight(level) || r < 0.4f) {
            int spread = underground ? 4 : 6;
            roamTarget = owner.blockPosition().offset(
                    getRandom().nextIntBetweenInclusive(-spread, spread), 0, getRandom().nextIntBetweenInclusive(-spread, spread));
            return;
        }
        // Đi khám phá: điểm ngẫu nhiên quanh người chơi, nghiêng về phía gần (bình phương).
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = getRandom().nextDouble() * Math.PI * 2;
            double d = 8 + (radius - 8) * Math.pow(getRandom().nextDouble(), 2.0);
            int x = Mth.floor(owner.getX() + Math.cos(ang) * d);
            int z = Mth.floor(owner.getZ() + Math.sin(ang) * d);
            // Mỗi lần chỉ đi tối đa ~40 block từ chỗ đang đứng cho tự nhiên.
            Vec3 from = position();
            Vec3 to = new Vec3(x, from.y, z);
            if (to.distanceTo(from) > 40) {
                Vec3 dir = to.subtract(from).normalize().scale(40);
                x = Mth.floor(from.x + dir.x);
                z = Mth.floor(from.z + dir.z);
            }
            BlockPos column = new BlockPos(x, owner.getBlockY(), z);
            if (!level.isLoaded(column)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getFluidState(p.below()).isEmpty()) continue;   // không nhảy xuống nước/lava
            if (Math.abs(y - owner.getBlockY()) > 24) continue;
            if (!p.closerToCenterThan(owner.position(), radius)) continue;
            roamTarget = p;
            return;
        }
        roamTarget = owner.blockPosition();
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
            Compat.keepChunk(home.level, new net.minecraft.world.level.ChunkPos(home.pos), 2);
            teleportNear(home.level, home.pos, 0, 2);
            return;
        }
        double dh = Vec3.atBottomCenterOf(home.pos).distanceTo(position());
        if (dh > 512) { // quá xa để đi bộ
            travelingHome = true;
            Compat.keepChunk(level, new net.minecraft.world.level.ChunkPos(home.pos), 2);
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
        return teleportNear(ol, owner.blockPosition(), 1, 3);
    }

    /** Tìm chỗ an toàn quanh {@code center} rồi dịch chuyển tới (kể cả khác chiều không gian). */
    public boolean teleportNear(ServerLevel target, BlockPos center, int minR, int maxR) {
        for (int i = 0; i < 24; i++) {
            int dx = getRandom().nextIntBetweenInclusive(-maxR, maxR);
            int dz = getRandom().nextIntBetweenInclusive(-maxR, maxR);
            if (Math.abs(dx) < minR && Math.abs(dz) < minR) continue;
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos p = center.offset(dx, dy, dz);
                if (isSafeSpot(target, p)) { doTeleport(target, p); return true; }
            }
        }
        if (isSafeSpot(target, center)) { doTeleport(target, center); return true; }
        // Cùng lắm thì lên mặt đất ngay trên điểm đó.
        int y = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        BlockPos top = new BlockPos(center.getX(), y, center.getZ());
        if (isSafeSpot(target, top)) { doTeleport(target, top); return true; }
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
        if (level().isClientSide) return InteractionResult.SUCCESS;
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

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null) {
            heal(Math.max(2, food.nutrition() * 2));
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
        if (player.isShiftKeyDown()) {
            setHome((ServerLevel) level(), blockPosition());
            say(Lines.HOME_SET);
            hearts();
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
