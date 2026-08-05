package com.railway.railway_operations.condition;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.decoration.slidingDoor.DoorControl;
import com.simibubi.create.content.decoration.slidingDoor.DoorControlBehaviour;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;

import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

/**
 * A schedule condition that controls train sliding doors.
 * When activated (train arrives at station), opens doors on the configured side.
 * Doors stay open until the condition's duration expires, then close.
 * <p>
 * Supports "Follow Station" mode — reads the station's own door control setting
 * and uses it as the target direction, overriding the station's auto-open.
 */
public class DoorControlCondition extends TimedWaitCondition {

    private static final String DOOR_SIDE_KEY = "DoorSide";

    /**
     * Ordinal stored in the data tag for "Follow Station" mode.
     * We reuse NONE's ordinal (5) as the sentinel since NONE is not
     * a useful door-control condition choice by itself.
     */
    private static final int FOLLOW_STATION_SENTINEL = DoorControl.NONE.ordinal();

    public DoorControlCondition() {
        super();
        data.putInt(DOOR_SIDE_KEY, DoorControl.ALL.ordinal());
    }

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath("railway_operations", "door_control");
    }

    /** The raw DoorControl stored in data (may be NONE meaning Follow Station). */
    public DoorControl getDoorSide() {
        return enumData(DOOR_SIDE_KEY, DoorControl.class);
    }

    /** Whether the condition should follow the station's own door control setting. */
    public boolean isFollowStation() {
        return getDoorSide() == DoorControl.NONE;
    }

    // ==================== Runtime ====================

    @Override
    public boolean tickCompletion(Level level, Train train, CompoundTag context) {
        int time = context.getInt("Time");
        int totalTicks = totalWaitTicks();

        if (time >= totalTicks) {
            // Duration expired: close doors (all doors if following station)
            setDoorsOnTrain(train, level, false,
                    isFollowStation() ? DoorControl.ALL : getDoorSide());
            return true;
        }

        if (time == 0) {
            DoorControl side = isFollowStation()
                    ? resolveStationSide(train, level)
                    : getDoorSide();
            setDoorsOnTrain(train, level, true, side);
        }

        context.putInt("Time", time + 1);
        requestDisplayIfNecessary(context, time);
        return false;
    }

    // ==================== Persistence ====================

    @Override
    protected void writeAdditional(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        tag.putInt(DOOR_SIDE_KEY, getDoorSide().ordinal());
        super.writeAdditional(provider, tag);
    }

    @Override
    protected void readAdditional(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        if (tag.contains(DOOR_SIDE_KEY)) {
            data.putInt(DOOR_SIDE_KEY, tag.getInt(DOOR_SIDE_KEY));
        }
        super.readAdditional(provider, tag);
    }

    // ==================== GUI ====================

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(
                AllBlocks.TRAIN_DOOR.asStack(),
                Component.translatable("railway_operations.schedule.condition.door_control.summary",
                        formatDoorSideLabel(),
                        formatTime(true)));
    }

    @Override
    public List<Component> getTitleAs(String displayType) {
        return ImmutableList.of(
                Component.translatable(getId().getNamespace() + ".schedule." + displayType
                        + "." + getId().getPath())
                        .withStyle(ChatFormatting.GOLD),
                Component.translatable("railway_operations.schedule.condition.door_control.format",
                                formatDoorSideLabel(),
                                formatTime(false))
                        .withStyle(ChatFormatting.DARK_AQUA));
    }

    @Override
    public ItemStack getSecondLineIcon() {
        return AllBlocks.TRAIN_DOOR.asStack();
    }

    @Override
    public List<Component> getSecondLineTooltip(int slot) {
        return ImmutableList.of(
                Component.translatable("create.generic.duration"));
    }

    @Override
    public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
        // Layout: DoorSide(0-50) | Value(52-74) | TimeUnit(76-129)

        // 1) Door side selection — includes "Follow Station"
        builder.addSelectionScrollInput(0, 50, (selectionScroll, label) -> {
            List<Component> options = new ArrayList<>();
            for (DoorControl dc : DoorControl.values()) {
                if (dc == DoorControl.NONE) continue;
                options.add(Component.translatable(
                        "railway_operations.door_side." + dc.name().toLowerCase()));
            }
            options.add(Component.translatable("railway_operations.door_side.follow_station"));
            selectionScroll.forOptions(options);

            if (isFollowStation()) {
                selectionScroll.setState(options.size() - 1);
            } else {
                selectionScroll.setState(Math.min(getDoorSide().ordinal(), options.size() - 2));
            }
        }, DOOR_SIDE_KEY);

        // 2) Duration value scroll
        builder.addScrollInput(52, 22, (scrollInput, label) -> {
            scrollInput.titled(Component.translatable("create.generic.duration"))
                    .withShiftStep(15)
                    .withRange(0, 121);
        }, "Value");

        // 3) Time unit selection
        builder.addSelectionScrollInput(76, 53, (selectionScroll, label) -> {
            selectionScroll.forOptions(
                            com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                                    .translatedOptions())
                    .titled(Component.translatable("create.generic.timeUnit"));
        }, "TimeUnit");
    }

    // ==================== Station Side Resolution ====================

    /**
     * Reads the station's {@link DoorControlBehaviour} to get the configured
     * door side, falling back to ALL if the station cannot be queried.
     */
    private static DoorControl resolveStationSide(Train train, Level level) {
        GlobalStation station = train.getCurrentStation();
        if (station == null) return DoorControl.ALL;

        BlockPos pos = station.getBlockEntityPos();
        ResourceKey<Level> dim = station.getBlockEntityDimension();
        ServerLevel stationLevel = level.getServer().getLevel(dim);
        if (stationLevel == null || !stationLevel.isLoaded(pos)) return DoorControl.ALL;

        DoorControlBehaviour behaviour = BlockEntityBehaviour.get(
                stationLevel, pos, DoorControlBehaviour.TYPE);
        if (behaviour == null) return DoorControl.ALL;

        DoorControl mode = behaviour.mode;
        // NONE means "don't open anything" — follow that intent
        return mode == DoorControl.NONE ? DoorControl.ALL : mode;
    }

    // ==================== Display Formatting ====================

    private Component formatDoorSideLabel() {
        if (isFollowStation()) {
            return Component.translatable("railway_operations.door_side.follow_station");
        }
        return Component.translatable(
                "railway_operations.door_side." + getDoorSide().name().toLowerCase());
    }

    // ==================== Door Toggle Logic ====================

    private static void setDoorsOnTrain(Train train, Level level, boolean open, DoorControl doorSide) {
        if (doorSide == DoorControl.NONE) {
            return;
        }

        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) continue;

            Contraption contraption = getContraption(entity);
            if (contraption == null) continue;

            for (var entry : contraption.getBlocks().entrySet()) {
                BlockPos localPos = entry.getKey();
                StructureBlockInfo blockInfo = entry.getValue();
                BlockState state = blockInfo.state();

                if (!(state.getBlock() instanceof SlidingDoorBlock)) continue;
                if (state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;

                Direction worldFacing = getWorldFacing(entity, contraption, localPos, state);
                if (worldFacing == null) continue;

                if (doorSide != DoorControl.ALL && !doorSide.matches(worldFacing)) {
                    continue;
                }

                boolean isOpen = state.getValue(DoorBlock.OPEN);
                if (isOpen != open) {
                    toggleDoor(localPos, contraption, blockInfo);
                    level.playSound(null,
                            entity.blockPosition(),
                            getDoorSound(state.getBlock(), open),
                            SoundSource.BLOCKS, 1.0F, 1F);
                }
            }
        }
    }

    private static Direction getWorldFacing(AbstractContraptionEntity entity, Contraption contraption,
                                            BlockPos localPos, BlockState doorState) {
        if (!doorState.hasProperty(DoorBlock.FACING)) return null;

        Direction facing = doorState.getValue(DoorBlock.FACING);
        Direction positiveAxisDir = Direction.get(Direction.AxisDirection.POSITIVE, facing.getAxis());

        Vec3 center = contraption.bounds.getCenter();
        Vec3 doorCenter = Vec3.atCenterOf(localPos);
        Vec3 doorNormal = Vec3.atLowerCornerOf(facing.getNormal()).scale(-0.45);
        Vec3 doorFacePoint = doorCenter.add(doorNormal);
        Vec3 doorToCenter = doorFacePoint.subtract(center);

        if (positiveAxisDir.getAxis().choose(doorToCenter.x, doorToCenter.y, doorToCenter.z) < 0) {
            positiveAxisDir = positiveAxisDir.getOpposite();
        }

        Vec3 normal = Vec3.atLowerCornerOf(positiveAxisDir.getNormal());
        Vec3 rotated = entity.applyRotation(normal, 0);
        return Direction.getNearest(rotated.x, rotated.y, rotated.z);
    }

    private static void toggleDoor(BlockPos pos, Contraption contraption, StructureBlockInfo info) {
        BlockState newState = info.state().cycle(DoorBlock.OPEN);
        contraption.entity.setBlock(pos,
                new StructureBlockInfo(info.pos(), newState, info.nbt()));

        BlockPos otherPos = info.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                ? pos.above()
                : pos.below();

        StructureBlockInfo otherInfo = contraption.getBlocks().get(otherPos);
        if (otherInfo != null && otherInfo.state().hasProperty(DoorBlock.OPEN)) {
            BlockState otherNewState = otherInfo.state().cycle(DoorBlock.OPEN);
            contraption.entity.setBlock(otherPos,
                    new StructureBlockInfo(otherInfo.pos(), otherNewState, otherInfo.nbt()));
        }

        contraption.invalidateColliders();
    }

    // ==================== Reflection Bridges ====================

    private static Field contraptionField;

    static {
        try {
            contraptionField = AbstractContraptionEntity.class.getDeclaredField("contraption");
            contraptionField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to access AbstractContraptionEntity.contraption field", e);
        }
    }

    private static Contraption getContraption(AbstractContraptionEntity entity) {
        try {
            return (Contraption) contraptionField.get(entity);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    // ==================== Sound Logic ====================

    private static SoundEvent getDoorSound(Block doorBlock, boolean open) {
        String className = doorBlock.getClass().getName();
        if (className.startsWith("cn.autoforged.custom_train_door")) {
            try {
                if (className.contains("TarindoorBlock")) {
                    return getTarindoorSound(doorBlock, open);
                }
                Class<?> modSounds = Class.forName(
                        "cn.autoforged.custom_train_door.sound.ModSounds");
                String fieldName;
                if (className.contains("CRH2ADoorBlock")) {
                    fieldName = open ? "CRH2A_DOOR_OPEN" : "CRH2A_DOOR_CLOSE";
                } else {
                    fieldName = open ? "CR400BF_DOOR_OPEN" : "CR400BF_DOOR_CLOSE";
                }
                Field field = modSounds.getField(fieldName);
                Object holder = field.get(null);
                return (SoundEvent) holder.getClass().getMethod("get").invoke(holder);
            } catch (Exception ignored) {
            }
        }
        return open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE;
    }

    private static SoundEvent getTarindoorSound(Block doorBlock, boolean open) throws Exception {
        Object definition = doorBlock.getClass().getMethod("getDefinition").invoke(doorBlock);
        if (definition == null) return fallback(open);

        String id = (String) definition.getClass().getMethod("id").invoke(definition);

        Class<?> registry = Class.forName(
                "cn.autoforged.custom_train_door.tarindoor.TarindoorRegistry");
        String methodName = open ? "getOpenSound" : "getCloseSound";
        java.util.function.Supplier<?> supplier =
                (java.util.function.Supplier<?>) registry.getMethod(methodName, String.class)
                        .invoke(null, id);
        if (supplier == null) return fallback(open);

        return (SoundEvent) supplier.get();
    }

    private static SoundEvent fallback(boolean open) {
        return open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE;
    }
}
