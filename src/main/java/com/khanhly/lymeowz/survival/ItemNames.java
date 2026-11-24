package com.khanhly.lymeowz.survival;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/** Tên tiếng Việt cho những món cô ấy hay nhắc tới (server không có bản dịch của client). */
public final class ItemNames {
    private ItemNames() {}

    private static final Map<String, String> VI = Map.ofEntries(
            Map.entry("wooden_pickaxe", "cuốc gỗ"), Map.entry("wooden_axe", "rìu gỗ"), Map.entry("wooden_sword", "kiếm gỗ"),
            Map.entry("stone_pickaxe", "cuốc đá"), Map.entry("stone_axe", "rìu đá"), Map.entry("stone_sword", "kiếm đá"),
            Map.entry("iron_pickaxe", "cuốc sắt"), Map.entry("iron_axe", "rìu sắt"), Map.entry("iron_sword", "kiếm sắt"),
            Map.entry("crafting_table", "bàn chế tạo"), Map.entry("furnace", "lò nung"), Map.entry("torch", "đuốc"),
            Map.entry("stick", "que gỗ"), Map.entry("coal", "than"), Map.entry("charcoal", "than củi"),
            Map.entry("raw_iron", "sắt thô"), Map.entry("iron_ingot", "thỏi sắt"), Map.entry("cobblestone", "đá cuội"),
            Map.entry("cobbled_deepslate", "đá cuội sâu"), Map.entry("leather", "da"), Map.entry("beef", "thịt bò sống"),
            Map.entry("cooked_beef", "bít tết"), Map.entry("porkchop", "thịt heo sống"), Map.entry("cooked_porkchop", "thịt heo nướng"),
            Map.entry("chicken", "thịt gà sống"), Map.entry("cooked_chicken", "gà nướng"), Map.entry("mutton", "thịt cừu sống"),
            Map.entry("cooked_mutton", "thịt cừu nướng"), Map.entry("apple", "táo"), Map.entry("bread", "bánh mì"),
            Map.entry("wheat_seeds", "hạt giống"), Map.entry("feather", "lông gà"), Map.entry("white_wool", "len"),
            Map.entry("oak_log", "gỗ sồi"), Map.entry("oak_planks", "ván sồi"), Map.entry("oak_sapling", "cây sồi con"),
            Map.entry("birch_log", "gỗ bạch dương"), Map.entry("spruce_log", "gỗ vân sam"), Map.entry("dirt", "đất"),
            Map.entry("oak_fence", "hàng rào"), Map.entry("oak_fence_gate", "cổng rào"), Map.entry("oak_door", "cửa gỗ"),
            Map.entry("chest", "rương"), Map.entry("white_bed", "giường"), Map.entry("wheat", "lúa mì"), Map.entry("carrot", "cà rốt"),
            Map.entry("potato", "khoai tây"), Map.entry("wooden_hoe", "cuốc làm đất gỗ"), Map.entry("stone_hoe", "cuốc làm đất đá"),
            Map.entry("shears", "kéo"), Map.entry("bucket", "xô"), Map.entry("water_bucket", "xô nước"), Map.entry("shield", "khiên"),
            Map.entry("iron_helmet", "mũ sắt"), Map.entry("iron_chestplate", "áo giáp sắt"), Map.entry("iron_leggings", "quần giáp sắt"),
            Map.entry("iron_boots", "giày sắt"), Map.entry("diamond", "kim cương"), Map.entry("diamond_pickaxe", "cuốc kim cương"),
            Map.entry("diamond_sword", "kiếm kim cương"), Map.entry("oak_boat", "thuyền"), Map.entry("campfire", "lửa trại"),
            Map.entry("raw_copper", "đồng thô"), Map.entry("dandelion", "hoa bồ công anh"), Map.entry("poppy", "hoa anh túc"),
            Map.entry("cornflower", "hoa xa cúc"), Map.entry("glass", "kính"), Map.entry("sand", "cát"), Map.entry("obsidian", "obsidian"),
            Map.entry("flint", "đá lửa"), Map.entry("flint_and_steel", "bật lửa"), Map.entry("written_book", "sách"),
            Map.entry("cookie", "bánh quy"));

    public static String of(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        String vi = VI.get(path);
        if (vi != null) return vi;
        if (path.endsWith("_log")) return "gỗ";
        if (path.endsWith("_planks")) return "ván gỗ";
        if (path.endsWith("_sapling")) return "cây con";
        return new ItemStack(item).getHoverName().getString().toLowerCase();
    }
}
