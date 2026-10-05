package com.catherinepereira.mcdrone.task;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A box of block states in the Sponge Schematic format (.schem), the one WorldEdit and most schematic tools use.
 * Writes version 3 and reads versions 2 and 3. Block entity contents (chest items, sign text) are not kept.
 * Cells are indexed x + z * width + y * width * length, as the format specifies
 */
public final class Schematic {
	public static final int VERSION = 3;

	public final int width;
	public final int height;
	public final int length;
	private final BlockState[] blocks;

	public Schematic(int width, int height, int length, BlockState[] blocks) {
		if (blocks.length != width * height * length) {
			throw new IllegalArgumentException("expected " + width * height * length + " blocks, got " + blocks.length);
		}
		this.width = width;
		this.height = height;
		this.length = length;
		this.blocks = blocks;
	}

	public static Schematic fromWorld(ServerLevel level, BlockPos min, BlockPos max) {
		int w = max.getX() - min.getX() + 1;
		int h = max.getY() - min.getY() + 1;
		int l = max.getZ() - min.getZ() + 1;
		BlockState[] blocks = new BlockState[w * h * l];
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					blocks[x + z * w + y * w * l] = level.getBlockState(min.offset(x, y, z));
				}
			}
		}
		return new Schematic(w, h, l, blocks);
	}

	public BlockState get(int x, int y, int z) {
		return this.blocks[x + z * this.width + y * this.width * this.length];
	}

	public BlockPos size() {
		return new BlockPos(this.width, this.height, this.length);
	}

	public void write(Path file) throws IOException {
		Map<BlockState, Integer> palette = new HashMap<>();
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for (BlockState state : this.blocks) {
			writeVarInt(data, palette.computeIfAbsent(state, s -> palette.size()));
		}
		CompoundTag paletteTag = new CompoundTag();
		palette.forEach((state, id) -> paletteTag.putInt(BlockStateParser.serialize(state), id));
		CompoundTag blocksTag = new CompoundTag();
		blocksTag.put("Palette", paletteTag);
		blocksTag.putByteArray("Data", data.toByteArray());
		blocksTag.put("BlockEntities", new ListTag());
		CompoundTag schematic = new CompoundTag();
		schematic.putInt("Version", VERSION);
		schematic.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		schematic.putShort("Width", (short) this.width);
		schematic.putShort("Height", (short) this.height);
		schematic.putShort("Length", (short) this.length);
		schematic.putIntArray("Offset", new int[] {0, 0, 0});
		schematic.put("Blocks", blocksTag);
		CompoundTag root = new CompoundTag();
		root.put("Schematic", schematic);
		Files.createDirectories(file.toAbsolutePath().getParent());
		NbtIo.writeCompressed(root, file);
	}

	public static Schematic read(Path file) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024));
		// version 3 wraps everything in a Schematic compound, version 2 keeps it at the root
		CompoundTag tag = root.getCompound("Schematic").orElse(root);
		int version = tag.getIntOr("Version", 0);
		int w = tag.getShortOr("Width", (short) 0) & 0xFFFF;
		int h = tag.getShortOr("Height", (short) 0) & 0xFFFF;
		int l = tag.getShortOr("Length", (short) 0) & 0xFFFF;
		CompoundTag paletteTag;
		byte[] data;
		if (version >= 3) {
			CompoundTag blocksTag = tag.getCompoundOrEmpty("Blocks");
			paletteTag = blocksTag.getCompoundOrEmpty("Palette");
			data = blocksTag.getByteArray("Data").orElse(new byte[0]);
		} else if (version == 2) {
			paletteTag = tag.getCompoundOrEmpty("Palette");
			data = tag.getByteArray("BlockData").orElse(new byte[0]);
		} else {
			throw new IOException(file + " is not a Sponge schematic (version " + version + ")");
		}
		Map<Integer, BlockState> palette = new HashMap<>();
		for (String key : paletteTag.keySet()) {
			palette.put(paletteTag.getIntOr(key, 0), parseState(key));
		}
		List<Integer> ids = readVarInts(data);
		if (ids.size() != w * h * l) {
			throw new IOException(file + " has " + ids.size() + " blocks for a " + w + "x" + h + "x" + l + " box");
		}
		BlockState[] blocks = new BlockState[ids.size()];
		for (int i = 0; i < blocks.length; i++) {
			blocks[i] = palette.getOrDefault(ids.get(i), Blocks.AIR.defaultBlockState());
		}
		return new Schematic(w, h, l, blocks);
	}

	// a block this game doesn't know, from a newer version or a mod, reads as air
	private static BlockState parseState(String text) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
		} catch (CommandSyntaxException e) {
			return Blocks.AIR.defaultBlockState();
		}
	}

	private static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	private static List<Integer> readVarInts(byte[] data) {
		List<Integer> out = new ArrayList<>();
		int i = 0;
		while (i < data.length) {
			int value = 0;
			int shift = 0;
			byte b;
			do {
				b = data[i++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && i < data.length);
			out.add(value);
		}
		return out;
	}
}
