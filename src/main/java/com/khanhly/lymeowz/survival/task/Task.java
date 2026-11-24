package com.khanhly.lymeowz.survival.task;

import com.khanhly.lymeowz.entity.LyMeowzEntity;
import net.minecraft.server.level.ServerLevel;

/** Một việc LyMeowz đang làm. */
public abstract class Task {
    protected final LyMeowzEntity ly;
    protected int age;
    /** Quá thời gian này (tick) thì bỏ cuộc. */
    protected int timeout = 20 * 60;

    protected Task(LyMeowzEntity ly) {
        this.ly = ly;
    }

    public abstract String name();

    public void start(ServerLevel level) {}

    /** @return true nếu vẫn đang làm. */
    public final boolean tick(ServerLevel level) {
        if (++age > timeout) return false;
        return run(level);
    }

    protected abstract boolean run(ServerLevel level);

    /** Dừng (xong việc hoặc bị ngắt): dọn dẹp. */
    public void stop(ServerLevel level) {}
}
