package com.catherinepereira.mcdrone.client.obs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimal PNG writer for 8-bit RGB and 16-bit grayscale, and a reader for the files it writes.
 * java.desktop (ImageIO) isn't guaranteed in the launcher's runtime, and NativeImage has no 16-bit mode
 */
public final class Png {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final int COLOR_GRAY = 0;
	private static final int COLOR_RGB = 2;

	private Png() {
	}

	public static void writeRgb(Path path, byte[] rgb, int width, int height) throws IOException {
		Files.write(path, encode(rgb, width, height, 8, COLOR_RGB, width * 3));
	}

	public static void writeGray16(Path path, short[] values, int width, int height) throws IOException {
		// PNG stores 16-bit samples big-endian
		byte[] raw = new byte[values.length * 2];
		for (int i = 0; i < values.length; i++) {
			raw[i * 2] = (byte) (values[i] >> 8);
			raw[i * 2 + 1] = (byte) values[i];
		}
		Files.write(path, encode(raw, width, height, 16, COLOR_GRAY, width * 2));
	}

	public static byte[] readRgb(Path path, int width, int height) throws IOException {
		return readRaw(path, width * 3, height);
	}

	public static short[] readGray16(Path path, int width, int height) throws IOException {
		byte[] raw = readRaw(path, width * 2, height);
		short[] values = new short[width * height];
		for (int i = 0; i < values.length; i++) {
			values[i] = (short) (((raw[i * 2] & 0xFF) << 8) | (raw[i * 2 + 1] & 0xFF));
		}
		return values;
	}

	private static byte[] encode(byte[] raw, int width, int height, int bitDepth, int colorType, int stride) throws IOException {
		ByteArrayOutputStream filtered = new ByteArrayOutputStream(raw.length + height);
		try (DeflaterOutputStream deflate = new DeflaterOutputStream(filtered, new Deflater(Deflater.BEST_SPEED))) {
			for (int y = 0; y < height; y++) {
				deflate.write(0);
				deflate.write(raw, y * stride, stride);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(SIGNATURE);
		ByteBuffer ihdr = ByteBuffer.allocate(13);
		ihdr.putInt(width).putInt(height).put((byte) bitDepth).put((byte) colorType).put((byte) 0).put((byte) 0).put((byte) 0);
		chunk(out, "IHDR", ihdr.array());
		chunk(out, "IDAT", filtered.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
		byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		out.write(ByteBuffer.allocate(4).putInt(data.length).array());
		out.write(typeBytes);
		out.write(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}

	// only handles single-IDAT, filter-0 files, which is everything writeRgb and writeGray16 produce
	private static byte[] readRaw(Path path, int stride, int height) throws IOException {
		ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(path));
		file.position(SIGNATURE.length);
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		while (file.remaining() >= 12) {
			int length = file.getInt();
			byte[] type = new byte[4];
			file.get(type);
			byte[] data = new byte[length];
			file.get(data);
			file.getInt();
			if (new String(type, java.nio.charset.StandardCharsets.US_ASCII).equals("IDAT")) {
				idat.write(data);
			}
		}
		byte[] raw = new byte[stride * height];
		try (InputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(idat.toByteArray()))) {
			for (int y = 0; y < height; y++) {
				int filter = in.read();
				if (filter != 0) {
					throw new IOException("unsupported PNG filter " + filter + " in " + path);
				}
				in.readNBytes(raw, y * stride, stride);
			}
		}
		return raw;
	}
}
