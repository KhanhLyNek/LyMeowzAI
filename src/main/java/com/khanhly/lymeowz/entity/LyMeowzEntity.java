package com.khanhly.lymeowz.entity;

import com.khanhly.lymeowz.chat.Brain;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.compat.LyMeowzBase;
import com.khanhly.lymeowz.config.LyConfig;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.mind.MindData;
import com.khanhly.lymeowz.speech.Speech;
import com.khanhly.lymeowz.survival.Camp;
import com.khanhly.lymeowz.survival.ItemNames;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Survival;
import com.khanhly.lymeowz.survival.Tools;
import com.khanhly.lymeowz.survival.task.StoreTask;
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
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Một "người chơi" AI độc lập.
 *
 * <ul>
 *   <li>Không có chủ: cô ấy tự sống theo lộ trình sinh tồn (gỗ → đá → nhà → ruộng, chuồng → giường → sắt → kim cương).</li>
 *   <li>Thường loanh quanh trong ~30 block quanh người chơi gần nhất; không có ai thì ở nhà của mình.</li>
 *   <li>Mỗi người chơi có một độ thân thiết riêng; chỉ bạn bè mới nhờ được việc (đi theo, về nhà...).</li>
 *   <li>Hỏi "toạ độ" thì cô ấy đọc toạ độ và đứng đợi, tối thì đốt lửa trại, có quái thì xây hộp đá trú.</li>
 *   <li>Không có tên cho tới khi ai đó dùng bảng tên đặt cho cô ấy.</li>
 *   <li>Tự vệ: tránh creeper, đánh quái lại gần, mặc giáp tốt nhất, cầm khiên; tối thì về giường ngủ.</li>
 * </ul>
 * Dữ liệu lâu dài nằm trong {@link CompanionStore} (không nằm trong NBT).
 */
public class LyMeowzEntity extends LyMeowzBase {
    /** Các mức thân mật: xa lạ (0–20), quen biết (21–40), bạn bè (41–60), bạn thân (61–80), tri kỷ (81–100). */
    public static final float ACQUAINTANCE = 21, FRIEND = 41, CLOSE = 61, SOULMATE = 81;
    /** Việc ở nhà: được phép đi xa người chơi hơn khi đang làm. */
    private static final Set<String> BASE_TASKS = Set.of("xây nhà", "làm ruộng", "thu hoạch", "làm chuồng", "chăn nuôi",
            "đặt giường", "cất đồ", "đào mỏ");
    private static final boolean DEBUG = Boolean.getBoolean("lymeowz.debug");

    public final Brain brain = new Brain(this);
    public final Survival survival = new Survival(this);
    public final com.khanhly.lymeowz.bond.Bond bond = new com.khanhly.lymeowz.bond.Bond(this);
    /** Cử chỉ vụn vặt: đảo mắt, nhảy, ngó quanh, trò ngây ngô. */
    public final Mannerisms manner = new Mannerisms(this);
    private final Camp camp = new Camp();

    @Nullable private LyInventory inventory;
    @Nullable private Mind mind;
    private float hunger = -1;
    private int hungerTimer;
    private int blocksBroken;
    private int lastSpeakTick = -1000;
    @Nullable private LivingEntity lastFightTarget;
    private Vec3 lastStuckCheck = Vec3.ZERO;
    private int stuckCount;
    private boolean nameSynced;
    private boolean dismantling;
    private int greetTicks;
    private final Map<UUID, Long> lastNear = new HashMap<>();
    private long nextChatter;
    private boolean nightCamp;

    public LyMeowzEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        if (this.getNavigation() instanceof GroundPathNavigation nav) nav.setCanOpenDoors(true);
        for (EquipmentSlot slot : EquipmentSlot.values()) this.setDropChance(slot, 0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.STEP_HEIGHT, 0.6);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Creeper.class, 6.0f, 1.0, 1.3));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, true));
        this.goalSelector.addGoal(3, new OpenDoorGoal(this, true));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        addMonsterTargetGoal(2, e -> !(e instanceof Creeper) && e.distanceToSqr(this) < 12 * 12 && !isSleeping()
                && (getHealth() > 8 || mind().p().brave > 0.5f));
    }

    // ------------------------------------------------------------------ dữ liệu

    /**
     * Luôn tra lại (một lần tra HashMap): entity ở chunk xa có thể bị "ẩn" rồi hiện lại với cùng đối tượng Java.
     */
    @Nullable
    public CompanionStore.Data data() {
        return CompanionStore.get(getUUID());
    }

    private CompanionStore.Spot here() {
        return new CompanionStore.Spot(Compat.dimId(level().dimension()), getBlockX(), getBlockY(), getBlockZ());
    }

    private MinecraftServer server() {
        return ((ServerLevel) level()).getServer();
    }

    public Mind mind() {
        CompanionStore.Data d = data();
        if (d != null) {
            if (d.mind == null) { d.mind = new MindData(); CompanionStore.markDirty(); }
            if (mind == null || mind.data() != d.mind) mind = new Mind(this, d.mind);
        } else if (mind == null) {
            mind = new Mind(this, new MindData());
        }
        return mind;
    }

    public float affinity(Player p) {
        return mind().affinity(p.getUUID());
    }

    /** Tên do người chơi đặt, hoặc null nếu chưa có tên. */
    @Nullable
    public String displayName() {
        CompanionStore.Data d = data();
        if (d != null && d.name != null) return d.name;
        Component c = getCustomName();
        return c == null ? null : c.getString();
    }

    public void setHome(ServerLevel level, BlockPos pos) {
        CompanionStore.Data d = data();
        if (d == null) return;
        d.home = new CompanionStore.Spot(Compat.dimId(level.dimension()), pos.getX(), pos.getY(), pos.getZ());
        CompanionStore.markDirty();
    }

    /** Nhà của cô ấy (cùng chiều không gian), hoặc null. */
    @Nullable
    public BlockPos getHomePos() {
        return structurePos(data() == null ? null : data().home);
    }

    /** Vị trí một công trình của cô ấy ("house", "farm", "pen", "chest", "bed") nếu ở cùng chiều không gian. */
    @Nullable
    public BlockPos structure(String key) {
        CompanionStore.Data d = data();
        return d == null ? null : structurePos(d.structures.get(key));
    }

    @Nullable
    private BlockPos structurePos(@Nullable CompanionStore.Spot s) {
        if (s == null || !s.dim.equals(Compat.dimId(level().dimension()))) return null;
        return new BlockPos(s.x, s.y, s.z);
    }

    public int storedCount() {
        BlockPos chest = structure("chest");
        return chest == null || !(level() instanceof ServerLevel sl) ? 0 : StoreTask.stored(sl, chest);
    }

    // ------------------------------------------------------------------ yêu cầu của người chơi

    @Nullable
    public CompanionStore.Request request() {
        CompanionStore.Data d = data();
        return d == null ? null : d.request;
    }

    public void setRequest(String type, ServerPlayer player, long durationTicks, @Nullable BlockPos at) {
        CompanionStore.Data d = data();
        if (d == null) return;
        CompanionStore.Request r = new CompanionStore.Request();
        r.type = type;
        r.player = player.getUUID().toString();
        r.playerName = player.getName().getString();
        r.until = durationTicks <= 0 ? Long.MAX_VALUE : level().getGameTime() + durationTicks;
        r.at = at == null ? null : new CompanionStore.Spot(Compat.dimId(level().dimension()), at.getX(), at.getY(), at.getZ());
        d.request = r;
        CompanionStore.markDirty();
        survival.interrupt((ServerLevel) level());
        getNavigation().stop();
        camp.reset(at);
    }

    public void clearRequest() {
        CompanionStore.Data d = data();
        if (d == null || d.request == null) return;
        d.request = null;
        CompanionStore.markDirty();
        if (camp.enclosed()) dismantling = true;
    }

    @Nullable
    private ServerPlayer requester(CompanionStore.Request r) {
        try {
            UUID id = UUID.fromString(r.player);
            ServerPlayer p = server().getPlayerList().getPlayer(id);
            return p != null ? p : com.khanhly.lymeowz.command.SurvivalTest.fakePlayer(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Người cô ấy đang "để ý": người nhờ việc, hoặc người chơi gần nhất trong 32 block. */
    @Nullable
    public ServerPlayer focusPlayer() {
        CompanionStore.Request r = request();
        if (r != null) {
            ServerPlayer p = requester(r);
            if (p != null) return p;
        }
        return level() instanceof ServerLevel sl ? nearestPlayer(sl, 32) : null;
    }

    @Nullable
    public ServerPlayer nearestPlayer(ServerLevel level, double range) {
        ServerPlayer best = null;
        double bd = range * range;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = p.distanceToSqr(this);
            if (d < bd) { bd = d; best = p; }
        }
        ServerPlayer fake = com.khanhly.lymeowz.command.SurvivalTest.fakeNear(this, range);
        if (fake != null && fake.distanceToSqr(this) < bd) best = fake;
        return best;
    }

    // ------------------------------------------------------------------ nói

    /** Tự ghép câu theo ý muốn nói + cảm xúc + tính cách rồi nói ra (kèm biểu cảm bằng hành động). */
    public void speak(Speech.Act act, Speech.Ctx ctx) {
        String line = Speech.build(this, act, ctx);
        if (!line.isEmpty()) sayRaw(line, ctx.listener instanceof ServerPlayer sp ? sp : null);
        Expr e = switch (act) {
            case PRAISED, SHY, NAMED -> Expr.SHY;
            case GIFT_THANKS, SAVED_THANKS, MET_AT_SPOT, WELCOME_BACK -> Expr.HAPPY;
            case INSULTED, REFUSE, HURT_BY_OWNER, NOT_CLOSE -> Expr.ANGRY;
            case CONFUSED, STRANGER -> Expr.CONFUSED;
            case FAINT, HUNGRY -> Expr.SAD;
            default -> null;
        };
        if (e != null) express(e);
    }

    // ------------------------------------------------------------------ biểu cảm

    /** Cảm xúc thể hiện bằng hành động (cô ấy ít nói nên "nói" bằng cử chỉ). */
    public enum Expr { HAPPY, LOVE, SHY, ANGRY, SAD, CONFUSED }

    private int exprTicks;
    @Nullable private Expr expr;

    public void express(Expr e) {
        if (!(level() instanceof ServerLevel sl) || isSleeping()) return;
        expr = e;
        exprTicks = 30;
        double hx = getX(), hy = getY() + getBbHeight() + 0.25, hz = getZ();
        ServerPlayer near = nearestPlayer(sl, 10);
        switch (e) {
            case HAPPY -> {
                if (onGround()) getJumpControl().jump();           // nhảy cẫng lên
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE, hx, hy, hz, 3, 0.4, 0.2, 0.4, 1.0);
            }
            case LOVE -> {
                swingArm();
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART, hx, hy, hz, 5, 0.4, 0.3, 0.4, 0.0);
            }
            case SHY -> {
                setPose(Pose.CROUCHING);                           // co người lại
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, hx + 0.3, hy - 0.3, hz, 6, 0.1, 0.1, 0.1, 0.0); // giọt mồ hôi
            }
            case ANGRY -> sl.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, hx, hy, hz, 3, 0.3, 0.2, 0.3, 0.0);
            case SAD -> {
                setPose(Pose.CROUCHING);
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.FALLING_WATER, hx, getEyeY(), hz, 4, 0.2, 0.05, 0.2, 0.0); // nước mắt
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, hx, hy + 0.3, hz, 6, 0.3, 0.05, 0.3, 0.0);       // đám mây đen
            }
            case CONFUSED -> sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, hx, hy, hz, 3, 0.2, 0.1, 0.2, 0.01);
        }
        if (near != null && (e == Expr.SHY || e == Expr.ANGRY)) {
            // Ngại thì quay mặt đi, giận thì quay lưng.
            Vec3 away = position().subtract(near.position()).normalize().scale(3).add(getEyePosition());
            getLookControl().setLookAt(away.x, away.y, away.z, 40f, 40f);
        } else if (near != null && e != Expr.SAD) getLookControl().setLookAt(near, 30f, 30f);
    }

    private void tickExpression(ServerLevel level) {
        if (exprTicks > 0) {
            exprTicks--;
            if (expr == Expr.CONFUSED && exprTicks % 8 == 0)   // nghiêng đầu nhìn quanh
                getLookControl().setLookAt(getX() + getRandom().nextGaussian() * 3, getEyeY() + 0.5, getZ() + getRandom().nextGaussian() * 3, 30f, 30f);
            if (exprTicks == 0 && getPose() == Pose.CROUCHING && !"buồn".equals(survival.currentName())) setPose(Pose.STANDING);
            return;
        }
        if (tickCount % 200 != 0) return;
        MindData m = mind().data();
        if (bond.isSad() && getRandom().nextInt(2) == 0) express(Expr.SAD);
        else if (m.joy > 85 && !survival.busy() && getRandom().nextInt(4) == 0) express(Expr.HAPPY);
    }

    public void speak(Speech.Act act) {
        speak(act, Speech.Ctx.of());
    }

    private void speakRare(Speech.Act act, int cooldownTicks, Speech.Ctx ctx) {
        if (this.tickCount - lastSpeakTick > cooldownTicks) speak(act, ctx);
    }

    public void sayRaw(String line) {
        sayRaw(line, null);
    }

    /** Nói cho người chơi trong 32 block nghe (và cả {@code to} dù ở xa – ví dụ khi trả lời toạ độ). */
    public void sayRaw(String line, @Nullable ServerPlayer to) {
        if (!(level() instanceof ServerLevel sl)) return;
        String n = displayName();
        Component msg = Component.literal("<")
                .append(Component.literal(n != null ? n : "Cô gái lạ").withStyle(n != null ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY))
                .append(Component.literal("> " + line));
        if (to != null) to.sendSystemMessage(msg);
        for (ServerPlayer p : sl.players()) {
            if (p != to && p.distanceToSqr(this) < 32 * 32) p.sendSystemMessage(msg);
        }
        lastSpeakTick = this.tickCount;
        if (com.khanhly.lymeowz.command.SurvivalTest.running())
            com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[survival] <{}> {}", n != null ? n : "Cô gái lạ", line);
    }

    public void hearts() {
        if (level() instanceof ServerLevel sl) Compat.hearts(sl, getX(), getY() + 2.0, getZ());
    }

    // ------------------------------------------------------------------ vòng lặp chính

    @Override
    protected void lyServerTick(ServerLevel level) {
        CompanionStore.Data d = data();
        if (d == null) {
            if (CompanionStore.isRetired(getUUID())) { discard(); return; }
            d = CompanionStore.create(getUUID(), here()); // vừa được /summon
        }
        LyConfig cfg = LyConfig.get();
        tickHunger(level);
        if (tickCount % 100 == 0) { flushInventory(); equipGear(); }
        if (tickCount % 20 == 0) {
            if (d.last == null || !d.last.same(Compat.dimId(level.dimension()), getBlockX(), getBlockY(), getBlockZ())) {
                d.last = here();
                CompanionStore.markDirty();
            }
            syncName(level, d);
        }
        // Như một người chơi thật: thế giới quanh cô ấy luôn chạy.
        if (tickCount % 5 == 0) Compat.keepChunk(level, blockPosition(), cfg.chunkLoadRadius);

        ServerPlayer focus = focusPlayer();
        if (tickCount % 20 == 0) {
            mind().tickSecond(level, focus, survival.busy() && !survival.currentName().equals("nghỉ") && !isSleeping(),
                    isSleeping() ? "ngủ" : survival.currentName());
            noticePlayers(level);
            if (!isSleeping()) bond.tick(level);
            com.khanhly.lymeowz.mind.Diary.tick(this, level);
        }
        LivingEntity tgt = getTarget();
        if (lastFightTarget != null && !lastFightTarget.isAlive() && lastFightTarget instanceof Enemy)
            mind().event(Mind.Event.KILLED_MOB, null, Speech.mobName(lastFightTarget.getType()), lastFightTarget.blockPosition());
        lastFightTarget = tgt != null && tgt.isAlive() ? tgt : null;
        if (tgt != null && tgt.isAlive() && tickCount % 10 == 0) holdBestWeapon();
        if (isSprinting() && getNavigation().isDone()) setSprinting(false);
        if (greetTicks > 0) tickGreet();
        else tickExpression(level);

        if (isSleeping()) { tickSleeping(level); return; }
        if (greetTicks == 0 && exprTicks == 0) manner.tick(level);
        if (dismantling) {
            survival.tickMaintenance(level);
            if (!camp.dismantle(this, level)) { dismantling = false; nightCamp = false; }
            return;
        }

        CompanionStore.Request r = d.request;
        if (r != null && level.getGameTime() > r.until) {
            if ("WAIT".equals(r.type)) speakRare(Speech.Act.RELEASED, 200, Speech.Ctx.of());
            clearRequest();
            r = null;
        }
        if (r != null) {
            ServerPlayer who = requester(r);
            switch (r.type) {
                case "FOLLOW" -> { if (who != null) { tickFollow(level, who); return; } }
                case "WAIT" -> { tickWait(level, r, who); return; }
                case "HOME" -> { if (tickGoHome(level)) return; clearRequest(); }
                case "VISIT", "FETCH", "DELIVER", "LEAD", "MOVE" -> { tickGoTo(level, r, who); return; }
                case "CAMP" -> {
                    survival.tickMaintenance(level);
                    getNavigation().stop();
                    if (who != null) getLookControl().setLookAt(who, 10f, 10f);
                    return;
                }
                default -> clearRequest();
            }
        }
        if (tickNight(level)) return;
        tickIndependent(level, cfg);
    }

    // ------------------------------------------------------------------ sống độc lập

    private void tickIndependent(ServerLevel level, LyConfig cfg) {
        ServerPlayer anchor = nearestPlayer(level, cfg.noticePlayerRange);
        if (anchor != null && (affinity(anchor) < -30 || bond.hasLeft(anchor))) anchor = null; // tránh người mình ghét / đã bỏ đi
        BlockPos center;
        int radius;
        if (anchor != null) {
            double dist = distanceTo(anchor);
            int stay = cfg.stayNearPlayer;
            double tolerance = BASE_TASKS.contains(survival.currentName()) ? cfg.noticePlayerRange : stay + 14;
            if (dist > tolerance) {
                survival.interrupt(level);
                travelTo(level, anchor.blockPosition(), dist);
                return;
            }
            center = anchor.blockPosition();
            radius = stay;
        } else {
            BlockPos home = getHomePos();
            center = home != null ? home : blockPosition();
            radius = cfg.homeWorkRadius;
            if (home != null && !BASE_TASKS.contains(survival.currentName()) && home.distSqr(blockPosition()) > 96 * 96) {
                survival.interrupt(level);
                travelTo(level, home, Math.sqrt(home.distSqr(blockPosition())));
                return;
            }
        }
        survival.tick(level, center, radius);
        maybeChatter(level, anchor);
    }

    /** Đi bộ (chạy nếu xa) tới một chỗ, từng chặng ~24 block. */
    private void travelTo(ServerLevel level, BlockPos to, double dist) {
        if (getNavigation().isDone() || tickCount % 60 == 0) {
            setSprinting(dist > 12);
            Vec3 dir = Vec3.atBottomCenterOf(to).subtract(position());
            double step = Math.min(dist, 24);
            Vec3 hop = position().add(dir.normalize().scale(step));
            BlockPos hp = BlockPos.containing(hop);
            int y = dist <= 24 ? to.getY() : (level.isLoaded(hp) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hp.getX(), hp.getZ()) : to.getY());
            if (!getNavigation().moveTo(hop.x, y, hop.z, 1.0)) stuckCount++;
        }
        if (checkStuck() || stuckCount > 8) {
            stuckCount = 0;
            // Kẹt thật sự (hố sâu, nước...): nhảy tới chỗ đứng an toàn gần đó cho đỡ kẹt.
            BlockPos near = blockPosition().offset(getRandom().nextIntBetweenInclusive(-6, 6), 0, getRandom().nextIntBetweenInclusive(-6, 6));
            teleportNear(level, near, 1, 4);
        }
    }

    /** Bạn thân ở gần thì thỉnh thoảng bắt chuyện (rất ít). */
    private void maybeChatter(ServerLevel level, @Nullable ServerPlayer anchor) {
        if (!LyConfig.get().idleChatter || anchor == null || level.getGameTime() < nextChatter) return;
        nextChatter = level.getGameTime() + 20 * 60 * (8 + getRandom().nextInt(12));
        if (affinity(anchor) >= CLOSE && distanceTo(anchor) < 16 && getTarget() == null
                && getRandom().nextFloat() < 0.4f + 0.5f * mind().p().talkative) {
            speak(Speech.Act.SMALLTALK, Speech.Ctx.of().to(anchor));
        }
    }

    /** Ghi nhận ai đang ở gần; bạn thân mới tới thì cúi chào kiểu người chơi (ngồi xuống đứng lên). */
    private void noticePlayers(ServerLevel level) {
        long now = level.getGameTime();
        for (ServerPlayer p : level.players()) {
            double d = p.distanceTo(this);
            if (d > 24 || p.isSpectator()) continue;
            Long last = lastNear.put(p.getUUID(), now);
            if (d < 7 && (last == null || now - last > 20 * 120) && affinity(p) >= CLOSE && greetTicks == 0) {
                greetTicks = 40;
                getLookControl().setLookAt(p, 30f, 30f);
                if (getRandom().nextBoolean()) speak(Speech.Act.GREET, Speech.Ctx.of().to(p));
            }
        }
    }

    private void tickGreet() {
        greetTicks--;
        setPose(greetTicks > 0 && (greetTicks / 5) % 2 == 0 ? Pose.CROUCHING : Pose.STANDING);
    }

    // ------------------------------------------------------------------ đêm: ngủ / cắm trại / làm đêm

    private boolean nightOwl() {
        var p = mind().p();
        return p.brave > 0.65f && p.diligent > 0.5f;
    }

    /** @return true nếu đêm nay cô ấy đang lo ngủ / trú. */
    private boolean tickNight(ServerLevel level) {
        boolean night = level.dimensionType().hasSkyLight() && Compat.isNight(level);
        if (!night) {
            if (nightCamp) { nightCamp = false; if (camp.enclosed()) dismantling = true; }
            return false;
        }
        // Cú đêm có giáp + kiếm thì làm đêm (thường là đào mỏ).
        if (nightOwl() && Survival.bestTier(inventoryWithHand(), Tools.Kind.SWORD) >= 2 && getHealth() > 12) return false;
        BlockPos bed = structure("bed");
        if (bed != null && !(level.getBlockState(bed).getBlock() instanceof BedBlock)) {
            CompanionStore.Data d = data();
            if (d != null) { d.structures.remove("bed"); d.bed = null; CompanionStore.markDirty(); }
            bed = null;
        }
        if (bed != null && bed.distSqr(blockPosition()) < 128 * 128) {
            if (getTarget() != null && getTarget().isAlive()) return false; // đánh xong đã
            survival.interrupt(level);
            double d = Vec3.atBottomCenterOf(bed).distanceTo(position());
            if (d > 2.0) { travelTo(level, bed, d); return true; }
            getNavigation().stop();
            startSleeping(bed);
            return true;
        }
        // Không có giường: có bạn ở gần thì thức cùng bạn; không thì cắm trại tại chỗ.
        ServerPlayer p = nearestPlayer(level, 12);
        if (p != null && affinity(p) >= FRIEND) return false;
        if (!nightCamp) { nightCamp = true; survival.interrupt(level); camp.reset(blockPosition()); }
        survival.tickMaintenance(level);
        getNavigation().stop();
        camp.hold(this, level, true, hostilesNear(level, 16) || level.canSeeSky(blockPosition()));
        return true;
    }

    private void tickSleeping(ServerLevel level) {
        getNavigation().stop();
        boolean night = Compat.isNight(level);
        boolean threat = getTarget() != null && getTarget().isAlive() && getTarget().distanceToSqr(this) < 16;
        if (!night || threat || getHealth() < 6) { stopSleeping(); return; }
        if (tickCount % 20 == 0) {
            MindData m = mind().data();
            m.energy = Math.min(100, m.energy + 1.5f);
            m.fear = Math.max(0, m.fear - 1);
        }
    }

    private boolean hostilesNear(ServerLevel level, double r) {
        return !level.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(r), m -> m instanceof Enemy && m.isAlive()).isEmpty();
    }

    // ------------------------------------------------------------------ yêu cầu: đi theo / đợi / về nhà

    private void tickFollow(ServerLevel level, ServerPlayer p) {
        boolean other = p.level() != level;
        double dist = other ? Double.MAX_VALUE : distanceTo(p);
        if (other || dist > LyConfig.get().teleportDistance) {
            if (!p.isSpectator() && p.level() instanceof ServerLevel pl) teleportNear(pl, p.blockPosition(), 1, 3);
            return;
        }
        defend(p);
        if (getTarget() != null && getTarget().isAlive()) return;
        if (dist > 10) {
            survival.interrupt(level);
            travelTo(level, p.blockPosition(), dist);
            return;
        }
        if (p.getDeltaMovement().horizontalDistanceSqr() > 0.003 && dist > 4) {
            survival.interrupt(level);
            if (getNavigation().isDone() || tickCount % 20 == 0) getNavigation().moveTo(p, 1.0);
            return;
        }
        survival.tick(level, p.blockPosition(), 10);
    }

    private void defend(ServerPlayer p) {
        if (tickCount % 10 != 0) return;
        if (getTarget() != null && getTarget().isAlive()) return;
        LivingEntity threat = p.getLastHurtByMob();
        if (!(threat instanceof Enemy) || !threat.isAlive()) threat = p.getLastHurtMob();
        if (threat instanceof Enemy && threat.isAlive() && !(threat instanceof Creeper)
                && threat.level() == level() && threat.distanceToSqr(this) < 20 * 20 && (getHealth() > 8 || mind().p().brave > 0.5f)) {
            setTarget(threat);
        }
    }

    /** Đợi ở toạ độ đã nói: đứng yên, tối thì đốt lửa, có quái thì xây hộp trú; người kia tới thì dỡ ra. */
    private void tickWait(ServerLevel level, CompanionStore.Request r, @Nullable ServerPlayer who) {
        BlockPos at = r.at == null ? blockPosition() : new BlockPos(r.at.x, r.at.y, r.at.z);
        if (who != null && who.level() == level && who.distanceTo(this) < 6) {
            mind().event(Mind.Event.OWNER_RETURNED, who, null, null);
            speak(Speech.Act.MET_AT_SPOT, Speech.Ctx.of().to(who));
            clearRequest();
            return;
        }
        if (getTarget() != null && getTarget().isAlive() && getHealth() > 8) return; // tự vệ trước
        double d = Vec3.atBottomCenterOf(at).distanceTo(position());
        if (d > 3 && !camp.enclosed()) { travelTo(level, at, d); return; }
        survival.tickMaintenance(level);
        getNavigation().stop();
        if (who != null && who.level() == level) getLookControl().setLookAt(who, 10f, 10f);
        boolean dark = Compat.isNight(level) || level.getMaxLocalRawBrightness(blockPosition()) < 7;
        camp.hold(this, level, dark, hostilesNear(level, 16) || (dark && getHealth() < 14));
    }

    /** Đi tới chỗ của một yêu cầu (tới người chơi, tới chỗ đồ rơi, tới rương, dẫn đường, dọn nhà). */
    private void tickGoTo(ServerLevel level, CompanionStore.Request r, @Nullable ServerPlayer who) {
        if (getTarget() != null && getTarget().isAlive() && getHealth() > 8) return;
        BlockPos to;
        if ("VISIT".equals(r.type)) {
            if (who == null || who.level() != level) { clearRequest(); return; }
            to = who.blockPosition();
            if (distanceTo(who) > 300) { teleportNear(level, to.offset(20, 0, 20), 2, 6); return; }
        } else {
            if (r.at == null) { clearRequest(); return; }
            to = new BlockPos(r.at.x, r.at.y, r.at.z);
        }
        double d = Vec3.atBottomCenterOf(to).distanceTo(position());
        double near = "VISIT".equals(r.type) ? 3.5 : "DELIVER".equals(r.type) ? 3.0 : 4.0;
        if (d > near) {
            survival.interrupt(level);
            if ("MOVE".equals(r.type) && level.isLoaded(to)) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ());
                if (Math.abs(y - to.getY()) > 2 && r.at != null) { r.at.y = y; CompanionStore.markDirty(); }
            }
            travelTo(level, to, d);
            return;
        }
        getNavigation().stop();
        bond.arrived(level, r, who);
    }

    /** Đi về nhà. @return false khi đã tới nơi (hoặc không có nhà). */
    private boolean tickGoHome(ServerLevel level) {
        BlockPos home = getHomePos();
        if (home == null) return false;
        double d = Vec3.atBottomCenterOf(home).distanceTo(position());
        if (d <= 3) return false;
        survival.interrupt(level);
        if (d > 600) { teleportNear(level, home, 0, 2); return true; }
        travelTo(level, home, d);
        return true;
    }

    // ------------------------------------------------------------------ dịch chuyển / kẹt

    /** Tìm chỗ an toàn quanh {@code center} rồi dịch chuyển tới (kể cả khác chiều không gian). */
    public boolean teleportNear(ServerLevel target, BlockPos center, int minR, int maxR) {
        if (!target.isLoaded(center)) { Compat.keepChunk(target, center, 2); target.getChunk(center); }
        for (int i = 0; i < 24; i++) {
            int dx = getRandom().nextIntBetweenInclusive(-maxR, maxR);
            int dz = getRandom().nextIntBetweenInclusive(-maxR, maxR);
            if (Math.abs(dx) < minR && Math.abs(dz) < minR) continue;
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos p = center.offset(dx, dy, dz);
                if (isSafeSpot(target, p)) { doTeleport(target, p); return true; }
            }
        }
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
        if (!target.dimensionType().hasCeiling()) {
            int y = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
            BlockPos top = new BlockPos(center.getX(), y, center.getZ());
            if (isSafeSpot(target, top)) { doTeleport(target, top); return true; }
        }
        return false;
    }

    private boolean isSafeSpot(ServerLevel l, BlockPos p) {
        BlockPos below = p.below();
        if (!l.isLoaded(p) || !l.getBlockState(below).isFaceSturdy(l, below, Direction.UP)) return false;
        if (!l.getFluidState(p).isEmpty() || !l.getFluidState(p.above()).isEmpty()) return false;
        return l.noCollision(this, getType().getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(p)));
    }

    private void doTeleport(ServerLevel target, BlockPos p) {
        getNavigation().stop();
        stuckCount = 0;
        resetFallDistance();
        Vec3 pos = Vec3.atBottomCenterOf(p);
        Compat.keepChunk(target, p, 2);
        if (target == level()) teleportTo(pos.x, pos.y, pos.z);
        else Compat.changeDimension(this, target, pos, getYRot(), getXRot());
        lastStuckCheck = pos;
    }

    private boolean checkStuck() {
        if (tickCount % 40 != 0) return false;
        Vec3 now = position();
        boolean moving = !getNavigation().isDone();
        if (moving && now.distanceToSqr(lastStuckCheck) < 0.5) stuckCount++;
        else stuckCount = 0;
        lastStuckCheck = now;
        if (stuckCount >= 4) { stuckCount = 0; return true; }
        return false;
    }

    /** Bị kẹt: đang ở trong chuồng thì mở cổng đi ra; không thì nhảy ra chỗ trống gần đó. */
    public void unstuck(ServerLevel level) {
        BlockPos pen = structure("pen");
        if (pen != null && com.khanhly.lymeowz.survival.task.PenTask.inside(pen).inflate(0.3, 0, 0.3).contains(position())) {
            survival.interrupt(level);
            survival.forceTask(level, new com.khanhly.lymeowz.survival.task.SimpleTasks.LeavePen(this, pen));
            return;
        }
        BlockPos near = blockPosition().offset(getRandom().nextIntBetweenInclusive(-5, 5), 0, getRandom().nextIntBetweenInclusive(-5, 5));
        teleportNear(level, near, 2, 5);
    }

    // ------------------------------------------------------------------ tên (bảng tên)

    private void syncName(ServerLevel level, CompanionStore.Data d) {
        Component c = getCustomName();
        String now = c == null ? null : c.getString();
        if (!nameSynced) {
            nameSynced = true;
            if (now == null && d.name != null) { setCustomName(Component.literal(d.name)); return; }
        }
        if (Objects.equals(now, d.name) || now == null) return;
        d.name = now;
        CompanionStore.markDirty();
        ServerPlayer by = nearestPlayer(level, 8);
        mind().event(Mind.Event.NAMED, by, now, blockPosition());
        speak(Speech.Act.NAMED, Speech.Ctx.of().to(by));
        hearts();
    }

    // ------------------------------------------------------------------ tương tác

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(Items.NAME_TAG)) return InteractionResult.PASS; // để bảng tên của Minecraft làm việc
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        ServerPlayer sp = (ServerPlayer) player;
        float a = affinity(player);
        if (!isSleeping() && bond.onInteract(sp, stack)) return InteractionResult.SUCCESS;
        if (isSleeping()) { speakRare(Speech.Act.SHY, 100, Speech.Ctx.of().to(player)); return InteractionResult.SUCCESS; }

        if (player.isShiftKeyDown() && stack.isEmpty()) {
            if (a >= CLOSE) openInventory(sp);
            else speakRare(Speech.Act.NOT_CLOSE, 40, Speech.Ctx.of().to(player));
            return InteractionResult.SUCCESS;
        }
        if (a < -30) { speakRare(Speech.Act.REFUSE, 60, Speech.Ctx.of().to(player)); return InteractionResult.SUCCESS; }

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null && getHunger() < 18) {
            addHunger(food.nutrition());
            heal(2);
            mind().event(Mind.Event.FED, player, ItemNames.of(stack.getItem()), null);
            speak(Speech.Act.GIFT_THANKS, Speech.Ctx.of().to(player).item(stack.getItem(), 1));
            useOne(stack, player);
            hearts();
            return InteractionResult.SUCCESS;
        }
        if (Compat.isFlower(stack)) {
            mind().event(Mind.Event.GIFT, player, "hoa", null);
            mind().gift(player, 3);
            speak(Speech.Act.PRAISED, Speech.Ctx.of().to(player));
            express(Expr.LOVE);
            useOne(stack, player);
            hearts();
            return InteractionResult.SUCCESS;
        }
        if (!stack.isEmpty()) {
            ItemStack left = inv().put(stack.copy());
            int given = stack.getCount() - left.getCount();
            if (given > 0) {
                int value = Tools.value(stack) * Math.max(1, given / 8);
                mind().event(Mind.Event.GIFT, player, ItemNames.of(stack.getItem()), null);
                mind().gift(player, value);
                speak(Speech.Act.GIFT_THANKS, Speech.Ctx.of().to(player).item(stack.getItem(), given));
                if (!player.getAbilities().instabuild) stack.shrink(given);
            } else {
                speak(Speech.Act.FAILED, Speech.Ctx.of().to(player).action("cất đồ").text("túi đầy mất rồi"));
            }
            return InteractionResult.SUCCESS;
        }
        // Tay không: bạn bè thì rủ đi cùng / để cô ấy tự do; người lạ thì cô ấy ngại.
        if (a >= FRIEND) {
            CompanionStore.Request r = request();
            if (r != null && "FOLLOW".equals(r.type) && r.player.equals(player.getUUID().toString())) {
                clearRequest();
                speak(Speech.Act.RELEASED, Speech.Ctx.of().to(player));
            } else {
                setRequest("FOLLOW", sp, 0, null);
                speak(Speech.Act.ACK, Speech.Ctx.of().to(player).text("FOLLOW"));
            }
        } else if (a >= ACQUAINTANCE) {
            speakRare(Speech.Act.GREET, 60, Speech.Ctx.of().to(player));
        } else {
            getLookControl().setLookAt(player, 30f, 30f);
            speakRare(mind().knows(player.getUUID()) ? Speech.Act.SHY : Speech.Act.STRANGER, 80, Speech.Ctx.of().to(player));
            mind().event(Mind.Event.CHATTED, player, null, null);
        }
        return InteractionResult.SUCCESS;
    }

    private static void useOne(ItemStack stack, Player player) {
        if (!player.getAbilities().instabuild) stack.shrink(1);
    }

    // ------------------------------------------------------------------ bị thương / ngất

    @Override
    protected boolean lyAllowHurt(ServerLevel level, DamageSource source, float amount) {
        if (source.is(DamageTypes.GENERIC_KILL)) return true;
        if (isSleeping()) stopSleeping();
        if (com.khanhly.lymeowz.command.SurvivalTest.running())
            com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[survival]     (bị thương {} bởi {} ở {}, đang {})", amount, source.getMsgId(), blockPosition().toShortString(), survival.currentName());
        Entity attacker = source.getEntity();
        if (attacker instanceof Player p) {
            express(Expr.ANGRY);
            mind().event(Mind.Event.HIT_BY_PLAYER, p, null, blockPosition());
            bond.onHit(p);
            CompanionStore.Request r = request();
            if (r != null && r.player.equals(p.getUUID().toString())) clearRequest();
            if (affinity(p) > FRIEND) { speakRare(Speech.Act.HURT_BY_OWNER, 100, Speech.Ctx.of().to(p)); return false; }
        } else if (attacker instanceof Mob mob && amount > 0) {
            mind().event(Mind.Event.HURT_BY_MOB, null, Speech.mobName(mob.getType()), blockPosition());
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
        mind().event(Mind.Event.FAINTED, null, null, blockPosition());
        bond.onFaint(level);
        speak(Speech.Act.FAINT);
        clearRequest();
        survival.interrupt(level);
        BlockPos home = getHomePos();
        if (home != null) teleportNear(level, home, 0, 2);
    }

    @Override
    public void remove(RemovalReason reason) {
        flushInventory();
        if (level() instanceof ServerLevel sl) { survival.interrupt(sl); camp.cancel(this, sl); }
        if (DEBUG) com.khanhly.lymeowz.LyMeowzMod.LOGGER.info("[debug] remove {} at {}", reason, blockPosition().toShortString());
        super.remove(reason);
        if (reason.shouldDestroy() && level() instanceof ServerLevel) {
            CompanionStore.remove(getUUID());
            CompanionStore.forgetRetired(getUUID());
        }
    }

    @Override public boolean removeWhenFarAway(double distanceSquared) { return false; }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof Player p) return affinity(p) < -30 && super.canAttack(target);
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

    /** Mặc giáp tốt nhất có trong túi (thay đồ kém hơn), cầm khiên tay trái. */
    private void equipGear() {
        if (data() == null) return;
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            Equippable eq = s.get(DataComponents.EQUIPPABLE);
            if (eq == null) continue;
            EquipmentSlot slot = eq.slot();
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR || Tools.armorRank(s) == 0) continue;
            ItemStack worn = getItemBySlot(slot);
            if (!worn.isEmpty() && Tools.armorRank(s) <= Tools.armorRank(worn)) continue;
            setItemSlot(slot, inv().takeSlot(i));
            if (!worn.isEmpty()) inv().put(worn);
            if (level() instanceof ServerLevel sl)
                sl.playSound(null, blockPosition(), eq.equipSound().value(), SoundSource.NEUTRAL, 1f, 1f);
        }
        if (getOffhandItem().isEmpty()) {
            ItemStack shield = inv().takeOne(s -> s.is(Items.SHIELD));
            if (!shield.isEmpty()) setItemSlot(EquipmentSlot.OFFHAND, shield);
        }
    }

    public void openInventory(ServerPlayer player) {
        stowHand();
        LyInventory container = inv();
        String n = displayName();
        player.openMenu(new SimpleMenuProvider((id, playerInv, p) -> ChestMenu.threeRows(id, playerInv, container),
                Component.literal("Túi đồ của " + (n != null ? n : "cô ấy"))));
    }

    /** Đưa tài nguyên cho bạn (giữ lại dụng cụ, giáp, bàn, lò và 4 phần ăn). */
    public int giveResourcesTo(ServerPlayer to) {
        if (!(level() instanceof ServerLevel sl)) return 0;
        stowHand();
        int keptFood = 0, given = 0;
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty()) continue;
            boolean tool = s.isDamageableItem();
            boolean gear = s.is(Items.FURNACE) || s.is(Items.CRAFTING_TABLE) || s.get(DataComponents.EQUIPPABLE) != null;
            if (tool || gear) continue;
            if (Tools.isGoodFood(s) && keptFood < 4) { keptFood += s.getCount(); continue; }
            ItemStack out = inv().takeSlot(i);
            given += out.getCount();
            ItemEntity e = new ItemEntity(sl, getX(), getEyeY() - 0.3, getZ(), out);
            Vec3 dir = to.position().subtract(position()).normalize().scale(0.3);
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
        if (path != null) {
            setSprinting(Math.sqrt(pos.distSqr(blockPosition())) > 14);
            getNavigation().moveTo(path, speed);
        }
    }

    public void placeBlock(ServerLevel level, BlockPos pos, BlockState state) {
        getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        // Hàng rào cần tự nối với hàng xóm; cửa, giường (2 nửa) thì đặt nguyên trạng.
        BlockState placed = state.getBlock() instanceof net.minecraft.world.level.block.CrossCollisionBlock
                ? Block.updateFromNeighbourShapes(state, level, pos) : state;
        level.setBlock(pos, placed, Block.UPDATE_ALL);
        var sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 2f, sound.getPitch() * 0.8f);
        swingArm();
    }

    /** Đặt block xuống một ô trống cạnh mình (không chắn chỗ người chơi đứng). */
    @Nullable
    public BlockPos placeNear(ServerLevel level, BlockState state) {
        BlockPos feet = blockPosition();
        ServerPlayer near = nearestPlayer(level, 6);
        for (int r = 1; r <= 2; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos p = feet.offset(dx, dy, dz);
                if (!level.getBlockState(p).canBeReplaced() || !level.getFluidState(p).isEmpty()) continue;
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                if (near != null && (near.blockPosition().equals(p) || near.blockPosition().above().equals(p))) continue;
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

    /** Không chặt/đào/săn quanh giường của người chơi, và không phá công trình của chính mình. */
    public boolean isProtected(BlockPos p) {
        if (!(level() instanceof ServerLevel sl)) return false;
        int r = LyConfig.get().protectRadius;
        String dim = Compat.dimId(sl.dimension());
        for (ServerPlayer pl : sl.getServer().getPlayerList().getPlayers()) {
            GlobalPos spawn = Compat.respawn(pl);
            if (spawn == null || !Compat.dimId(spawn.dimension()).equals(dim)) continue;
            if (spawn.pos().distSqr(p) <= (double) r * r) return true;
        }
        CompanionStore.Data d = data();
        if (d != null) {
            for (var e : d.structures.entrySet()) {
                CompanionStore.Spot s = e.getValue();
                if (!s.dim.equals(dim)) continue;
                double dx = s.x + 2 - p.getX(), dz = s.z + 2 - p.getZ(), dy = s.y - p.getY();
                if (dx * dx + dz * dz <= 7 * 7 && Math.abs(dy) < 8) return true;
            }
        }
        return false;
    }

    /** Block này có thuộc công trình của cô ấy (nhà, ruộng, chuồng...) không. */
    public boolean ownsBlock(BlockPos p) {
        CompanionStore.Data d = data();
        if (d == null) return false;
        String dim = Compat.dimId(level().dimension());
        for (var e : d.structures.entrySet()) {
            CompanionStore.Spot s = e.getValue();
            if (!s.dim.equals(dim) || e.getKey().startsWith("plan:")) continue;
            int size = e.getKey().equals("pen") ? 6 : 5;
            if (p.getX() >= s.x - 1 && p.getX() <= s.x + size && p.getZ() >= s.z - 1 && p.getZ() <= s.z + size
                    && p.getY() >= s.y - 1 && p.getY() <= s.y + 4) return true;
        }
        return false;
    }

    public void onBlockBroken(BlockState state) {
        if (++blocksBroken % 16 == 0) addHunger(-1);
    }

    /** Ghi nhận đã chế được một món (không khoe ra miệng). */
    public void announceCrafted(net.minecraft.world.item.Item item) {
        if (item == Items.STICK || item == Items.OAK_PLANKS) return;
        mind().event(Mind.Event.CRAFTED, null, ItemNames.of(item), null);
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

    private void tickHunger(ServerLevel level) {
        if (++hungerTimer % 20 != 0) return;
        getHunger();
        int rate = isSleeping() ? 20 * 90 : isSprinting() ? 20 * 25 : 20 * 40;
        if (hungerTimer % rate == 0) addHunger(-1);
        if (hungerTimer % 80 == 0) {
            if (hunger >= 18 && getHealth() < getMaxHealth()) { heal(1); addHunger(-0.5f); }
            else if (hunger <= 0 && getHealth() > 2) hurtServer(level, damageSources().starve(), 1);
        }
    }

    // ------------------------------------------------------------------ sự kiện từ bên ngoài

    /** Một người chơi vừa giết con quái đang đánh cô ấy. */
    public void savedBy(ServerPlayer p, LivingEntity mob) {
        mind().event(Mind.Event.SAVED, p, Speech.mobName(mob.getType()), blockPosition());
        if (getRandom().nextFloat() < 0.7f) speak(Speech.Act.SAVED_THANKS, Speech.Ctx.of().to(p));
    }

    /** Debug: tóm tắt trạng thái. */
    public String status() {
        CompanionStore.Request r = request();
        return (isSleeping() ? "đang ngủ" : survival.currentName())
                + (r != null ? ", yêu cầu " + r.type + " của " + r.playerName : "")
                + ", máu " + (int) getHealth() + "/" + (int) getMaxHealth() + ", đói " + getHunger() + "/20"
                + ", giai đoạn " + com.khanhly.lymeowz.survival.Roadmap.current(this).title;
    }
}
