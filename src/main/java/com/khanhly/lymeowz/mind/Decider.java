package com.khanhly.lymeowz.mind;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Survival;
import com.khanhly.lymeowz.survival.Tools;
import com.khanhly.lymeowz.survival.task.ChopTreeTask;
import com.khanhly.lymeowz.survival.task.CollectTask;
import com.khanhly.lymeowz.survival.task.CraftTask;
import com.khanhly.lymeowz.survival.task.EatTask;
import com.khanhly.lymeowz.survival.task.ExploreTask;
import com.khanhly.lymeowz.survival.task.HuntTask;
import com.khanhly.lymeowz.survival.task.MineTask;
import com.khanhly.lymeowz.survival.task.SimpleTasks;
import com.khanhly.lymeowz.survival.task.SmeltTask;
import com.khanhly.lymeowz.survival.task.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Utility AI: mỗi lần cần quyết định, chấm điểm (0–1) mọi việc có thể làm dựa trên
 * nhu cầu & cảm xúc × tính cách × kinh nghiệm đã học, rồi chọn việc điểm cao nhất.
 * Không có thứ tự cứng: cô ấy chán thì đi khám phá, sợ thì nghỉ cạnh chủ, đói thì đi săn...
 */
public class Decider {
    public record Option(String action, float score, String reason, boolean urgent, Supplier<Task> make) {}

    private final LyMeowzEntity ly;
    private final Set<BlockPos> blacklist;
    // Bộ nhớ đệm cho các phép tìm kiếm tốn kém.
    private ChopTreeTask treeCache;
    private BlockPos mineCache;
    private Item craftCache;
    private long cacheTime = -1000;

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
        BlockPos here = ly.blockPosition();

        long now = level.getGameTime();
        if (now - cacheTime > 100) {
            cacheTime = now;
            treeCache = ChopTreeTask.find(ly, level, center, radius, blacklist);
            mineCache = Survival.bestTier(all, Tools.Kind.PICKAXE) >= 1 ? MineTask.findTarget(ly, level, center, radius, blacklist) : null;
            craftCache = Survival.nextCraft(ly, level, all);
        }

        // Chạy trốn: nhút nhát + có quái + đang yếu hoặc rất sợ.
        Mob threat = SimpleTasks.Flee.nearestThreat(ly, level);
        if (threat != null && p.brave < 0.6f && (ly.getHealth() < 8 || m.fear > 70)) {
            out.add(new Option("chạy trốn", 0.95f, "SCARED", true, () -> new SimpleTasks.Flee(ly, threat)));
        }

        // Ăn.
        boolean starving = ly.getHunger() <= 4;
        if (ly.getHunger() <= 14 && all.count(s -> Tools.isGoodFood(s) || (starving && Tools.isFood(s))) > 0) {
            out.add(new Option("ăn", 0.45f + needH * 0.6f, "HUNGRY", ly.getHunger() <= 6, () -> new EatTask(ly)));
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
        if (SmeltTask.canSmelt(ly) && (raw >= 2 || rawIron >= 3 || (raw > 0 && needH > 0.3f))) {
            out.add(new Option("nung", 0.35f + needH * 0.4f, raw > 0 ? "RAW_FOOD" : "UPGRADE", false, () -> new SmeltTask(ly)));
        }

        // Đi săn.
        int food = all.count(Tools::isGoodFood) + raw;
        if (food < 4 || focus == Survival.Focus.FOOD) {
            Animal prey = HuntTask.find(ly, level, radius);
            if (prey != null) {
                float s = (0.3f + needH * 0.5f + (food == 0 ? 0.15f : 0)) * (0.6f + 0.4f * p.fighter)
                        * expect("đi săn") * (1 - fear * 0.5f) + (focus == Survival.Focus.FOOD ? 0.4f : 0);
                out.add(new Option("đi săn", s, needH > 0.4f ? "HUNGRY" : "FOOD_LOW", false, () -> new HuntTask(ly, prey)));
            }
        }

        // Chặt cây.
        int pickTier = Survival.bestTier(all, Tools.Kind.PICKAXE);
        int logs = all.count(ItemTags.LOGS) + all.count(ItemTags.PLANKS) / 4;
        int woodTarget = focus == Survival.Focus.WOOD ? 64 : (pickTier >= 2 ? 12 + (int) (12 * p.builder) : 8);
        if (treeCache != null && logs < woodTarget) {
            float need = 1 - (float) logs / woodTarget;
            ChopTreeTask t = treeCache;
            float s = (0.25f + 0.35f * need) * (0.6f + 0.4f * p.diligent) * expect("chặt cây") + (focus == Survival.Focus.WOOD ? 0.4f : 0);
            out.add(new Option("chặt cây", s, "WOOD_LOW", false, () -> { invalidate(); return t; }));
        }

        // Đào đá / quặng.
        if (mineCache != null) {
            int cobble = all.count(ItemTags.STONE_TOOL_MATERIALS), coal = all.count(ItemTags.COALS);
            boolean ironDone = pickTier >= 3 && Survival.bestTier(all, Tools.Kind.SWORD) >= 3 && Survival.bestTier(all, Tools.Kind.AXE) >= 3;
            int iron = rawIron + all.count(Items.IRON_INGOT);
            int stoneTarget = focus == Survival.Focus.STONE ? 64 : 16;
            boolean wantOre = coal < 8 || (pickTier >= 2 && !ironDone && iron < 8);
            if (cobble < stoneTarget || wantOre) {
                float need = Math.max(1 - (float) cobble / stoneTarget, wantOre ? 0.6f : 0);
                float s = (0.25f + 0.3f * need) * (0.6f + 0.4f * p.diligent) * (1 - 0.4f * fear) * expect("đào đá")
                        + (focus == Survival.Focus.STONE ? 0.4f : 0);
                out.add(new Option("đào đá", s, wantOre ? "ORE" : "STONE_LOW", false,
                        () -> { invalidate(); return new MineTask(ly, 8, center, radius, blacklist); }));
            }
        }

        // Khám phá: chán / tò mò, ban ngày, không đói, không sợ.
        if (!night && ly.getHunger() > 8 && m.fear < 50 && m.energy > 25 && focus == Survival.Focus.AUTO) {
            BlockPos target = ExploreTask.pickTarget(ly, level, center);
            if (target != null) {
                float s = (0.12f + 0.35f * bored + 0.35f * curious) * (0.5f + p.curious) * expect("khám phá");
                out.add(new Option("khám phá", s, bored > curious ? "BORED" : "CURIOUS", false, () -> new ExploreTask(ly, target)));
            }
        }

        // Nghỉ cạnh chủ: mệt, sợ, trời tối.
        float rest = 0.08f + 0.35f * tired + 0.25f * fear + (night ? 0.2f : 0);
        String restWhy = tired >= 0.3f && tired >= fear ? "TIRED" : fear >= 0.3f ? "SCARED" : night ? "NIGHT" : "IDLE";
        out.add(new Option("nghỉ", rest, restWhy, false, () -> new SimpleTasks.Rest(ly)));

        // Trò chuyện khi cô đơn.
        ServerPlayer owner = ly.getOwner();
        if (owner != null && owner.level() == level && owner.distanceTo(ly) < 20 && m.social < 50) {
            float s = lonely * 0.5f * (0.5f + p.social) + 0.05f * p.talkative;
            out.add(new Option("trò chuyện", s, "LONELY", false, () -> new SimpleTasks.Socialize(ly)));
        }

        // Trồng cây (thích xây/chăm vườn).
        if (all.count(ItemTags.SAPLINGS) > 0 && p.builder > 0.3f) {
            float s = (0.1f + 0.25f * p.builder + 0.1f * bored) * expect("trồng cây");
            out.add(new Option("trồng cây", s, "BUILDER", false, () -> new SimpleTasks.Plant(ly)));
        }

        // Thắp đuốc ban đêm.
        if (night && p.builder > 0.3f && SimpleTasks.Light.canLight(ly)) {
            float s = (0.2f + 0.3f * p.builder) * expect("thắp đuốc");
            out.add(new Option("thắp đuốc", s, "DARK", false, () -> new SimpleTasks.Light(ly)));
        }
        return out;
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

    private float expect(String action) {
        // 0.5 → 1.0 (chưa có kinh nghiệm), thất bại nhiều → thấp dần.
        return 0.4f + 1.2f * ly.mind().expectation(action, ly.blockPosition()) * 0.83f;
    }
}
