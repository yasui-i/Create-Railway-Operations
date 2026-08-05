package com.railway.railway_operations;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.railway.railway_operations.audio.AudioHashRegistry;
import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.block.GhostSeatBlock;
import com.railway.railway_operations.broadcast.BroadcastCondition;
import com.railway.railway_operations.command.RailwayCommand;
import com.railway.railway_operations.condition.DoorControlCondition;
import com.railway.railway_operations.network.ClientboundAudioDataPacket;
import com.railway.railway_operations.network.ClientboundAudioSyncPacket;
import com.railway.railway_operations.network.ClientboundPlayBroadcastPacket;
import com.railway.railway_operations.network.ServerboundAudioRequestPacket;
import com.railway.railway_operations.network.ServerboundUploadChunkPacket;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.actors.seat.SeatInteractionBehaviour;
import com.simibubi.create.content.contraptions.actors.seat.SeatMovementBehaviour;
import com.simibubi.create.content.trains.schedule.Schedule;

import net.createmod.catnip.data.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CreateRailwayOperations.MODID)
public class CreateRailwayOperations {

    public static final String MODID = "railway_operations";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<GhostSeatBlock> GHOST_SEAT =
            BLOCKS.register("ghost_seat", GhostSeatBlock::new);
    public static final DeferredItem<BlockItem> GHOST_SEAT_ITEM =
            ITEMS.registerSimpleBlockItem("ghost_seat", GHOST_SEAT);

    public CreateRailwayOperations(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerNetwork);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent e) -> RailwayCommand.register(e.getDispatcher()));
        modEventBus.addListener(this::addCreative);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        AudioManager.reload();
        event.enqueueWork(() -> {
            Schedule.CONDITION_TYPES.add(Pair.of(
                    ResourceLocation.fromNamespaceAndPath(MODID, "door_control"),
                    DoorControlCondition::new));
            Schedule.CONDITION_TYPES.add(Pair.of(
                    ResourceLocation.fromNamespaceAndPath(MODID, "broadcast"),
                    BroadcastCondition::new));
            LOGGER.info("Registered schedule conditions");

            GhostSeatBlock block = GHOST_SEAT.get();
            MovingInteractionBehaviour.REGISTRY.register(block, new SeatInteractionBehaviour());
            MovementBehaviour.REGISTRY.register(block, new SeatMovementBehaviour());
            LOGGER.info("Registered seat behaviours for GhostSeatBlock");
        });
    }

    private void registerNetwork(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID).versioned("1");
        registrar.playToClient(
                ClientboundPlayBroadcastPacket.TYPE,
                ClientboundPlayBroadcastPacket.STREAM_CODEC,
                ClientboundPlayBroadcastPacket::handle);
        registrar.playToClient(
                ClientboundAudioSyncPacket.TYPE,
                ClientboundAudioSyncPacket.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    // Client stores hash→filePath mapping
                    for (var e : p.entries()) {
                        com.railway.railway_operations.audio.ClientAudioCache
                                .registerHash(e.hash(), e.filePath());
                    }
                }));
        registrar.playToClient(
                ClientboundAudioDataPacket.TYPE,
                ClientboundAudioDataPacket.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    com.railway.railway_operations.audio.ClientAudioCache
                            .put(p.hash(), p.data());
                    com.railway.railway_operations.network.BroadcastPacketHandler
                            .onDataReceived(p.hash());
                }));
        registrar.playToServer(
                ServerboundAudioRequestPacket.TYPE,
                ServerboundAudioRequestPacket.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    byte[] data = AudioHashRegistry.getData(p.hash());
                    if (data != null && ctx.player() != null) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                (net.minecraft.server.level.ServerPlayer) ctx.player(),
                                new ClientboundAudioDataPacket(p.hash(), data));
                    }
                }));
        registrar.playToServer(
                ServerboundUploadChunkPacket.TYPE,
                ServerboundUploadChunkPacket.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        com.railway.railway_operations.command.RailwayCommand
                                .handleChunk(p, sp);
                    }
                }));
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(GHOST_SEAT_ITEM);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Create: Railway Operations loaded");
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            var packet = ClientboundAudioSyncPacket.build();
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, packet);
            LOGGER.info("Synced {} audio hashes to {}", packet.entries().size(), sp.getName().getString());
        }
    }
}
