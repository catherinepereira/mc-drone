package com.catherinepereira.mcdrone.entity;

import com.catherinepereira.mcdrone.ModContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Moved only by its owner's client, which sends poses to the server.
 * The server never simulates it
 */
public class DroneEntity extends Entity implements ItemSupplier {
	private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);

	public static final int INVENTORY_SIZE = 27;

	// set by the owning client while it drives this drone, makes the client ignore server position echoes
	public boolean clientControlled;

	// server-side tool state, see DroneTools
	// players open the inventory as a chest by using the drone, within reach like a chest boat
	public final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE) {
		@Override
		public boolean stillValid(Player player) {
			return !DroneEntity.this.isRemoved() && player.isWithinEntityInteractionRange(DroneEntity.this.getBoundingBox(), 4.0);
		}
	};
	public @Nullable BlockPos openContainer;
	public @Nullable BlockPos breakingPos;
	public float breakProgress;

	public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
		super(type, level);
		this.setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(OWNER, "");
	}

	public void setOwner(UUID owner) {
		this.entityData.set(OWNER, owner.toString());
	}

	public boolean isOwnedBy(Player player) {
		return player.getUUID().toString().equals(this.entityData.get(OWNER));
	}

	@Override
	protected boolean isLocalClientAuthoritative() {
		return this.clientControlled;
	}

	@Override
	public boolean isClientAuthoritative() {
		return true;
	}

	@Override
	public void tick() {
		this.baseTick();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return true;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (player.level() instanceof ServerLevel) {
			player.openMenu(new SimpleMenuProvider((id, playerInventory, p) -> ChestMenu.threeRows(id, playerInventory, this.inventory), this.getDisplayName()));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public ItemStack getItem() {
		return new ItemStack(ModContent.DRONE_ITEM);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(OWNER, input.getStringOr("owner", ""));
		this.inventory.clearContent();
		ContainerHelper.loadAllItems(input, this.inventory.getItems());
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("owner", this.entityData.get(OWNER));
		ContainerHelper.saveAllItems(output, this.inventory.getItems());
	}
}
