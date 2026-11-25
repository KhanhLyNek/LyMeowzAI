package com.khanhly.lymeowz.house;

import com.khanhly.lymeowz.survival.LyInventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Giá của từng block trong bản vẽ tính ra nguyên liệu thô cô ấy phải tự đi kiếm:
 * gỗ (tính bằng ván; 1 khúc gỗ = 4 ván), đá cuội, kính, sắt, than, len, hoa, đất, da.
 * Cô ấy "chế" cầu thang, phiến, hàng rào, cửa, rương... từ nguyên liệu đó lúc xây (như người chơi ngồi
 * chế ở bàn rồi mang ra đặt). Gỗ loại nào cũng dùng được cho mọi đồ gỗ; hoa nào cũng được cho mọi chậu/luống hoa.
 */
public final class Materials {
    private Materials() {}

    public enum Mat {
        WOOD("gỗ"), STONE("đá"), GLASS("kính"), IRON("sắt"), COAL("than"), WOOL("len"), FLOWER("hoa"), DIRT("đất"), LEATHER("da");
        public final String vn;
        Mat(String vn) { this.vn = vn; }
    }

    public static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "pale_oak", "bamboo", "crimson", "warped"};

    /** Giá một block. Rỗng = đồ trang trí không tốn gì (lá, nến...). */
    public static Map<Mat, Float> cost(BlockState st) {
        Map<Mat, Float> m = new EnumMap<>(Mat.class);
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        float shape = id.endsWith("_stairs") ? 1.5f : id.endsWith("_slab") ? 0.5f : 1f;

        if (id.equals("grass_block") || id.endsWith("dirt") || id.equals("podzol") || id.equals("mycelium") || id.equals("dirt_path")) {
            m.put(Mat.DIRT, 1f);
        } else if (id.startsWith("potted_")) {
            m.put(Mat.STONE, 1f);
            m.put(Mat.FLOWER, 0.5f);
        } else if (st.is(net.minecraft.tags.BlockTags.LEAVES) || id.contains("candle") || id.equals("scaffolding") || id.contains("decorated_pot")) {
            // đồ trang trí lặt vặt
        } else if (id.endsWith("_bed")) {
            m.put(Mat.WOOL, 1.5f);
            m.put(Mat.WOOD, 1.5f);
        } else if (id.endsWith("_carpet")) {
            m.put(Mat.WOOL, 0.67f);
        } else if (id.endsWith("_wool")) {
            m.put(Mat.WOOL, 1f);
        } else if (id.contains("glass_pane")) {
            m.put(Mat.GLASS, 0.375f);
        } else if (id.contains("glass")) {
            m.put(Mat.GLASS, 1f);
        } else if (id.equals("lantern") || id.equals("soul_lantern")) {
            m.put(Mat.IRON, 1f);
            m.put(Mat.COAL, 0.25f);
        } else if (id.endsWith("torch")) {
            m.put(Mat.COAL, 0.25f);
            m.put(Mat.WOOD, 0.25f);
        } else if (id.equals("campfire")) {
            m.put(Mat.WOOD, 13.5f);
            m.put(Mat.COAL, 1f);
        } else if (id.equals("barrel")) {
            m.put(Mat.WOOD, 7f);
        } else if (id.equals("chest") || id.equals("trapped_chest")) {
            m.put(Mat.WOOD, 8f);
        } else if (id.equals("crafting_table")) {
            m.put(Mat.WOOD, 4f);
        } else if (id.equals("bookshelf") || id.equals("chiseled_bookshelf") || id.equals("lectern")) {
            m.put(Mat.WOOD, 6f);
            m.put(Mat.LEATHER, 1f);
        } else if (id.equals("smithing_table")) {
            m.put(Mat.WOOD, 4f);
            m.put(Mat.IRON, 2f);
        } else if (id.equals("stonecutter")) {
            m.put(Mat.STONE, 3f);
            m.put(Mat.IRON, 1f);
        } else if (id.equals("furnace") || id.equals("smoker") || id.equals("blast_furnace")) {
            m.put(Mat.STONE, 8f);
        } else if (id.equals("ladder")) {
            m.put(Mat.WOOD, 1.2f);
        } else if (isWood(id)) {
            float w;
            if (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae")) w = 4;
            else if (id.endsWith("_planks")) w = 1;
            else if (id.endsWith("_fence_gate")) w = 4;
            else if (id.endsWith("_fence")) w = 1.7f;
            else if (id.endsWith("_trapdoor")) w = 3;
            else if (id.endsWith("_door")) w = 1;
            else if (id.endsWith("_button")) w = 1;
            else if (id.endsWith("_pressure_plate")) w = 2;
            else if (id.contains("sign")) w = 2.2f;
            else w = shape;
            m.put(Mat.WOOD, w);
        } else if (st.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS) || st.is(net.minecraft.tags.BlockTags.FLOWERS)) {
            m.put(Mat.FLOWER, st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) ? 0.5f : 1f);
        } else if (id.contains("brick") && !id.contains("stone") || id.equals("bricks")) {
            m.put(Mat.STONE, shape);
            m.put(Mat.COAL, 0.25f * shape);
        } else if (id.startsWith("stone") || id.contains("stone_brick") || id.startsWith("smooth_stone")) {
            m.put(Mat.STONE, shape);
            m.put(Mat.COAL, 0.125f * shape);
        } else {
            // đá các loại (cuội, đá cuội rêu, andesite, deepslate...) và mọi block lạ khác: tính như đá
            m.put(Mat.STONE, shape);
        }
        return m;
    }

    private static boolean isWood(String id) {
        if (id.contains("leaves") || id.contains("sapling") || id.startsWith("potted")) return false;
        for (String w : WOODS) if (id.startsWith(w + "_") || id.startsWith("stripped_" + w + "_")) return true;
        return false;
    }

    /** Đồ trang trí (không bắt buộc): xây xong phần chính là ở được, mấy thứ này bổ sung dần. */
    public static boolean optional(BlockState st) {
        Map<Mat, Float> c = cost(st);
        if (c.isEmpty()) return true;
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        return c.containsKey(Mat.FLOWER) || c.containsKey(Mat.IRON) || id.endsWith("_carpet") || id.equals("campfire")
                || id.equals("bookshelf") || id.endsWith("_button") || id.endsWith("_bed");
    }

    // ------------------------------------------------------------------ đếm / trả bằng đồ trong túi

    public static Predicate<ItemStack> source(Mat m) {
        return switch (m) {
            case WOOD -> s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS);
            case STONE -> s -> s.is(ItemTags.STONE_TOOL_MATERIALS) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.STONE) || s.is(Items.STONE_BRICKS);
            case GLASS -> s -> s.is(Items.GLASS);
            case IRON -> s -> s.is(Items.IRON_INGOT);
            case COAL -> s -> s.is(ItemTags.COALS);
            case WOOL -> s -> s.is(ItemTags.WOOL);
            case FLOWER -> s -> s.is(BlockItemTags.SMALL_FLOWERS.item());
            case DIRT -> s -> s.is(Items.DIRT) || s.is(Items.COARSE_DIRT);
            case LEATHER -> s -> s.is(Items.LEATHER);
        };
    }

    /** Lượng nguyên liệu (tính theo đơn vị của {@link Mat}) trong một kho đồ. */
    public static float amount(Container inv, Mat m) {
        Predicate<ItemStack> src = source(m);
        float n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !src.test(s)) continue;
            n += m == Mat.WOOD && s.is(ItemTags.LOGS) ? s.getCount() * 4 : s.getCount();
        }
        return n;
    }

    public static Map<Mat, Float> amounts(Container inv) {
        Map<Mat, Float> out = new EnumMap<>(Mat.class);
        for (Mat m : Mat.values()) out.put(m, amount(inv, m));
        return out;
    }

    /** Phần lẻ đã trả trước (ví dụ chặt 1 khúc gỗ ra 4 ván mà mới dùng 1.5). */
    public static final class Wallet {
        private final Map<Mat, Float> credit = new EnumMap<>(Mat.class);

        public boolean canPay(LyInventory inv, Map<Mat, Float> cost) {
            for (var e : cost.entrySet()) {
                float have = amount(inv, e.getKey()) + credit.getOrDefault(e.getKey(), 0f);
                if (have + 1e-3 < e.getValue()) return false;
            }
            return true;
        }

        /**
         * Trừ nguyên liệu khỏi túi. Gỗ: dùng ván trước, hết ván thì "xẻ" khúc gỗ (1 khúc = 4 ván, phần thừa giữ lại
         * làm tiền lẻ) — không cần chỗ trống trong túi để chứa ván, nên túi đầy vẫn xây được.
         */
        public boolean pay(LyInventory inv, Map<Mat, Float> cost) {
            if (!canPay(inv, cost)) return false;
            for (var e : cost.entrySet()) {
                Mat m = e.getKey();
                float need = e.getValue() - credit.getOrDefault(m, 0f);
                Predicate<ItemStack> src = source(m);
                while (need > 1e-3f) {
                    if (m == Mat.WOOD) {
                        if (!inv.takeOne(s -> s.is(ItemTags.PLANKS)).isEmpty()) need -= 1;
                        else if (!inv.takeOne(s -> s.is(ItemTags.LOGS)).isEmpty()) need -= 4;
                        else return false;
                    } else {
                        if (inv.takeOne(src).isEmpty()) return false;
                        need -= 1;
                    }
                }
                credit.put(m, -need);
            }
            return true;
        }
    }

}
