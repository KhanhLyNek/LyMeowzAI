package com.khanhly.lymeowz.mind;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Roadmap;
import com.khanhly.lymeowz.survival.Survival;
import com.khanhly.lymeowz.survival.Tools;
import com.khanhly.lymeowz.survival.task.BedTask;
import com.khanhly.lymeowz.survival.task.ChillTask;
import com.khanhly.lymeowz.survival.task.ChopTreeTask;
import com.khanhly.lymeowz.survival.task.CollectTask;
import com.khanhly.lymeowz.survival.task.CraftTask;
import com.khanhly.lymeowz.survival.task.EatTask;
import com.khanhly.lymeowz.survival.task.ExploreTask;
import com.khanhly.lymeowz.survival.task.FarmTask;
import com.khanhly.lymeowz.survival.task.GatherTask;
import com.khanhly.lymeowz.survival.task.ObsidianTask;
import com.khanhly.lymeowz.survival.task.PortalTask;
import com.khanhly.lymeowz.survival.task.UpgradeTask;
import com.khanhly.lymeowz.survival.task.HouseTask;
import com.khanhly.lymeowz.survival.task.HuntTask;
import com.khanhly.lymeowz.survival.task.MineTask;
import com.khanhly.lymeowz.survival.task.PenTask;
import com.khanhly.lymeowz.survival.task.RanchTask;
import com.khanhly.lymeowz.survival.task.SimpleTasks;
import com.khanhly.lymeowz.survival.task.SmeltTask;
import com.khanhly.lymeowz.survival.task.StairMineTask;
import com.khanhly.lymeowz.survival.task.StoreTask;
import com.khanhly.lymeowz.survival.task.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Utility AI: mỗi lần cần quyết định, chấm điểm (0–1) mọi việc có thể làm dựa trên
 * nhu cầu & cảm xúc × tính cách × kinh nghiệm đã học × giai đoạn trong lộ trình sinh tồn,
 * rồi chọn việc điểm cao nhất. Không có thứ tự cứng.
 */
public class Decider {
    public record Option(String action, float score, String reason, boolean urgent, Supplier<Task> make) {}

    private final LyMeowzEntity ly;
    private final Set<BlockPos> blacklist;
    private ChopTreeTask treeCache;
    private BlockPos mineCache;
    private Item craftCache;
    private BlockPos houseSite, farmSite, penSite;
    private long cacheTime = -1000, siteTime = -100000, storeAgain;

    public Decider(LyMeowzEntity ly, Set<BlockPos> blacklist) {
        this.ly = ly;
        this.blacklist = blacklist;
    }

    public void invalidate() {
        cacheTime = -1000;
    }

    public List<Option> evaluate(ServerLevel level, BlockPos center, int radius, Survival.Focus focus) {
        Mind mind = ly.mind();
        MindData m = mind.data();
        Personality p = mind.p();
        LyInventory all = ly.inventoryWithHand();
        List<Option> out = new ArrayList<>();
        boolean night = Compat.isNight(level) && level.dimensionType().hasSkyLight();
        float needH = (20 - ly.getHunger()) / 20f;
        float fear = m.fear / 100f, bored = m.boredom / 100f, curious = m.curiosity / 100f;
        float tired = 1 - m.energy / 100f, lonely = 1 - m.social / 100f;
        Roadmap.Stage stage = Roadmap.current(ly);
        BlockPos home = ly.getHomePos();
        boolean homeNear = home != null && home.distSqr(ly.blockPosition()) < 80 * 80;
        int pickTier = Survival.bestTier(all, Tools.Kind.PICKAXE);

        long now = level.getGameTime();
        if (now - cacheTime > 100) {
            cacheTime = now;
            treeCache = ChopTreeTask.find(ly, level, center, radius, blacklist);
            mineCache = pickTier >= 1 ? MineTask.findTarget(ly, level, center, radius, blacklist) : null;
            craftCache = Survival.nextCraft(ly, level, all, stage);
            lavaCache = stage == Roadmap.Stage.NETHER ? nearLava(level) : null;
        }

        // Chạy trốn: nhút nhát + có quái + đang yếu hoặc rất sợ.
        Mob threat = SimpleTasks.Flee.nearestThreat(ly, level);
        if (threat != null && (ly.getHealth() < 7 || (p.brave < 0.5f && m.fear > 70))) {
            out.add(new Option("chạy trốn", 0.95f, "SCARED", true, () -> new SimpleTasks.Flee(ly, threat)));
        }

        // Ăn.
        if ((ly.getHunger() <= 14 || (ly.getHealth() < 12 && ly.getHunger() < 18))
                && all.count(s -> Tools.edible(s, ly.getHunger())) > 0) {
            out.add(new Option("ăn", 0.45f + needH * 0.6f, "HUNGRY", ly.getHunger() <= 6, () -> new EatTask(ly)));
        }

        // Hết đồ ăn mà rương ở nhà còn: về lấy.
        BlockPos chestPos = ly.structure("chest");
        if (ly.getHunger() <= 12 && all.count(s -> Tools.edible(s, 7)) == 0 && chestPos != null
                && chestPos.distSqr(ly.blockPosition()) < 96 * 96 && StoreTask.hasFood(level, chestPos)) {
            boolean starving2 = ly.getHunger() <= 6;
            out.add(new Option("cất đồ", starving2 ? 0.92f : 0.6f, "HUNGRY", starving2, () -> new StoreTask(ly, chestPos)));
        }

        // Nhặt đồ rơi.
        ItemEntity drop = CollectTask.find(ly, level, 8);
        if (drop != null) out.add(new Option("nhặt đồ", 0.5f, null, false, () -> new CollectTask(ly, drop)));

        // Chế đồ nâng cấp.
        if (craftCache != null) {
            Item target = craftCache;
            out.add(new Option("chế tạo", 0.55f + 0.2f * p.diligent, "UPGRADE", false, () -> new CraftTask(ly, target)));
        }

        // Nung.
        int raw = all.count(Tools::isRawMeat), rawIron = all.count(Items.RAW_IRON);
        boolean wantGlass = ly.data() != null && !ly.data().structures.containsKey(UpgradeTask.Kind.WINDOWS.key())
                && all.count(Items.GLASS) < 3 && all.count(Items.SAND) >= 3;
        if (SmeltTask.canSmelt(ly) && (raw >= 2 || rawIron >= 3 || (raw > 0 && needH > 0.3f) || wantGlass)) {
            out.add(new Option("nung", 0.35f + needH * 0.4f + (rawIron >= 3 ? 0.15f : 0), raw > 0 ? "RAW_FOOD" : "UPGRADE", false, () -> new SmeltTask(ly)));
        }

        // Làm bánh mì khi có lúa.
        if (all.count(Items.WHEAT) >= 3 && all.count(Tools::isGoodFood) < 8 && CraftTask.canMake(ly, level, Items.BREAD)) {
            out.add(new Option("chế tạo", 0.4f + needH * 0.3f, "FOOD_LOW", false, () -> new CraftTask(ly, Items.BREAD)));
        }

        // Đi săn (không đụng thú trong chuồng của mình).
        BlockPos pen = ly.structure("pen");
        int food = all.count(Tools::isGoodFood) + raw;
        boolean meatOnGround = drop != null && Tools.isFood(drop.getItem());
        if ((food < 4 || focus == Survival.Focus.FOOD) && !meatOnGround) { // nhặt thịt vừa rơi trước đã
            boolean desperate = ly.getHunger() <= 6 && food == 0;
            Animal prey = HuntTask.find(ly, level, desperate ? Math.max(radius, 40) : radius, desperate);
            if (prey != null && (pen == null || !RanchTask.inPen(pen, prey))) {
                float s = (0.3f + needH * 0.5f + (food == 0 ? 0.15f : 0)) * (0.6f + 0.4f * p.fighter)
                        * expect("đi săn") * (1 - fear * 0.5f) + (focus == Survival.Focus.FOOD ? 0.4f : 0);
                boolean urgent = ly.getHunger() <= 6 && food == 0;
                out.add(new Option("đi săn", urgent ? Math.max(s, 0.9f) : s, needH > 0.4f ? "HUNGRY" : "FOOD_LOW", urgent, () -> new HuntTask(ly, prey)));
            }
        }

        // Chặt cây: lượng gỗ muốn có tùy giai đoạn (cửa, rương, hàng rào, giường đều cần gỗ).
        int logs = all.count(ItemTags.LOGS) + all.count(ItemTags.PLANKS) / 4;
        int woodTarget = switch (stage) {
            case WOOD -> 8;
            case STONE -> 10;
            case SHELTER -> 16;
            case FARM, PEN -> 20;
            default -> 12 + (int) (8 * p.builder);
        };
        if (focus == Survival.Focus.WOOD) woodTarget = 64;
        if (treeCache != null && logs < woodTarget) {
            float need = 1 - (float) logs / woodTarget;
            ChopTreeTask t = treeCache;
            float s = (0.25f + 0.4f * need) * (0.6f + 0.4f * p.diligent) * expect("chặt cây") + (focus == Survival.Focus.WOOD ? 0.4f : 0);
            out.add(new Option("chặt cây", s, "WOOD_LOW", false, () -> { invalidate(); return t; }));
        }

        // Đá: nhà cần ~80 khối, sau đó giữ ít để bịt nham thạch, xây hộp trú.
        int cobble = all.count(ItemTags.STONE_TOOL_MATERIALS) + all.count(Items.COBBLED_DEEPSLATE);
        int stoneTarget = focus == Survival.Focus.STONE ? 64 : stage == Roadmap.Stage.SHELTER ? HouseTask.BLOCKS + 12 : 24;
        int coal = all.count(ItemTags.COALS);
        if (pickTier >= 1 && (cobble < stoneTarget || coal < 6)) {
            float need = Math.max(1 - (float) cobble / stoneTarget, coal < 6 ? 0.4f : 0);
            float s = (0.25f + 0.35f * need) * (0.6f + 0.4f * p.diligent) * (1 - 0.4f * fear) * expect("đào đá")
                    + (focus == Survival.Focus.STONE ? 0.4f : 0);
            if (mineCache != null) {
                out.add(new Option("đào đá", s, coal < 6 ? "ORE" : "STONE_LOW", false,
                        () -> { invalidate(); return new MineTask(ly, 12, center, radius, blacklist); }));
            } else if (mind.nearest(Mind.PlaceKind.STONE, ly.blockPosition()) != null
                    && ly.blockPosition().distSqr(placePos(mind.nearest(Mind.PlaceKind.STONE, ly.blockPosition()))) > 12 * 12) {
                // Nhớ có bãi đá ở chỗ khác: đi tới đó.
                BlockPos at = placePos(mind.nearest(Mind.PlaceKind.STONE, ly.blockPosition()));
                out.add(new Option("khám phá", s * 0.9f, "STONE_LOW", false, () -> new ExploreTask(ly, at)));
            } else if (pickTier >= 1 && ly.getHealth() > 12 && ly.getBlockY() - 10 > level.getMinY() + 8) {
                // Không thấy đá lộ thiên: đào cầu thang xuống một đoạn như người chơi.
                int target = ly.getBlockY() - 10;
                out.add(new Option("đào mỏ", s * 0.9f, "STONE_LOW", false, () -> new StairMineTask(ly, target, 8)));
            }
        }

        // ---------------------------------------------------------------- cổng Nether
        if (stage == Roadmap.Stage.NETHER) netherOptions(level, all, out, p, home);

        // Đào mỏ sâu tìm sắt / kim cương.
        if ((stage == Roadmap.Stage.IRON || stage == Roadmap.Stage.DIAMOND) && pickTier >= 2 && ly.getHealth() > 14
                && all.count(Tools::isGoodFood) >= 3 && cobble >= 8) {
            int iron = rawIron + all.count(Items.IRON_INGOT);
            boolean wantIron = stage == Roadmap.Stage.IRON && iron < 30;
            boolean wantDiamond = stage == Roadmap.Stage.DIAMOND && pickTier >= 3 && all.count(Items.DIAMOND) < 5;
            if (wantIron || wantDiamond) {
                int targetY = wantDiamond ? -54 : 16;
                int ty = Math.min(ly.getBlockY() - 4, targetY);
                float s = (0.35f + 0.25f * p.diligent + 0.15f * p.brave) * (1 - 0.5f * fear) * expect("đào mỏ")
                        + (night && p.brave > 0.6f ? 0.2f : 0);
                out.add(new Option("đào mỏ", s, "ORE", false, () -> new StairMineTask(ly, ty, wantDiamond ? 40 : 28)));
            }
        }

        // ---------------------------------------------------------------- nhà cửa & trang trại

        if (now - siteTime > 20 * 30) siteTime = now;
        boolean freshSites = siteTime == now;

        if (stage.ordinal() >= Roadmap.Stage.SHELTER.ordinal() && !Roadmap.has(ly, "house")) {
            int blocks = all.count(HouseTask.WALL);
            boolean resume = ly.structure("plan:house") != null;
            if ((blocks >= HouseTask.BLOCKS || (resume && blocks >= 8)) && all.count(ItemTags.PLANKS) + all.count(ItemTags.LOGS) * 4 >= 16) {
                BlockPos plan = ly.structure("plan:house");
                if (plan != null) houseSite = plan;
                else if (houseSite == null || freshSites) houseSite = HouseTask.findSite(ly, level, center, 20);
                if (houseSite != null) {
                    BlockPos site = houseSite;
                    float s = 0.6f + 0.3f * p.builder + (night ? 0.1f : 0);
                    out.add(new Option("xây nhà", s, "BUILDER", false, () -> { houseSite = null; return new HouseTask(ly, site); }));
                }
            }
        }
        BlockPos house = ly.structure("house");
        if (homeNear && house != null) {
            // Ruộng.
            BlockPos farm = ly.structure("farm");
            if (farm == null && stage.ordinal() >= Roadmap.Stage.FARM.ordinal() && FarmTask.hasHoe(ly)) {
                if (farmSite == null || freshSites) farmSite = FarmTask.findSite(ly, level, home);
                if (farmSite != null) {
                    BlockPos site = farmSite;
                    out.add(new Option("làm ruộng", 0.5f + 0.2f * p.builder + 0.1f * p.diligent, "FOOD_LOW", false,
                            () -> { farmSite = null; return new FarmTask(ly, site, false); }));
                }
            } else if (farm != null && !night) {
                int ripe = FarmTask.ripe(level, farm), empty = FarmTask.empty(level, farm);
                if (ripe >= 4 || (empty >= 4 && FarmTask.seeds(ly) > 0)) {
                    out.add(new Option("thu hoạch", 0.35f + 0.04f * ripe + needH * 0.3f, "FOOD_LOW", false, () -> new FarmTask(ly, farm, true)));
                }
            }
            // Chuồng.
            if (pen == null && stage.ordinal() >= Roadmap.Stage.PEN.ordinal()
                    && (all.count(ItemTags.PLANKS) + all.count(ItemTags.LOGS) * 4 >= PenTask.planksNeeded(ly) + 4
                        || (ly.structure("plan:pen") != null && all.count(Items.OAK_FENCE) > 0))) {
                BlockPos plan = ly.structure("plan:pen");
                if (plan != null) penSite = plan;
                else if (penSite == null || freshSites) penSite = PenTask.findSite(ly, level, home, farm);
                if (penSite != null) {
                    BlockPos site = penSite;
                    out.add(new Option("làm chuồng", 0.5f + 0.25f * p.builder, "BUILDER", false, () -> { penSite = null; return new PenTask(ly, site); }));
                }
            } else if (pen != null && !night) {
                ranch(level, pen, out, p);
            }
            // Giường: có len thì chế, có giường thì đặt; thiếu len thì đi tìm cừu.
            if (!Roadmap.has(ly, "bed") && stage.ordinal() >= Roadmap.Stage.BED.ordinal() - 1) {
                Item bedItem = Items.BED.white();
                if (all.count(ItemTags.BEDS) > 0) {
                    out.add(new Option("đặt giường", 0.7f, "BUILDER", false, () -> new BedTask(ly, house)));
                } else if (all.count(ItemTags.WOOL) >= 3) {
                    if (CraftTask.canMake(ly, level, bedItem))
                        out.add(new Option("chế tạo", 0.65f, "UPGRADE", false, () -> new CraftTask(ly, bedItem)));
                } else if (!all.has(Items.SHEARS)) {
                    Animal sheep = nearestSheep(level, pen);
                    if (sheep != null) {
                        float s = (0.35f + 0.2f * p.fighter) * expect("đi săn");
                        out.add(new Option("đi săn", s, "WOOL", false, () -> new HuntTask(ly, sheep)));
                    }
                }
            }
            // Nhà lớn dần: đuốc, vườn hoa, cửa sổ, phòng mới.
            if (!night && Roadmap.has(ly, "bed")) upgradeOptions(level, all, out, p, house);
            // Cất đồ vào rương.
            BlockPos chest = ly.structure("chest");
            if (chest != null && now > storeAgain && StoreTask.wants(ly)) {
                out.add(new Option("cất đồ", 0.55f + 0.1f * p.diligent, "BAG_FULL", false, () -> { storeAgain = level.getGameTime() + 20 * 60; return new StoreTask(ly, chest); }));
            }
        }

        // Khám phá: chán / tò mò, ban ngày, không đói, không sợ.
        if (!night && ly.getHunger() > 8 && m.fear < 50 && m.energy > 25 && focus == Survival.Focus.AUTO) {
            BlockPos target = ExploreTask.pickTarget(ly, level, center);
            if (target != null) {
                float s = (0.1f + 0.3f * bored + 0.3f * curious) * (0.5f + p.curious) * expect("khám phá");
                out.add(new Option("khám phá", s, bored > curious ? "BORED" : "CURIOUS", false, () -> new ExploreTask(ly, target)));
            }
        }

        // Thư giãn: cuộc sống đã ổn (có nhà) thì thỉnh thoảng ngồi thuyền ngắm trời.
        if (!night && house != null && ly.getHunger() > 12 && m.fear < 30 && threat == null) {
            float s = (0.08f + 0.3f * bored + 0.2f * tired) * (0.6f + 0.6f * p.cheerful);
            if (all.has(Items.OAK_BOAT) || all.count(ItemTags.PLANKS) + all.count(ItemTags.LOGS) * 4 >= 12) {
                if (!all.has(Items.OAK_BOAT) && CraftTask.canMake(ly, level, Items.OAK_BOAT)) {
                    out.add(new Option("chế tạo", s * 0.8f, "BORED", false, () -> new CraftTask(ly, Items.OAK_BOAT)));
                } else {
                    out.add(new Option("thư giãn", s, "BORED", false, () -> new ChillTask(ly)));
                }
            }
        }

        // Ngày buồn: ngồi một mình, ít nói.
        if (ly.bond.isSad()) out.add(new Option("buồn", 0.75f, "SAD", false, () -> new SimpleTasks.Mope(ly)));

        // Nghỉ: mệt, sợ, trời tối.
        float rest = 0.06f + 0.35f * tired + 0.25f * fear + (night ? 0.15f : 0);
        String restWhy = tired >= 0.3f && tired >= fear ? "TIRED" : fear >= 0.3f ? "SCARED" : night ? "NIGHT" : "IDLE";
        out.add(new Option("nghỉ", rest, restWhy, false, () -> new SimpleTasks.Rest(ly)));

        // Trò chuyện: chỉ với bạn bè.
        ServerPlayer friend = ly.focusPlayer();
        if (friend != null && friend.level() == level && friend.distanceTo(ly) < 20 && m.social < 50
                && ly.affinity(friend) >= LyMeowzEntity.FRIEND) {
            float s = lonely * 0.4f * (0.5f + p.social) + 0.04f * p.talkative;
            out.add(new Option("trò chuyện", s, "LONELY", false, () -> new SimpleTasks.Socialize(ly)));
        }

        // Trồng cây con.
        if (all.count(ItemTags.SAPLINGS) > 0 && p.builder > 0.3f) {
            float s = (0.1f + 0.2f * p.builder + 0.1f * bored) * expect("trồng cây");
            out.add(new Option("trồng cây", s, "BUILDER", false, () -> new SimpleTasks.Plant(ly)));
        }

        // Thắp đuốc ban đêm.
        if (night && p.builder > 0.3f && SimpleTasks.Light.canLight(ly)) {
            float s = (0.15f + 0.25f * p.builder) * expect("thắp đuốc");
            out.add(new Option("thắp đuốc", s, "DARK", false, () -> new SimpleTasks.Light(ly)));
        }
        return out;
    }

    /** Sửa sang nhà: việc đầu tiên đủ đồ thì làm; chưa đủ thì đi gom đồ cho những việc còn lại. */
    private void upgradeOptions(ServerLevel level, LyInventory all, List<Option> out, Personality p, BlockPos house) {
        var d = ly.data();
        if (d == null) return;
        float s = (0.3f + 0.35f * p.builder) * expect("sửa nhà");
        for (UpgradeTask.Kind k : UpgradeTask.Kind.values()) {
            if (d.structures.containsKey(k.key())) continue;
            if (k == UpgradeTask.Kind.ROOM2 && !UpgradeTask.roomFits(level, house)) {
                d.structures.put(k.key(), d.structures.get("house")); // không có chỗ xây thêm: thôi
                continue;
            }
            if (UpgradeTask.ready(ly, k)) {
                out.add(new Option("sửa nhà", s + 0.1f, "BUILDER", false, () -> new UpgradeTask(ly, house, k)));
                return;
            }
        }
        if (now(level) < gatherAgain) return;
        if (!d.structures.containsKey(UpgradeTask.Kind.GARDEN.key())) {
            BlockPos f = GatherTask.nearest(ly, level, st -> st.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS), 20, java.util.Set.of());
            if (f != null) out.add(new Option("hái hoa", s * 0.9f, "BUILDER", false, () -> { gatherAgain = now(level) + 1200;
                return new GatherTask(ly, "hái hoa", st -> st.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS), com.khanhly.lymeowz.compat.Compat::isFlower, 5, 12, 20); }));
        }
        if (!d.structures.containsKey(UpgradeTask.Kind.WINDOWS.key()) && all.count(Items.SAND) + all.count(Items.GLASS) < 3) {
            BlockPos sand = GatherTask.nearest(ly, level, st -> st.is(net.minecraft.tags.BlockTags.SAND), 24, java.util.Set.of());
            if (sand != null) out.add(new Option("xúc cát", s * 0.9f, "BUILDER", false, () -> { gatherAgain = now(level) + 1200;
                return new GatherTask(ly, "xúc cát", st -> st.is(net.minecraft.tags.BlockTags.SAND), x -> x.is(Items.SAND), 4, 8, 24); }));
        }
    }

    private long gatherAgain;

    private static long now(ServerLevel level) {
        return level.getGameTime();
    }

    /** Bước "cổng Nether": đá lửa, bật lửa, obsidian, dựng cổng. */
    private void netherOptions(ServerLevel level, LyInventory all, List<Option> out, Personality p, BlockPos home) {
        float base = (0.4f + 0.2f * p.diligent + 0.15f * p.curious);
        boolean flint = all.has(Items.FLINT) || all.has(Items.FLINT_AND_STEEL);
        if (!flint && now(level) > gatherAgain) {
            BlockPos g = GatherTask.nearest(ly, level, st -> st.is(net.minecraft.world.level.block.Blocks.GRAVEL), 24, java.util.Set.of());
            if (g != null) out.add(new Option("đào sỏi", base, "UPGRADE", false, () -> { gatherAgain = now(level) + 1200;
                return new GatherTask(ly, "đào sỏi", st -> st.is(net.minecraft.world.level.block.Blocks.GRAVEL), x -> x.is(Items.FLINT), 1, 24, 24); }));
        }
        int obsidian = all.count(Items.OBSIDIAN);
        if (obsidian < ObsidianTask.NEED && ObsidianTask.canStart(ly)) {
            boolean lavaKnown = mind().nearest(Mind.PlaceKind.LAVA, ly.blockPosition()) != null;
            if (lavaKnown || lavaCache != null) {
                out.add(new Option("làm obsidian", base + 0.1f, "UPGRADE", false, () -> new ObsidianTask(ly)));
            } else if (ly.getHealth() > 14 && all.count(Tools::isGoodFood) >= 3) {
                int ty = Math.min(ly.getBlockY() - 4, -45);
                out.add(new Option("đào mỏ", base, "LAVA", false, () -> new StairMineTask(ly, ty, 32)));
            }
        }
        if (PortalTask.canStart(ly) && home != null) {
            if (portalSite == null || portalSite.distSqr(home) > 20 * 20) portalSite = PortalTask.findSite(ly, level, home);
            if (portalSite != null) {
                BlockPos site = portalSite;
                out.add(new Option("dựng cổng Nether", base + 0.25f, "BUILDER", false, () -> new PortalTask(ly, site)));
            }
        }
    }

    private BlockPos portalSite, lavaCache;

    private Mind mind() {
        return ly.mind();
    }

    /** Chăn nuôi: dụ thú về, cho ăn để đẻ, cắt lông, làm thịt bớt khi đông. */
    private void ranch(ServerLevel level, BlockPos pen, List<Option> out, Personality p) {
        List<Animal> in = RanchTask.penned(level, pen);
        Map<net.minecraft.world.entity.EntityType<?>, List<Animal>> types = RanchTask.byType(in);
        // Đông quá thì làm thịt bớt một con trưởng thành.
        for (var e : types.entrySet()) {
            List<Animal> adults = e.getValue().stream().filter(a -> !a.isBaby()).toList();
            if (adults.size() > 5) {
                Animal victim = adults.get(0);
                out.add(new Option("chăn nuôi", 0.4f + 0.1f * p.fighter, "FOOD_LOW", false,
                        () -> new RanchTask(ly, pen, RanchTask.Mode.CULL, victim)));
                return;
            }
        }
        // Cắt lông cừu.
        if (ly.inv().has(Items.SHEARS)) {
            Animal sheep = in.stream().filter(a -> a instanceof Sheep s && s.readyForShearing()).findFirst().orElse(null);
            if (sheep != null) out.add(new Option("chăn nuôi", 0.35f, "WOOL", false, () -> new RanchTask(ly, pen, RanchTask.Mode.SHEAR, sheep)));
        }
        // Cho ăn để đẻ: cần 2 con cùng loài sẵn sàng.
        for (var e : types.entrySet()) {
            List<Animal> ready = e.getValue().stream().filter(a -> !a.isBaby() && a.canFallInLove() && !a.isInLove()).toList();
            if (ready.size() >= 2 && ready.size() + e.getValue().size() <= 8 && RanchTask.hasFoodFor(ly, ready.get(0))
                    && ly.inv().count(ready.get(0)::isFood) >= 2) {
                Animal a = ready.get(0);
                out.add(new Option("chăn nuôi", 0.4f + 0.2f * p.kind, "FOOD_LOW", false, () -> new RanchTask(ly, pen, RanchTask.Mode.BREED, a)));
                return;
            }
        }
        // Chuồng còn ít thú: dụ thêm về.
        if (in.size() < 4) {
            Animal wild = RanchTask.wildToLure(ly, level, pen, 32);
            if (wild != null) {
                out.add(new Option("chăn nuôi", 0.45f + 0.2f * p.kind, "FOOD_LOW", false, () -> new RanchTask(ly, pen, RanchTask.Mode.LURE, wild)));
            }
        }
    }

    private Animal nearestSheep(ServerLevel level, BlockPos pen) {
        return level.getEntitiesOfClass(Sheep.class, ly.getBoundingBox().inflate(32, 8, 32),
                        s -> s.isAlive() && !s.isBaby() && (pen == null || !RanchTask.inPen(pen, s)))
                .stream().min(Comparator.comparingDouble(s -> s.distanceToSqr(ly))).orElse(null);
    }

    /** Lựa chọn tốt nhất (có chút ngẫu nhiên cho tự nhiên). */
    public Option best(List<Option> options) {
        Option best = null;
        float bestScore = -1;
        for (Option o : options) {
            float s = o.score() + (ly.getRandom().nextFloat() - 0.5f) * 0.06f;
            if (s > bestScore) { bestScore = s; best = o; }
        }
        return best;
    }

    private BlockPos nearLava(ServerLevel level) {
        BlockPos c = ly.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-16, -8, -16), c.offset(16, 4, 16))) {
            var f = level.getFluidState(q);
            if (f.isSource() && f.is(net.minecraft.tags.FluidTags.LAVA)) return q.immutable();
        }
        return null;
    }

    private static BlockPos placePos(MindData.Place p) {
        return new BlockPos(p.x, p.y, p.z);
    }

    private float expect(String action) {
        return 0.4f + 1.2f * ly.mind().expectation(action, ly.blockPosition()) * 0.83f;
    }
}
