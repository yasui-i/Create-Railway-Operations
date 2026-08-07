package com.railway.railway_operations;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.block.GhostSeatBlock;
import com.railway.railway_operations.broadcast.BroadcastCondition;
import com.railway.railway_operations.command.RailwayCommand;
import com.railway.railway_operations.condition.DoorControlCondition;
import com.railway.railway_operations.network.AudioSyncAckPayload;
import com.railway.railway_operations.network.AudioSyncChunkPayload;
import com.railway.railway_operations.network.AudioSyncStartPayload;
import com.railway.railway_operations.network.ClientboundAudioDataPacket;
import com.railway.railway_operations.network.ClientboundPlayBroadcastPacket;
import com.railway.railway_operations.network.ServerboundAudioRequestPacket;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.actors.seat.SeatInteractionBehaviour;
import com.simibubi.create.content.contraptions.actors.seat.SeatMovementBehaviour;
import com.simibubi.create.content.trains.schedule.Schedule;

import java.util.function.Consumer;

import net.createmod.catnip.data.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
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
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CreateRailwayOperations.MODID)
public class CreateRailwayOperations {

    public static final String MODID = "railway_operations";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final ConfigurationTask.Type AUDIO_SYNC_TASK =
            new ConfigurationTask.Type(MODID + ":audio_sync");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<GhostSeatBlock> GHOST_SEAT =
            BLOCKS.register("ghost_seat", GhostSeatBlock::new);
    public static final DeferredItem<BlockItem> GHOST_SEAT_ITEM =
            ITEMS.registerSimpleBlockItem("ghost_seat", GHOST_SEAT);

    public CreateRailwayOperations(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerNetwork);
        modEventBus.addListener(this::registerConfigurationTasks);

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

    // ---- Configuration-phase audio sync ----

    private void registerConfigurationTasks(RegisterConfigurationTasksEvent event) {
        // Skip for integrated server (single player)
        if (event.getListener().getConnection().isMemoryConnection()) {
            LOGGER.debug("Skipping audio synchronization for the integrated server");
            return;
        }
        AudioManager.Transfer transfer = AudioManager.createTransfer();
        if (transfer.isError()) {
            LOGGER.error("Audio sync preparation failed: {}", transfer.error());
            return;
        }
        event.register(new AudioSyncTask(transfer));
    }

    private static class AudioSyncTask implements ConfigurationTask {
        private final AudioManager.Transfer transfer;

        AudioSyncTask(AudioManager.Transfer transfer) {
            this.transfer = transfer;
        }

        @Override
        public void start(Consumer<Packet<?>> sender) {
            byte[] bundle = transfer.bundle();
            int totalBytes = bundle.length;
            int chunkCount = (totalBytes + AudioSyncChunkPayload.MAX_CHUNK_BYTES - 1)
                    / AudioSyncChunkPayload.MAX_CHUNK_BYTES;

            // Send start packet
            sender.accept(new ClientboundCustomPayloadPacket(
                    new AudioSyncStartPayload(
                            transfer.id(), totalBytes, chunkCount, transfer.sha256())));

            // Send chunks
            for (int i = 0; i < chunkCount; i++) {
                int start = i * AudioSyncChunkPayload.MAX_CHUNK_BYTES;
                int end = Math.min(start + AudioSyncChunkPayload.MAX_CHUNK_BYTES, totalBytes);
                byte[] chunk = new byte[end - start];
                System.arraycopy(bundle, start, chunk, 0, chunk.length);
                sender.accept(new ClientboundCustomPayloadPacket(
                        new AudioSyncChunkPayload(transfer.id(), i, chunk)));
            }
        }

        @Override
        public Type type() {
            return AUDIO_SYNC_TASK;
        }
    }

    // ---- Network registration ----

    private void registerNetwork(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID).versioned("1");

        // Audio sync: configuration phase (initial join) + play phase (runtime /railway sync)
        // Use lambdas (invokedynamic) so client classes are only loaded when invoked on the client
        registrar.configurationToClient(
                AudioSyncStartPayload.TYPE,
                AudioSyncStartPayload.STREAM_CODEC,
                (p, ctx) -> com.railway.railway_operations.network.AudioClientSync
                        .handleStart(p, ctx));
        registrar.configurationToClient(
                AudioSyncChunkPayload.TYPE,
                AudioSyncChunkPayload.STREAM_CODEC,
                (p, ctx) -> com.railway.railway_operations.network.AudioClientSync
                        .handleChunk(p, ctx));
        // Register same packets for play-phase (runtime /railway sync command)
        registrar.playToClient(
                AudioSyncStartPayload.TYPE,
                AudioSyncStartPayload.STREAM_CODEC,
                (p, ctx) -> com.railway.railway_operations.network.AudioClientSync
                        .handleStart(p, ctx));
        registrar.playToClient(
                AudioSyncChunkPayload.TYPE,
                AudioSyncChunkPayload.STREAM_CODEC,
                (p, ctx) -> com.railway.railway_operations.network.AudioClientSync
                        .handleChunk(p, ctx));
        // Client → server acknowledgement
        registrar.configurationToServer(
                AudioSyncAckPayload.TYPE,
                AudioSyncAckPayload.STREAM_CODEC,
                (p, ctx) -> {
                    LOGGER.info("Client acknowledged audio synchronization {}", p.syncId());
                    ctx.finishCurrentTask(AUDIO_SYNC_TASK);
                });

        // Play phase: server → client broadcast trigger
        registrar.playToClient(
                ClientboundPlayBroadcastPacket.TYPE,
                ClientboundPlayBroadcastPacket.STREAM_CODEC,
                ClientboundPlayBroadcastPacket::handle);

        // Play phase: fallback lazy-load (if a client missed some chunks during sync)
        registrar.playToServer(
                ServerboundAudioRequestPacket.TYPE,
                ServerboundAudioRequestPacket.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    byte[] data = com.railway.railway_operations.audio.AudioHashRegistry.getData(p.hash());
                    if (data != null && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        PacketDistributor.sendToPlayer(sp,
                                new ClientboundAudioDataPacket(p.hash(), data));
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
}
