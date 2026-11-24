package com.khanhly.lymeowz.survival;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Công thức chế tạo theo đúng số lượng của Minecraft vanilla, cho những món một người chơi sinh tồn cần:
 * ván, que, bàn chế tạo, cuốc/rìu/kiếm gỗ–đá–sắt, lò nung, đuốc.
 * <p>
 * {@link #plan} chạy thử trên một bản sao của túi đồ để biết có làm được không và cần những bước nào.
 */
public final class Crafting {
    private Crafting() {}

    /** Một bước chế tạo. */
    public record Step(Item output, int count, boolean needsTable, List<Need> needs) {}

    public record Need(Predicate<ItemStack> what, int count) {}

    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    private static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
    private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
    private static final Predicate<ItemStack> COBBLE = s -> s.is(ItemTags.STONE_TOOL_MATERIALS);
    private static final Predicate<ItemStack> FURNACE_STONE = s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS);
    private static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);
    private static final Predicate<ItemStack> COAL = s -> s.is(ItemTags.COALS);

    /** Món → (vật liệu chính, số lượng, số que). */
    private record ToolRecipe(Predicate<ItemStack> material, int amount, int sticks) {}

    private static final Map<Item, ToolRecipe> TOOLS = Map.ofEntries(
            Map.entry(Items.WOODEN_PICKAXE, new ToolRecipe(PLANKS, 3, 2)),
            Map.entry(Items.WOODEN_AXE, new ToolRecipe(PLANKS, 3, 2)),
            Map.entry(Items.WOODEN_SWORD, new ToolRecipe(PLANKS, 2, 1)),
            Map.entry(Items.STONE_PICKAXE, new ToolRecipe(COBBLE, 3, 2)),
            Map.entry(Items.STONE_AXE, new ToolRecipe(COBBLE, 3, 2)),
            Map.entry(Items.STONE_SWORD, new ToolRecipe(COBBLE, 2, 1)),
            Map.entry(Items.IRON_PICKAXE, new ToolRecipe(IRON, 3, 2)),
            Map.entry(Items.IRON_AXE, new ToolRecipe(IRON, 3, 2)),
            Map.entry(Items.IRON_SWORD, new ToolRecipe(IRON, 2, 1)));

    /**
     * Lên kế hoạch làm {@code target} trên {@code inv} (nên là bản sao – hàm này sửa túi đồ).
     * @param tableNearby có sẵn bàn chế tạo đặt gần đó
     * @return danh sách bước, hoặc null nếu không đủ nguyên liệu
     */
    public static List<Step> plan(LyInventory inv, Item target, boolean tableNearby) {
        List<Step> steps = new ArrayList<>();
        return make(inv, target, tableNearby, steps) ? steps : null;
    }

    private static boolean make(LyInventory inv, Item target, boolean tableNearby, List<Step> steps) {
        if (target == Items.STICK) return makeSticks(inv, steps);
        if (target == Items.CRAFTING_TABLE) {
            if (!ensurePlanks(inv, 4, steps)) return false;
            return apply(inv, new Step(Items.CRAFTING_TABLE, 1, false, List.of(new Need(PLANKS, 4))), steps);
        }
        if (target == Items.TORCH) {
            if (inv.count(COAL) < 1) return false;
            if (inv.count(STICK) < 1 && !makeSticks(inv, steps)) return false;
            return apply(inv, new Step(Items.TORCH, 4, false, List.of(new Need(COAL, 1), new Need(STICK, 1))), steps);
        }
        if (target == Items.FURNACE) {
            if (inv.count(FURNACE_STONE) < 8) return false;
            if (!ensureTable(inv, tableNearby, steps)) return false; // bàn trước, rồi mới tới nguyên liệu
            return apply(inv, new Step(Items.FURNACE, 1, true, List.of(new Need(FURNACE_STONE, 8))), steps);
        }
        ToolRecipe r = TOOLS.get(target);
        if (r == null) return false;
        if (r.material != PLANKS && inv.count(r.material) < r.amount) return false;
        // Làm bàn trước (nếu cần) để ván dùng cho bàn không bị tính trùng với ván làm đồ.
        if (!ensureTable(inv, tableNearby, steps)) return false;
        if (r.material == PLANKS) {
            if (!ensurePlanks(inv, r.amount, steps)) return false;
        } else if (inv.count(r.material) < r.amount) {
            return false;
        }
        while (inv.count(STICK) < r.sticks) if (!makeSticks(inv, steps)) return false;
        if (r.material == PLANKS && !ensurePlanks(inv, r.amount, steps)) return false;
        return apply(inv, new Step(target, 1, true, List.of(new Need(r.material, r.amount), new Need(STICK, r.sticks))), steps);
    }

    /** Có bàn đặt gần đó, hoặc trong túi có bàn (sẽ đặt xuống rồi thu lại), hoặc làm một cái. */
    private static boolean ensureTable(LyInventory inv, boolean tableNearby, List<Step> steps) {
        if (tableNearby || inv.has(Items.CRAFTING_TABLE)) return true;
        return make(inv, Items.CRAFTING_TABLE, false, steps);
    }

    private static boolean makeSticks(LyInventory inv, List<Step> steps) {
        if (!ensurePlanks(inv, 2, steps)) return false;
        return apply(inv, new Step(Items.STICK, 4, false, List.of(new Need(PLANKS, 2))), steps);
    }

    private static boolean ensurePlanks(LyInventory inv, int n, List<Step> steps) {
        while (inv.count(PLANKS) < n) {
            ItemStack log = firstMatching(inv, LOGS);
            if (log == null) return false;
            Item planks = planksFor(log.getItem());
            Item logItem = log.getItem();
            if (!apply(inv, new Step(planks, 4, false, List.of(new Need(s -> s.is(logItem), 1))), steps)) return false;
        }
        return true;
    }

    private static boolean apply(LyInventory inv, Step step, List<Step> steps) {
        for (Need n : step.needs) if (inv.count(n.what) < n.count) return false;
        for (Need n : step.needs) inv.consume(n.what, n.count);
        ItemStack left = inv.put(new ItemStack(step.output, step.count));
        steps.add(step);
        return left.isEmpty();
    }

    /** Thực hiện một bước trên túi đồ thật. */
    public static boolean execute(LyInventory inv, Step step) {
        List<Step> ignored = new ArrayList<>();
        return apply(inv, step, ignored);
    }

    private static ItemStack firstMatching(LyInventory inv, Predicate<ItemStack> p) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && p.test(s)) return s;
        }
        return null;
    }

    /** oak_log → oak_planks, crimson_stem → crimson_planks… (mặc định ván sồi). */
    public static Item planksFor(Item log) {
        Identifier id = BuiltInRegistries.ITEM.getKey(log);
        String base = id.getPath().replace("stripped_", "")
                .replace("_log", "").replace("_wood", "").replace("_stem", "").replace("_hyphae", "");
        if (base.equals("bamboo_block")) base = "bamboo";
        Item planks = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), base + "_planks"));
        return planks == null || planks == Items.AIR ? Items.OAK_PLANKS : planks;
    }

    /** Bản sao túi đồ để chạy thử. */
    public static LyInventory copy(LyInventory inv) {
        LyInventory c = new LyInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) c.setItem(i, inv.getItem(i).copy());
        return c;
    }
}
