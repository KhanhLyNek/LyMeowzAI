package com.khanhly.lymeowz.bond;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.mind.MindData;
import com.khanhly.lymeowz.speech.Speech;
import com.khanhly.lymeowz.survival.Crafting;
import com.khanhly.lymeowz.survival.ItemNames;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Roadmap;
import com.khanhly.lymeowz.survival.Tools;
import com.khanhly.lymeowz.survival.task.FarmTask;
import com.khanhly.lymeowz.survival.task.HouseTask;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Các sự kiện theo mức thân mật: mỗi sự kiện có điều kiện, thời gian chờ và tỉ lệ xuất hiện phụ thuộc
 * tính cách, tâm trạng, thời tiết, giờ trong ngày, nên mỗi lần chơi diễn ra hơi khác nhau.
 *
 * <pre>
 *  Xa lạ (0–20)     1 ánh nhìn dè dặt · 2 đề nghị đổi đồ
 *  Quen (21–40)     3 chào buổi sáng · 4 nhờ việc nhỏ · 5 chia đồ ăn (sống)
 *  Bạn (41–60)      6 nấu ăn cho bạn · 7 đi cùng khám phá · 8 hang động bí ẩn · 9 ngày cô ấy buồn
 *  Thân (61–80)     10 an ủi khi chết (nhặt lại đồ) · 11 quà bất ngờ · 12 cùng xây nhà · 13 kể chuyện bên lửa
 *  Tri kỷ (81–100)  14 rủ đi cắm trại · 15 bảo vệ người chơi · 16 lời hứa · 17 nhớ nhung
 *  Khi giảm         18 giận dỗi · 19 rời đi
 * </pre>
 */
public class Bond {
    private static final long DAY = 24000, MIN = 1200;

    private final LyMeowzEntity ly;

    /** Câu hỏi đang chờ người chơi trả lời / lời đề nghị đang treo. */
    private static final class Pending {
        String type;          // TRADE, EXPLORE, CAVE, CAMP
        long until;
        Item want;
        int wantCount;
        ItemStack offer = ItemStack.EMPTY;
        BlockPos at;
    }

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Integer> nightWatch = new HashMap<>();
    private int nightChecks;
    private long watchedNight = -1;
    private boolean sad;
    private int cheerSeconds;
    @Nullable private UUID adventureWith;
    private long adventureUntil;
    private boolean adventureRewarded;
    // Cắm trại (sự kiện 14).
    @Nullable private UUID campWith;
    private int campStep;
    private long campStepAt;
    private BlockPos campAt;
    // Đồ nhặt lại giúp người chơi (sự kiện 10).
    private final List<ItemStack> parcel = new ArrayList<>();
    @Nullable private UUID parcelFor;
    private long lastMissDay = -1, lastSurpriseCheck = -1;

    public Bond(LyMeowzEntity ly) {
        this.ly = ly;
    }

    // ------------------------------------------------------------------ tiện ích

    private Speech.Voice voice(Player p) {
        return new Speech.Voice(ly, p);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void say(Player p, String line) {
        ly.sayRaw(line, p instanceof ServerPlayer sp ? sp : null);
    }

    private MindData.Relation rel(Player p) {
        return ly.mind().relationOf(p);
    }

    private float a(Player p) {
        return ly.affinity(p);
    }

    private static long day(ServerLevel l) {
        return l.getOverworldClockTime() / DAY;
    }

    private static long tod(ServerLevel l) {
        return Math.floorMod(l.getOverworldClockTime(), DAY);
    }

    private float sociable() {
        var p = ly.mind().p();
        return 0.4f + 0.6f * (p.social + p.talkative) / 2f;
    }

    public boolean isSad() {
        return sad;
    }

    public boolean isSulkingAt(Player p) {
        MindData.Relation r = ly.mind().relationOf(p.getUUID());
        return r != null && r.sulkUntil > ly.level().getGameTime();
    }

    public boolean hasLeft(Player p) {
        MindData.Relation r = ly.mind().relationOf(p.getUUID());
        return r != null && r.left;
    }

    /** Món cô ấy thích nhất trong thế giới này (chọn một lần, theo tính cách). */
    public Item favorite() {
        MindData m = ly.mind().data();
        if (m.favorite == null) {
            Item[] pool = {Items.POPPY, Items.DANDELION, Items.CORNFLOWER, Items.SWEET_BERRIES, Items.APPLE, Items.COOKIE,
                    Items.CAKE, Items.HONEY_BOTTLE, Items.GLOW_BERRIES, Items.AMETHYST_SHARD, Items.LAPIS_LAZULI, Items.PUMPKIN_PIE};
            m.favorite = BuiltInRegistries.ITEM.getKey(pool[Math.floorMod(ly.getUUID().hashCode(), pool.length)]).toString();
        }
        Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(m.favorite));
        return it == null ? Items.POPPY : it;
    }

    // ------------------------------------------------------------------ nhịp chính (mỗi giây)

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        pending.entrySet().removeIf(e -> e.getValue().until < now);
        Mind mind = ly.mind();
        MindData m = mind.data();

        // 9. Ngày cô ấy buồn.
        if (!sad && m.joy < 22 && m.lifeTicks > 20 * 60 * 5) sad = true;
        else if (sad && m.joy > 50) sad = false;

        boolean night = Compat.isNight(level) && level.dimensionType().hasSkyLight();
        long d = day(level), t = tod(level);
        if (night && watchedNight != d) { watchedNight = d; nightWatch.clear(); nightChecks = 0; }
        if (night) nightChecks++;

        for (ServerPlayer p : nearPlayers(level, 32)) {
            MindData.Relation r = rel(p);
            double dist = p.distanceTo(ly);
            float aff = a(p);
            if (dist < 24) returnAfterAbsence(level, p, r, now);
            if (dist < 24) r.seenAt = now;
            if (night && dist < 16) nightWatch.merge(p.getUUID(), 1, Integer::sum);

            if (r.sulkUntil > now) { turnAway(p); continue; }      // 18. đang giận: quay lưng
            if (r.left) continue;                                     // 19. đã bỏ đi: tránh mặt

            if (r.firstMetDay < 0 && dist < 9) { firstMeet(level, p, r); continue; }   // 1
            if (aff < 21) { offerTrade(level, p, r, now); continue; }                   // 2

            // Từ quen biết trở lên.
            if (!night && r.greetDay != d && (t > 23200 || t < 1600) && dist < 24) { goodMorning(level, p, r, d); continue; } // 3
            if (sad) { cheerUp(p, dist); continue; }                                     // 9
            if (p.getFoodData().getFoodLevel() <= 6 && dist < 16 && now - r.lastFood > 5 * MIN) { shareFood(level, p, r, now); continue; } // 5, 6
            if (aff < 61 && r.quest == null && !night && now - r.lastFavor > DAY && chance(0.004f * sociable())) { askFavor(level, p, r, now); continue; } // 4
            if (r.quest != null && now > r.questUntil) { r.quest = null; say(p, voice(p).pick("Thôi, việc lúc nãy không cần nữa đâu", "À, việc kia mình tự lo rồi") + voice(p).ending()); }

            if (aff >= 41) {
                if (adventureWith == null && ly.request() == null && now - r.lastExplore > DAY && dist < 12 && farFromHome(p)
                        && chance(0.02f * (0.5f + ly.mind().p().curious))) { askExplore(p, r, now); continue; }   // 7
                if (ly.request() == null && now - r.lastCave > 2 * DAY && !night && chance(0.01f * (0.5f + ly.mind().p().curious))) {
                    if (inviteCave(level, p, r, now)) continue;                               // 8
                }
            }
            if (aff >= 61 && night && now - r.lastStory > 4 * MIN && dist < 8 && campfireNear(level)) { fireStory(p, r, now); continue; } // 13
            if (aff >= 81) {
                protect(p);                                                               // 15
                if (aff >= 85 && campWith == null && ly.request() == null && now - r.lastCampAsk > 2 * DAY && m.joy > 60
                        && !level.isRaining() && t > 10500 && t < 12000 && dist < 32) { askCamp(p, r, now); continue; } // 14
            }
            if (aff >= 100 && !r.promised) { promise(p, r); }                             // 16
        }

        // 13b. Đêm đầu tiên an toàn bên nhau.
        if (!night && watchedNight == d - 1 && nightChecks > 60) {
            for (var e : nightWatch.entrySet()) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
                if (p == null || e.getValue() < nightChecks * 0.7) continue;
                MindData.Relation r = rel(p);
                if (r.safeNight) continue;
                r.safeNight = true;
                mind.bond(p, 5, false);
                Speech.Voice v = voice(p);
                say(p, v.pick("Đêm qua... may mà có " + v.you + " ở gần", "Phù, qua được một đêm rồi. Cảm ơn " + v.you + " nha", cap(v.self) + " không sợ lắm vì có " + v.you) + v.ending());
            }
            nightWatch.clear();
            nightChecks = 0;
        }

        // 11. Quà bất ngờ, 17. Nhớ nhung: mỗi ngày kiểm tra một lần.
        if (lastSurpriseCheck != d && t > 6000) { lastSurpriseCheck = d; surpriseGift(level, now, d); }
        if (lastMissDay != d && t > 9000) { lastMissDay = d; missSigns(level, now, d); }

        // Phiêu lưu trong hang kết thúc.
        if (adventureWith != null && now > adventureUntil) adventureWith = null;
        // Cảnh cắm trại.
        if (campWith != null) campTick(level, now);
    }

    private List<ServerPlayer> nearPlayers(ServerLevel level, double range) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (!p.isSpectator() && p.distanceTo(ly) < range) out.add(p);
        ServerPlayer fake = com.khanhly.lymeowz.command.SurvivalTest.fakeNear(ly, range);
        if (fake != null && !out.contains(fake)) out.add(fake);
        return out;
    }

    private boolean chance(float p) {
        return ly.getRandom().nextFloat() < p;
    }

    // ------------------------------------------------------------------ 1. ánh nhìn dè dặt

    private void firstMeet(ServerLevel level, ServerPlayer p, MindData.Relation r) {
        r.firstMetDay = day(level);
        boolean armed = Tools.weaponScore(p.getMainHandItem()) >= 10 || p.getMainHandItem().is(ItemTags.AXES);
        // Lùi lại giữ khoảng cách (cầm vũ khí thì lùi xa hơn).
        Vec3 away = ly.position().subtract(p.position()).normalize().scale(armed ? 8 : 4);
        Vec3 to = ly.position().add(away);
        ly.getNavigation().moveTo(to.x, to.y, to.z, armed ? 1.2 : 0.9);
        ly.getLookControl().setLookAt(p, 30f, 30f);
        String line = armed ? ly.getRandom().nextBoolean() ? "...Cất cái đó đi được không?" : "Đ-đừng lại gần..."
                : ly.getRandom().nextBoolean() ? "...Chào." : "Cậu là ai?";
        say(p, line);
        ly.mind().event(Mind.Event.CHATTED, p, null, null);
    }

    // ------------------------------------------------------------------ 2. đề nghị đổi đồ

    /** Món cô ấy đang cần (theo giai đoạn) và số lượng muốn đổi. */
    private ItemStack wanted() {
        LyInventory all = ly.inventoryWithHand();
        return switch (Roadmap.current(ly)) {
            case WOOD -> new ItemStack(Items.OAK_LOG, 8);
            case STONE, SHELTER -> all.count(ItemTags.STONE_TOOL_MATERIALS) < 64 ? new ItemStack(Items.COBBLESTONE, 16) : new ItemStack(Items.OAK_LOG, 8);
            case FARM -> new ItemStack(Items.WHEAT_SEEDS, 4);
            case PEN -> new ItemStack(Items.OAK_LOG, 8);
            case BED -> new ItemStack(Items.WOOL.white(), 3);
            default -> new ItemStack(Items.IRON_INGOT, 3);
        };
    }

    private static boolean sameKind(ItemStack held, Item want) {
        if (held.isEmpty()) return false;
        if (want == Items.OAK_LOG) return held.is(ItemTags.LOGS);
        if (want == Items.COBBLESTONE) return held.is(ItemTags.STONE_TOOL_MATERIALS);
        if (want == Items.WOOL.white()) return held.is(ItemTags.WOOL);
        if (want == Items.IRON_INGOT) return held.is(Items.IRON_INGOT) || held.is(Items.RAW_IRON);
        return held.is(want);
    }

    private void offerTrade(ServerLevel level, ServerPlayer p, MindData.Relation r, long now) {
        if (now - r.lastTrade < 8 * MIN || pending.containsKey(p.getUUID()) || p.distanceTo(ly) > 6) return;
        ItemStack want = wanted();
        if (!sameKind(p.getMainHandItem(), want.getItem())) return;
        ItemStack offer = tradeGood();
        if (offer.isEmpty()) return;
        r.lastTrade = now;
        Pending pd = new Pending();
        pd.type = "TRADE";
        pd.until = now + 2 * MIN;
        pd.want = want.getItem();
        pd.wantCount = want.getCount();
        pd.offer = offer;
        pending.put(p.getUUID(), pd);
        ly.getLookControl().setLookAt(p, 30f, 30f);
        say(p, "Cậu... có thể đổi " + pd.wantCount + " " + ItemNames.of(p.getMainHandItem().getItem()) + " lấy "
                + offer.getCount() + " " + ItemNames.of(offer.getItem()) + " không? (đưa cho mình là được)");
    }

    /** Thứ cô ấy có dư để đổi: đồ ăn chín, táo, cây con, than... */
    private ItemStack tradeGood() {
        LyInventory inv = ly.inv();
        Item[] goods = {Items.APPLE, Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN,
                Items.COAL, Items.OAK_SAPLING, Items.WHEAT, Items.LEATHER};
        for (Item g : goods) {
            int have = inv.count(g);
            int give = Tools.isGoodFood(new ItemStack(g)) ? Math.min(have - 2, 3) : Math.min(have / 2, 4);
            if (give > 0) return new ItemStack(g, give);
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ 3. chào buổi sáng

    private void goodMorning(ServerLevel level, ServerPlayer p, MindData.Relation r, long d) {
        r.greetDay = d;
        if (!chance(0.5f + 0.5f * sociable())) return;
        Speech.Voice v = voice(p);
        ly.getLookControl().setLookAt(p, 30f, 30f);
        ly.swingArm(); // vẫy tay
        String line;
        if (level.isThundering()) line = v.pick("Sấm to quá... " + v.you + " ở trong nhà đi", "Bão rồi, hôm nay đừng đi xa nha");
        else if (level.isRaining()) line = v.pick("Trời mưa rồi, ngủ thêm chút đi nhỉ", "Mưa rồi... hôm nay chắc " + v.self + " ở nhà thôi", "Chào buổi sáng, mưa lạnh ghê");
        else line = v.pick("Chào buổi sáng~", "Sáng rồi! Hôm nay trời đẹp ghê", cap(v.you) + " dậy sớm vậy", "Chào " + v.you + ", ngủ ngon không");
        say(p, line + v.ending());
    }

    // ------------------------------------------------------------------ 4. nhờ việc nhỏ

    private void askFavor(ServerLevel level, ServerPlayer p, MindData.Relation r, long now) {
        r.lastFavor = now;
        Speech.Voice v = voice(p);
        Fox fox = level.getEntitiesOfClass(Fox.class, ly.getBoundingBox().inflate(32), LivingEntity::isAlive).stream().findFirst().orElse(null);
        int kind = fox != null ? 2 : ly.getRandom().nextInt(2);
        r.questHave = 0;
        r.questUntil = now + DAY;
        switch (kind) {
            case 0 -> { r.quest = "FLOWERS"; r.questNeed = 5; say(p, cap(v.you) + " ơi... tìm giúp " + v.self + " 5 bông hoa được không? " + cap(v.self) + " muốn trang trí nhà" + v.ending()); }
            case 1 -> { r.quest = "LOGS"; r.questNeed = 10; say(p, "Ừm... " + v.you + " đốn giúp " + v.self + " 10 khúc gỗ được không? " + cap(v.self) + " đang thiếu" + v.ending()); }
            default -> { r.quest = "FOX"; r.questNeed = 1; say(p, "Có con cáo đang rình trộm gà kìa! " + cap(v.you) + " đuổi nó đi giúp " + v.self + " với" + v.ending()); }
        }
    }

    private boolean questItem(String quest, ItemStack s) {
        return switch (quest) {
            case "FLOWERS" -> Compat.isFlower(s);
            case "LOGS" -> s.is(ItemTags.LOGS);
            default -> false;
        };
    }

    private void questDone(ServerPlayer p, MindData.Relation r) {
        r.quest = null;
        ly.mind().bond(p, 5, false);
        Speech.Voice v = voice(p);
        ItemStack gift = smallGift();
        say(p, v.pick("Xong rồi! Cảm ơn " + v.you + " nhiều", "Oa, " + v.you + " giỏi ghê", cap(v.self) + " biết là " + v.you + " làm được mà")
                + (gift.isEmpty() ? "" : ". Cái này cho " + v.you + " nè") + v.ending());
        if (!gift.isEmpty()) throwTo(p, gift);
        ly.hearts();
    }

    /** Fox bị giết (sự kiện 4, nhiệm vụ đuổi cáo). */
    public void onKill(ServerPlayer p, LivingEntity dead) {
        MindData.Relation r = ly.mind().relationOf(p.getUUID());
        if (r != null && "FOX".equals(r.quest) && dead instanceof Fox) questDone(p, r);
    }

    private ItemStack smallGift() {
        LyInventory inv = ly.inv();
        Item[] g = {Items.IRON_INGOT, Items.COOKED_BEEF, Items.BREAD, Items.APPLE, Items.COAL, Items.OAK_SAPLING};
        for (Item it : g) {
            int n = inv.count(it);
            if (n >= 2) { int give = Math.min(3, n / 2); inv.consume(it, give); return new ItemStack(it, give); }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ 5, 6. chia đồ ăn / nấu ăn

    private void shareFood(ServerLevel level, ServerPlayer p, MindData.Relation r, long now) {
        r.lastFood = now;
        Speech.Voice v = voice(p);
        boolean friend = a(p) >= 41;
        ItemStack food = ly.inv().takeOne(s -> Tools.isGoodFood(s) && !Tools.isRawMeat(s));
        if (food.isEmpty() && friend) {
            // Bạn bè: có thịt sống thì nướng chín rồi mới đưa; không có thịt thì ra vườn hái.
            ItemStack raw = ly.inv().takeOne(Tools::isRawMeat);
            if (!raw.isEmpty()) {
                Item cooked = Tools.smeltResult(raw);
                if (cooked != null) {
                    level.playSound(null, ly.blockPosition(), SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.NEUTRAL, 1f, 1f);
                    food = new ItemStack(cooked, Math.min(2, raw.getCount()));
                    if (raw.getCount() > food.getCount()) { raw.shrink(food.getCount()); ly.inv().put(raw); }
                } else ly.inv().put(raw);
            }
            if (food.isEmpty()) food = harvestForFriend(level);
        }
        if (food.isEmpty() && !friend) food = ly.inv().takeOne(Tools::isRawMeat);
        if (food.isEmpty()) food = fromChest(level, s -> friend ? Tools.isGoodFood(s) : Tools.isFood(s) && !s.is(Items.ROTTEN_FLESH));
        if (food.isEmpty()) {
            say(p, "Mugyu~ " + v.self + " cũng hết đồ ăn rồi...");
            return;
        }
        boolean cooked = !Tools.isRawMeat(food);
        throwTo(p, food);
        if (friend && cooked) say(p, v.pick("Ăn đi, nóng đấy!", cap(v.self) + " mới nướng xong, ăn đi", "Đói hả? Nè, của " + v.you) + v.ending());
        else say(p, v.pick("Đói hả... cái này còn sống, " + v.you + " tự nướng nha", cap(v.self) + " chỉ có cái này thôi", "Cầm lấy đi") + v.ending());
        ly.mind().bond(p, cooked ? 2 : 1, true);
    }

    /** Ra ruộng hái cà rốt / khoai tây chín. */
    private ItemStack harvestForFriend(ServerLevel level) {
        BlockPos farm = ly.structure("farm");
        if (farm == null) return ItemStack.EMPTY;
        for (int x = 0; x < FarmTask.SIZE; x++) for (int z = 0; z < FarmTask.SIZE; z++) {
            BlockPos c = farm.offset(x, 0, z);
            BlockState st = level.getBlockState(c);
            if (!(st.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(st)) continue;
            Item got = st.is(Blocks.CARROTS) ? Items.CARROT : st.is(Blocks.POTATOES) ? Items.BAKED_POTATO : null;
            if (got == null) continue;
            level.setBlock(c, crop.getStateForAge(0), 3);
            return new ItemStack(got, 2);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack fromChest(ServerLevel level, java.util.function.Predicate<ItemStack> what) {
        BlockPos chest = ly.structure("chest");
        if (chest == null) return ItemStack.EMPTY;
        BlockState st = level.getBlockState(chest);
        if (!(st.getBlock() instanceof ChestBlock cb)) return ItemStack.EMPTY;
        Container c = ChestBlock.getContainer(cb, st, level, chest, true);
        if (c == null) return ItemStack.EMPTY;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) { ItemStack out = s.split(Math.min(3, s.getCount())); c.setChanged(); return out; }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ 7. đi cùng khám phá

    private boolean farFromHome(ServerPlayer p) {
        BlockPos home = ly.getHomePos();
        return home != null && home.distSqr(p.blockPosition()) > 80 * 80;
    }

    private void askExplore(ServerPlayer p, MindData.Relation r, long now) {
        r.lastExplore = now;
        Pending pd = new Pending();
        pd.type = "EXPLORE";
        pd.until = now + MIN;
        pending.put(p.getUUID(), pd);
        Speech.Voice v = voice(p);
        say(p, v.pick("Cho " + v.self + " đi cùng nhé?", cap(v.you) + " đi đâu vậy... " + v.self + " đi với được không?", "Ừm... đi chung không?"));
    }

    // ------------------------------------------------------------------ 8. hang động bí ẩn

    private boolean inviteCave(ServerLevel level, ServerPlayer p, MindData.Relation r, long now) {
        MindData.Place cave = ly.mind().nearest(Mind.PlaceKind.CAVE, ly.blockPosition());
        if (cave == null || cave.seen < ly.mind().data().lifeTicks - 3 * DAY) return false;
        BlockPos at = new BlockPos(cave.x, cave.y, cave.z);
        if (at.distSqr(ly.blockPosition()) > 96 * 96) return false;
        r.lastCave = now;
        Pending pd = new Pending();
        pd.type = "CAVE";
        pd.until = now + MIN;
        pd.at = at;
        pending.put(p.getUUID(), pd);
        Speech.Voice v = voice(p);
        say(p, v.pick("Này... lúc đi lượm củi " + v.self + " thấy một cái hang ", cap(v.self) + " tìm thấy một cửa hang tối om ")
                + where(at) + ". " + cap(v.you) + " đi xem cùng " + v.self + " không?");
        return true;
    }

    private String where(BlockPos pos) {
        double dx = pos.getX() - ly.getX(), dz = pos.getZ() - ly.getZ();
        String[] dirs = {"bắc", "đông bắc", "đông", "đông nam", "nam", "tây nam", "tây", "tây bắc"};
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        return "ở phía " + dirs[(int) Math.floorMod(Math.round(ang / 45.0), 8L)] + ", cách đây chừng " + (int) (Math.round(Math.sqrt(dx * dx + dz * dz) / 10.0) * 10) + " bước";
    }

    /** Người chơi vừa cứu cô ấy (gọi từ Mind khi có sự kiện SAVED). */
    public void onSaved(Player p) {
        if (adventureWith != null && adventureWith.equals(p.getUUID()) && !adventureRewarded) {
            adventureRewarded = true;
            ly.mind().bond(p, 8, false);
        }
    }

    // ------------------------------------------------------------------ 9. ngày buồn

    private void cheerUp(ServerPlayer p, double dist) {
        if (dist < 4) cheerSeconds++;
        else cheerSeconds = Math.max(0, cheerSeconds - 1);
        if (cheerSeconds >= 30) cheered(p);
    }

    private void cheered(Player p) {
        sad = false;
        cheerSeconds = 0;
        MindData m = ly.mind().data();
        m.joy = Math.min(100, m.joy + 35);
        ly.mind().bond(p, 3, false);
        Speech.Voice v = voice(p);
        say(p, v.pick("Cảm ơn " + v.you + "... " + v.self + " thấy đỡ hơn rồi", "...Có " + v.you + " ngồi đây, " + v.self + " vui hơn rồi") + ".");
        ly.hearts();
    }

    // ------------------------------------------------------------------ 10. an ủi khi người chơi chết

    public void onPlayerRespawn(ServerPlayer p) {
        if (a(p) < 61 || isSulkingAt(p) || hasLeft(p)) return;
        GlobalPos death = p.getLastDeathLocation().orElse(null);
        ServerLevel level = (ServerLevel) ly.level();
        parcel.clear();
        parcelFor = p.getUUID();
        if (death != null && Compat.dimId(death.dimension()).equals(Compat.dimId(level.dimension()))
                && death.pos().distSqr(ly.blockPosition()) < 160 * 160) {
            ly.setRequest("FETCH", p, 5 * MIN, death.pos());
        } else if (p.level() == level) {
            ly.setRequest("VISIT", p, 5 * MIN, null);
        }
    }

    // ------------------------------------------------------------------ 11. quà bất ngờ

    private void surpriseGift(ServerLevel level, long now, long d) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            MindData.Relation r = ly.mind().relationOf(p.getUUID());
            if (r == null || r.affinity < 61 || r.left || ly.request() != null) continue;
            boolean anniversary = r.firstMetDay >= 0 && d > r.firstMetDay && (d - r.firstMetDay) % 7 == 0;
            if (!anniversary && (now - r.lastSurprise < 2 * DAY || !chance(0.35f * (0.5f + ly.mind().p().kind)))) continue;
            BlockPos chest = playerChest(level, p);
            if (chest == null) continue;
            r.lastSurprise = now;
            ly.setRequest("DELIVER", p, 6 * MIN, chest);
            return;
        }
    }

    /** Rương gần giường của người chơi. */
    @Nullable
    private BlockPos playerChest(ServerLevel level, ServerPlayer p) {
        GlobalPos spawn = Compat.respawn(p);
        if (spawn == null || !Compat.dimId(spawn.dimension()).equals(Compat.dimId(level.dimension()))) return null;
        if (spawn.pos().distSqr(ly.blockPosition()) > 200 * 200) return null;
        for (BlockPos c : BlockPos.betweenClosed(spawn.pos().offset(-8, -3, -8), spawn.pos().offset(8, 3, 8))) {
            if (level.isLoaded(c) && level.getBlockState(c).getBlock() instanceof ChestBlock) return c.immutable();
        }
        return null;
    }

    private ItemStack giftFor(ServerLevel level) {
        Item[] g = {Items.DIAMOND, Items.EMERALD, Items.IRON_INGOT, Items.GOLD_INGOT, Items.COOKED_BEEF, Items.BREAD, Items.APPLE};
        for (Item it : g) {
            int n = ly.inv().count(it);
            if (n > 0) { int give = Math.max(1, Math.min(n / 2, it == Items.DIAMOND ? 1 : 4)); ly.inv().consume(it, give); return new ItemStack(it, give); }
        }
        ItemStack s = fromChest(level, x -> x.is(Items.IRON_INGOT) || x.is(Items.DIAMOND) || Tools.isGoodFood(x));
        if (!s.isEmpty()) return s;
        return new ItemStack(Items.POPPY, 3);
    }

    private void deliver(ServerLevel level, ServerPlayer p, BlockPos chest) {
        BlockState st = level.getBlockState(chest);
        if (!(st.getBlock() instanceof ChestBlock cb)) return;
        Container c = ChestBlock.getContainer(cb, st, level, chest, true);
        if (c == null) return;
        ItemStack gift = giftFor(level);
        boolean put = false;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) { c.setItem(i, gift); put = true; break; }
        if (!put) { ly.inv().put(gift); return; }
        c.setChanged();
        level.blockEvent(chest, st.getBlock(), 1, 1);
        level.playSound(null, chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1f);
        level.blockEvent(chest, st.getBlock(), 1, 0);
        ly.swingArm();
        MindData.Relation r = rel(p);
        boolean anniversary = r.firstMetDay >= 0 && (day(level) - r.firstMetDay) % 7 == 0;
        String name = ly.displayName();
        placeSign(level, chest, anniversary ? new String[]{"Hôm nay là ngày", "tụi mình gặp nhau", "Tặng cậu nè~", name != null ? "- " + name : "- cô ấy"}
                : new String[]{"Tặng cậu", "một chút quà", "đừng hỏi vì sao~", name != null ? "- " + name : "- cô ấy"});
    }

    // ------------------------------------------------------------------ 12. cùng xây nhà

    public void onPlayerBuild(ServerPlayer p, BlockPos pos) {
        BlockPos home = ly.getHomePos();
        long now = ly.level().getGameTime();
        MindData.Relation r = rel(p);
        if (home == null || a(p) < 61 || home.distSqr(pos) > 24 * 24 || ly.distanceTo(p) > 24 || now - r.lastBuildHelp < 5 * MIN) return;
        r.lastBuildHelp = now;
        Speech.Voice v = voice(p);
        String idea = v.pick("Thêm chậu hoa ở đây đi!", "Chỗ này để cửa sổ thì đẹp lắm", "Lát sàn gỗ cho ấm đi " + v.you,
                "Cắm đuốc quanh đây kẻo quái vào", "Xây thêm hàng rào trồng hoa nha");
        say(p, idea);
        // Góp vật liệu.
        ItemStack mats = ly.inv().takeOne(s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.STONE_TOOL_MATERIALS) || s.is(Items.TORCH));
        if (!mats.isEmpty()) {
            if (mats.getCount() > 16) { ItemStack keep = mats.split(mats.getCount() - 16); ly.inv().put(keep); }
            throwTo(p, mats);
        }
    }

    // ------------------------------------------------------------------ 13. kể chuyện bên lửa

    private boolean campfireNear(ServerLevel level) {
        for (BlockPos q : BlockPos.betweenClosed(ly.blockPosition().offset(-6, -2, -6), ly.blockPosition().offset(6, 2, 6))) {
            BlockState st = level.getBlockState(q);
            if (st.getBlock() instanceof CampfireBlock && st.getValue(CampfireBlock.LIT)) return true;
        }
        return false;
    }

    private void fireStory(ServerPlayer p, MindData.Relation r, long now) {
        r.lastStory = now;
        Speech.Voice v = voice(p);
        String name = p.getName().getString();
        MindData m = ly.mind().data();
        MindData.Episode best = null;
        for (MindData.Episode e : m.episodes) {
            if (name.equals(e.who) && (e.type.equals("SAVED") || e.type.equals("GIFT") || e.type.equals("NAMED"))) best = e;
        }
        if (best != null && best.type.equals("SAVED")) {
            say(p, "Nhớ hôm " + v.you + " cứu " + v.self + " khỏi " + (best.what != null ? "lũ " + best.what : "quái") + " không? " + cap(v.self) + " vẫn nhớ hoài" + v.ending());
            return;
        }
        if (best != null) {
            String s = Speech.recall(ly, v, best);
            if (s != null) { say(p, s + v.ending()); return; }
        }
        if (!m.episodes.isEmpty()) {
            String s = Speech.recall(ly, v, m.episodes.get(ly.getRandom().nextInt(m.episodes.size())));
            if (s != null) { say(p, s + v.ending()); return; }
        }
        say(p, v.pick("Lửa ấm ghê...", "Ngồi bên lửa thế này, " + v.self + " chẳng muốn đi đâu nữa", "Sao đêm nay sáng quá") + v.ending());
    }

    // ------------------------------------------------------------------ 14. rủ đi cắm trại

    private void askCamp(ServerPlayer p, MindData.Relation r, long now) {
        r.lastCampAsk = now;
        BlockPos spot = campSpot((ServerLevel) ly.level());
        if (spot == null) return;
        Pending pd = new Pending();
        pd.type = "CAMP";
        pd.until = now + MIN;
        pd.at = spot;
        pending.put(p.getUUID(), pd);
        ly.setRequest("VISIT", p, MIN, null);
        Speech.Voice v = voice(p);
        say(p, "Ừm… tối nay " + v.you + " rảnh không? " + cap(v.self) + " biết một chỗ đẹp lắm.");
    }

    /** Bờ hồ, đỉnh đồi, hoặc cánh đồng hoa đã nhớ; không có thì một bãi trống gần đó. */
    @Nullable
    private BlockPos campSpot(ServerLevel level) {
        Mind.PlaceKind[] kinds = {Mind.PlaceKind.WATER, Mind.PlaceKind.VIEW, Mind.PlaceKind.FLOWERS};
        List<BlockPos> options = new ArrayList<>();
        for (Mind.PlaceKind k : kinds) {
            MindData.Place pl = ly.mind().nearest(k, ly.blockPosition());
            if (pl != null && new BlockPos(pl.x, pl.y, pl.z).distSqr(ly.blockPosition()) < 120 * 120) options.add(new BlockPos(pl.x, pl.y, pl.z));
        }
        if (options.isEmpty()) {
            double ang = ly.getRandom().nextDouble() * Math.PI * 2;
            int x = ly.getBlockX() + (int) (Math.cos(ang) * 24), z = ly.getBlockZ() + (int) (Math.sin(ang) * 24);
            if (!level.isLoaded(new BlockPos(x, 0, z))) return null;
            options.add(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z));
        }
        BlockPos p = options.get(ly.getRandom().nextInt(options.size()));
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return new BlockPos(p.getX(), y, p.getZ());
    }

    private void startCamp(ServerPlayer p, BlockPos at) {
        campWith = p.getUUID();
        campAt = at;
        campStep = 0;
        ly.setRequest("LEAD", p, 8 * MIN, at);
    }

    private void campTick(ServerLevel level, long now) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(campWith);
        if (p == null) p = com.khanhly.lymeowz.command.SurvivalTest.fakePlayer(campWith);
        var req = ly.request();
        if (p == null || req == null || !("LEAD".equals(req.type) || "CAMP".equals(req.type))) { campWith = null; return; }
        if (!"CAMP".equals(req.type)) return; // còn đang dẫn đường
        Speech.Voice v = voice(p);
        if (now - campStepAt < 20 * 15) return;
        campStepAt = now;
        switch (campStep++) {
            case 0 -> {
                BlockPos fire = ly.blockPosition().relative(ly.getDirection(), 2);
                if (level.getBlockState(fire).canBeReplaced() && level.getBlockState(fire.below()).isSolid()) {
                    ly.placeBlock(level, fire, Blocks.CAMPFIRE.defaultBlockState());
                    campAt = fire;
                }
                say(p, v.pick("Tới rồi nè. Đẹp không?", "Đây, chỗ này nè. Ngồi đi " + v.you, "Tadaa~ chỗ bí mật của " + v.self));
            }
            case 1 -> {
                ItemStack raw = ly.inv().takeOne(Tools::isRawMeat);
                Item cooked = raw.isEmpty() ? null : Tools.smeltResult(raw);
                ItemStack food = cooked != null ? new ItemStack(cooked, Math.min(2, raw.getCount())) : ly.inv().takeOne(Tools::isGoodFood);
                if (!food.isEmpty()) { throwTo(p, food); say(p, v.pick("Nướng xong rồi, ăn đi, nóng đấy!", "Cho " + v.you + " phần ngon nhất nè")); }
                else say(p, v.pick("Lẽ ra " + v.self + " phải mang đồ ăn theo... Mugyu~", "Quên mang đồ ăn rồi..."));
            }
            case 2, 3 -> {
                ly.getLookControl().setLookAt(ly.getX(), ly.getEyeY() + 10, ly.getZ() + 3, 10f, 10f);
                MindData.Episode memory = null;
                for (MindData.Episode e : ly.mind().data().episodes) if (p.getName().getString().equals(e.who)) memory = e;
                String recall = memory == null ? null : Speech.recall(ly, v, memory);
                say(p, campStep == 3 && recall != null ? recall + "... " + v.self + " vẫn nhớ đó"
                        : v.pick("Nhiều sao quá...", "Ước gì đêm nào cũng như đêm nay", "Ngắm sao với " + v.you + " vui hơn ngắm một mình nhiều"));
            }
            default -> {
                MindData.Relation r = rel(p);
                ly.mind().bond(p, 6, false);
                if (!r.campRewarded) {
                    r.campRewarded = true;
                    ItemStack stone = new ItemStack(Items.AMETHYST_SHARD);
                    stone.set(DataComponents.CUSTOM_NAME, Component.literal("Viên đá ngắm sao"));
                    stone.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Đêm cắm trại đầu tiên"),
                            Component.literal("với " + (ly.displayName() != null ? ly.displayName() : "cô ấy")))));
                    throwTo(p, stone);
                    say(p, "Cái này... cho " + v.you + ". Để nhớ đêm nay nha.");
                } else say(p, v.pick("Lần sau mình lại đi nữa nhé", "Cảm ơn " + v.you + " đã đi cùng " + v.self));
                ly.hearts();
                campWith = null;
                ly.clearRequest();
            }
        }
    }

    // ------------------------------------------------------------------ 15. bảo vệ người chơi

    private void protect(ServerPlayer p) {
        if (p.getHealth() > 8) return;
        LivingEntity threat = p.getLastHurtByMob();
        if (!(threat instanceof Enemy) || !threat.isAlive() || threat.distanceTo(ly) > 24) return;
        if (ly.getTarget() == threat) return;
        ly.setTarget(threat); // lao vào chắn, kể cả khi đang yếu
        ly.setSprinting(true);
        ly.getNavigation().moveTo(threat, 1.2);
        if (chance(0.5f)) say(p, voice(p).pick("Tránh ra! Để " + voice(p).self + " lo!", "Không được đụng vào " + voice(p).you + "!"));
    }

    // ------------------------------------------------------------------ 16. lời hứa

    private void promise(ServerPlayer p, MindData.Relation r) {
        r.promised = true;
        Speech.Voice v = voice(p);
        ly.getLookControl().setLookAt(p, 30f, 30f);
        say(p, cap(v.you) + " biết không... từ giờ dù " + v.you + " đi đâu, " + v.self + " cũng sẽ ở đây chờ " + v.you + " về. Hứa đó.");
        ly.hearts();
    }

    /** Người chơi vào game. */
    public void onPlayerJoin(ServerPlayer p) {
        MindData.Relation r = ly.mind().relationOf(p.getUUID());
        if (r == null || r.left) return;
        if (r.promised && p.level() == ly.level() && ly.request() == null) ly.setRequest("VISIT", p, 5 * MIN, null); // chạy ra đón
    }

    // ------------------------------------------------------------------ 17. nhớ nhung + lâu ngày không gặp

    private void returnAfterAbsence(ServerLevel level, ServerPlayer p, MindData.Relation r, long now) {
        if (r.seenAt < 0) return;
        long days = (now - r.seenAt) / DAY;
        if (days < 3) return;
        Speech.Voice v = voice(p);
        if (r.affinity > 0) {
            float loss = Math.min(10, days - 2);
            r.affinity = Math.max(0, r.affinity - loss); // lâu ngày không gặp, thân giảm chậm
        }
        if (r.affinity >= 61) say(p, cap(v.you) + " đi lâu quá đấy! " + (r.missSigns > 0 ? cap(v.self) + " có để lại mấy tấm biển ở nhà đó" : cap(v.self) + " nhớ " + v.you + " lắm") + "...");
        else if (r.affinity >= 21) say(p, "A... lâu rồi không gặp" + v.ending());
        r.missSigns = 0;
    }

    private void missSigns(ServerLevel level, long now, long d) {
        BlockPos house = ly.structure("house");
        if (house == null) return;
        for (MindData.Relation r : ly.mind().data().relations.values()) {
            if (r.affinity < 61 || r.left || r.seenAt < 0 || now - r.seenAt < 2 * DAY || r.missSigns >= 5) continue;
            long days = (now - r.seenAt) / DAY;
            String did = todayText();
            BlockPos spot = HouseTask.door(house).north(2).east(r.missSigns - 2);
            if (placeSign(level, spot.above(-1).above(), new String[]{"Ngày " + days + ":", did, "...cậu đâu rồi", ""})) r.missSigns++;
            return;
        }
    }

    private String todayText() {
        MindData m = ly.mind().data();
        ly.mind().rollDay();
        if (!m.craftedToday.isEmpty()) return "tớ làm " + m.craftedToday.get(m.craftedToday.size() - 1);
        if (m.today.containsKey("trồng cây")) return "tớ trồng thêm cây";
        if (m.today.containsKey("thu hoạch")) return "tớ ra ruộng";
        if (m.today.containsKey("đào mỏ")) return "tớ đi đào mỏ";
        return ly.getRandom().nextBoolean() ? "trời đẹp lắm" : "tớ ngồi đợi";
    }

    // ------------------------------------------------------------------ 18. giận dỗi, 19. rời đi

    /** Bị người chơi đánh. */
    public void onHit(Player p) {
        MindData.Relation r = rel(p);
        long now = ly.level().getGameTime();
        if (r.sulkUntil < now) say(p, voice(p).pick("Hừ!", "...Đau đó.", "Cậu làm gì vậy hả!"));
        r.sulkUntil = now + 6 * MIN;
        checkLeave(p, r);
    }

    /** Người chơi phá công trình của cô ấy. */
    public void onHouseBroken(Player p) {
        MindData.Relation r = rel(p);
        ly.mind().bond(p, -6, false);
        long now = ly.level().getGameTime();
        if (r.sulkUntil < now && p.distanceTo(ly) < 32) say(p, voice(p).pick("Đó là nhà của mình mà!", "Sao cậu phá đồ của mình...", "Hừ. Đừng đụng vào nhà mình."));
        r.sulkUntil = now + 8 * MIN;
        checkLeave(p, r);
    }

    /** Ngất mà người quen đứng gần không giúp: bị bỏ mặc. */
    public void onFaint(ServerLevel level) {
        for (ServerPlayer p : nearPlayers(level, 12)) {
            if (a(p) >= 21 && ly.getLastHurtByMob() != null) ly.mind().bond(p, -5, false);
        }
    }

    private void checkLeave(Player p, MindData.Relation r) {
        if (r.affinity >= 0 || r.peak < 21 || r.left) return;
        r.left = true;
        ServerLevel level = (ServerLevel) ly.level();
        BlockPos house = ly.structure("house");
        if (house != null) {
            placeSign(level, HouseTask.door(house).north(1), new String[]{"Tớ đi đây.", "Đừng tìm tớ...", "", ""});
            CompanionStore.Data d = ly.data();
            if (d != null) {
                for (String k : new String[]{"house", "farm", "pen", "bed", "chest", "plan:house", "plan:pen"}) d.structures.remove(k);
                d.home = null;
                d.bed = null;
                CompanionStore.markDirty();
            }
        }
        double ang = ly.getRandom().nextDouble() * Math.PI * 2;
        int dist = 150 + ly.getRandom().nextInt(100);
        BlockPos to = new BlockPos(ly.getBlockX() + (int) (Math.cos(ang) * dist), ly.getBlockY(), ly.getBlockZ() + (int) (Math.sin(ang) * dist));
        if (p instanceof ServerPlayer sp) ly.setRequest("MOVE", sp, 20 * MIN, to);
    }

    // ------------------------------------------------------------------ móc vào tương tác / chat

    /** Người chơi cầm đồ chuột phải vào cô ấy. @return true nếu sự kiện đã xử lý. */
    public boolean onInteract(ServerPlayer p, ItemStack stack) {
        MindData.Relation r = rel(p);
        long now = ly.level().getGameTime();
        Speech.Voice v = voice(p);
        // 19. Đã bỏ đi: làm lành lại từ đầu bằng quà.
        if (r.left) {
            if (stack.isEmpty()) { say(p, "..."); return true; }
            r.left = false;
            r.affinity = Math.max(r.affinity, 5);
            r.sulkUntil = 0;
            takeOne(p, stack);
            say(p, "...Cậu tìm tới tận đây à. Thôi được... làm lại từ đầu nhé.");
            return true;
        }
        // 18. Đang giận: chỉ quà mới làm lành.
        if (r.sulkUntil > now) {
            if (stack.isEmpty()) { ly.setYRot(p.getYRot()); say(p, v.pick("Hừ.", "...", "Không nói chuyện với cậu.")); return true; }
            r.sulkUntil = 0;
            ly.mind().gift(p, Tools.value(stack) + 2);
            takeOne(p, stack);
            say(p, "...Thôi được rồi. Tha cho " + v.you + " đó.");
            return true;
        }
        if (stack.isEmpty()) return false;
        // 2. Đổi đồ.
        Pending pd = pending.get(p.getUUID());
        if (pd != null && "TRADE".equals(pd.type) && sameKind(stack, pd.want)) {
            int n = Math.min(pd.wantCount, stack.getCount());
            ItemStack got = stack.copyWithCount(n);
            if (!p.getAbilities().instabuild) stack.shrink(n);
            ly.inv().put(got);
            ItemStack offer = pd.offer;
            if (ly.inv().count(offer.getItem()) >= offer.getCount()) {
                ly.inv().consume(offer.getItem(), offer.getCount());
                throwTo(p, offer.copy());
            }
            pending.remove(p.getUUID());
            ly.mind().bond(p, 3, false);
            say(p, v.pick("C-cảm ơn...", "Đổi xong rồi. Cảm ơn " + v.you, "Hihi... vậy là có đủ rồi") + v.ending());
            return true;
        }
        // 4. Nhiệm vụ nhỏ.
        if (r.quest != null && questItem(r.quest, stack)) {
            int n = Math.min(r.questNeed - r.questHave, stack.getCount());
            ly.inv().put(stack.copyWithCount(n));
            if (!p.getAbilities().instabuild) stack.shrink(n);
            r.questHave += n;
            if (r.questHave >= r.questNeed) questDone(p, r);
            else say(p, "Còn " + (r.questNeed - r.questHave) + " nữa thôi" + v.ending());
            return true;
        }
        // 9. Đang buồn: hoa hay đồ ăn làm cô ấy vui lên.
        if (sad && (Compat.isFlower(stack) || Tools.isGoodFood(stack))) {
            takeOne(p, stack);
            cheered(p);
            return true;
        }
        // Món yêu thích: được nhiều điểm hơn.
        if (stack.is(favorite())) {
            takeOne(p, stack);
            ly.mind().event(Mind.Event.GIFT, p, ItemNames.of(stack.getItem()), null);
            ly.mind().gift(p, 6);
            ly.mind().bond(p, 4, false);
            say(p, v.pick("C-cái này... sao " + v.you + " biết " + v.self + " thích nó?!", "Oa! Đúng món " + v.self + " thích nhất luôn!", "Mugyu~! " + cap(v.self) + " thích cái này lắm đó!"));
            ly.hearts();
            return true;
        }
        return false;
    }

    private static void takeOne(Player p, ItemStack stack) {
        if (!p.getAbilities().instabuild && !stack.isEmpty()) stack.shrink(1);
    }

    /** Câu chat của người chơi. @return true nếu sự kiện đã trả lời. */
    public boolean onChat(ServerPlayer p, String n) {
        MindData.Relation r = rel(p);
        long now = ly.level().getGameTime();
        Speech.Voice v = voice(p);
        if (r.left) { if (ly.distanceTo(p) < 16 && chance(0.3f)) say(p, "..."); return true; }
        if (r.sulkUntil > now) {
            if (has(n, "xin loi", "sorry", "toi loi")) say(p, "Hừ... xin lỗi suông thôi à?");
            else if (chance(0.3f)) say(p, "Hừ.");
            return true;
        }
        boolean yes = has(n, "co", "u", "uh", "ok", "oke", "okie", "duoc", "dong y", "di", "yes", "yep", "sure", "di thoi", "uk", "um");
        boolean no = has(n, "khong", "thoi", "no", "khong can", "de sau", "ban roi", "nope");
        Pending pd = pending.get(p.getUUID());
        if (pd != null && (yes || no) && !"TRADE".equals(pd.type)) {
            pending.remove(p.getUUID());
            if (no) {
                say(p, pd.type.equals("CAMP") ? "À… ừ, không sao đâu." : v.pick("À... ừ", "Vậy thôi", "Ừm, lần khác nha") + v.ending());
                return true;
            }
            switch (pd.type) {
                case "EXPLORE" -> { ly.setRequest("FOLLOW", p, 10 * MIN, null); say(p, v.pick("Yay~ đi thôi", "Đợi " + v.self + " với!") + v.ending()); }
                case "CAVE" -> {
                    adventureWith = p.getUUID();
                    adventureUntil = now + 12 * MIN;
                    adventureRewarded = false;
                    ly.setRequest("LEAD", p, 6 * MIN, pd.at);
                    say(p, "Theo " + v.self + " nè! Nhớ mang đuốc nha" + v.ending());
                }
                case "CAMP" -> { startCamp(p, pd.at); say(p, "Thật hả?! Vậy... đi theo " + v.self + " nha."); }
                default -> {}
            }
            return true;
        }
        if (pd != null && "TRADE".equals(pd.type) && no) {
            pending.remove(p.getUUID());
            say(p, "À... ừ, không sao.");
            return true;
        }
        // 5. "có đồ ăn không?"
        if (has(n, "do an", "doi qua", "co gi an", "dang doi", "toi doi", "minh doi")) {
            if (a(p) >= 21) { shareFood((ServerLevel) ly.level(), p, r, now); return true; }
        }
        // Đi cùng khám phá: người chơi rủ.
        return false;
    }

    private static boolean has(String n, String... words) {
        for (String w : words) if (n.contains(" " + w + " ")) return true;
        return false;
    }

    // ------------------------------------------------------------------ yêu cầu tới nơi

    /** Cô ấy tới nơi theo một yêu cầu (VISIT, FETCH, DELIVER, LEAD, MOVE). */
    public void arrived(ServerLevel level, CompanionStore.Request r, @Nullable ServerPlayer who) {
        Speech.Voice v = who == null ? null : voice(who);
        switch (r.type) {
            case "VISIT" -> {
                ly.clearRequest();
                if (who == null) return;
                ly.getLookControl().setLookAt(who, 30f, 30f);
                if (!parcel.isEmpty() && who.getUUID().equals(parcelFor)) {
                    for (ItemStack s : parcel) throwTo(who, s);
                    parcel.clear();
                    say(who, "Cậu ổn chứ? Tớ lo quá... " + cap(v.self) + " nhặt lại đồ cho " + v.you + " nè.");
                } else if (who.getUUID().equals(parcelFor)) {
                    say(who, "Cậu ổn chứ? Tớ lo quá.");
                } else if (pending.containsKey(who.getUUID())) {
                    // đến rủ đi cắm trại: câu hỏi đã nói rồi
                } else {
                    MindData.Relation rr = rel(who);
                    say(who, rr.promised ? v.pick("Cậu về rồi!", "A! " + cap(v.you) + " về rồi~ " + cap(v.self) + " đợi mãi") : v.pick("Chào " + v.you, "A, " + v.you + " đây rồi"));
                    ly.hearts();
                }
                parcelFor = null;
            }
            case "FETCH" -> {
                for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, ly.getBoundingBox().inflate(8, 4, 8), ItemEntity::isAlive)) {
                    parcel.add(e.getItem().copy());
                    ly.take(e, e.getItem().getCount());
                    e.discard();
                }
                ly.swingArm();
                if (who != null) ly.setRequest("VISIT", who, 6 * MIN, null);
                else ly.clearRequest();
            }
            case "DELIVER" -> {
                if (r.at != null && who != null) deliver(level, who, new BlockPos(r.at.x, r.at.y, r.at.z));
                ly.clearRequest();
            }
            case "LEAD" -> {
                // Đợi người chơi theo kịp (tối đa vài phút), rồi bắt đầu.
                if (who == null) { ly.clearRequest(); return; }
                if (who.level() != level || who.distanceTo(ly) > 8) {
                    ly.getLookControl().setLookAt(who, 20f, 20f);
                    return;
                }
                if (campWith != null && campWith.equals(who.getUUID())) {
                    ly.setRequest("CAMP", who, 4 * MIN, ly.blockPosition());
                    campStepAt = 0;
                } else {
                    // Hang động: vào hang cùng nhau.
                    say(who, "Đây rồi... vào thôi. " + cap(v.you) + " đi trước nha, " + v.self + " theo sau.");
                    ly.setRequest("FOLLOW", who, 8 * MIN, null);
                }
            }
            case "MOVE" -> {
                ly.clearRequest();
                ly.setHome(level, ly.blockPosition());
            }
            default -> ly.clearRequest();
        }
    }

    // ------------------------------------------------------------------ đồ vật

    private void throwTo(Player p, ItemStack stack) {
        if (stack.isEmpty() || !(ly.level() instanceof ServerLevel sl)) return;
        ItemEntity e = new ItemEntity(sl, ly.getX(), ly.getEyeY() - 0.3, ly.getZ(), stack);
        Vec3 dir = p.position().subtract(ly.position()).normalize().scale(0.3);
        e.setDeltaMovement(dir.x, 0.25, dir.z);
        e.setThrower(ly);
        e.setPickUpDelay(10);
        sl.addFreshEntity(e);
        ly.swingArm();
    }

    private void turnAway(ServerPlayer p) {
        if (p.distanceTo(ly) > 10) return;
        Vec3 away = ly.position().subtract(p.position()).normalize().scale(4).add(ly.getEyePosition());
        ly.getLookControl().setLookAt(away.x, away.y, away.z, 30f, 30f);
    }

    /** Đặt một tấm biển có chữ cạnh {@code near}. Cần ván để làm biển. */
    private boolean placeSign(ServerLevel level, BlockPos near, String[] lines) {
        if (!ly.inv().has(Items.OAK_SIGN)) {
            var steps = Crafting.plan(Crafting.copy(ly.inv()), Items.OAK_SIGN, false);
            if (steps == null) return false;
            for (var s : steps) if (!Crafting.execute(ly.inv(), s)) return false;
        }
        BlockPos spot = null;
        for (BlockPos c : new BlockPos[]{near, near.above(), near.north(), near.south(), near.east(), near.west(),
                near.north().above(), near.south().above(), near.east().above(), near.west().above()}) {
            if (level.getBlockState(c).canBeReplaced() && level.getFluidState(c).isEmpty() && level.getBlockState(c.below()).isSolid()) { spot = c; break; }
        }
        if (spot == null) return false;
        ly.inv().consume(Items.OAK_SIGN, 1);
        int rot = Math.floorMod(Math.round((ly.getYRot() + 180f) * 16f / 360f), 16);
        ly.placeBlock(level, spot, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot));
        if (level.getBlockEntity(spot) instanceof SignBlockEntity sign) {
            SignText.Mutable text = SignText.EMPTY.asMutable();
            for (int i = 0; i < Math.min(4, lines.length); i++) text.setLine(i, Component.literal(lines[i]));
            sign.setText(text.asImmutable(), SignTextSlot.FRONT);
            sign.setWaxed(true);
            sign.setChanged();
            level.sendBlockUpdated(spot, level.getBlockState(spot), level.getBlockState(spot), 3);
        }
        return true;
    }
}
