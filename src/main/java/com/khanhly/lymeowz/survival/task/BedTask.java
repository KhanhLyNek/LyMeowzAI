package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.mind.Mind;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/** Mang giường về nhà và đặt vào góc đã chừa sẵn. */
public class BedTask extends Task {
    private final BlockPos house;
    private boolean placed;

    public BedTask(LyMeowzEntity ly, BlockPos house) {
        super(ly);
        this.house = house.immutable();
        this.timeout = 20 * 90;
    }

    @Override public String name() { return "đặt giường"; }

    @Override public boolean succeeded() { return placed; }

    @Override
    protected boolean run(ServerLevel level) {
        BlockPos foot = HouseTask.bedFoot(house), head = HouseTask.bedHead(house);
        if (ly.position().distanceTo(Vec3.atBottomCenterOf(HouseTask.inside(house))) > 2.5) {
            if (ly.getNavigation().isDone() || age % 40 == 0) ly.walkTo(HouseTask.inside(house), 1.0);
            return true;
        }
        if (!level.getBlockState(foot).canBeReplaced() || !level.getBlockState(head).canBeReplaced()) {
            // Chỗ đã có đồ: tìm chỗ khác trong nhà không được thì thôi.
            return false;
        }
        ItemStack bed = ly.inv().takeOne(s -> s.is(ItemTags.BEDS));
        if (!(bed.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof BedBlock)) {
            if (!bed.isEmpty()) ly.inv().put(bed);
            return false;
        }
        BlockState st = bi.getBlock().defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        ly.placeBlock(level, foot, st.setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlock(head, st.setValue(BedBlock.PART, BedPart.HEAD), 3);
        placed = true;
        CompanionStore.Data d = ly.data();
        if (d != null) {
            String dim = Compat.dimId(level.dimension());
            d.bed = new CompanionStore.Spot(dim, head.getX(), head.getY(), head.getZ());
            d.structures.put("bed", d.bed);
            CompanionStore.markDirty();
        }
        ly.mind().event(Mind.Event.BUILT, null, "giường", head);
        return false;
    }
}
