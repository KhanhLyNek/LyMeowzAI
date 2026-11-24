package com.khanhly.lymeowz.survival;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/** Nhận biết dụng cụ, cấp vật liệu, đồ ăn, đồ nung. */
public final class Tools {
    private Tools() {}

    public enum Kind { PICKAXE, AXE, SWORD }

    /** 0 = không phải, 1 gỗ/vàng, 2 đá, 3 sắt, 4 kim cương, 5 netherite. */
    public static int tier(ItemStack s) {
        if (s.isEmpty()) return 0;
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (path.startsWith("netherite_")) return 5;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("iron_")) return 3;
        if (path.startsWith("stone_")) return 2;
        if (path.startsWith("wooden_") || path.startsWith("golden_")) return 1;
        return 1;
    }

    public static boolean isKind(ItemStack s, Kind k) {
        return switch (k) {
            case PICKAXE -> s.is(ItemTags.PICKAXES);
            case AXE -> s.is(ItemTags.AXES);
            case SWORD -> s.is(ItemTags.SWORDS);
        };
    }

    /** Điểm của một dụng cụ cho việc phá block này (0 = không giúp gì). */
    public static double scoreFor(ItemStack s, BlockState state) {
        if (s.isEmpty()) return 0;
        float speed = s.getDestroySpeed(state);
        if (speed <= 1.0f) return 0;                              // không nhanh hơn tay không → không phải dụng cụ cho việc này
        boolean correct = !state.requiresCorrectToolForDrops() || s.isCorrectToolForDrops(state);
        return (correct ? 100 : 0) + speed;
    }

    public static double weaponScore(ItemStack s) {
        if (s.is(ItemTags.SWORDS)) return 10 + tier(s);
        if (s.is(ItemTags.AXES)) return 5 + tier(s);
        return 0;
    }

    // ------------------------------------------------------------------ đồ ăn

    public static boolean isFood(ItemStack s) {
        return s.get(DataComponents.FOOD) != null;
    }

    /** Đồ ăn "an toàn" (không ăn thịt thối, mắt nhện... trừ khi sắp chết đói). */
    public static boolean isGoodFood(ItemStack s) {
        if (!isFood(s)) return false;
        return !(s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO)
                || s.is(Items.PUFFERFISH) || s.is(Items.CHORUS_FRUIT) || s.is(Items.SUSPICIOUS_STEW)
                || isRawMeat(s));
    }

    public static int nutrition(ItemStack s) {
        FoodProperties f = s.get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
    }

    // ------------------------------------------------------------------ nung

    private static final Map<Item, Item> SMELT = Map.of(
            Items.BEEF, Items.COOKED_BEEF,
            Items.PORKCHOP, Items.COOKED_PORKCHOP,
            Items.CHICKEN, Items.COOKED_CHICKEN,
            Items.MUTTON, Items.COOKED_MUTTON,
            Items.RABBIT, Items.COOKED_RABBIT,
            Items.COD, Items.COOKED_COD,
            Items.SALMON, Items.COOKED_SALMON,
            Items.POTATO, Items.BAKED_POTATO,
            Items.RAW_IRON, Items.IRON_INGOT);

    public static Item smeltResult(ItemStack s) {
        return SMELT.get(s.getItem());
    }

    public static boolean isRawMeat(ItemStack s) {
        Item r = SMELT.get(s.getItem());
        return r != null && r != Items.IRON_INGOT && r != Items.BAKED_POTATO;
    }

    /** Số món nung được từ một món chất đốt (0 = không phải chất đốt mình dùng). */
    public static double fuelValue(ItemStack s) {
        if (s.is(ItemTags.COALS)) return 8;
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) return 1.5;
        if (s.is(Items.STICK)) return 0.5;
        return 0;
    }
}
