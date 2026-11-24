package com.khanhly.lymeowz.entity;

import com.khanhly.lymeowz.chat.Brain;
import com.khanhly.lymeowz.chat.Lines;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.world.ChunkKeeper;
import com.khanhly.lymeowz.world.CompanionState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
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
 */
public class LyMeowzEntity extends PathAwareEntity {

    public enum Mode { FOLLOW, STAY, HOME }

    @Nullable private UUID ownerUuid;
    private Mode mode = Mode.FOLLOW;

    /** Nhà do người chơi đặt (null = dùng điểm hồi sinh / giường của người chơi). */
    @Nullable private BlockPos homePos;
    @Nullable private RegistryKey<World> homeDim;
    /** Điểm hồi sinh cuối cùng biết được của người chơi (dùng khi họ offline). */
    @Nullable private BlockPos ownerSpawnPos;
    @Nullable private RegistryKey<World> ownerSpawnDim;

    // Trạng thái AI tạm thời (không cần lưu)
    @Nullable private BlockPos roamTarget;
    private int roamTimer;
    private Vec3d lastStuckCheck = Vec3d.ZERO;
    private int stuckCount;
    private boolean searching;
    private boolean travelingHome;
    private boolean arrivedHomeSaid;
    private boolean sleepHandled;
    private boolean nightSaid;
    private int chatterCooldown = 2400;
    private int lastSpeakAge = -1000;

    public final Brain brain = new Brain(this);

    public LyMeowzEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.setPersistent();
        this.setCanPickUpLoot(false);
        if (this.getNavigation() instanceof MobNavigation nav) {
            nav.setCanPathThroughDoors(true);
            nav.setCanEnterOpenDoors(true);
        }
        this.setCustomName(Text.literal(LyConfig.get().name).formatted(Formatting.LIGHT_PURPLE));
        this.setCustomNameVisible(true);
        this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.32)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 5.0)
                .add(EntityAttributes.GENERIC_ARMOR, 6.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.GENERIC_STEP_HEIGHT, 1.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.25, true));
        this.goalSelector.add(2, new LongDoorInteractGoal(this, true));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(9, new LookAroundGoal(this));

        this.targetSelector.add(1, new RevengeGoal(this, PlayerEntity.class));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, HostileEntity.class, 10, true, false,
                e -> !(e instanceof CreeperEntity) && e.squaredDistanceTo(this) < 14 * 14));
    }

    // ------------------------------------------------------------------ owner

    @Nullable public UUID getOwnerUuid() { return ownerUuid; }
    public Mode getMode() { return mode; }

    public void setOwner(PlayerEntity player) {
        this.ownerUuid = player.getUuid();
        rememberOwnerSpawn((ServerPlayerEntity) player);
        if (getWorld() instanceof ServerWorld sw) {
            CompanionState.get(sw.getServer()).put(ownerUuid, getUuid(), sw.getRegistryKey(), getBlockPos(), mode == Mode.FOLLOW);
        }
    }

    public boolean isOwner(PlayerEntity player) {
        return ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    @Nullable
    public ServerPlayerEntity getOwner() {
        if (ownerUuid == null || !(getWorld() instanceof ServerWorld sw)) return null;
        return sw.getServer().getPlayerManager().getPlayer(ownerUuid);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.roamTarget = null;
        this.searching = false;
        this.arrivedHomeSaid = false;
        this.getNavigation().stop();
    }

    public void setHome(ServerWorld world, BlockPos pos) {
        this.homePos = pos.toImmutable();
        this.homeDim = world.getRegistryKey();
    }

    @Nullable public BlockPos getHomePos() { return homePos; }

    // ------------------------------------------------------------------ talking

    /** Nói một câu cho chủ và người chơi xung quanh nghe. */
    public void say(String text) {
        sayRaw(text.replace("%s", LyConfig.get().name));
    }

    /** Nói nguyên văn (không thay %s). */
    public void sayRaw(String line) {
        if (!(getWorld() instanceof ServerWorld sw)) return;
        Text msg = Text.literal("<").formatted(Formatting.WHITE)
                .append(Text.literal(LyConfig.get().name).formatted(Formatting.LIGHT_PURPLE))
                .append(Text.literal("> ").formatted(Formatting.WHITE))
                .append(Text.literal(line).formatted(Formatting.WHITE));
        ServerPlayerEntity owner = getOwner();
        if (owner != null) owner.sendMessage(msg, false);
        for (ServerPlayerEntity p : sw.getPlayers()) {
            if (p != owner && p.squaredDistanceTo(this) < 32 * 32) p.sendMessage(msg, false);
        }
        lastSpeakAge = this.age;
    }

    public void say(String[] lines) {
        say(Lines.pick(lines, getRandom()));
    }

    /** Nói nhưng có giãn cách để không spam. */
    private void sayRare(String[] lines, int cooldownTicks) {
        if (this.age - lastSpeakAge > cooldownTicks) say(lines);
    }

    public void hearts() {
        if (getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.HEART, getX(), getY() + 2.0, getZ(), 5, 0.4, 0.3, 0.4, 0.0);
        }
    }

    // ------------------------------------------------------------------ main AI loop

    @Override
    protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world)) return;
        LyConfig cfg = LyConfig.get();
        ServerPlayerEntity owner = getOwner();

        if (this.age % 20 == 0) {
            if (getHealth() < getMaxHealth()) heal(1.0f);
            if (ownerUuid != null) {
                CompanionState.get(world.getServer()).put(ownerUuid, getUuid(), world.getRegistryKey(), getBlockPos(), mode == Mode.FOLLOW);
            }
            if (owner != null) rememberOwnerSpawn(owner);
            // Tự giữ chunk khi đang theo người chơi hoặc đang trên đường về nhà.
            boolean keep = (owner != null && mode == Mode.FOLLOW) || travelingHome;
            if (keep) ChunkKeeper.keep(world, getBlockPos(), cfg.chunkLoadRadius);
        }

        if (ownerUuid == null) return; // chưa có chủ: đứng chơi một mình

        if (owner == null) { // chủ offline → về nhà đợi
            tickGoHome(world);
            return;
        }

        if (cfg.goHomeWhenOwnerSleeps && owner.isSleeping()) {
            if (!sleepHandled) { sleepHandled = true; arrivedHomeSaid = true; say(Lines.OWNER_SLEEP); }
            tickGoHome(world);
            return;
        }
        sleepHandled = false;

        switch (mode) {
            case STAY -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 30f, 30f);
            }
            case HOME -> tickGoHome(world);
            case FOLLOW -> tickFollow(world, owner);
        }

        if (mode != Mode.HOME) defendOwner(owner);
        tickMood(world, owner);
    }

    // ------------------------------------------------------------------ FOLLOW

    private void tickFollow(ServerWorld world, ServerPlayerEntity owner) {
        LyConfig cfg = LyConfig.get();
        travelingHome = false;

        boolean otherWorld = owner.getWorld() != world;
        double dist = otherWorld ? Double.MAX_VALUE : distanceTo(owner);

        // 1) Quá xa hoặc khác chiều → tự tìm người chơi bằng dịch chuyển.
        if (otherWorld || dist > cfg.teleportDistance) {
            if (!owner.isSpectator() && teleportToOwner(owner)) {
                if (age - lastSpeakAge > 200) say(Lines.FOUND);
            }
            return;
        }

        if (getTarget() != null && getTarget().isAlive()) return; // đang đánh nhau

        // 2) Ngoài bán kính tự do → chạy về phía người chơi.
        if (dist > cfg.followRadius) {
            if (!searching) { searching = true; say(Lines.SEARCHING); }
            roamTarget = null;
            getNavigation().startMovingTo(owner, 1.35);
            if (checkStuck()) teleportToOwner(owner);
            return;
        }
        if (searching && dist < 10) { searching = false; say(Lines.FOUND); }

        // 3) Trong bán kính → tự do đi chơi.
        roamTimer--;
        boolean reached = roamTarget != null && roamTarget.isWithinDistance(getPos(), 2.5);
        if (roamTarget == null || roamTimer <= 0 || reached || getNavigation().isIdle()) {
            if (reached || roamTarget == null || roamTimer <= 0) pickRoamTarget(world, owner);
            if (roamTarget != null) {
                double speed = dist > 24 ? 1.2 : 1.0;
                if (!getNavigation().startMovingTo(roamTarget.getX() + 0.5, roamTarget.getY(), roamTarget.getZ() + 0.5, speed)) {
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
    private void pickRoamTarget(ServerWorld world, ServerPlayerEntity owner) {
        int radius = LyConfig.get().followRadius;
        boolean underground = !world.isSkyVisible(owner.getBlockPos().up());
        float r = getRandom().nextFloat();
        roamTimer = 160 + getRandom().nextInt(340);

        // Hay quay về cạnh người chơi, nhất là khi ở trong hang hoặc ban đêm.
        if (underground || world.isNight() || r < 0.4f) {
            int spread = underground ? 4 : 6;
            roamTarget = owner.getBlockPos().add(
                    getRandom().nextBetween(-spread, spread), 0, getRandom().nextBetween(-spread, spread));
            return;
        }
        // Đi khám phá: điểm ngẫu nhiên quanh người chơi, nghiêng về phía gần (bình phương).
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = getRandom().nextDouble() * Math.PI * 2;
            double d = 8 + (radius - 8) * Math.pow(getRandom().nextDouble(), 2.0);
            int x = MathHelper.floor(owner.getX() + Math.cos(ang) * d);
            int z = MathHelper.floor(owner.getZ() + Math.sin(ang) * d);
            // Mỗi lần chỉ đi tối đa ~40 block từ chỗ đang đứng cho tự nhiên.
            Vec3d from = getPos();
            Vec3d to = new Vec3d(x, from.y, z);
            if (to.distanceTo(from) > 40) {
                Vec3d dir = to.subtract(from).normalize().multiply(40);
                x = MathHelper.floor(from.x + dir.x);
                z = MathHelper.floor(from.z + dir.z);
            }
            BlockPos column = new BlockPos(x, 0, z);
            if (!world.isChunkLoaded(column)) continue;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (!world.getFluidState(p.down()).isEmpty()) continue;    // không nhảy xuống nước/lava
            if (Math.abs(y - owner.getBlockY()) > 24) continue;
            if (!p.isWithinDistance(owner.getPos(), radius)) continue;
            roamTarget = p;
            return;
        }
        roamTarget = owner.getBlockPos();
    }

    private void defendOwner(ServerPlayerEntity owner) {
        if (age % 10 != 0) return;
        LivingEntity current = getTarget();
        if (current != null && current.isAlive()) return;
        LivingEntity threat = owner.getAttacker();
        if (!(threat instanceof Monster) || !threat.isAlive()) threat = owner.getAttacking();
        if (threat instanceof Monster && threat.isAlive() && !(threat instanceof CreeperEntity)
                && threat.getWorld() == getWorld() && threat.squaredDistanceTo(this) < 24 * 24) {
            setTarget(threat);
            sayRare(Lines.FIGHT, 600);
        }
    }

    private void tickMood(ServerWorld world, ServerPlayerEntity owner) {
        if (owner.getWorld() != world) return;
        double d2 = owner.squaredDistanceTo(this);
        if (world.getDimension().hasSkyLight()) {
            if (world.isNight()) {
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

    private void tickGoHome(ServerWorld world) {
        HomeSpot home = resolveHome(world);
        if (home == null) { getNavigation().stop(); return; }

        if (home.world != world) {
            travelingHome = true;
            ChunkKeeper.keep(home.world, home.pos, 2);
            teleportNear(home.world, home.pos, 0, 2);
            return;
        }
        double dh = Math.sqrt(home.pos.getSquaredDistance(getPos()));
        if (dh > 512) { // quá xa để đi bộ
            travelingHome = true;
            ChunkKeeper.keep(world, home.pos, 2);
            teleportNear(world, home.pos, 0, 2);
            return;
        }
        if (dh > 3) {
            travelingHome = true;
            arrivedHomeSaid = false;
            if (getNavigation().isIdle() || age % 60 == 0) {
                // Đi từng chặng ~24 block về phía nhà.
                Vec3d dir = Vec3d.ofBottomCenter(home.pos).subtract(getPos());
                double step = Math.min(dh, 24);
                Vec3d hop = getPos().add(dir.normalize().multiply(step));
                BlockPos hopPos = BlockPos.ofFloored(hop);
                int y = dh <= 24 ? home.pos.getY()
                        : (world.isChunkLoaded(hopPos) ? world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, hopPos.getX(), hopPos.getZ()) : home.pos.getY());
                if (!getNavigation().startMovingTo(hop.x, y, hop.z, 1.1)) stuckCount++;
            }
            if (checkStuck() || stuckCount > 6) {
                stuckCount = 0;
                teleportNear(world, home.pos, 0, 2);
            }
            return;
        }
        // Tới nhà rồi.
        travelingHome = false;
        if (!arrivedHomeSaid) {
            arrivedHomeSaid = true;
            ServerPlayerEntity owner = getOwner();
            if (owner != null && !owner.isSleeping()) say(Lines.ARRIVED_HOME);
        }
        if (getNavigation().isIdle() && getRandom().nextInt(200) == 0) {
            BlockPos p = home.pos.add(getRandom().nextBetween(-4, 4), 0, getRandom().nextBetween(-4, 4));
            getNavigation().startMovingTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.7);
        }
    }

    private record HomeSpot(ServerWorld world, BlockPos pos) {}

    @Nullable
    private HomeSpot resolveHome(ServerWorld current) {
        var server = current.getServer();
        if (homePos != null && homeDim != null) {
            ServerWorld w = server.getWorld(homeDim);
            if (w != null) return new HomeSpot(w, homePos);
        }
        if (ownerSpawnPos != null && ownerSpawnDim != null) {
            ServerWorld w = server.getWorld(ownerSpawnDim);
            if (w != null) return new HomeSpot(w, ownerSpawnPos);
        }
        ServerWorld ow = server.getOverworld();
        return new HomeSpot(ow, ow.getSpawnPos());
    }

    private void rememberOwnerSpawn(ServerPlayerEntity owner) {
        BlockPos spawn = owner.getSpawnPointPosition();
        if (spawn != null) {
            ownerSpawnPos = spawn;
            ownerSpawnDim = owner.getSpawnPointDimension();
        }
    }

    // ------------------------------------------------------------------ teleport / stuck

    public boolean teleportToOwner(ServerPlayerEntity owner) {
        if (!(owner.getWorld() instanceof ServerWorld ow)) return false;
        roamTarget = null;
        searching = false;
        return teleportNear(ow, owner.getBlockPos(), 1, 3);
    }

    /** Tìm chỗ an toàn quanh {@code center} rồi dịch chuyển tới (kể cả khác chiều không gian). */
    public boolean teleportNear(ServerWorld target, BlockPos center, int minR, int maxR) {
        for (int i = 0; i < 24; i++) {
            int dx = getRandom().nextBetween(-maxR, maxR);
            int dz = getRandom().nextBetween(-maxR, maxR);
            if (Math.abs(dx) < minR && Math.abs(dz) < minR) continue;
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos p = center.add(dx, dy, dz);
                if (isSafeSpot(target, p)) { doTeleport(target, p); return true; }
            }
        }
        if (isSafeSpot(target, center)) { doTeleport(target, center); return true; }
        // Cùng lắm thì lên mặt đất ngay trên điểm đó.
        int y = target.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        BlockPos top = new BlockPos(center.getX(), y, center.getZ());
        if (isSafeSpot(target, top)) { doTeleport(target, top); return true; }
        return false;
    }

    private boolean isSafeSpot(ServerWorld w, BlockPos p) {
        BlockPos below = p.down();
        if (!w.getBlockState(below).isSideSolidFullSquare(w, below, Direction.UP)) return false;
        if (!w.getFluidState(p).isEmpty() || !w.getFluidState(p.up()).isEmpty()) return false;
        return w.isSpaceEmpty(this, getType().getDimensions().getBoxAt(Vec3d.ofBottomCenter(p)));
    }

    private void doTeleport(ServerWorld target, BlockPos p) {
        getNavigation().stop();
        stuckCount = 0;
        fallDistance = 0;
        Vec3d pos = Vec3d.ofBottomCenter(p);
        if (target == getWorld()) {
            requestTeleport(pos.x, pos.y, pos.z);
        } else {
            // Đổi chiều không gian: Minecraft tạo bản sao giữ nguyên UUID + dữ liệu NBT.
            teleportTo(new TeleportTarget(target, pos, Vec3d.ZERO, getYaw(), getPitch(), TeleportTarget.NO_OP));
        }
        lastStuckCheck = pos;
    }

    /** Mỗi 2 giây kiểm tra: đang muốn đi mà gần như không nhúc nhích → coi như bị kẹt. */
    private boolean checkStuck() {
        if (age % 40 != 0) return false;
        Vec3d now = getPos();
        boolean moving = !getNavigation().isIdle();
        if (moving && now.squaredDistanceTo(lastStuckCheck) < 0.5) stuckCount++;
        else stuckCount = 0;
        lastStuckCheck = now;
        if (stuckCount >= 3) { stuckCount = 0; return true; }
        return false;
    }

    // ------------------------------------------------------------------ interaction

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (getWorld().isClient) return ActionResult.SUCCESS;
        ServerPlayerEntity sp = (ServerPlayerEntity) player;
        ItemStack stack = player.getStackInHand(hand);

        if (ownerUuid == null) {
            CompanionState.Entry existing = CompanionState.get(sp.getServer()).get(player.getUuid());
            if (existing != null && !existing.companion.equals(getUuid())) {
                say(Lines.STRANGER);
                return ActionResult.SUCCESS;
            }
            setOwner(player);
            say(Lines.FIRST_MEET);
            hearts();
            return ActionResult.SUCCESS;
        }
        if (!isOwner(player)) {
            sayRare(Lines.STRANGER, 60);
            return ActionResult.SUCCESS;
        }

        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        if (food != null) {
            heal(Math.max(2, food.nutrition() * 2));
            stack.decrementUnlessCreative(1, player);
            say(Lines.FED);
            hearts();
            return ActionResult.SUCCESS;
        }
        if (stack.isIn(ItemTags.FLOWERS)) {
            stack.decrementUnlessCreative(1, player);
            say(Lines.GIFT_FLOWER);
            hearts();
            return ActionResult.SUCCESS;
        }
        if (player.isSneaking()) {
            setHome((ServerWorld) getWorld(), getBlockPos());
            say(Lines.HOME_SET);
            hearts();
            return ActionResult.SUCCESS;
        }
        if (mode == Mode.FOLLOW) { setMode(Mode.STAY); say(Lines.STAY); }
        else { setMode(Mode.FOLLOW); say(Lines.FOLLOW); }
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ damage / never die

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (getWorld().isClient || ownerUuid == null) return super.damage(source, amount);
        if (source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(source, amount);

        Entity attacker = source.getAttacker();
        if (attacker instanceof PlayerEntity p && isOwner(p)) {
            sayRare(Lines.HURT_BY_OWNER, 100);
            return false;
        }
        if (getHealth() - amount <= 1.0f) {
            faint();
            return false;
        }
        return super.damage(source, amount);
    }

    private void faint() {
        setHealth(getMaxHealth() * 0.5f);
        extinguish();
        clearStatusEffects();
        setTarget(null);
        fallDistance = 0;
        say(Lines.FAINT);
        mode = Mode.HOME;
        if (getWorld() instanceof ServerWorld sw) {
            HomeSpot home = resolveHome(sw);
            if (home != null) teleportNear(home.world, home.pos, 0, 2);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (reason.shouldDestroy() && ownerUuid != null && getWorld() instanceof ServerWorld sw) {
            CompanionState state = CompanionState.get(sw.getServer());
            CompanionState.Entry e = state.get(ownerUuid);
            if (e != null && e.companion.equals(getUuid())) state.remove(ownerUuid);
        }
    }

    @Override public boolean cannotDespawn() { return true; }
    @Override public boolean canImmediatelyDespawn(double distanceSquared) { return false; }
    @Override protected boolean isDisallowedInPeaceful() { return false; }

    @Override
    public boolean canTarget(LivingEntity target) {
        if (target instanceof PlayerEntity p && isOwner(p)) return false;
        if (target instanceof LyMeowzEntity) return false;
        return super.canTarget(target);
    }

    // ------------------------------------------------------------------ events from tracker

    /** Gọi khi chủ vừa vào game và tìm thấy cô ấy. */
    public void onOwnerReturned(ServerPlayerEntity owner) {
        if (mode == Mode.HOME) setMode(Mode.FOLLOW);
        say(Lines.WELCOME_BACK);
        hearts();
    }

    // ------------------------------------------------------------------ save / load

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (ownerUuid != null) nbt.putUuid("LyOwner", ownerUuid);
        nbt.putString("LyMode", mode.name());
        if (homePos != null && homeDim != null) {
            nbt.putLong("LyHome", homePos.asLong());
            nbt.putString("LyHomeDim", homeDim.getValue().toString());
        }
        if (ownerSpawnPos != null && ownerSpawnDim != null) {
            nbt.putLong("LyOwnerSpawn", ownerSpawnPos.asLong());
            nbt.putString("LyOwnerSpawnDim", ownerSpawnDim.getValue().toString());
        }
        brain.writeNbt(nbt);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        ownerUuid = nbt.containsUuid("LyOwner") ? nbt.getUuid("LyOwner") : null;
        try { mode = Mode.valueOf(nbt.getString("LyMode")); } catch (IllegalArgumentException e) { mode = Mode.FOLLOW; }
        if (nbt.contains("LyHome")) {
            homePos = BlockPos.fromLong(nbt.getLong("LyHome"));
            homeDim = worldKey(nbt.getString("LyHomeDim"));
        }
        if (nbt.contains("LyOwnerSpawn")) {
            ownerSpawnPos = BlockPos.fromLong(nbt.getLong("LyOwnerSpawn"));
            ownerSpawnDim = worldKey(nbt.getString("LyOwnerSpawnDim"));
        }
        brain.readNbt(nbt);
    }

    @Nullable
    private static RegistryKey<World> worldKey(String id) {
        Identifier i = Identifier.tryParse(id);
        return i == null ? null : RegistryKey.of(RegistryKeys.WORLD, i);
    }
}
