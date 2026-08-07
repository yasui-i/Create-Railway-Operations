package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Server → Client during configuration: a chunk of the audio bundle. */
public record AudioSyncChunkPayload(
        UUID syncId,
        int index,
        byte[] data
) implements CustomPacketPayload {

    public static final int MAX_CHUNK_BYTES = 921_600; // ~900 KiB, matches custom-train-door

    public static final Type<AudioSyncChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "audio_sync_chunk"));

    public static final StreamCodec<FriendlyByteBuf, AudioSyncChunkPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    AudioSyncChunkPayload::write,
                    AudioSyncChunkPayload::new);

    private AudioSyncChunkPayload(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readVarInt(), buf.readByteArray(MAX_CHUNK_BYTES));
    }

    private void write(FriendlyByteBuf buf) {
        if (data.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException(
                    "Audio sync chunk exceeds " + MAX_CHUNK_BYTES + " bytes");
        }
        buf.writeUUID(syncId);
        buf.writeVarInt(index);
        buf.writeByteArray(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
