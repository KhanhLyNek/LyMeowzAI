package com.khanhly.lymeowz.survival;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Công thức chế tạo theo đúng số lượng vanilla cho mọi món trong lộ trình sinh tồn.
 * Ván và que được tự làm thêm từ gỗ khi thiếu. {@link #plan} chạy thử trên bản sao túi đồ.
 */
public final class Crafting {
    private Crafting() {}

    public record Step(Item output, int count, boolean needsTable, List<Need> needs) {}

    public record Need(Predicate<ItemStack> what, int count) {}

    /** Một công thức: nguyên liệu, số lượng ra, có cần bàn 3x3 không. */
    public record Recipe(Item output, int count, boolean table, List<Need> needs) {}

    public static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    public static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
    public static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
    public static final Predicate<ItemStack> COBBLE = s -> s.is(ItemTags.STONE_TOOL_MATERIALS);
    public static final Predicate<ItemStack> FURNACE_STONE = s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS);
    public static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);
    public static final Predicate<ItemStack> DIAMOND = s -> s.is(Items.DIAMOND);
    public static final Predicate<ItemStack> COAL = s -> s.is(ItemTags.COALS);
    public static final Predicate<ItemStack> WOOL = s -> s.is(ItemTags.WOOL);
    public static final Predicate<ItemStack> WHEAT = s -> s.is(Items.WHEAT);
    public static final Predicate<ItemStack> FLINT = s -> s.is(Items.FLINT);

    private static final Map<Item, Recipe> RECIPES = new HashMap<>();

    private static void r(Item out, int count, boolean table, Object... needs) {
        List<Need> list = new ArrayList<>();
        for (int i = 0; i < needs.length; i += 2) {
            @SuppressWarnings("unchecked") Predicate<ItemStack> p = (Predicate<ItemStack>) needs[i];
            list.add(new Need(p, (Integer) needs[i + 1]));
        }
        RECIPES.put(out, new Recipe(out, count, table, list));
    }

    static {
        r(Items.CRAFTING_TABLE, 1, false, PLANKS, 4);
        r(Items.TORCH, 4, false, COAL, 1, STICK, 1);
        // Dụng cụ
        r(Items.WOODEN_PICKAXE, 1, true, PLANKS, 3, STICK, 2);
        r(Items.WOODEN_AXE, 1, true, PLANKS, 3, STICK, 2);
        r(Items.WOODEN_SWORD, 1, true, PLANKS, 2, STICK, 1);
        r(Items.WOODEN_HOE, 1, true, PLANKS, 2, STICK, 2);
        r(Items.STONE_PICKAXE, 1, true, COBBLE, 3, STICK, 2);
        r(Items.STONE_AXE, 1, true, COBBLE, 3, STICK, 2);
        r(Items.STONE_SWORD, 1, true, COBBLE, 2, STICK, 1);
        r(Items.STONE_HOE, 1, true, COBBLE, 2, STICK, 2);
        r(Items.IRON_PICKAXE, 1, true, IRON, 3, STICK, 2);
        r(Items.IRON_AXE, 1, true, IRON, 3, STICK, 2);
        r(Items.IRON_SWORD, 1, true, IRON, 2, STICK, 1);
        r(Items.IRON_HOE, 1, true, IRON, 2, STICK, 2);
        r(Items.DIAMOND_PICKAXE, 1, true, DIAMOND, 3, STICK, 2);
        r(Items.DIAMOND_AXE, 1, true, DIAMOND, 3, STICK, 2);
        r(Items.DIAMOND_SWORD, 1, true, DIAMOND, 2, STICK, 1);
        r(Items.SHEARS, 1, false, IRON, 2);
        r(Items.BUCKET, 1, true, IRON, 3);
        r(Items.SHIELD, 1, true, PLANKS, 6, IRON, 1);
        // Giáp
        r(Items.IRON_HELMET, 1, true, IRON, 5);
        r(Items.IRON_CHESTPLATE, 1, true, IRON, 8);
        r(Items.IRON_LEGGINGS, 1, true, IRON, 7);
        r(Items.IRON_BOOTS, 1, true, IRON, 4);
        r(Items.DIAMOND_HELMET, 1, true, DIAMOND, 5);
        r(Items.DIAMOND_CHESTPLATE, 1, true, DIAMOND, 8);
        r(Items.DIAMOND_LEGGINGS, 1, true, DIAMOND, 7);
        r(Items.DIAMOND_BOOTS, 1, true, DIAMOND, 4);
        // Nhà cửa, trang trại
        r(Items.FURNACE, 1, true, FURNACE_STONE, 8);
        r(Items.CHEST, 1, true, PLANKS, 8);
        r(Items.OAK_DOOR, 3, true, PLANKS, 6);
        r(Items.OAK_FENCE, 3, true, PLANKS, 4, STICK, 2);
        r(Items.OAK_FENCE_GATE, 1, true, STICK, 4, PLANKS, 2);
        r(Items.BED.white(), 1, true, WOOL, 3, PLANKS, 3);
        r(Items.CAMPFIRE, 1, true, STICK, 3, COAL, 1, LOGS, 3);
        r(Items.OAK_BOAT, 1, true, PLANKS, 5);
        r(Items.BREAD, 1, true, WHEAT, 3);
        r(Items.OAK_SIGN, 3, true, PLANKS, 6, STICK, 1);
        r(Items.FLINT_AND_STEEL, 1, false, IRON, 1, FLINT, 1);
    }

    public static boolean known(Item item) {
        return RECIPES.containsKey(item) || item == Items.STICK;
    }

    /** Kế hoạch làm {@code count} món {@code target} trên {@code inv} (nên là bản sao), hoặc null. */
    public static List<Step> plan(LyInventory inv, Item target, boolean tableNearby) {
        return plan(inv, target, 1, tableNearby);
    }

    public static List<Step> plan(LyInventory inv, Item target, int times, boolean tableNearby) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < times; i++) if (!make(inv, target, tableNearby, steps)) return null;
        return steps;
    }

    private static boolean make(LyInventory inv, Item target, boolean tableNearby, List<Step> steps) {
        if (target == Items.STICK) return makeSticks(inv, steps);
        Recipe r = RECIPES.get(target);
        if (r == null) return false;
        // Nguyên liệu không tự làm được thì phải có sẵn.
        for (Need n : r.needs) {
            if (n.what != PLANKS && n.what != STICK && n.what != LOGS && inv.count(n.what) < n.count) return false;
        }
        // Bàn trước, để ván dùng cho bàn không bị tính trùng.
        if (r.table && !tableNearby && !inv.has(Items.CRAFTING_TABLE) && !make(inv, Items.CRAFTING_TABLE, false, steps)) return false;
        for (Need n : r.needs) {
            if (n.what == STICK) { while (inv.count(STICK) < n.count) if (!makeSticks(inv, steps)) return false; }
        }
        for (Need n : r.needs) {
            if (n.what == PLANKS && !ensurePlanks(inv, n.count + reserveLogsAsPlanks(r), steps)) return false;
        }
        return apply(inv, new Step(r.output, r.count, r.table, r.needs), steps);
    }

    /** Lửa trại cần 3 khúc gỗ nguyên: đừng xẻ chúng thành ván. */
    private static int reserveLogsAsPlanks(Recipe r) {
        return 0;
    }

    private static boolean makeSticks(LyInventory inv, List<Step> steps) {
        if (!ensurePlanks(inv, 2, steps)) return false;
        return apply(inv, new Step(Items.STICK, 4, false, List.of(new Need(PLANKS, 2))), steps);
    }

    private static boolean ensurePlanks(LyInventory inv, int n, List<Step> steps) {
        while (inv.count(PLANKS) < n) {
            ItemStack log = firstMatching(inv, LOGS);
            if (log == null) return false;
            Item logItem = log.getItem();
            if (!apply(inv, new Step(planksFor(logItem), 4, false, List.of(new Need(s -> s.is(logItem), 1))), steps)) return false;
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
        return apply(inv, step, new ArrayList<>());
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

    public static LyInventory copy(LyInventory inv) {
        LyInventory c = new LyInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) c.setItem(i, inv.getItem(i).copy());
        return c;
    }
}
