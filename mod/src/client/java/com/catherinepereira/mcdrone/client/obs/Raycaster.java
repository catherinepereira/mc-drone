package com.catherinepereira.mcdrone.client.obs;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Depth and semantic mask from one ray per output pixel, through the same frustum as the rendered frame.
 * Renderer-independent, so it works with any graphics backend and needs no second render pass
 */
public final class Raycaster {
	public static final int ENTITY_BASE = 32768;

	private Raycaster() {
	}

	public record Camera(Vec3 origin, Vector3fc forward, Vector3fc up, Vector3fc left, float fovDegrees, float windowAspect) {
	}

	/**
	 * Fills depth (z-distance in blocks, maxDist on a miss), mask (0 sky, 1 + block id, ENTITY_BASE + entity type id),
	 * and state (0 for sky and entities, 1 + block state id, which carries properties such as a crop's age).
	 * Rows run top to bottom. The output aspect is center-cropped from the window, matching FrameGrabber
	 */
	public static void cast(ClientLevel level, Camera cam, Entity exclude, int width, int height, float maxDist, float[] depth, short[] mask, short[] state) {
		float outAspect = (float) width / height;
		float tanY = (float) Math.tan(Math.toRadians(cam.fovDegrees()) / 2.0);
		float tanX = tanY * Math.min(cam.windowAspect(), outAspect);
		float tanYe = tanX / outAspect;
		Vector3f right = new Vector3f(cam.left()).negate();
		Vec3 origin = cam.origin();

		List<Entity> entities = level.getEntities(exclude, new AABB(origin, origin).inflate(maxDist), e -> !e.isSpectator() && !e.isInvisible());

		IntStream.range(0, height).parallel().forEach(row -> {
			float ny = 1.0F - (row + 0.5F) / height * 2.0F;
			for (int col = 0; col < width; col++) {
				float nx = (col + 0.5F) / width * 2.0F - 1.0F;
				// forward component is 1, so the ray parameter at a hit is its z-depth
				double dx = cam.forward().x() + right.x * nx * tanX + cam.up().x() * ny * tanYe;
				double dy = cam.forward().y() + right.y * nx * tanX + cam.up().y() * ny * tanYe;
				double dz = cam.forward().z() + right.z * nx * tanX + cam.up().z() * ny * tanYe;
				Vec3 to = new Vec3(origin.x + dx * maxDist, origin.y + dy * maxDist, origin.z + dz * maxDist);

				float hitDepth = maxDist;
				int id = 0;
				int stateId = 0;
				BlockHitResult hit = clip(level, origin, to);
				if (hit != null) {
					hitDepth = zDepth(hit.getLocation(), origin, cam.forward());
					BlockState blockState = level.getBlockState(hit.getBlockPos());
					id = 1 + BuiltInRegistries.BLOCK.getId(blockState.getBlock());
					stateId = 1 + Block.getId(blockState);
				}
				for (Entity entity : entities) {
					Optional<Vec3> entityHit = entity.getBoundingBox().clip(origin, to);
					if (entityHit.isPresent()) {
						float d = zDepth(entityHit.get(), origin, cam.forward());
						if (d < hitDepth) {
							hitDepth = d;
							id = ENTITY_BASE + BuiltInRegistries.ENTITY_TYPE.getId(entity.getType());
							stateId = 0;
						}
					}
				}
				int i = row * width + col;
				depth[i] = hitDepth;
				mask[i] = (short) id;
				// read back as uint16, the game has fewer than 65535 block states
				state[i] = (short) stateId;
			}
		});
	}

	/** The block or fluid a ray from from to to hits first, as Level.clip with outline shapes and any fluid finds it, or null */
	public static @Nullable BlockHitResult clip(ClientLevel level, Vec3 from, Vec3 to) {
		return new Ray(level, new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty())).cast();
	}

	/**
	 * Level.clip's per-block test, with the chunk looked up once per section instead of twice per block, and blocks in
	 * sections that hold only air skipped. The lookups were most of a frame's raycast
	 */
	private static final class Ray {
		private final ClientLevel level;
		private final ClipContext context;
		private long section = Long.MIN_VALUE;
		private boolean sectionEmpty;
		private @Nullable LevelChunk chunk;

		Ray(ClientLevel level, ClipContext context) {
			this.level = level;
			this.context = context;
		}

		@Nullable BlockHitResult cast() {
			return BlockGetter.traverseBlocks(this.context.getFrom(), this.context.getTo(), this, Ray::visit, ray -> null);
		}

		private @Nullable BlockHitResult visit(BlockPos pos) {
			long key = SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
			if (key != this.section) {
				this.section = key;
				int chunkX = SectionPos.blockToSectionCoord(pos.getX());
				int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
				if (this.chunk == null || this.chunk.getPos().x() != chunkX || this.chunk.getPos().z() != chunkZ) {
					this.chunk = this.level.getChunk(chunkX, chunkZ);
				}
				this.sectionEmpty = this.level.isOutsideBuildHeight(pos.getY()) || this.chunk.getSection(this.level.getSectionIndex(pos.getY())).hasOnlyAir();
			}
			if (this.sectionEmpty) {
				return null;
			}
			// the rest is BlockGetter.clip's per-block function, a fluid is part of its block's state
			BlockState blockState = this.chunk.getBlockState(pos);
			FluidState fluidState = blockState.getFluidState();
			Vec3 from = this.context.getFrom();
			Vec3 to = this.context.getTo();
			BlockHitResult blockResult = this.level.clipWithInteractionOverride(from, to, pos, this.context.getBlockShape(blockState, this.level, pos), blockState);
			BlockHitResult liquidResult = this.context.getFluidShape(fluidState, this.level, pos).clip(from, to, pos);
			double blockDistance = blockResult == null ? Double.MAX_VALUE : from.distanceToSqr(blockResult.getLocation());
			double liquidDistance = liquidResult == null ? Double.MAX_VALUE : from.distanceToSqr(liquidResult.getLocation());
			return blockDistance <= liquidDistance ? blockResult : liquidResult;
		}
	}

	private static float zDepth(Vec3 point, Vec3 origin, Vector3fc forward) {
		return (float) ((point.x - origin.x) * forward.x() + (point.y - origin.y) * forward.y() + (point.z - origin.z) * forward.z());
	}
}
