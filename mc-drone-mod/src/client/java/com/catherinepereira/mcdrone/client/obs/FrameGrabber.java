package com.catherinepereira.mcdrone.client.obs;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Async GPU readback of the main framebuffer, center-cropped to the output aspect and box-downsampled.
 * Same copy path as vanilla's Screenshot.takeScreenshot
 */
public final class FrameGrabber {
	private static final int MAX_SAMPLES = 4;

	private FrameGrabber() {
	}

	public static void grab(RenderTarget target, int width, int height, Consumer<byte[]> onRgb, Consumer<String> onError) {
		GpuTexture texture = target.getColorTexture();
		if (texture == null) {
			onError.accept("framebuffer has no color texture");
			return;
		}
		if (texture.getFormat() != GpuFormat.RGBA8_UNORM) {
			onError.accept("unsupported framebuffer format " + texture.getFormat());
			return;
		}
		int srcW = target.width;
		int srcH = target.height;
		int blockSize = texture.getFormat().blockSize();
		GpuBuffer buffer = RenderSystem.getDevice()
			.createBuffer(() -> "mcdrone frame", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) srcW * srcH * blockSize);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
			try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
				onRgb.accept(downsample(view.data(), srcW, srcH, blockSize, width, height));
			} catch (RuntimeException e) {
				onError.accept(e.toString());
			} finally {
				buffer.close();
			}
		}, 0);
	}

	static byte[] downsample(ByteBuffer src, int srcW, int srcH, int blockSize, int outW, int outH) {
		float outAspect = (float) outW / outH;
		int cropW = srcW;
		int cropH = srcH;
		if ((float) srcW / srcH > outAspect) {
			cropW = Math.round(srcH * outAspect);
		} else {
			cropH = Math.round(srcW / outAspect);
		}
		int x0 = (srcW - cropW) / 2;
		int y0 = (srcH - cropH) / 2;
		float cellW = (float) cropW / outW;
		float cellH = (float) cropH / outH;
		int samplesX = Math.clamp((int) Math.ceil(cellW), 1, MAX_SAMPLES);
		int samplesY = Math.clamp((int) Math.ceil(cellH), 1, MAX_SAMPLES);
		int count = samplesX * samplesY;

		byte[] rgb = new byte[outW * outH * 3];
		for (int oy = 0; oy < outH; oy++) {
			for (int ox = 0; ox < outW; ox++) {
				int r = 0;
				int g = 0;
				int b = 0;
				for (int sy = 0; sy < samplesY; sy++) {
					// GPU rows start at the bottom, output rows start at the top
					int py = y0 + (int) ((oy + (sy + 0.5F) / samplesY) * cellH);
					int row = srcH - 1 - Math.min(py, srcH - 1);
					for (int sx = 0; sx < samplesX; sx++) {
						int px = Math.min(x0 + (int) ((ox + (sx + 0.5F) / samplesX) * cellW), srcW - 1);
						int at = (px + row * srcW) * blockSize;
						r += src.get(at) & 0xFF;
						g += src.get(at + 1) & 0xFF;
						b += src.get(at + 2) & 0xFF;
					}
				}
				int i = (oy * outW + ox) * 3;
				rgb[i] = (byte) (r / count);
				rgb[i + 1] = (byte) (g / count);
				rgb[i + 2] = (byte) (b / count);
			}
		}
		return rgb;
	}
}
