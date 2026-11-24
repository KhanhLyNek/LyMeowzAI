package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Về nhà mở rương cất bớt đồ (đá thừa, quặng, đồ lặt vặt), giữ lại đồ nghề, đồ ăn, đuốc và vật liệu hay dùng;
 * hết đồ ăn thì lấy từ rương ra. Người chơi mở rương sẽ thấy cô ấy "giàu" hơn mình tưởng.
 */
public class StoreTask extends Task {
    private final BlockPos chest;
    private boolean opened;
    private int moved, taken;
    private int slot;
    private int wait;

    public StoreTask(LyMeowzEntity ly, BlockPos chest) {
        super(ly);
        this.chest = chest.immutable();
        this.timeout = 20 * 90;
    }

    @Override public String name() { return "cất đồ"; }

    @Override public boolean succeeded() { return moved > 0 || taken > 0; }

    /** Bao nhiêu món loại này cô ấy muốn mang theo người. */
    public static int keep(ItemStack s) {
        if (s.isDamageableItem() || s.get(DataComponents.EQUIPPABLE) != null) return 64; // đồ nghề, giáp, khiên
        if (Tools.isGoodFood(s)) return 16;
        if (s.is(Items.TORCH)) return 32;
        if (s.is(ItemTags.STONE_TOOL_MATERIALS) || s.is(Items.COBBLED_DEEPSLATE)) return 64;
        if (s.is(ItemTags.PLANKS)) return 32;
        if (s.is(ItemTags.LOGS)) return 16;
        if (s.is(ItemTags.COALS)) return 24;
        if (s.is(Items.STICK)) return 16;
        if (s.is(Items.IRON_INGOT) || s.is(Items.DIAMOND)) return 24;
        if (s.is(Items.WHEAT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO)) return 12;
        if (s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE) || s.is(Items.OAK_BOAT) || s.is(Items.BUCKET)
                || s.is(Items.WATER_BUCKET) || s.is(ItemTags.BEDS) || s.is(ItemTags.WOOL)) return 4;
        if (s.is(Items.OAK_FENCE) || s.is(Items.OAK_FENCE_GATE) || s.is(Items.OAK_DOOR)) return 8;
        if (s.is(Items.RAW_IRON)) return 16;
        return 0;
    }

    /** Có nên về cất đồ không: mang nhiều đồ thừa, hoặc túi gần đầy mà có đồ thừa để cất. */
    public static boolean wants(LyMeowzEntity ly) {
        LyInventory inv = ly.inv();
        int used = 0, excess = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty()) used++;
        for (var e : totals(inv).entrySet()) excess += Math.max(0, e.getValue() - keep(new ItemStack(e.getKey())));
        return excess >= 48 || (used >= 20 && excess > 0);
    }

    private static Map<net.minecraft.world.item.Item, Integer> totals(LyInventory inv) {
        Map<net.minecraft.world.item.Item, Integer> m = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return m;
    }

    private Container container(ServerLevel level) {
        BlockState st = level.getBlockState(chest);
        if (!(st.getBlock() instanceof ChestBlock cb)) return null;
        return ChestBlock.getContainer(cb, st, level, chest, true);
    }

    @Override
    protected boolean run(ServerLevel level) {
        Container c = container(level);
        if (c == null) {
            var d = ly.data();
            if (d != null) { d.structures.remove("chest"); com.khanhly.lymeowz.world.CompanionStore.markDirty(); }
            return false;
        }
        if (ly.getEyePosition().distanceTo(Vec3.atCenterOf(chest)) > 3.5) {
            if (ly.getNavigation().isDone() || age % 40 == 0) ly.walkTo(chest, 1.0);
            return true;
        }
        ly.getNavigation().stop();
        ly.getLookControl().setLookAt(chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
        if (!opened) {
            opened = true;
            lid(level, true);
            wait = 10;
            ly.stowHand();
            return true;
        }
        if (wait-- > 0) return true;
        LyInventory inv = ly.inv();
        Map<net.minecraft.world.item.Item, Integer> totals = totals(inv);
        // Cất đồ thừa, mỗi lần một chồng.
        while (slot < inv.getContainerSize()) {
            ItemStack s = inv.getItem(slot);
            int keep = s.isEmpty() ? 0 : keep(s);
            int have = s.isEmpty() ? 0 : totals.getOrDefault(s.getItem(), 0);
            if (!s.isEmpty() && have > keep) {
                int amount = Math.min(s.getCount(), have - keep);
                ItemStack out = s.split(amount);
                ItemStack left = insert(c, out);
                if (!left.isEmpty()) s.grow(left.getCount());
                inv.setChanged();
                if (left.getCount() < amount) { moved += amount - left.getCount(); wait = 4; slot++; ly.swingArm(); return true; }
            }
            slot++;
        }
        // Hết đồ ăn thì lấy ra.
        if (inv.count(Tools::isGoodFood) < 4) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!Tools.isGoodFood(s)) continue;
                ItemStack got = s.split(Math.min(s.getCount(), 8));
                c.setChanged();
                ItemStack left = inv.put(got);
                if (!left.isEmpty()) { s.grow(left.getCount()); break; }
                taken += got.getCount();
                break;
            }
        }
        return false;
    }

    private static ItemStack insert(Container c, ItemStack stack) {
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack t = c.getItem(i);
            if (!t.isEmpty() && ItemStack.isSameItemSameComponents(t, stack) && t.getCount() < t.getMaxStackSize()) {
                int n = Math.min(stack.getCount(), t.getMaxStackSize() - t.getCount());
                t.grow(n);
                stack.shrink(n);
            }
        }
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (c.getItem(i).isEmpty()) { c.setItem(i, stack.copy()); stack.setCount(0); }
        }
        c.setChanged();
        return stack;
    }

    private void lid(ServerLevel level, boolean open) {
        BlockState st = level.getBlockState(chest);
        level.blockEvent(chest, st.getBlock(), 1, open ? 1 : 0);
        level.playSound(null, chest, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1f);
        if (open) ly.swingArm();
    }

    @Override
    public void stop(ServerLevel level) {
        if (opened && level.getBlockState(chest).getBlock() instanceof ChestBlock) lid(level, false);
    }

    /** Số món đang cất trong rương. */
    public static int stored(ServerLevel level, BlockPos chest) {
        BlockState st = level.getBlockState(chest);
        if (!(st.getBlock() instanceof ChestBlock cb)) return 0;
        Container c = ChestBlock.getContainer(cb, st, level, chest, true);
        if (c == null) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
        return n;
    }
}
