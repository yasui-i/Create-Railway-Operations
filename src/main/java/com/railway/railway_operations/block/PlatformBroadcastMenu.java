package com.railway.railway_operations.block;

import com.railway.railway_operations.CreateRailwayOperations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class PlatformBroadcastMenu extends AbstractContainerMenu {

    public static final DeferredRegister<MenuType<?>> REGISTRY =
            DeferredRegister.create(Registries.MENU, CreateRailwayOperations.MODID);

    public static final Supplier<MenuType<PlatformBroadcastMenu>> TYPE =
            REGISTRY.register("platform_broadcast",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension
                            .create(PlatformBroadcastMenu::new));

    final BlockPos pos;
    final PlatformBroadcastBlockEntity blockEntity;

    /** Simple struct to pass block pos from server to client-side constructor */
    public static class LastPos { public static BlockPos pos = BlockPos.ZERO; }

    /** Client-side constructor */
    public PlatformBroadcastMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        super(TYPE.get(), id);
        this.pos = LastPos.pos;
        this.blockEntity = inv.player.level().getBlockEntity(pos)
                instanceof PlatformBroadcastBlockEntity be ? be : null;
    }

    /** Server-side: direct constructor */
    public PlatformBroadcastMenu(int id, BlockPos pos, PlatformBroadcastBlockEntity be) {
        super(TYPE.get(), id);
        this.pos = pos;
        this.blockEntity = be;
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) { return true; }
}
