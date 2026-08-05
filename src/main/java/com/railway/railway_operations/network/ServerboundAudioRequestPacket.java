package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → Server: request audio data for a specific hash. */
public record ServerboundAudioRequestPacket(String hash) implements CustomPacketPayload {

    public static final Type<ServerboundAudioRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "audio_request"));

    public static final StreamCodec<ByteBuf, ServerboundAudioRequestPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerboundAudioRequestPacket::hash,
                    ServerboundAudioRequestPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
