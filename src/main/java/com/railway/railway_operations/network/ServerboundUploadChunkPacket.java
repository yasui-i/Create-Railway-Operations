package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → Server: a chunk of an uploaded zip file. */
public record ServerboundUploadChunkPacket(
        String fileName,
        int chunkIndex,
        int totalChunks,
        byte[] data
) implements CustomPacketPayload {

    public static final Type<ServerboundUploadChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "upload_chunk"));

    public static final StreamCodec<ByteBuf, ServerboundUploadChunkPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ServerboundUploadChunkPacket::fileName,
                    ByteBufCodecs.VAR_INT, ServerboundUploadChunkPacket::chunkIndex,
                    ByteBufCodecs.VAR_INT, ServerboundUploadChunkPacket::totalChunks,
                    ByteBufCodecs.BYTE_ARRAY, ServerboundUploadChunkPacket::data,
                    ServerboundUploadChunkPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
