package com.railway.railway_operations;

import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.ClientAudioPlayer;
import com.railway.railway_operations.command.RailwayCommand;

import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = CreateRailwayOperations.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateRailwayOperations.MODID, value = Dist.CLIENT)
public class CreateRailwayOperationsClient {

    public CreateRailwayOperationsClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(
                (RegisterClientCommandsEvent e) -> e.getDispatcher().register(
                        Commands.literal("railway")
                                .then(Commands.literal("upload")
                                        .executes(ctx -> {
                                            RailwayCommand.uploadAllFromClient();
                                            return 1;
                                        }))));
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
}
