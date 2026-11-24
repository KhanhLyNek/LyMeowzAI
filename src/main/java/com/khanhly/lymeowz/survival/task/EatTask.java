package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.Tools;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Lấy đồ ăn ra cầm trên tay, ăn trong 1,6 giây như người chơi. */
public class EatTask extends Task {
    private ItemStack food = ItemStack.EMPTY;
    private boolean eaten;

    public EatTask(LyMeowzEntity ly) {
        super(ly);
        this.timeout = 60;
    }

    @Override
    public boolean succeeded() {
        return eaten;
    }

    @Override
    public String name() {
        return "ăn";
    }

    @Override
    public void start(ServerLevel level) {
        boolean starving = ly.getHunger() <= 4;
        int slot = ly.inv().bestSlot(s -> (Tools.isGoodFood(s) || (starving && Tools.isFood(s))) ? Tools.nutrition(s) : 0);
        if (slot < 0) return;
        ly.stowHand();
        food = ly.inv().getItem(slot).split(1);
        ly.inv().setChanged();
        ly.setItemSlot(EquipmentSlot.MAINHAND, food);
    }

    @Override
    protected boolean run(ServerLevel level) {
        if (food.isEmpty()) return false;
        ly.getNavigation().stop();
        if (age % 4 == 0) level.playSound(null, ly.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.5f, 1.0f);
        if (age < 32) return true;
        ly.addHunger(Tools.nutrition(food));
        ly.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        level.playSound(null, ly.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.NEUTRAL, 0.5f, 1.0f);
        food = ItemStack.EMPTY;
        eaten = true;
        ly.mind().event(com.khanhly.lymeowz.mind.Mind.Event.ATE, null, null, null);
        return false;
    }

    @Override
    public void stop(ServerLevel level) {
        // Bị ngắt khi đang ăn dở: cất đồ ăn lại vào túi.
        if (!food.isEmpty() && ly.getMainHandItem() == food) {
            ly.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ly.inv().put(food);
        }
    }
}
