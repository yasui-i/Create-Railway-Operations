package com.railway.railway_operations.block;

import com.railway.railway_operations.CreateRailwayOperations;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> REGISTRY =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateRailwayOperations.MODID);

    public static final Supplier<BlockEntityType<PlatformBroadcastBlockEntity>> PLATFORM_BROADCAST =
            REGISTRY.register("platform_broadcast",
                    () -> BlockEntityType.Builder.of(
                            PlatformBroadcastBlockEntity::new,
                            CreateRailwayOperations.PLATFORM_BROADCAST.get()
                    ).build(null));
}
