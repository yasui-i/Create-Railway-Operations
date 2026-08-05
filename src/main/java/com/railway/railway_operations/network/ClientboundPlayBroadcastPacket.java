package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.resources.ResourceLocation;

/** Server → Client: play audio identified by hash at the given entity. */
public record ClientboundPlayBroadcastPacket(
        String hash,
        int entityId,
        int delayTicks
) implements CustomPacketPayload {

    public static final Type<ClientboundPlayBroadcastPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "play_broadcast"));

    public static final StreamCodec<ByteBuf, ClientboundPlayBroadcastPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClientboundPlayBroadcastPacket::hash,
                    ByteBufCodecs.VAR_INT, ClientboundPlayBroadcastPacket::entityId,
                    ByteBufCodecs.VAR_INT, ClientboundPlayBroadcastPacket::delayTicks,
                    ClientboundPlayBroadcastPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ClientboundPlayBroadcastPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> clz = Class.forName(
                        "com.railway.railway_operations.network.BroadcastPacketHandler");
                clz.getMethod("handle", ClientboundPlayBroadcastPacket.class)
                        .invoke(null, packet);
            } catch (Exception ignored) {}
        });
    }
}
