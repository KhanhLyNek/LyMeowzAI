package com.khanhly.lymeowz.survival;

import com.google.gson.JsonElement;
import com.khanhly.lymeowz.LyMeowzMod;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Túi đồ 27 ô của LyMeowz (giống hành trang người chơi, không tính thanh nóng). */
public class LyInventory extends SimpleContainer {
    public static final int SIZE = 27;
    private boolean dirty;

    public LyInventory() {
        super(SIZE);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        dirty = true;
    }

    public boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    // ------------------------------------------------------------------ đếm / tìm

    public int count(Item item) {
        return count(s -> s.is(item));
    }

    public int count(TagKey<Item> tag) {
        return count(s -> s.is(tag));
    }

    public int count(Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack s = getItem(i);
            if (!s.isEmpty() && p.test(s)) n += s.getCount();
        }
        return n;
    }

    public boolean has(Item item) {
        return count(item) > 0;
    }

    /** Ô chứa món tốt nhất theo điểm (điểm ≤ 0 = bỏ qua), hoặc -1. */
    public int bestSlot(java.util.function.ToDoubleFunction<ItemStack> score) {
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack s = getItem(i);
            if (s.isEmpty()) continue;
            double v = score.applyAsDouble(s);
            if (v > bestScore) { bestScore = v; best = i; }
        }
        return best;
    }

    // ------------------------------------------------------------------ lấy ra / bỏ vào

    /** Lấy ra đúng n món khớp điều kiện; trả về false (và không lấy gì) nếu không đủ. */
    public boolean consume(Predicate<ItemStack> p, int n) {
        if (count(p) < n) return false;
        for (int i = 0; i < getContainerSize() && n > 0; i++) {
            ItemStack s = getItem(i);
            if (s.isEmpty() || !p.test(s)) continue;
            int take = Math.min(n, s.getCount());
            s.shrink(take);
            n -= take;
            if (s.isEmpty()) setItem(i, ItemStack.EMPTY);
        }
        setChanged();
        return true;
    }

    public boolean consume(Item item, int n) {
        return consume(s -> s.is(item), n);
    }

    public boolean consume(TagKey<Item> tag, int n) {
        return consume(s -> s.is(tag), n);
    }

    /** Lấy một món đầu tiên khớp điều kiện ra khỏi túi (cả chồng: tách 1). */
    public ItemStack takeOne(Predicate<ItemStack> p) {
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack s = getItem(i);
            if (!s.isEmpty() && p.test(s)) {
                ItemStack one = s.split(1);
                if (s.isEmpty()) setItem(i, ItemStack.EMPTY);
                setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Lấy hẳn món ở một ô ra. */
    public ItemStack takeSlot(int slot) {
        ItemStack s = getItem(slot);
        setItem(slot, ItemStack.EMPTY);
        return s;
    }

    /** Bỏ vào túi; trả về phần không vừa. */
    public ItemStack put(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        return addItem(stack);
    }

    public boolean isFull() {
        for (int i = 0; i < getContainerSize(); i++) if (getItem(i).isEmpty()) return false;
        return true;
    }

    // ------------------------------------------------------------------ lưu / đọc (JSON qua codec của Minecraft)

    public JsonElement toJson(HolderLookup.Provider registries, ItemStack extra) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < getContainerSize(); i++) if (!getItem(i).isEmpty()) list.add(getItem(i));
        if (!extra.isEmpty()) list.add(extra);
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        return ItemStack.CODEC.listOf().encodeStart(ops, list).resultOrPartial(e -> LyMeowzMod.LOGGER.warn("Lưu túi đồ: {}", e))
                .orElse(null);
    }

    public void fromJson(HolderLookup.Provider registries, JsonElement json) {
        clearContent();
        if (json == null || json.isJsonNull()) return;
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        ItemStack.CODEC.listOf().parse(ops, json).resultOrPartial(e -> LyMeowzMod.LOGGER.warn("Đọc túi đồ: {}", e))
                .ifPresent(list -> list.forEach(this::put));
        dirty = false;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        dirty = true;
    }
}
