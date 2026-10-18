package com.railway.railway_operations.network;

import com.railway.railway_operations.CreateRailwayOperations;
import com.railway.railway_operations.block.PlatformBroadcastBlockEntity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlatformBroadcastConfigPacket(BlockPos pos, CompoundTag config)
        implements CustomPacketPayload {

    public static final Type<PlatformBroadcastConfigPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "broadcast_config"));

    public static final StreamCodec<FriendlyByteBuf, PlatformBroadcastConfigPacket> STREAM_CODEC =
            StreamCodec.ofMember(PlatformBroadcastConfigPacket::write,
                    PlatformBroadcastConfigPacket::new);

    public PlatformBroadcastConfigPacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), buf.readNbt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeNbt(config);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(PlatformBroadcastConfigPacket pkt,
                               net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                var be = sp.level().getBlockEntity(pkt.pos);
                if (be instanceof PlatformBroadcastBlockEntity pbe) {
                    for (String key : pkt.config.getAllKeys()) {
                        pbe.getConfig().put(key, pkt.config.get(key));
                    }
                    pbe.setChanged();
                    sp.level().sendBlockUpdated(pkt.pos,
                            sp.level().getBlockState(pkt.pos),
                            sp.level().getBlockState(pkt.pos), 3);
                }
            }
        });
    }
}
