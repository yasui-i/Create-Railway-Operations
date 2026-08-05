package com.railway.railway_operations.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.railway.railway_operations.CreateRailwayOperations;
import com.railway.railway_operations.audio.AudioHashRegistry;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client on login: syncs the full hash → filePath registry.
 */
public record ClientboundAudioSyncPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(String hash, String filePath) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Entry::hash,
                        ByteBufCodecs.STRING_UTF8, Entry::filePath,
                        Entry::new);
    }

    public static final Type<ClientboundAudioSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    CreateRailwayOperations.MODID, "audio_sync"));

    public static final StreamCodec<ByteBuf, ClientboundAudioSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    ClientboundAudioSyncPacket::entries,
                    ClientboundAudioSyncPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static ClientboundAudioSyncPacket build() {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : AudioHashRegistry.getAllData().entrySet()) {
            var path = AudioHashRegistry.getPath(e.getKey());
            if (path != null) {
                list.add(new Entry(e.getKey(), path.getFileName().toString()));
            }
        }
        return new ClientboundAudioSyncPacket(list);
    }
}
