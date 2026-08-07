package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Server → Client during configuration: announces an audio bundle transfer. */
public record AudioSyncStartPayload(
        UUID syncId,
        int totalBytes,
        int chunkCount,
        String sha256
) implements CustomPacketPayload {

    public static final Type<AudioSyncStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "audio_sync_start"));

    public static final StreamCodec<FriendlyByteBuf, AudioSyncStartPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    AudioSyncStartPayload::write,
                    AudioSyncStartPayload::new);

    private AudioSyncStartPayload(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(syncId);
        buf.writeVarInt(totalBytes);
        buf.writeVarInt(chunkCount);
        buf.writeUtf(sha256, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
