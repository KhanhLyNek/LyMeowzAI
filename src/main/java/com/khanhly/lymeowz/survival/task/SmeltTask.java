package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.BlockBreaker;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Nung: đặt lò của mình xuống, đốt chất đốt, đứng canh đúng 10 giây mỗi món như lò thật,
 * nung xong thì đào lò lên mang theo.
 */
public class SmeltTask extends Task {
    public static final int TICKS_PER_ITEM = 200; // giống lò nung vanilla
    private static final int MAX_ITEMS = 8;

    private BlockPos furnace;
    private double fuelLeft;
    private int progress;
    private int done;
    private Item lastResult;
    private final java.util.Map<Item, Integer> results = new java.util.HashMap<>();
    private boolean cleaning;
    private final BlockBreaker breaker = new BlockBreaker();

    public SmeltTask(LyMeowzEntity ly) {
        super(ly);
        this.timeout = TICKS_PER_ITEM * MAX_ITEMS + 20 * 30;
    }

    @Override
    public boolean succeeded() {
        return done > 0;
    }

    @Override
    public String name() {
        return "nung";
    }

    @Override
    public void start(ServerLevel level) {
        ly.stowHand();
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (cleaning) return cleanUp(level);
        if (furnace == null) {
            if (ly.inv().takeOne(s -> s.is(Items.FURNACE)).isEmpty()) return false;
            Direction face = ly.getDirection().getOpposite();
            furnace = ly.placeNear(level, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, face));
            if (furnace == null) { ly.inv().put(new ItemStack(Items.FURNACE)); return false; }
            return true;
        }
        BlockState st = level.getBlockState(furnace);
        if (!st.is(Blocks.FURNACE)) return false;
        ly.getNavigation().stop();
        ly.getLookControl().setLookAt(furnace.getX() + 0.5, furnace.getY() + 0.5, furnace.getZ() + 0.5);

        ItemStack input = nextInput();
        if (input == null || done >= MAX_ITEMS) return finish(level, st);
        if (fuelLeft < 1.0) {
            // Ưu tiên than, rồi ván, rồi gỗ (gỗ để dành làm đồ).
            ItemStack fuel = ly.inv().takeOne(x -> x.is(ItemTags.COALS));
            if (fuel.isEmpty()) fuel = ly.inv().takeOne(x -> x.is(ItemTags.PLANKS));
            if (fuel.isEmpty() && ly.inv().count(ItemTags.LOGS) > 4) fuel = ly.inv().takeOne(x -> x.is(ItemTags.LOGS));
            if (fuel.isEmpty()) return finish(level, st);
            fuelLeft += Tools.fuelValue(fuel);
            if (!st.getValue(AbstractFurnaceBlock.LIT)) level.setBlock(furnace, st.setValue(AbstractFurnaceBlock.LIT, true), 3);
        }
        if (++progress >= TICKS_PER_ITEM) {
            progress = 0;
            Item result = Tools.smeltResult(input);
            if (result != null) results.merge(result, 1, Integer::sum);
            ItemStack raw = ly.inv().takeOne(s -> s.getItem() == input.getItem());
            if (raw.isEmpty() || result == null) return finish(level, st);
            fuelLeft -= 1.0;
            done++;
            ItemStack left = ly.inv().put(new ItemStack(result));
            if (!left.isEmpty()) ly.spawnAtLocation(level, left);
            ly.swingArm();
            level.playSound(null, furnace, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8f, 1.0f);
        }
        return true;
    }

    private ItemStack nextInput() {
        for (int i = 0; i < ly.inv().getContainerSize(); i++) {
            ItemStack s = ly.inv().getItem(i);
            if (!s.isEmpty() && Tools.smeltResult(s) != null) return s;
        }
        return null;
    }

    private boolean finish(ServerLevel level, BlockState st) {
        if (st.getValue(AbstractFurnaceBlock.LIT)) level.setBlock(furnace, st.setValue(AbstractFurnaceBlock.LIT, false), 3);
        results.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).ifPresent(e ->
                ly.speak(com.khanhly.lymeowz.speech.Speech.Act.DONE, com.khanhly.lymeowz.speech.Speech.Ctx.of().action("nung").item(e.getKey(), e.getValue())));
        cleaning = true;
        return true;
    }

    private boolean cleanUp(ServerLevel level) {
        if (!level.getBlockState(furnace).is(Blocks.FURNACE)) return false;
        if (breaker.target() == null) {
            ly.holdBestFor(level.getBlockState(furnace));
            breaker.start(furnace);
        }
        BlockBreaker.Result r = breaker.tick(ly, level);
        if (r == BlockBreaker.Result.OUT_OF_REACH && ly.getNavigation().isDone()) ly.walkTo(furnace, 1.0);
        return r == BlockBreaker.Result.WORKING || r == BlockBreaker.Result.OUT_OF_REACH;
    }

    @Override
    public void stop(ServerLevel level) {
        breaker.cancel(level, ly);
        if (furnace != null && level.getBlockState(furnace).is(Blocks.FURNACE)) {
            level.removeBlock(furnace, false);
            ly.inv().put(new ItemStack(Items.FURNACE));
        }
    }

    /** Có gì để nung và có chất đốt + lò không? */
    public static boolean canSmelt(LyMeowzEntity ly) {
        var inv = ly.inv();
        if (!inv.has(Items.FURNACE)) return false;
        if (inv.count(s -> Tools.smeltResult(s) != null) == 0) return false;
        return inv.count(ItemTags.COALS) > 0 || inv.count(ItemTags.LOGS) + inv.count(ItemTags.PLANKS) > 4;
    }
}
