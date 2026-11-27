package com.khanhly.lymeowz.command;

import com.khanhly.lymeowz.LyMeowzMod;
import com.khanhly.lymeowz.compat.Compat;
import com.khanhly.lymeowz.entity.LyMeowzEntity;
import com.khanhly.lymeowz.survival.LyInventory;
import com.khanhly.lymeowz.survival.Survival;
import com.khanhly.lymeowz.survival.Tools;
import com.khanhly.lymeowz.world.CompanionStore;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * /lymeowz selftest survival – dựng một khu rừng nhỏ + đồi đá có quặng + đàn bò, đặt một "người chơi giả" đứng yên làm chủ,
 * rồi tua nhanh thời gian và theo dõi LyMeowz tự sinh tồn từ hai bàn tay trắng.
 */
public final class SurvivalTest {
    private SurvivalTest() {}

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000051ee");
    private static final int MAX_TICKS = 20 * 60 * 60; // 60 phút trong game (3 ngày đêm)

    private static MinecraftServer server;
    private static Consumer<String> out;
    private static FakePlayer owner;
    private static UUID lyId;
    private static BlockPos center;
    private static List<BlockPos> pillar;
    private static int ticks;
    private static int startHunger;
    private static boolean ate;
    private static int lastHunger;
    private static final Map<String, Integer> milestones = new LinkedHashMap<>();

    public static boolean running() {
        return server != null;
    }

    private static boolean slept, fished, berries;

    /** Cho LyMeowzEntity tìm thấy người chơi giả theo UUID. */
    private static boolean freeOk, comeOk;

    public static ServerPlayer fakePlayer(UUID id) {
        return owner != null && OWNER.equals(id) ? owner : null;
    }

    /** Người chơi giả (nếu đang chạy thử và ở gần). */
    public static ServerPlayer fakeNear(net.minecraft.world.entity.Entity e, double range) {
        return owner != null && owner.level() == e.level() && owner.distanceToSqr(e) < range * range ? owner : null;
    }

    public static void start(MinecraftServer srv, Consumer<String> output) {
        freeOk = false; comeOk = false;
        com.khanhly.lymeowz.util.Perf.start(srv.getTickCount());
        server = srv;
        out = output;
        ticks = 0;
        ate = false;
        slept = false;
        fished = false;
        berries = false;
        milestones.clear();
        ServerLevel ow = srv.overworld();
        srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), "time set day");

        BlockPos c0 = new BlockPos(200, 0, 200);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            Compat.keepChunk(ow, c0.offset(dx * 16, 0, dz * 16), 2);
            ow.getChunk(c0.offset(dx * 16, 0, dz * 16)); // load trước rồi mới đo độ cao mặt đất
        }
        int y = ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 200, 200);
        center = new BlockPos(200, y, 200);

        // Cây tự nhiên.
        int[][] trees = {{8, 3}, {-7, 6}, {5, -9}, {-9, -5}, {9, 9}, {-4, -10}, {14, -6}, {-14, 10}, {12, 14}, {-2, 16}, {16, 2}, {-16, -12},
                {22, -4}, {-21, 3}, {4, 23}, {-6, -22}, {19, 17}, {-19, -18}, {24, 10}, {-24, 14}, {10, -24}, {-12, 24}};
        for (int[] t : trees) tree(ow, center.offset(t[0], 0, t[1]), 5, false);
        // "Nhà" của chủ: cột gỗ với lá người chơi đặt → không được chặt.
        pillar = tree(ow, center.offset(3, 0, -3), 4, true);
        // Đồi đá có quặng than và sắt lộ ra ngoài.
        BlockPos hill = center.offset(-12, 0, 0);
        for (int dx = -4; dx < 5; dx++) for (int dz = -5; dz <= 5; dz++) for (int dy = 0; dy < 3; dy++)
            ow.setBlockAndUpdate(hill.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
        // Bãi cát nhỏ và mấy bông hoa (để làm cửa sổ kính, vườn hoa).
        for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) {
            BlockPos s = center.offset(10 + dx, -1, 14 + dz);
            ow.setBlockAndUpdate(s, Blocks.SAND.defaultBlockState());
        }
        for (int i = 0; i < 14; i++) {
            BlockPos f = center.offset(-14 + (i % 7) * 2, 0, -14 - (i / 7) * 2);
            ow.setBlockAndUpdate(f.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            ow.setBlockAndUpdate(f, (i % 2 == 0 ? Blocks.POPPY : Blocks.DANDELION).defaultBlockState());
        }
        // Ao nhỏ (câu cá), bụi quả mọng, mạng nhện (tơ làm cần câu).
        for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++)
            ow.setBlockAndUpdate(center.offset(-12 + dx, -1, 12 + dz), Blocks.WATER.defaultBlockState());
        for (int i = 0; i < 4; i++)
            ow.setBlockAndUpdate(center.offset(15, 0, -12 + i * 2), Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3));
        ow.setBlockAndUpdate(center.offset(17, 0, 6), Blocks.COBWEB.defaultBlockState());
        ow.setBlockAndUpdate(center.offset(17, 0, 8), Blocks.COBWEB.defaultBlockState());
        // Cỏ để nhặt hạt giống.
        for (int i = 0; i < 80; i++) {
            BlockPos g = center.offset(ow.getRandom().nextIntBetweenInclusive(-18, 18), 0, ow.getRandom().nextIntBetweenInclusive(-18, 18));
            int gy = ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g.getX(), g.getZ());
            BlockPos gp = new BlockPos(g.getX(), gy, g.getZ());
            if (ow.getBlockState(gp.below()).is(Blocks.GRASS_BLOCK) && ow.getBlockState(gp).isAir())
                ow.setBlockAndUpdate(gp, Blocks.SHORT_GRASS.defaultBlockState());
        }
        int[][] ores = {{4, 0, -2, 0}, {4, 1, -1, 0}, {4, 0, 1, 0}, {4, 2, 0, 0}, {4, 1, 2, 1}, {4, 0, 3, 1}, {4, 1, -3, 1}, {4, 0, 0, 1}};
        for (int[] o : ores)
            ow.setBlockAndUpdate(hill.offset(o[0], o[1], o[2]), (o[3] == 0 ? Blocks.COAL_ORE : Blocks.IRON_ORE).defaultBlockState());
        // Đàn cừu (len làm giường).
        for (int i = 0; i < 5; i++) {
            var sheep = EntityTypes.SHEEP.create(ow, EntitySpawnReason.COMMAND);
            if (sheep == null) continue;
            Compat.placeAt(sheep, center.getX() - 6 - i % 3, y, center.getZ() + 8 + i / 3, 0, 0);
            ow.addFreshEntity(sheep);
        }
        // Đàn bò.
        for (int i = 0; i < 6; i++) {
            Cow cow = EntityTypes.COW.create(ow, EntitySpawnReason.COMMAND);
            if (cow == null) continue;
            Compat.placeAt(cow, center.getX() + 6 + i % 3, y, center.getZ() - 4 - i / 3, 0, 0);
            ow.addFreshEntity(cow);
        }

        // Chủ giả đứng yên ở giữa.
        owner = FakePlayer.get(ow, new GameProfile(OWNER, "LyTester"));
        owner.snapTo(center.getX() + 0.5, y, center.getZ() + 0.5);

        // LyMeowz tay trắng, hơi đói.
        LyMeowzEntity ly = Compat.create(LyMeowzMod.LYMEOWZ, ow);
        Compat.placeAt(ly, center.getX() + 2.5, y, center.getZ() + 0.5, 0, 0);
        CompanionStore.create(ly.getUUID(), new CompanionStore.Spot("minecraft:overworld", center.getX(), y, center.getZ()));
        ow.addFreshEntity(ly);
        lyId = ly.getUUID();
        ly.addHunger(-8);
        startHunger = lastHunger = 12;

        var pers = ly.mind().p();
        log(String.format("Tính cách: dũng cảm %.2f, chăm %.2f, tò mò %.2f, thích xây %.2f, thích đánh %.2f, nói nhiều %.2f, vui vẻ %.2f → \"%s\", lý tưởng: %s",
                pers.brave, pers.diligent, pers.curious, pers.builder, pers.fighter, pers.talkative, pers.cheerful, pers.describe(), pers.idealText()));
        srv.tickRateManager().requestGameToSprint(MAX_TICKS / 20);
        log("Khu thử: " + trees.length + " cây, đồi đá có 4 quặng than + 4 quặng sắt, 6 con bò. Tua nhanh " + MAX_TICKS / 1200 + " phút...");
    }

    /** Dựng cây: thân + lá. playerMade = lá người chơi đặt (PERSISTENT). */
    private static List<BlockPos> tree(ServerLevel l, BlockPos base, int height, boolean playerMade) {
        List<BlockPos> logs = new ArrayList<>();
        l.setBlockAndUpdate(base.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        for (int i = 0; i < height; i++) {
            l.setBlockAndUpdate(base.above(i), Blocks.OAK_LOG.defaultBlockState());
            logs.add(base.above(i));
        }
        BlockPos top = base.above(height - 1);
        for (int dy = -1; dy <= 2; dy++) {
            int r = dy >= 1 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r && Math.abs(dz) == r && r == 2) continue;
                BlockPos p = top.offset(dx, dy, dz);
                if (!l.getBlockState(p).isAir()) continue;
                int dist = Math.max(1, Math.abs(dx) + Math.abs(dz) + Math.max(0, dy));
                BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(LeavesBlock.PERSISTENT, playerMade).setValue(LeavesBlock.DISTANCE, Math.min(6, dist));
                l.setBlock(p, leaf, 2);
            }
        }
        return logs;
    }

    public static void tick(MinecraftServer srv) {
        if (server == null) return;
        ticks++;
        LyMeowzEntity ly = srv.overworld().getEntity(lyId) instanceof LyMeowzEntity e ? e : null;
        if (ly == null) {
            if (ticks > 100) finish("không tìm thấy LyMeowz");
            return;
        }
        LyInventory all = ly.inventoryWithHand();
        mark("có gỗ", all.count(ItemTags.LOGS) > 0);
        mark("cuốc gỗ", Survival.bestTier(all, Tools.Kind.PICKAXE) >= 1);
        mark("rìu gỗ", Survival.bestTier(all, Tools.Kind.AXE) >= 1);
        mark("có đá cuội", all.count(ItemTags.STONE_TOOL_MATERIALS) > 0);
        mark("cuốc đá", Survival.bestTier(all, Tools.Kind.PICKAXE) >= 2);
        mark("rìu đá", Survival.bestTier(all, Tools.Kind.AXE) >= 2);
        mark("kiếm đá", Survival.bestTier(all, Tools.Kind.SWORD) >= 2);
        mark("lò nung", all.has(Items.FURNACE));
        mark("than", all.count(ItemTags.COALS) > 0);
        mark("săn được thịt", all.count(Tools::isRawMeat) > 0 || all.has(Items.COOKED_BEEF));
        mark("nướng chín thịt", all.has(Items.COOKED_BEEF));
        mark("sắt thỏi", all.has(Items.IRON_INGOT) || Survival.bestTier(all, Tools.Kind.PICKAXE) >= 3);
        mark("cuốc sắt", Survival.bestTier(all, Tools.Kind.PICKAXE) >= 3);
        mark("tìm đất làm nhà", ly.data() != null && (ly.data().blueprintAt != null || ly.data().structures.containsKey("home")));
        mark("xây nhà", com.khanhly.lymeowz.survival.Roadmap.has(ly, "home") || com.khanhly.lymeowz.survival.Roadmap.has(ly, "house"));
        mark("làm ruộng", com.khanhly.lymeowz.survival.Roadmap.has(ly, "farm"));
        mark("làm chuồng", com.khanhly.lymeowz.survival.Roadmap.has(ly, "pen"));
        mark("đặt giường", com.khanhly.lymeowz.survival.Roadmap.has(ly, "bed"));
        if (ly.isSleeping()) slept = true;
        mark("ngủ trên giường", slept);
        mark("viết nhật ký", !ly.mind().data().diary.isEmpty());
        mark("tự lên kế hoạch", ly.thinker.plan() != null);
        if (all.count(Items.COD) + all.count(Items.SALMON) + all.count(Items.COOKED_COD) + all.count(Items.COOKED_SALMON) > 0) fished = true;
        mark("câu được cá", fished);
        if (all.count(Items.SWEET_BERRIES) > 0) berries = true;
        mark("hái quả mọng", berries);
        mark("sửa nhà", ly.data() != null && ly.data().structures.keySet().stream().anyMatch(k -> k.startsWith("deco:")));
        if (ly.getHunger() > lastHunger) ate = true;
        lastHunger = ly.getHunger();
        mark("tự ăn khi đói", ate);

        if (ticks % 200 == 0 && (ticks <= 2400 || (ticks % 400 == 0 && ly.survival.currentName().equals("xây nhà")))) {
            var t = ly.getTarget();
            log(String.format("  · t=%d %s task=%s target=%s nav=%s distOwner=%.1f pos=%s hp=%.1f", ticks, ly.request() == null ? "tự do" : ly.request().type, ly.survival.currentName(),
                    t == null ? "-" : t.getType().toShortString() + (t.isAlive() ? "" : "(chết)"), ly.getNavigation().isDone() ? "done" : "moving",
                    ly.distanceTo(owner), ly.blockPosition().toShortString(), ly.getHealth()) + " " + ly.survival.currentDebug());
        }
        if (ticks % 12000 == 0) {
            for (String l : com.khanhly.lymeowz.util.Perf.report(server.getTickCount())) log("  ⏱ " + l);
            Runtime rt = Runtime.getRuntime();
            log(String.format("  ⏱ RAM server đang dùng: %d MB / %d MB", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20));
            com.khanhly.lymeowz.util.Perf.start(server.getTickCount());
        }
        if (ticks % 1200 == 0) {
            var m = ly.mind().data();
            log(String.format("[%2d phút] %s, đang %s (%s), đói %d, máu %.0f | sợ %.0f vui %.0f chán %.0f tò mò %.0f năng lượng %.0f giao tiếp %.0f → %s | nhớ %d nơi | %s",
                    ticks / 1200, com.khanhly.lymeowz.survival.Roadmap.current(ly).title, ly.isSleeping() ? "ngủ" : ly.survival.currentName(), ly.survival.currentReason(), ly.getHunger(), ly.getHealth(),
                    m.fear, m.joy, m.boredom, m.curiosity, m.energy, m.social, ly.mind().dominantFeeling(), m.places.size(),
                    Survival.summary(ly.inventoryWithHand())));
            String dbg = ly.survival.currentDebug();
            if (!dbg.isEmpty()) log("      ⚙ " + dbg);
            String hp = com.khanhly.lymeowz.survival.Roadmap.houseProgress(ly);
            if (hp != null) log("      🏠 " + hp);
        }
        // Trò chuyện thử với chủ giả ở vài thời điểm.
        if (ticks == 6000) {
            // Giả lập đã làm thân được một thời gian.
            var r = ly.mind().data().relations.computeIfAbsent(OWNER.toString(), k -> new com.khanhly.lymeowz.mind.MindData.Relation());
            r.name = "LyTester";
            r.affinity = 45;
            log("  (độ thân với LyTester → 45: bạn bè)");
        }
        if (ticks == 12390 || ticks == 9590) owner.snapTo(ly.getX() + 3, ly.getY(), ly.getZ()); // tới cạnh cô ấy rồi hỏi
        String[] chats = switch (ticks) {
            case 600 -> new String[]{"chào", "cậu tên gì?", "đi theo mình đi", "cậu đang ở đâu vậy?"};
            case 3600 -> new String[]{"cậu đói không?", "cậu đang làm gì vậy?", "cậu dễ thương quá"};
            case 6020 -> new String[]{"theo mình nào", "tự do đi", "tọa độ của cậu là gì?", "kế hoạch của cậu là gì?"};
            case 9600 -> new String[]{"cậu thấy sao?", "mỏ đá ở đâu?", "trong túi có gì?", "nhà cậu ở đâu?"};
            case 12400 -> new String[]{"cậu đang nghĩ gì vậy?", "hôm nay cậu định làm gì?", "sao cậu lại làm vậy?"};
            case 16800 -> new String[]{"đồ ngốc", "kể chuyện đi", "hôm nay làm được gì?", "cậu có thích mình không?", "blorp xyz?"};
            default -> null;
        };
        if (chats != null) for (String c : chats) {
            log("  <LyTester> " + c);
            ly.brain.hear(owner, c);
            if (ly.request() != null) log("    → yêu cầu: " + ly.request().type);
        }
        if (ticks == 6020 && ly.request() != null && "WAIT".equals(ly.request().type)) {
            // Đi tới chỗ cô ấy đợi để cô ấy dỡ trại.
            owner.snapTo(ly.getX() + 2, ly.getY(), ly.getZ());
        }
        if (ticks == 6400) owner.snapTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        // Cô ấy tự do: bạn bè bỏ chạy xa thì cô ấy thôi theo; gọi "lại đây" từ xa thì cô ấy không tới (chưa đủ thân).
        if (ticks == 6500) { ly.clearRequest(); log("  <LyTester> đi theo mình"); ly.brain.hear(owner, "đi theo mình"); }
        if (ticks == 6520) owner.snapTo(ly.getX() + 70, ly.getY(), ly.getZ());
        if (ticks == 6520 + 20 * 6) {
            boolean dropped = ly.request() == null;
            freeOk = dropped && ly.distanceTo(owner) > 40;
            log((freeOk ? "PASS" : "FAIL") + " bị bỏ xa thì thôi đi theo, không dịch chuyển tới (cách " + (int) ly.distanceTo(owner) + " block)");
            log("  <LyTester> lại đây");
            ly.brain.hear(owner, "lại đây");
            boolean refused = ly.request() == null;
            comeOk = refused;
            log((refused ? "PASS" : "FAIL") + " bạn bè gọi từ xa thì không chạy tới");
            owner.snapTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        }
        if (ticks == 20 * 60 * 25 && ly.data() != null && ly.data().blueprintAt != null && !ly.data().structures.containsKey("home")) {
            ServerLevel ow = srv.overworld();
            // Bãi thử chỉ có ~22 cây, không đủ gỗ cho cả căn nhà: tặng một rương nguyên liệu để thử phần xây cho tới khi xong.
            BlockPos chest = ly.structure("chest");
            if (chest == null || !(ow.getBlockState(chest).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) {
                var a = ly.data().blueprintAt;
                chest = new BlockPos(a.x - 3, a.y + 1, a.z - 3);
                ow.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                ly.data().structures.put("chest", new CompanionStore.Spot("minecraft:overworld", chest.getX(), chest.getY(), chest.getZ()));
            }
            if (ow.getBlockEntity(chest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c) {
                for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                net.minecraft.world.item.Item[] items = {Items.OAK_LOG, Items.OAK_LOG, Items.OAK_LOG, Items.OAK_LOG, Items.SPRUCE_LOG,
                        Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE, Items.DIRT, Items.GLASS, Items.IRON_INGOT,
                        Items.COAL, Items.WOOL.white(), Items.POPPY, Items.LEATHER, Items.COOKED_BEEF};
                for (int i = 0; i < items.length; i++) c.setItem(i, new net.minecraft.world.item.ItemStack(items[i], 64));
                c.setChanged();
            }
            log("  (thử nghiệm: đặt một rương đầy nguyên liệu cạnh nền nhà để xem cô ấy xây tới khi xong)");
        }
        boolean allDone = milestones.size() >= 24;
        if (allDone && !ly.survival.busy() && ticks % 100 == 0) finish("xong mọi mốc");
        else if (ticks >= MAX_TICKS) finish("hết giờ");
    }

    private static void mark(String name, boolean ok) {
        if (ok && !milestones.containsKey(name)) {
            milestones.put(name, ticks);
            log(String.format("  ✔ %-16s lúc %d:%02d", name, ticks / 1200, (ticks / 20) % 60));
        }
    }

    private static void finish(String why) {
        ServerLevel ow = server.overworld();
        server.tickRateManager().stopSprinting();
        LyMeowzEntity ly = ow.getEntity(lyId) instanceof LyMeowzEntity e ? e : null;
        String[] all = {"có gỗ", "cuốc gỗ", "rìu gỗ", "có đá cuội", "cuốc đá", "rìu đá", "kiếm đá", "lò nung", "than",
                "săn được thịt", "nướng chín thịt", "tự ăn khi đói", "tìm đất làm nhà", "xây nhà", "làm ruộng", "làm chuồng", "đặt giường", "ngủ trên giường", "viết nhật ký", "tự lên kế hoạch"};
        int ok = 0;
        StringBuilder missing = new StringBuilder();
        for (String m : all) if (milestones.containsKey(m)) ok++; else missing.append(" ").append(m);
        boolean pillarOk = pillar.stream().allMatch(p -> ow.getBlockState(p).is(Blocks.OAK_LOG));
        int litter = 0;
        BlockPos table = ly == null ? null : ly.workTable();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-40, -3, -40), center.offset(40, 6, 40))) {
            BlockState st = ow.getBlockState(p);
            boolean mine = ly != null && (ly.ownsBlock(p) || p.equals(table)); // trong nhà / bàn đang dùng ở chỗ làm việc
            if ((st.is(Blocks.CRAFTING_TABLE) || st.is(Blocks.FURNACE)) && !mine) litter++;
        }
        int campLeft = ly == null || ly.data() == null ? 0 : ly.data().campBlocks.size();
        int cows = ow.getEntitiesOfClass(Cow.class, new net.minecraft.world.phys.AABB(center).inflate(40)).size();
        for (String l : com.khanhly.lymeowz.util.Perf.report(server.getTickCount())) log("  ⏱ " + l);
        com.khanhly.lymeowz.util.Perf.on = false;
        {
            long t0 = System.nanoTime();
            CompanionStore.markDirty();
            CompanionStore.save();
            long t1 = System.nanoTime();
            var f = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("lymeowz.json");
            long size = 0;
            try { size = java.nio.file.Files.size(f); } catch (Exception ignored) {}
            log(String.format("  ⏱ lưu dữ liệu: %.1f ms, tệp %d KB", (t1 - t0) / 1e6, size >> 10));
        }
        log("== Kết quả (" + why + ", " + ticks / 1200 + " phút trong game) ==");
        log((ok == all.length ? "PASS" : "FAIL") + " tiến trình sinh tồn " + ok + "/" + all.length + (missing.length() > 0 ? " — thiếu:" + missing : ""));
        log((pillarOk ? "PASS" : "FAIL") + " không chặt cột gỗ có lá người chơi đặt (giả làm nhà)");
        log((litter == 0 ? "PASS" : "FAIL") + " không bỏ bàn chế tạo / lò lại ngoài nhà (" + litter + ")");
        log("Hộp trú đêm còn dựng: " + campLeft + " khối" + (ly != null && ly.isSleeping() ? " (đang ngủ)" : ""));
        log((cows >= 2 ? "PASS" : "FAIL") + " chừa lại ít nhất 2 con bò (" + cows + ")");
        if (ly != null) {
            log("Túi đồ cuối: " + Survival.summary(ly.inventoryWithHand()) + " | rương: " + ly.storedCount() + " món");
            log("Công trình: " + (ly.data() == null ? "-" : ly.data().structures.keySet()));
            log(com.khanhly.lymeowz.survival.Roadmap.describe(ly, true));
        }
        int pass = (ok == all.length ? 1 : 0) + (pillarOk ? 1 : 0) + (litter == 0 ? 1 : 0) + (cows >= 2 ? 1 : 0)
                + (freeOk ? 1 : 0) + (comeOk ? 1 : 0);
        log("== survival: " + pass + "/6 PASS ==");
        if (ly != null) ly.discard();
        if (ly != null) CompanionStore.remove(ly.getUUID());
        server = null;
        owner = null;
    }

    private static void log(String s) {
        LyMeowzMod.LOGGER.info("[survival] {}", s);
        if (out != null) out.accept(s);
    }
}
