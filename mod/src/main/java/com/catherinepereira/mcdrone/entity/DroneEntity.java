package com.catherinepereira.mcdrone.entity;

import com.catherinepereira.mcdrone.Json;
import com.catherinepereira.mcdrone.ModContent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Moved only by its owner's client, which sends poses to the server.
 * The server never simulates it. It's a living entity so a mob it attacks can fight back, and only mobs hurt it
 */
public class DroneEntity extends LivingEntity implements ItemSupplier {
	private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> TIER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	// battery charge from 0 to 1, see BatteryConfig
	private static final EntityDataAccessor<Float> CHARGE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
	// the charging station it goes back to, set by using the tablet on a station
	private static final EntityDataAccessor<Optional<BlockPos>> HOME = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
	// jobs waiting to run, a JSON array of job options with a "label", see ClientRuntime.queueJob
	private static final EntityDataAccessor<String> QUEUE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	// the mob the attack beam is locked on, -1 for none, and the drone ticks it has charged, see DroneTools
	private static final EntityDataAccessor<Integer> BEAM_TARGET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> BEAM_CHARGE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	// a training arena's drone can't die, damage that would kill it wrecks it instead, which ends its episode
	private static final EntityDataAccessor<Boolean> WRECKED = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
	// health a docked drone gets back each tick, empty to full in about 20 seconds
	private static final float REPAIR_PER_TICK = 0.05F;
	public static final int MAX_QUEUE = 16;
	// ticks the motors stay on after the drone last moved or broke a block, idle drones don't draw power
	private static final int POWERED_TICKS = 100;
	// a drone this close above its station's top is docked and charges
	private static final double DOCK_REACH = 1.5;

	public static final int INVENTORY_SIZE = 27;
	public static final String DEFAULT_NAME = "Drone";
	public static final int MAX_NAME = 32;

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
	// shown over the drone as "name (owner)", see refreshNameTag
	private String name = DEFAULT_NAME;
	private String ownerName = "";
	private @Nullable Vec3 lastPos;
	private int poweredFor;
	// a pose arrived since the last server tick, the pose already ran the battery for that tick
	private boolean posed;
	// set while it flies a training arena, where the battery stays out of the way, player jobs clear it
	public boolean trainingArena;

	public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
		super(type, level);
		this.setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(OWNER, "");
		builder.define(TIER, DroneTier.COPPER.ordinal());
		builder.define(CHARGE, 1.0F);
		builder.define(HOME, Optional.empty());
		builder.define(QUEUE, "[]");
		builder.define(BEAM_TARGET, -1);
		builder.define(BEAM_CHARGE, 0);
		builder.define(WRECKED, false);
	}

	public void setOwner(Player owner) {
		this.entityData.set(OWNER, owner.getUUID().toString());
		this.ownerName = owner.getName().getString();
		this.refreshNameTag();
	}

	/** Names the drone, a blank name puts back the default */
	public void rename(String name) {
		String trimmed = name.strip();
		this.name = trimmed.isEmpty() ? DEFAULT_NAME : trimmed.substring(0, Math.min(trimmed.length(), MAX_NAME));
		this.refreshNameTag();
	}

	public String name() {
		return this.name;
	}

	/** The name without its owner, on either side, the client only has the synced nametag "name (owner)" */
	public String shownName() {
		if (!this.level().isClientSide()) {
			return this.name;
		}
		return this.getCustomName() == null ? DEFAULT_NAME : this.getCustomName().getString().replaceFirst(" \\([^()]*\\)$", "");
	}

	// vanilla draws a visible custom name over the drone, and the chest screen uses it as its title
	private void refreshNameTag() {
		this.setCustomName(Component.literal(this.ownerName.isEmpty() ? this.name : this.name + " (" + this.ownerName + ")"));
		this.setCustomNameVisible(true);
	}

	public DroneTier tier() {
		return DroneTier.byOrdinal(this.entityData.get(TIER));
	}

	public void setTier(DroneTier tier) {
		this.entityData.set(TIER, tier.ordinal());
	}

	public float charge() {
		return this.entityData.get(CHARGE);
	}

	/** The entity id the attack beam is locked on, -1 without a beam */
	public int beamTarget() {
		return this.entityData.get(BEAM_TARGET);
	}

	public int beamCharge() {
		return this.entityData.get(BEAM_CHARGE);
	}

	public void setBeam(int target, int charge) {
		this.entityData.set(BEAM_TARGET, target);
		this.entityData.set(BEAM_CHARGE, charge);
	}

	/** Out of charge with the battery on, the drone can't fly or use tools */
	public boolean flat() {
		return BatteryConfig.get().enabled && !this.trainingArena && this.charge() <= 0.0F;
	}

	public @Nullable BlockPos home() {
		return this.entityData.get(HOME).orElse(null);
	}

	public void setHome(@Nullable BlockPos home) {
		this.entityData.set(HOME, Optional.ofNullable(home).map(BlockPos::immutable));
	}

	public void recharge() {
		this.entityData.set(CHARGE, 1.0F);
	}

	public boolean wrecked() {
		return this.entityData.get(WRECKED);
	}

	/** Full health and no longer wrecked, how a training arena hands out its drone */
	public void repair() {
		this.setHealth(this.getMaxHealth());
		this.entityData.set(WRECKED, false);
	}

	/** Spends charge for a block broken */
	public void spendBreak() {
		this.poweredFor = POWERED_TICKS;
		this.spend(BatteryConfig.get().breakCost());
	}

	private void spend(double amount) {
		if (BatteryConfig.get().enabled && !this.trainingArena) {
			this.entityData.set(CHARGE, (float) Math.clamp(this.charge() - amount, 0.0, 1.0));
		}
	}

	public JsonArray queue() {
		try {
			return JsonParser.parseString(this.entityData.get(QUEUE)).getAsJsonArray();
		} catch (RuntimeException e) {
			return new JsonArray();
		}
	}

	public void setQueue(JsonArray queue) {
		this.entityData.set(QUEUE, queue.toString());
	}

	/** On its station, where it charges */
	public boolean docked() {
		BlockPos home = this.home();
		if (home == null || !this.level().getBlockState(home).is(ModContent.CHARGING_STATION)) {
			return false;
		}
		Vec3 top = Vec3.atBottomCenterOf(home.above());
		return Math.abs(this.getX() - top.x) < 0.6 && Math.abs(this.getZ() - top.z) < 0.6 && this.getY() >= top.y - 0.1 && this.getY() <= top.y + DOCK_REACH;
	}

	/** The charge, the home station, and the battery's settings, for the drone's state */
	public JsonObject batteryJson() {
		JsonObject json = BatteryConfig.get().toJson();
		json.addProperty("charge", this.charge());
		json.add("home", Json.pos(this.home()));
		json.addProperty("docked", this.docked());
		return json;
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

	/**
	 * A pose from the owner's client, one simulated tick of flight. Lockstep freezes the server's ticks, so the battery runs
	 * on poses while a client flies the drone and on server ticks otherwise
	 */
	public void onPose(double x, double y, double z, float yaw, float pitch) {
		this.snapTo(x, y, z, yaw, pitch);
		this.posed = true;
		this.batteryTick();
	}

	@Override
	public void tick() {
		this.baseTick();
		if (this.level().isClientSide()) {
			return;
		}
		if (this.posed) {
			this.posed = false;
		} else {
			this.batteryTick();
		}
	}

	private void batteryTick() {
		BatteryConfig battery = BatteryConfig.get();
		Vec3 pos = this.position();
		boolean moved = this.lastPos != null && pos.distanceToSqr(this.lastPos) > 1e-4;
		this.lastPos = pos;
		if (moved) {
			this.poweredFor = POWERED_TICKS;
		}
		if (this.docked()) {
			this.entityData.set(CHARGE, (float) Math.min(1.0, this.charge() + battery.chargePerTick()));
			this.heal(REPAIR_PER_TICK);
		} else if (this.poweredFor > 0) {
			this.poweredFor--;
			this.spend(moved ? battery.flightPerTick() : battery.flightPerTick() * battery.hoverShare);
		}
	}

	/**
	 * Only mobs hurt a drone, their hits and their arrows. Players pick it up instead, and a drone takes no fall,
	 * fire, or drowning damage. A training arena's drone is wrecked where it would die
	 */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (!(source.getEntity() instanceof Mob) || this.wrecked()) {
			return false;
		}
		if (this.trainingArena && damage >= this.getHealth()) {
			this.entityData.set(WRECKED, true);
			damage = this.getHealth() - 1.0F;
		}
		return super.hurtServer(level, source, damage);
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		// shot down, its cargo and the drone itself drop where it fell
		if (this.level() instanceof ServerLevel level) {
			Containers.dropContents(level, this, this.inventory);
			this.spawnAtLocation(level, new ItemStack(ModContent.droneItem(this.tier())));
		}
	}

	@Override
	public boolean canBreatheUnderwater() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}

	// a living entity's camera and look follow its head, the drone's camera turns with its body
	@Override
	public float getViewYRot(float partialTick) {
		return partialTick == 1.0F ? this.getYRot() : Mth.rotLerp(partialTick, this.yRotO, this.getYRot());
	}

	@Override
	public float getYHeadRot() {
		return this.getYRot();
	}

	// flight never steps up onto a block the way walking mobs do
	@Override
	public float maxUpStep() {
		return 0.0F;
	}

	@Override
	public boolean isPickable() {
		return true;
	}

	// drones bump into each other, which counts as a collision in their episodes
	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return other instanceof DroneEntity;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty() && player.isSecondaryUseActive() && this.isOwnedBy(player)) {
			// sneaking with an empty hand picks the drone back up, its cargo drops like a broken chest's
			if (this.level() instanceof ServerLevel level) {
				Containers.dropContents(level, this, this.inventory);
				ItemStack item = new ItemStack(ModContent.droneItem(this.tier()));
				if (!player.getInventory().add(item)) {
					this.spawnAtLocation(level, item);
				}
				this.discard();
			}
			return InteractionResult.SUCCESS;
		}
		Component tagName = held.is(Items.NAME_TAG) ? held.get(DataComponents.CUSTOM_NAME) : null;
		if (tagName != null && this.isOwnedBy(player)) {
			// a named name tag renames the drone, like a mob
			if (player.level() instanceof ServerLevel) {
				this.rename(tagName.getString());
				held.consume(1, player);
			}
			return InteractionResult.SUCCESS;
		}
		if (player.level() instanceof ServerLevel) {
			player.openMenu(new SimpleMenuProvider((id, playerInventory, p) -> ChestMenu.threeRows(id, playerInventory, this.inventory), this.getDisplayName()));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public ItemStack getItem() {
		return new ItemStack(ModContent.droneItem(this.tier()));
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.entityData.set(OWNER, input.getStringOr("owner", ""));
		this.ownerName = input.getStringOr("ownerName", "");
		this.rename(input.getStringOr("name", DEFAULT_NAME));
		this.setTier(DroneTier.parse(input.getStringOr("tier", DroneTier.COPPER.id)));
		this.entityData.set(CHARGE, (float) input.getDoubleOr("charge", 1.0));
		this.setHome(input.getLong("home").map(BlockPos::of).orElse(null));
		this.entityData.set(QUEUE, input.getStringOr("queue", "[]"));
		this.inventory.clearContent();
		ContainerHelper.loadAllItems(input, this.inventory.getItems());
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("owner", this.entityData.get(OWNER));
		output.putString("ownerName", this.ownerName);
		output.putString("name", this.name);
		output.putString("tier", this.tier().id);
		output.putDouble("charge", this.charge());
		output.putString("queue", this.entityData.get(QUEUE));
		if (this.home() != null) {
			output.putLong("home", this.home().asLong());
		}
		ContainerHelper.saveAllItems(output, this.inventory.getItems());
	}
}
