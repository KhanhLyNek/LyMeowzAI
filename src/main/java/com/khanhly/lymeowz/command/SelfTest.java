package com.khanhly.lymeowz.command;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.world.CompanionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * /lymeowz selftest – tự kiểm tra các phần phụ thuộc phiên bản Minecraft mà không cần người chơi:
 * tạo entity, dịch chuyển, giữ chunk, đổi chiều không gian, tự về nhà khi không có ai, đặt tên, ngất, lưu dữ liệu.
 */
public final class SelfTest {
    private SelfTest() {}

    private record Step(int afterTicks, String name, Consumer<Ctx> run) {}

    private static final class Ctx {
        MinecraftServer server;
        Consumer<String> out;
        UUID lyId;
        BlockPos home;
        double distBefore;
        int passed, failed;
        boolean seenInNether;
        int waited;
        net.minecraft.world.entity.Mob zombie;

        LyMeowzEntity ly() {
            for (ServerLevel l : server.getAllLevels()) {
                if (l.getEntity(lyId) instanceof LyMeowzEntity e && e.isAlive()) return e;
            }
            return null;
        }

        void check(String name, boolean ok, String info) {
            if (ok) passed++; else failed++;
            String line = (ok ? "PASS " : "FAIL ") + name + (info.isEmpty() ? "" : "  — " + info);
            out.accept(line);
            LyMeowzMod.LOGGER.info("[selftest] {}", line);
        }
    }

    private static List<Step> steps;
    private static Ctx ctx;
    private static int index, wait;

    /** Gọi từ sự kiện ENTITY_LOAD. */
    public static void onEntityLoad(net.minecraft.world.entity.Entity e, ServerLevel level) {
        if (ctx != null && e.getUUID().equals(ctx.lyId) && level.dimension().equals(Level.NETHER)) ctx.seenInNether = true;
    }

    public static boolean running() {
        return ctx != null;
    }

    public static void start(MinecraftServer server, Consumer<String> out) {
        ctx = new Ctx();
        ctx.server = server;
        ctx.out = out;
        index = 0;
        wait = 0;
        steps = new ArrayList<>();
        ServerLevel ow = server.overworld();
        steps.add(new Step(0, "compat cơ bản", c -> {
            c.check("dim id ↔ key", Compat.dimId(Level.OVERWORLD).equals("minecraft:overworld")
                    && Compat.dim("minecraft:the_nether") != null && Compat.dim("minecraft:the_nether").equals(Level.NETHER), "");
            c.check("nhận ra hoa", Compat.isFlower(new ItemStack(Items.POPPY)) && Compat.isFlower(new ItemStack(Items.SUNFLOWER))
                    && !Compat.isFlower(new ItemStack(Items.STONE)), "");
            Compat.isNight(ow);
            c.check("điểm hồi sinh thế giới", Compat.worldSpawn(ow) != null, String.valueOf(Compat.worldSpawn(ow)));
        }));
        steps.add(new Step(0, "bản vẽ nhà", c -> {
            var all = com.khanhly.lymeowz.house.Blueprint.all();
            c.check("đọc được bản vẽ .schem", all.size() >= 2, all.stream().map(b -> b.name + " " + b.width + "x" + b.height + "x" + b.length).toList().toString());
            for (var bp : all) {
                var need = com.khanhly.lymeowz.survival.task.BlueprintTask.need(ow, bp, new BlockPos(0, ow.getMaxY() - bp.height - 2, 0), true);
                c.check("giá nhà " + bp.name, !need.isEmpty() && need.getOrDefault(com.khanhly.lymeowz.house.Materials.Mat.WOOD, 0f) > 0,
                        need.toString() + " | giường " + bp.bedHead + " rương " + bp.chest + " trong nhà " + bp.inside);
            }
        }));
        steps.add(new Step(0, "tạo LyMeowz", c -> {
            BlockPos spawn = Compat.worldSpawn(ow);
            c.home = new BlockPos(spawn.getX(), ow.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()), spawn.getZ());
            // Từ 1.21.9 không còn "spawn chunks": khi không có người chơi phải tự giữ chunk.
            Compat.keepChunk(ow, c.home, 3);
            ow.getChunk(c.home);
            LyMeowzEntity e = Compat.create(LyMeowzMod.LYMEOWZ, ow);
            c.check("tạo entity", e != null, "");
            if (e == null) return;
            Compat.placeAt(e, c.home.getX() + 0.5, c.home.getY(), c.home.getZ() + 0.5, 0f, 0f);
            c.lyId = e.getUUID();
            CompanionStore.create(c.lyId, new CompanionStore.Spot("minecraft:overworld", c.home.getX(), c.home.getY(), c.home.getZ()));
            ow.addFreshEntity(e);
            e.setHome(ow, c.home);
            c.check("có dữ liệu riêng, chưa có tên", e.data() != null && e.displayName() == null && e.getCustomName() == null, "");
            c.check("hiểu câu hỏi toạ độ", com.khanhly.lymeowz.chat.Brain.parse("cậu đang ở đâu vậy?") == com.khanhly.lymeowz.chat.Brain.Intent.WHERE
                    && com.khanhly.lymeowz.chat.Brain.parse("tọa độ nào vậy") == com.khanhly.lymeowz.chat.Brain.Intent.WHERE
                    && com.khanhly.lymeowz.chat.Brain.parse("nhà cậu ở đâu?") != com.khanhly.lymeowz.chat.Brain.Intent.WHERE
                    && com.khanhly.lymeowz.chat.Brain.parse("đi theo mình") == com.khanhly.lymeowz.chat.Brain.Intent.FOLLOW, "");
        }));
        steps.add(new Step(20, "đợi cô ấy xuất hiện", c -> {
            // Chunk mới load cần vài tick mới cho truy cập entity (26.x không có spawn chunks): đợi tối đa 10 giây.
            if (c.ly() == null && c.waited++ < 20) { index--; wait = 10; }
        }));
        steps.add(new Step(0, "dịch chuyển xa + giữ chunk", c -> {
            LyMeowzEntity e = c.ly();
            BlockPos far = c.home.offset(300, 0, 0);
            Compat.keepChunk(ow, far, 3);
            ow.getChunk(far);
            int y = ow.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, far.getX(), far.getZ());
            boolean ok = e != null && e.teleportNear(ow, new BlockPos(far.getX(), y, far.getZ()), 0, 2);
            c.check("teleportNear cùng chiều", ok && e.position().distanceTo(Vec3.atCenterOf(far)) < 10, e == null ? "null" : e.blockPosition().toShortString());
        }));
        steps.add(new Step(60, "không có ai → tự đi về nhà", c -> {
            LyMeowzEntity e = c.ly();
            c.check("vẫn được tick ở xa (chunk ticket)", e != null && e.tickCount > 40, e == null ? "null" : "tick " + e.tickCount);
            if (e != null) c.distBefore = e.position().distanceTo(Vec3.atBottomCenterOf(c.home));
        }));
        steps.add(new Step(200, "đã đi về phía nhà", c -> {
            LyMeowzEntity e = c.ly();
            double now = e == null ? 1e9 : e.position().distanceTo(Vec3.atBottomCenterOf(c.home));
            c.check("đang đi về nhà", now < c.distBefore - 10, String.format("%.0f → %.0f block", c.distBefore, now));
        }));
        steps.add(new Step(0, "sang Nether", c -> {
            ServerLevel nether = c.server.getLevel(Level.NETHER);
            LyMeowzEntity e = c.ly();
            if (nether == null || e == null) { c.check("sang Nether", false, "nether/entity null"); return; }
            BlockPos base = new BlockPos(0, 100, 0);
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                nether.setBlockAndUpdate(base.offset(x, -1, z), Blocks.OBSIDIAN.defaultBlockState());
                for (int y = 0; y < 4; y++) nether.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            Compat.keepChunk(nether, base, 2);
            c.seenInNether = false;
            e.teleportNear(nether, base, 0, 2);
        }));
        steps.add(new Step(60, "kiểm tra Nether", c -> {
            // Kiểm tra bằng sự kiện "được load ở Nether".
            LyMeowzEntity e = c.ly();
            c.check("đổi chiều không gian (giữ UUID + dữ liệu)", c.seenInNether && e != null && e.data() != null,
                    "đã thấy ở Nether: " + c.seenInNether);
        }));
        steps.add(new Step(0, "về lại Overworld + đặt tên", c -> {
            LyMeowzEntity e = c.ly();
            if (e == null) return;
            e.teleportNear(ow, c.home, 0, 2);
        }));
        steps.add(new Step(20, "đặt tên bằng bảng tên", c -> {
            LyMeowzEntity e = c.ly();
            if (e != null) e.setCustomName(Component.literal("Mimi"));
        }));
        steps.add(new Step(30, "kiểm tra tên", c -> {
            LyMeowzEntity e = c.ly();
            c.check("nhớ tên được đặt", e != null && "Mimi".equals(e.displayName()) && e.data() != null && "Mimi".equals(e.data().name),
                    e == null ? "null" : String.valueOf(e.displayName()));
        }));
        steps.add(new Step(0, "nhật ký + biểu cảm", c -> {
            LyMeowzEntity e = c.ly();
            if (e == null) return;
            String page = com.khanhly.lymeowz.mind.Diary.compose(e, ow, e.mind().data(), 0);
            var book = com.khanhly.lymeowz.mind.Diary.book(e, e.mind().data());
            c.check("viết nhật ký", page.startsWith("Ngày 1") && page.length() > 20 && book.is(Items.WRITTEN_BOOK), page.replace("\n", " "));
            for (LyMeowzEntity.Expr x : LyMeowzEntity.Expr.values()) e.express(x);
            c.check("biểu cảm", true, "");
        }));
        steps.add(new Step(0, "dò khối nhanh", c -> {
            LyMeowzEntity e = c.ly();
            if (e == null) return;
            BlockPos gold = e.blockPosition().offset(9, 1, -7);
            ow.setBlockAndUpdate(gold, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
            BlockPos found = e.findBlockNear(ow, net.minecraft.world.level.block.Blocks.GOLD_BLOCK, 12);
            long t0 = System.nanoTime();
            BlockPos far = com.khanhly.lymeowz.util.BlockScan.nearest(ow, e.blockPosition(), e.blockPosition().offset(-48, -3, -48),
                    e.blockPosition().offset(48, 3, 48), st -> st.is(net.minecraft.tags.BlockTags.LOGS), p -> true);
            long t1 = System.nanoTime();
            int brute = 0;
            for (BlockPos p : BlockPos.betweenClosed(e.blockPosition().offset(-48, -3, -48), e.blockPosition().offset(48, 3, 48)))
                if (ow.isLoaded(p) && ow.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS)) brute++;
            long t2 = System.nanoTime();
            ow.setBlockAndUpdate(gold, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            c.check("dò khối theo khúc chunk", gold.equals(found),
                    String.format("tìm gỗ bán kính 48: %.2f ms (dò từng ô kiểu cũ: %.2f ms, %d ô gỗ)", (t1 - t0) / 1e6, (t2 - t1) / 1e6, brute));
        }));
        steps.add(new Step(0, "tốc độ như người chơi + bảng Tab + quái", c -> {
            LyMeowzEntity e = c.ly();
            if (e == null) return;
            e.setSprinting(false);
            double attr = e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            e.setSpeed((float) (1.4 * attr));
            float walk = e.getSpeed();
            e.setSprinting(true);
            attr = e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            e.setSpeed((float) (1.4 * attr));
            float sprint = e.getSpeed();
            e.setSprinting(false);
            c.check("tốc độ như người chơi (đi ≈ 4.3 m/s, chạy ≈ 5.6 m/s)", Math.abs(walk - 0.313f) < 0.01f && Math.abs(sprint - 0.357f) < 0.01f,
                    String.format("đi %.3f (vận tốc %.3f), chạy %.3f (vận tốc %.3f)", walk, walk * walk, sprint, sprint * sprint));
            String id = e.getUUID().toString();
            c.check("dòng bảng Tab", com.khanhly.lymeowz.world.TabPresence.isOurs(com.khanhly.lymeowz.world.TabPresence.tabId(id))
                    && !com.khanhly.lymeowz.world.TabPresence.isOurs(e.getUUID()), com.khanhly.lymeowz.world.TabPresence.tabId(id).toString());
            var z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(ow, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (z != null) {
                Compat.placeAt(z, e.getX() + 6, e.getY(), e.getZ(), 0f, 0f);
                z.setPersistenceRequired();
                ow.addFreshEntity(z);
                c.zombie = z;
            }
        }));
        steps.add(new Step(80, "quái vật săn cô ấy", c -> {
            LyMeowzEntity e = c.ly();
            var z = c.zombie;
            c.check("thây ma nhắm vào cô ấy như nhắm người chơi", z != null && e != null && z.getTarget() == e,
                    z == null ? "không tạo được thây ma" : "mục tiêu: " + (z.getTarget() == null ? "không" : z.getTarget().getName().getString()));
            if (z != null) z.discard();
            if (e != null) { e.setTarget(null); e.heal(20); }
        }));
        steps.add(new Step(5, "ngất khi kiệt sức", c -> {
            LyMeowzEntity e = c.ly();
            if (e == null) { c.check("ngất", false, "null"); return; }
            Compat.damage(e, (ServerLevel) e.level(), e.damageSources().generic(), 1000f);
        }));
        steps.add(new Step(5, "kiểm tra ngất", c -> {
            LyMeowzEntity e = c.ly();
            c.check("không chết, về nhà nghỉ", e != null && e.isAlive() && e.getHealth() >= 9
                    && e.level().dimension().equals(Level.OVERWORLD) && e.position().distanceTo(Vec3.atBottomCenterOf(c.home)) < 6,
                    e == null ? "null" : Compat.dimId(e.level().dimension()) + " " + e.blockPosition().toShortString());
        }));
        steps.add(new Step(0, "lưu + xóa", c -> {
            CompanionStore.markDirty();
            CompanionStore.save();
            c.check("lưu lymeowz.json", CompanionStore.get(c.lyId) != null, "");
            LyMeowzEntity e = c.ly();
            if (e != null) e.discard();
            c.check("xóa cô ấy → xóa dữ liệu", CompanionStore.get(c.lyId) == null, "");
        }));
    }

    public static void tick(MinecraftServer server) {
        if (ctx == null) return;
        if (wait > 0) { wait--; return; }
        if (index >= steps.size()) {
            String sum = "== selftest: " + ctx.passed + "/" + (ctx.passed + ctx.failed) + " PASS ==";
            ctx.out.accept(sum);
            LyMeowzMod.LOGGER.info("[selftest] {}", sum);
            ctx = null;
            return;
        }
        Step s = steps.get(index);
        if (s.afterTicks > 0 && wait == 0 && !s.name.equals("_waited")) {
            steps.set(index, new Step(0, s.name, s.run));
            wait = s.afterTicks;
            return;
        }
        index++;
        try {
            s.run.accept(ctx);
        } catch (Throwable t) {
            ctx.check(s.name, false, t.toString());
            LyMeowzMod.LOGGER.error("[selftest] {}", s.name, t);
        }
    }

    public static Component text(String s) {
        return Component.literal(s);
    }
}
