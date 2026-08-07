package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Client → Server during configuration: acknowledges completion of audio bundle sync. */
public record AudioSyncAckPayload(
        UUID syncId
) implements CustomPacketPayload {

    public static final Type<AudioSyncAckPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "audio_sync_ack"));

    public static final StreamCodec<FriendlyByteBuf, AudioSyncAckPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    AudioSyncAckPayload::write,
                    AudioSyncAckPayload::new);

    private AudioSyncAckPayload(FriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(syncId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
