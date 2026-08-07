package com.railway.railway_operations;

import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.ClientAudioPlayer;
import com.railway.railway_operations.network.AudioClientSync;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = CreateRailwayOperations.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateRailwayOperations.MODID, value = Dist.CLIENT)
public class CreateRailwayOperationsClient {

    public CreateRailwayOperationsClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        AudioManager.reload();
        CreateRailwayOperations.LOGGER.info("Railway Operations client loaded");
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        ClientAudioPlayer.tick();
    }

    /** Clean up sync state when disconnecting from a server. */
    @SubscribeEvent
    static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AudioClientSync.onLoggingOut();
    }
}
