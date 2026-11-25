package com.khanhly.lymeowz.house;

import com.khanhly.lymeowz.LyMeowzMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Bản vẽ nhà đọc từ file .schem (Sponge schematic v2/v3, như WorldEdit xuất ra).
 * Lớp y = 0 của bản vẽ là lớp đất nền (cỏ, đất); nhà bắt đầu từ y = 1.
 *
 * <p>Bản vẽ có sẵn trong mod nằm ở {@code data/lymeowz/houses/}. Người chơi có thể thả thêm file .schem vào
 * {@code config/lymeowz/houses/}. Cô ấy xây lần lượt từ nhà nhỏ (ít block) tới nhà lớn.
 */
public final class Blueprint {
    public record Piece(BlockPos pos, BlockState state) {}

    public final String name;
    public final int width, height, length;
    /** Mọi block không phải không khí. */
    public final List<Piece> pieces;
    /** Ô bên trong nhà phải trống (không khí) – để dọn cây cối, đất đá chắn chỗ. */
    public final List<BlockPos> air;
    @Nullable public final BlockPos bedHead, chest, inside;

    private Blueprint(String name, int w, int h, int l, List<Piece> pieces, List<BlockPos> air) {
        this.name = name;
        this.width = w;
        this.height = h;
        this.length = l;
        this.pieces = pieces;
        this.air = air;
        BlockPos bed = null, ch = null;
        for (Piece p : pieces) {
            if (p.state.getBlock() instanceof BedBlock && p.state.getValue(BedBlock.PART) == BedPart.HEAD && bed == null) bed = p.pos;
            if (p.state.getBlock() instanceof ChestBlock && ch == null) ch = p.pos;
        }
        this.bedHead = bed;
        this.chest = ch;
        this.inside = findInside();
    }

    public int blockCount() {
        return pieces.size();
    }

    /** Một ô trống ở tầng trệt gần giữa nhà (chỗ đứng trong nhà). */
    @Nullable
    private BlockPos findInside() {
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (Piece p : pieces) m.put(p.pos, p.state);
        BlockPos best = null;
        double bd = 1e9;
        double cx = width / 2.0, cz = length / 2.0;
        for (int x = 1; x < width - 1; x++) for (int z = 1; z < length - 1; z++) {
            BlockPos feet = new BlockPos(x, 1, z);
            BlockState floor = m.get(feet.below());
            if (floor == null || m.containsKey(feet) || m.containsKey(feet.above())) continue;
            // Phải có mái che phía trên (trong nhà).
            boolean roof = false;
            for (int y = 3; y < height && !roof; y++) roof = m.containsKey(new BlockPos(x, y, z));
            if (!roof) continue;
            double d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
            if (d < bd) { bd = d; best = feet; }
        }
        return best;
    }

    // ------------------------------------------------------------------ danh sách bản vẽ

    private static List<Blueprint> all;

    /** Mọi bản vẽ, xếp từ nhỏ tới lớn. */
    public static synchronized List<Blueprint> all() {
        if (all != null) return all;
        List<Blueprint> list = new ArrayList<>();
        for (String n : new String[]{"survivalhouse5", "survivalhouse2"}) {
            try (InputStream in = Blueprint.class.getResourceAsStream("/data/lymeowz/houses/" + n + ".schem")) {
                if (in != null) list.add(read(n, NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap())));
            } catch (Exception e) {
                LyMeowzMod.LOGGER.warn("Không đọc được bản vẽ {}: {}", n, e.toString());
            }
        }
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("lymeowz").resolve("houses");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".schem")).toList()) {
                    try {
                        String n = f.getFileName().toString().replace(".schem", "");
                        list.add(read(n, NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap())));
                    } catch (Exception e) {
                        LyMeowzMod.LOGGER.warn("Không đọc được bản vẽ {}: {}", f, e.toString());
                    }
                }
            } catch (Exception ignored) {}
        }
        list.sort(Comparator.comparingInt(Blueprint::blockCount));
        for (Blueprint b : list)
            LyMeowzMod.LOGGER.info("Bản vẽ nhà {}: {}x{}x{}, {} block", b.name, b.width, b.height, b.length, b.blockCount());
        all = list;
        return all;
    }

    @Nullable
    public static Blueprint byName(String name) {
        for (Blueprint b : all()) if (b.name.equals(name)) return b;
        return null;
    }

    static Blueprint read(String name, CompoundTag root) throws Exception {
        CompoundTag s = root.getCompound("Schematic").orElse(root);
        int w = s.getShortOr("Width", (short) 0), h = s.getShortOr("Height", (short) 0), l = s.getShortOr("Length", (short) 0);
        CompoundTag palette;
        byte[] data;
        if (s.getCompound("Blocks").isPresent()) {
            CompoundTag blocks = s.getCompoundOrEmpty("Blocks");
            palette = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElseThrow();
        } else {
            palette = s.getCompoundOrEmpty("Palette");
            data = s.getByteArray("BlockData").orElseThrow();
        }
        Map<Integer, BlockState> states = new HashMap<>();
        for (String key : palette.keySet()) {
            int id = palette.getIntOr(key, -1);
            BlockState st;
            try {
                st = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, key, false).blockState();
            } catch (Exception e) {
                st = Blocks.AIR.defaultBlockState(); // block của mod khác / phiên bản khác: bỏ qua
            }
            states.put(id, st);
        }
        List<Piece> pieces = new ArrayList<>();
        List<BlockPos> air = new ArrayList<>();
        int i = 0, index = 0;
        while (i < data.length) {
            int value = 0, shift = 0;
            byte b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            int x = index % w, z = (index / w) % l, y = index / (w * l);
            index++;
            BlockState st = states.getOrDefault(value, Blocks.AIR.defaultBlockState());
            BlockPos p = new BlockPos(x, y, z);
            if (st.isAir() || st.is(Blocks.STRUCTURE_VOID)) { if (y >= 1) air.add(p); }
            else pieces.add(new Piece(p, st));
        }
        return new Blueprint(name, w, h, l, pieces, air);
    }
}
