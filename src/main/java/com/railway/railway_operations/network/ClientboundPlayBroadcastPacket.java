package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: play audio identified by hash at the given position.
 * radius controls attenuation distance on the client (default 16.0).
 */
public record ClientboundPlayBroadcastPacket(
        String hash,
        double posX, double posY, double posZ,
        int[] bogieIds,
        int delayTicks,
        double radius
) implements CustomPacketPayload {

    public static final Type<ClientboundPlayBroadcastPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "play_broadcast"));

    public static final StreamCodec<FriendlyByteBuf, ClientboundPlayBroadcastPacket> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundPlayBroadcastPacket::write, ClientboundPlayBroadcastPacket::new);

    public ClientboundPlayBroadcastPacket(FriendlyByteBuf f) {
        this(f.readUtf(), f.readDouble(), f.readDouble(), f.readDouble(),
                readIds(f), f.readVarInt(), f.readDouble());
    }

    private static int[] readIds(FriendlyByteBuf f) {
        int count = f.readVarInt();
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) ids[i] = f.readVarInt();
        return ids;
    }

    public void write(FriendlyByteBuf f) {
        f.writeUtf(hash);
        f.writeDouble(posX); f.writeDouble(posY); f.writeDouble(posZ);
        f.writeVarInt(bogieIds.length);
        for (int id : bogieIds) f.writeVarInt(id);
        f.writeVarInt(delayTicks);
        f.writeDouble(radius);
    }

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
