package com.railway.railway_operations;

import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.ClientAudioPlayer;
import com.railway.railway_operations.network.AudioClientSync;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@Mod(value = CreateRailwayOperations.MODID, dist = Dist.CLIENT)
public class CreateRailwayOperationsClient {

    public CreateRailwayOperationsClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(this::onClientSetup);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onClientTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onClientLoggingOut);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onPlatformBroadcastUse);
    }
    void onClientSetup(FMLClientSetupEvent event) {
        AudioManager.reload();
        CreateRailwayOperations.LOGGER.info("Railway Operations client loaded");
    }
    void onClientTick(ClientTickEvent.Post event) {
        ClientAudioPlayer.tick();
    }
    void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAudioPlayer.stopAll();
        AudioClientSync.onLoggingOut();
    }
    void onPlatformBroadcastUse(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()
                || !(event.getLevel().getBlockEntity(event.getPos())
                instanceof com.railway.railway_operations.block.PlatformBroadcastBlockEntity blockEntity)) {
            return;
        }

        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.railway.railway_operations.block.PlatformBroadcastScreen(
                        event.getPos(), blockEntity.getConfig()));
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
    }

}
