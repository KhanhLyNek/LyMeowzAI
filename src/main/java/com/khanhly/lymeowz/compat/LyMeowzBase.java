package com.khanhly.lymeowz.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

import java.util.function.Predicate;

/** Lớp nền của LyMeowz: nhận các hàm có chữ ký thay đổi theo phiên bản (1.21.2+). */
public abstract class LyMeowzBase extends PathfinderMob {
    protected LyMeowzBase(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    /** Mỗi tick phía server. */
    protected abstract void lyServerTick(ServerLevel level);

    /** Trả về false để chặn sát thương. */
    protected abstract boolean lyAllowHurt(ServerLevel level, DamageSource source, float amount);

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        lyServerTick(level);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (!lyAllowHurt(level, source, amount)) return false;
        return super.hurtServer(level, source, amount);
    }

    protected void addMonsterTargetGoal(int priority, Predicate<LivingEntity> filter) {
        this.targetSelector.addGoal(priority, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                (target, level) -> filter.test(target)));
    }
}
