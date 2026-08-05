package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → Client: raw audio data for a requested hash. */
public record ClientboundAudioDataPacket(String hash, byte[] data) implements CustomPacketPayload {

    public static final Type<ClientboundAudioDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "audio_data"));

    public static final StreamCodec<ByteBuf, ClientboundAudioDataPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundAudioDataPacket::hash,
                    ByteBufCodecs.BYTE_ARRAY, ClientboundAudioDataPacket::data,
                    ClientboundAudioDataPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
