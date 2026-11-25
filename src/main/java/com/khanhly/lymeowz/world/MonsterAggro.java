package com.khanhly.lymeowz.world;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Quái vật săn cô ấy y như săn người chơi: con quái nào có mục tiêu "người chơi gần nhất" thì được thêm một mục tiêu
 * "LyMeowz gần nhất" cùng độ ưu tiên (nhện thì chỉ khi tối như với người chơi). Quái trung lập (người sói/Enderman,
 * piglin thây ma...) giữ nguyên tính cách: chỉ đánh khi bị chọc.
 */
public final class MonsterAggro {
    private MonsterAggro() {}

    private static final Set<Mob> DONE = Collections.newSetFromMap(new WeakHashMap<>());
    private static Field targetSelector, targetType;
    private static boolean broken;

    public static void hook(Entity e) {
        if (broken || !(e instanceof Mob mob) || !(e instanceof Enemy) || e instanceof NeutralMob || e instanceof LyMeowzEntity) return;
        if (!DONE.add(mob)) return;
        try {
            if (targetSelector == null) {
                targetSelector = Mob.class.getDeclaredField("targetSelector");
                targetSelector.setAccessible(true);
                targetType = NearestAttackableTargetGoal.class.getDeclaredField("targetType");
                targetType.setAccessible(true);
            }
            GoalSelector sel = (GoalSelector) targetSelector.get(mob);
            List<WrappedGoal> goals = new ArrayList<>(sel.getAvailableGoals());
            for (WrappedGoal w : goals) {
                if (!(w.getGoal() instanceof NearestAttackableTargetGoal<?> g)) continue;
                Class<?> type = (Class<?>) targetType.get(g);
                if (!Player.class.isAssignableFrom(type)) continue;
                boolean spider = mob instanceof Spider;
                sel.addGoal(w.getPriority(), new NearestAttackableTargetGoal<>(mob, LyMeowzEntity.class, 10, true, false,
                        (target, level) -> !spider || mob.getLightLevelDependentMagicValue() < 0.5f));
                break;
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            broken = true;
            LyMeowzMod.LOGGER.warn("[lymeowz] Không gắn được mục tiêu cho quái vật: {}", ex.toString());
        }
    }
}
