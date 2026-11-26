package com.khanhly.lymeowz.mind;

import com.khanhly.lymeowz.util.Perf;

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
import com.khanhly.lymeowz.house.Materials;
import com.khanhly.lymeowz.survival.task.BlueprintTask;
import com.khanhly.lymeowz.survival.task.FindHomeTask;
import com.khanhly.lymeowz.survival.task.FarmTask;
import com.khanhly.lymeowz.survival.task.FishTask;
import com.khanhly.lymeowz.survival.task.ForageTask;
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

    /**
     * Chỉ những việc gấp (chạy trốn, đói lả) — gọi mỗi giây khi đang bận việc khác. Nhẹ hơn {@link #evaluate} nhiều:
     * không dò cây, đá, chỗ làm ruộng... (trước đây cứ mỗi giây lại chấm điểm lại toàn bộ mọi việc).
     */
    private long grassCheck = Long.MIN_VALUE / 2;
    private boolean grassNear;

    public List<Option> urgent(ServerLevel level, int radius) {
        List<Option> out = new ArrayList<>();
        MindData m = ly.mind().data();
        Personality p = ly.mind().p();
        Mob threat = SimpleTasks.Flee.nearestThreat(ly, level);
        if (threat != null && (ly.getHealth() < 7 || (p.brave < 0.5f && m.fear > 70)))
            out.add(new Option("chạy trốn", 0.95f, "SCARED", true, () -> new SimpleTasks.Flee(ly, threat)));
        if (ly.getHunger() > 6) return out;
        LyInventory all = ly.inventoryWithHand();
        if (all.count(s -> Tools.edible(s, ly.getHunger())) > 0) {
            out.add(new Option("ăn", 0.45f + (20 - ly.getHunger()) / 20f * 0.6f, "HUNGRY", true, () -> new EatTask(ly)));
            return out;
        }
        BlockPos chestPos = ly.structure("chest");
        if (chestPos != null && chestPos.distSqr(ly.blockPosition()) < 96 * 96 && StoreTask.hasFood(level, chestPos)) {
            out.add(new Option("cất đồ", 0.92f, "HUNGRY", true, () -> new StoreTask(ly, chestPos)));
            return out;
        }
        int food = all.count(Tools::isGoodFood) + all.count(Tools::isRawMeat);
        // Thịt vừa rơi còn nằm dưới đất: nhặt trước đã (lần chọn việc thường sẽ làm), đừng giết thêm con nữa.
        ItemEntity drop = CollectTask.find(ly, level, 8);
        if (drop != null && Tools.isFood(drop.getItem())) return out;
        if (food == 0) {
            Animal prey = HuntTask.find(ly, level, Math.max(radius, 40), true);
            BlockPos pen = ly.structure("pen");
            if (prey != null && (pen == null || !RanchTask.inPen(pen, prey))) {
                out.add(new Option("đi săn", 0.9f, "HUNGRY", true, () -> new HuntTask(ly, prey)));
                return out;
            }
        }
        if (food == 0 && !"khám phá".equals(ly.survival.currentName())) {
            // Quanh đây hết cái ăn: đi tới chỗ từng thấy đàn thú, hoặc đi vùng mới tìm.
            var animals = ly.mind().nearest(Mind.PlaceKind.ANIMALS, ly.blockPosition());
            BlockPos to = animals != null && placePos(animals).distSqr(ly.blockPosition()) > 16 * 16 ? placePos(animals)
                    : ExploreTask.pickTarget(ly, level, ly.blockPosition());
            if (to != null) out.add(new Option("khám phá", 0.93f, "HUNGRY", true, () -> new ExploreTask(ly, to)));
        }
        return out;
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
            long q = Perf.t();
            treeCache = ChopTreeTask.find(ly, level, center, radius, blacklist);
            Perf.add("· dò cây", q); q = Perf.t();
            mineCache = pickTier >= 1 ? MineTask.findTarget(ly, level, center, radius, blacklist) : null;
            Perf.add("· dò đá/quặng", q); q = Perf.t();
            craftCache = Survival.nextCraft(ly, level, all, stage);
            Perf.add("· tính đồ cần chế", q); q = Perf.t();
            lavaCache = stage == Roadmap.Stage.NETHER ? nearLava(level) : null;
            waterCache = FishTask.findWater(ly, level, 24);
            Perf.add("· dò nước/dung nham", q);
            long pt = com.khanhly.lymeowz.util.Perf.t();
            houseNeed = houseShortfall(level);
            com.khanhly.lymeowz.util.Perf.add("tính nguyên liệu nhà", pt);
            q = Perf.t();
            berryCache = ForageTask.find(ly, level, 20, java.util.Set.of());
            Perf.add("· dò quả mọng", q);
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
        long q0 = Perf.t();
        ItemEntity drop = CollectTask.find(ly, level, 8);
        Perf.add("· dò đồ rơi", q0);
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

        // Đồ ăn dự phòng: hái quả mọng, câu cá.
        if (berryCache != null && (food < 6 || needH > 0.3f)) {
            out.add(new Option("hái quả", (0.3f + needH * 0.5f) * expect("hái quả"), "FOOD_LOW", false, () -> new ForageTask(ly)));
        }
        if (waterCache != null && !night) {
            boolean rod = FishTask.hasRod(ly);
            float hungry = food < 4 ? 0.3f + needH * 0.45f : 0;
            float relax = (Roadmap.has(ly, "home") || Roadmap.has(ly, "house")) && m.fear < 30 ? (0.08f + 0.35f * bored) * (0.5f + p.cheerful) : 0;
            float s = Math.max(hungry, relax) * expect("câu cá");
            if (s > 0.05f) {
                if (rod) out.add(new Option("câu cá", s, hungry > relax ? "FOOD_LOW" : "BORED", false, () -> new FishTask(ly, hungry > relax ? 5 : 3)));
                else if (CraftTask.canMake(ly, level, Items.FISHING_ROD))
                    out.add(new Option("chế tạo", s * 0.9f, "FOOD_LOW", false, () -> new CraftTask(ly, Items.FISHING_ROD)));
                else if (all.count(Items.STRING) < 2 && now > gatherAgain) {
                    // Chưa có dây câu: chém mạng nhện lấy tơ.
                    BlockPos web = GatherTask.nearest(ly, level, st -> st.is(net.minecraft.world.level.block.Blocks.COBWEB), 32, java.util.Set.of());
                    if (web != null) out.add(new Option("lấy tơ", s * 0.8f, "FOOD_LOW", false, () -> { gatherAgain = level.getGameTime() + 1200;
                        return new GatherTask(ly, "lấy tơ", st -> st.is(net.minecraft.world.level.block.Blocks.COBWEB), x -> x.is(Items.STRING), 2, 6, 32); }));
                }
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
        if (houseNeed != null)
            woodTarget = Math.max(woodTarget, logs + Math.min(24, (int) Math.ceil(houseNeed.getOrDefault(Materials.Mat.WOOD, 0f) / 4f)));
        if (focus == Survival.Focus.WOOD) woodTarget = 64;
        if (treeCache != null && logs < woodTarget) {
            float need = 1 - (float) logs / woodTarget;
            ChopTreeTask t = treeCache;
            float s = (0.25f + 0.4f * need) * (0.6f + 0.4f * p.diligent) * expect("chặt cây") + (focus == Survival.Focus.WOOD ? 0.4f : 0);
            out.add(new Option("chặt cây", s, "WOOD_LOW", false, () -> { invalidate(); return t; }));
        }

        // Đá: nhà cần ~80 khối, sau đó giữ ít để bịt nham thạch, xây hộp trú.
        int cobble = all.count(ItemTags.STONE_TOOL_MATERIALS) + all.count(Items.COBBLED_DEEPSLATE);
        int cobbleNow = cobble;
        int stoneTarget = focus == Survival.Focus.STONE ? 64 : 24;
        if (houseNeed != null) {
            // Nhà đang xây còn cần bao nhiêu đá / gỗ thì gom thêm bấy nhiêu (mỗi chuyến một ít).
            stoneTarget = Math.max(stoneTarget, cobbleNow + Math.min(64, (int) Math.ceil(houseNeed.getOrDefault(Materials.Mat.STONE, 0f))));
        }
        int coal = all.count(ItemTags.COALS);
        // Thiếu than thì đào đá tìm than, nhưng túi đã ních đá rồi thì thôi (khỏi đào trụi cả quả đồi).
        boolean wantCoal = coal < 6 && cobble < 96;
        if (pickTier >= 1 && (cobble < stoneTarget || wantCoal)) {
            float need = Math.max(1 - (float) cobble / stoneTarget, wantCoal ? 0.4f : 0);
            float s = (0.25f + 0.35f * need) * (0.6f + 0.4f * p.diligent) * (1 - 0.4f * fear) * expect("đào đá")
                    + (focus == Survival.Focus.STONE ? 0.4f : 0);
            if (mineCache != null) {
                out.add(new Option("đào đá", s, wantCoal ? "ORE" : "STONE_LOW", false,
                        () -> { invalidate(); return new MineTask(ly, 12, center, radius, blacklist); }));
            } else if (mind.nearest(Mind.PlaceKind.STONE, ly.blockPosition()) != null
                    && ly.blockPosition().distSqr(placePos(mind.nearest(Mind.PlaceKind.STONE, ly.blockPosition()))) > 12 * 12) {
                // Nhớ có bãi đá ở chỗ khác: đi tới đó.
                BlockPos at = placePos(mind.nearest(Mind.PlaceKind.STONE, ly.blockPosition()));
                out.add(new Option("khám phá", s * 0.9f, "STONE_LOW", false, () -> new ExploreTask(ly, at)));
            } else if (pickTier >= 1 && ly.getHealth() > 12 && ly.getBlockY() - 14 > level.getMinY() + 8) {
                // Không thấy đá lộ thiên: đào cầu thang xuống một đoạn như người chơi (một chuyến cho ra trò, đỡ lên xuống nhiều lần).
                int target = ly.getBlockY() - 14;
                out.add(new Option("đào mỏ", s * 0.9f, "STONE_LOW", false, () -> new StairMineTask(ly, target, 14)));
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

        // Nhà theo bản vẽ: tìm đất → gom đồ → xây dần; ở ổn rồi thì xây tiếp nhà lớn hơn, trang trí dần.
        long qh = Perf.t();
        houseOptions(level, all, out, p, night, stage);
        Perf.add("· các việc nhà cửa", qh);
        BlockPos house = ly.structure("home") != null ? ly.structure("home") : ly.structure("house");
        boolean legacyHouse = ly.structure("home") == null && ly.structure("house") != null;
        if (homeNear && house != null) {
            // Ruộng.
            BlockPos farm = ly.structure("farm");
            if (farm == null && stage.ordinal() >= Roadmap.Stage.FARM.ordinal() && FarmTask.hasHoe(ly)) {
                if (farmSite == null || freshSites) { long q = Perf.t(); farmSite = FarmTask.findSite(ly, level, home); Perf.add("· tìm chỗ làm ruộng", q); }
                // Chưa có hạt giống mà quanh đây cũng chẳng còn bụi cỏ nào: khoan làm ruộng (đi đâu đó có cỏ đã).
                boolean seedsOk = FarmTask.seeds(ly) >= 4;
                if (!seedsOk) {
                    if (now - grassCheck >= 600) { grassCheck = now; grassNear = FarmTask.findGrass(ly, level, 48) != null; }
                    seedsOk = grassNear;
                }
                if (farmSite != null && seedsOk) {
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
                else if (penSite == null || freshSites) { long q = Perf.t(); penSite = PenTask.findSite(ly, level, home, farm); Perf.add("· tìm chỗ làm chuồng", q); }
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
                if (all.count(ItemTags.BEDS) > 0 && legacyHouse) {
                    out.add(new Option("đặt giường", 0.7f, "BUILDER", false, () -> new BedTask(ly, house)));
                } else if (all.count(ItemTags.WOOL) >= 3) {
                    if (legacyHouse && CraftTask.canMake(ly, level, bedItem))
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
            if (!night && legacyHouse && Roadmap.has(ly, "bed")) upgradeOptions(level, all, out, p, house);
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

        // ---------------------------------------------------------------- bản năng sinh tồn
        // Đói mà trong túi không có gì ăn được: không còn đồ ăn quanh đây thì đi xa tìm (chỗ nhớ có thú, hoặc vùng mới).
        boolean noFood = all.count(s -> Tools.edible(s, Math.min(ly.getHunger(), 4))) == 0;
        if (noFood && ly.getHunger() <= 10 && out.stream().noneMatch(o -> isFood(o.reason()) && !o.action().equals("chế tạo"))) {
            var animals = mind.nearest(Mind.PlaceKind.ANIMALS, ly.blockPosition());
            BlockPos to = animals != null && placePos(animals).distSqr(ly.blockPosition()) > 16 * 16 ? placePos(animals)
                    : ExploreTask.pickTarget(ly, level, ly.blockPosition());
            if (to != null) {
                boolean starving = ly.getHunger() <= 6;
                out.add(new Option("khám phá", starving ? 0.93f : 0.6f, "HUNGRY", starving, () -> new ExploreTask(ly, to)));
            }
        }
        // Đói lả (≤ 8) mà chưa có gì ăn: mọi việc khác (xây nhà, đào đá...) đều xếp sau chuyện kiếm ăn.
        if (noFood && ly.getHunger() <= 8) {
            float k = ly.getHunger() <= 4 ? 0.2f : 0.4f;
            out.replaceAll(o -> isFood(o.reason()) || SURVIVE.contains(o.action()) ? o
                    : new Option(o.action(), o.score() * k, o.reason(), false, o.make()));
        }
        return out;
    }

    /** Những lý do thuộc về chuyện ăn uống. */
    public static final java.util.Set<String> FOOD_REASONS = java.util.Set.of("HUNGRY", "FOOD_LOW", "RAW_FOOD");

    public static boolean isFood(String reason) {
        return reason != null && FOOD_REASONS.contains(reason);
    }
    private static final java.util.Set<String> SURVIVE = java.util.Set.of("chạy trốn", "nhặt đồ", "ăn", "nung");

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
            if (portalSite == null || portalSite.distSqr(home) > 20 * 20) { long q = Perf.t(); portalSite = PortalTask.findSite(ly, level, home); Perf.add("· tìm chỗ dựng cổng", q); }
            if (portalSite != null) {
                BlockPos site = portalSite;
                out.add(new Option("dựng cổng Nether", base + 0.25f, "BUILDER", false, () -> new PortalTask(ly, site)));
            }
        }
    }

    private BlockPos portalSite, lavaCache, waterCache, berryCache;
    /** Nguyên liệu nhà đang xây còn thiếu (đã trừ đồ trong túi và trong rương). */
    private java.util.Map<Materials.Mat, Float> houseNeed;
    private boolean houseCanProgress;

    /** Bản vẽ đang xây (hoặc sẽ xây) và chỗ đặt, nếu có. */
    private com.khanhly.lymeowz.house.Blueprint currentBlueprint() {
        var d = ly.data();
        if (d == null || d.blueprint == null) return null;
        return com.khanhly.lymeowz.house.Blueprint.byName(d.blueprint);
    }

    public BlockPos currentOrigin() {
        var d = ly.data();
        if (d == null || d.blueprintAt == null || !d.blueprintAt.dim.equals(com.khanhly.lymeowz.compat.Compat.dimId(ly.level().dimension()))) return null;
        return new BlockPos(d.blueprintAt.x, d.blueprintAt.y, d.blueprintAt.z);
    }

    /** Thiếu gì cho nhà đang xây (null nếu không xây gì). */
    private java.util.Map<Materials.Mat, Float> houseShortfall(ServerLevel level) {
        var bp = currentBlueprint();
        BlockPos at = currentOrigin();
        houseCanProgress = false;
        if (bp == null || at == null || !level.isLoaded(at)) return null;
        var need = BlueprintTask.need(level, bp, at, false);
        java.util.Map<Materials.Mat, Float> have = Materials.amounts(ly.inventoryWithHand());
        BlockPos chest = ly.structure("chest");
        if (chest != null) {
            var st = level.getBlockState(chest);
            if (st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock cb) {
                var c = net.minecraft.world.level.block.ChestBlock.getContainer(cb, st, level, chest, true);
                if (c != null) for (var e : Materials.amounts(c).entrySet()) have.merge(e.getKey(), e.getValue(), Float::sum);
            }
        }
        java.util.Map<Materials.Mat, Float> out = new java.util.EnumMap<>(Materials.Mat.class);
        for (var e : need.entrySet()) {
            float h = have.getOrDefault(e.getKey(), 0f);
            if (e.getValue() > h) out.put(e.getKey(), e.getValue() - h);
        }
        // Đủ đồ xây tiếp một đoạn ra trò (≥ 24 khối, hoặc phần còn lại) thì mới đi xây, không thì đi kiếm tiếp.
        int left = BlueprintTask.remaining(level, bp, at)[0];
        int batch = Math.min(24, left);
        houseCanProgress = need.isEmpty() || left == 0 || BlueprintTask.affordable(level, bp, at, have, batch) >= batch;
        return out;
    }

    /** Nhà theo bản vẽ. */
    private void houseOptions(ServerLevel level, LyInventory all, List<Option> out, Personality p, boolean night, Roadmap.Stage stage) {
        var d = ly.data();
        if (d == null) return;
        var plans = com.khanhly.lymeowz.house.Blueprint.all();
        if (plans.isEmpty()) return;
        boolean hasHome = d.structures.containsKey("home");
        // Xây nhà đầu tiên khi đã có đồ đá; nhà tiếp theo khi đã có đồ sắt.
        boolean wantNew = !hasHome ? stage.ordinal() >= Roadmap.Stage.SHELTER.ordinal()
                : stage.ordinal() >= Roadmap.Stage.IRON.ordinal() && d.builtHouses.size() < plans.size();
        // Nhà đầu tiên: sống ngoài trời khám phá, tích gỗ đá một thời gian (~5 phút) rồi mới đi chọn đất.
        if (wantNew && !hasHome && d.shelterSince == 0) { d.shelterSince = level.getGameTime(); com.khanhly.lymeowz.world.CompanionStore.markDirty(); }
        if (wantNew && !hasHome && level.getGameTime() - d.shelterSince < 6000 && Materials.amount(all, Materials.Mat.WOOD) < 96) wantNew = false;
        if (wantNew && d.blueprint == null && !night) {
            var next = plans.get(Math.min(d.builtHouses.size(), plans.size() - 1));
            if (!d.builtHouses.contains(next.name)) {
                float s = hasHome ? 0.3f + 0.2f * p.builder : 0.55f + 0.2f * p.curious + 0.1f * p.builder;
                out.add(new Option("tìm đất làm nhà", s, "BUILDER", false, () -> new FindHomeTask(ly, next, hasHome)));
            }
        }
        var bp = currentBlueprint();
        BlockPos at = currentOrigin();
        if (bp != null && at != null && houseCanProgress && at.distSqr(ly.blockPosition()) < 160 * 160) {
            float s = 0.6f + 0.3f * p.builder - (night ? 0.2f : 0);
            out.add(new Option("xây nhà", s, "BUILDER", false, () -> new BlueprintTask(ly, bp, at, false)));
        }
        // Trang trí dần nhà đang ở: hoa, đèn lồng, thảm, giường... khi có đồ.
        if (hasHome && d.homeBlueprint != null && !night && level.getGameTime() > decorAgain) {
            var home = com.khanhly.lymeowz.house.Blueprint.byName(d.homeBlueprint);
            BlockPos origin = ly.structure("home");
            if (home != null && origin != null && level.isLoaded(origin)) {
                var need = BlueprintTask.need(level, home, origin, true);
                java.util.Map<Materials.Mat, Float> have = Materials.amounts(ly.inventoryWithHand());
                boolean can = false;
                for (var e : need.entrySet()) if (have.getOrDefault(e.getKey(), 0f) >= Math.min(e.getValue(), 1)) can = true;
                if (can && !need.isEmpty()) {
                    out.add(new Option("trang trí nhà", 0.35f + 0.25f * p.builder, "BUILDER", false, () -> {
                        decorAgain = level.getGameTime() + 20 * 60 * 2;
                        return new BlueprintTask(ly, home, origin, true);
                    }));
                }
            }
        }
    }

    private long decorAgain;

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
