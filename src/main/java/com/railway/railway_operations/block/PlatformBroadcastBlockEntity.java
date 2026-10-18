package com.railway.railway_operations.block;

import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.AudioPack;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlatformBroadcastBlockEntity extends BlockEntity {

    private static final String PACK_KEY = "AudioPack";
    private static final String BROADCAST_KEY = "Broadcast";
    private static final String PACK_ID_KEY = "AudioPackId";
    private static final String BROADCAST_ID_KEY = "BroadcastId";
    private static final String DELAY_KEY = "Delay";
    private static final String DELAY_UNIT_KEY = "DelayUnit";
    private static final String RADIUS_KEY = "Radius";
    private static final String CYCLE_KEY = "CycleInterval";

    private final CompoundTag config = new CompoundTag();
    private boolean lastPowered = false;
    private int cycleTicks = 0;

    public PlatformBroadcastBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLATFORM_BROADCAST.get(), pos, state);
        config.putInt(PACK_KEY, 0);
        config.putInt(BROADCAST_KEY, 0);
        config.putInt(DELAY_KEY, 0);
        config.putInt(DELAY_UNIT_KEY, 0);
        config.putDouble(RADIUS_KEY, 16.0);
        config.putInt(CYCLE_KEY, 0);
    }

    public double getRadius() {
        double r = config.getDouble(RADIUS_KEY);
        return r <= 0 ? 16.0 : r;
    }
    public void setRadius(double r) { config.putDouble(RADIUS_KEY, r); setChanged(); }

    public int getCycleInterval() { return Math.max(0, config.getInt(CYCLE_KEY)); }
    public void setCycleInterval(int v) { config.putInt(CYCLE_KEY, v); setChanged(); }

    /** Called each tick by the block. Only cycles while redstone is powered. */
    public static void tick(Level level, BlockPos pos, BlockState state, PlatformBroadcastBlockEntity be) {
        int interval = be.getCycleInterval();
        if (interval <= 0) return;
        if (!level.hasNeighborSignal(pos)) {
            be.cycleTicks = 0; // reset when unpowered
            return;
        }
        be.cycleTicks++;
        if (be.cycleTicks >= interval * 20L) {
            be.cycleTicks = 0;
            be.playBroadcast();
        }
    }

    // ---- data access ----

    public CompoundTag getConfig() { return config; }

    public int getPackIndex() { return config.getInt(PACK_KEY); }
    public void setPackIndex(int idx) {
        config.putInt(PACK_KEY, idx);
        AudioPack pack = getPackByIndex(idx);
        if (pack != null) config.putString(PACK_ID_KEY, pack.id());
        setChanged();
    }

    public int getBroadcastIndex() { return config.getInt(BROADCAST_KEY); }
    public void setBroadcastIndex(int idx) {
        config.putInt(BROADCAST_KEY, idx);
        AudioPack.BroadcastDef def = getBroadcastDef(getPack());
        if (def != null) config.putString(BROADCAST_ID_KEY, getBroadcastKey(getPack(), def));
        setChanged();
    }

    public int getDelayTicks() {
        int val = config.getInt(DELAY_KEY);
        var unit = com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                .values()[Math.min(config.getInt(DELAY_UNIT_KEY), 2)];
        return val * unit.ticksPer;
    }

    public int getDelayValue() { return config.getInt(DELAY_KEY); }
    public void setDelayValue(int v) { config.putInt(DELAY_KEY, v); setChanged(); }
    public int getDelayUnit() { return config.getInt(DELAY_UNIT_KEY); }
    public void setDelayUnit(int u) { config.putInt(DELAY_UNIT_KEY, u); setChanged(); }

    // ---- playback ----

    /** Called from GUI "play test" button or redstone. */
    public void playNow() {
        playBroadcast();
    }

    // ---- redstone ----

    public void onNeighborPowered(boolean powered) {
        if (powered && !lastPowered) {
            playBroadcast();
        }
        lastPowered = powered;
    }

    private void playBroadcast() {
        Level level = this.level;
        if (level == null || level.isClientSide) return;

        AudioPack pack = resolvePack();
        if (pack == null) return;
        AudioPack.BroadcastDef def = getBroadcastDef(pack);
        if (def == null) return;

        int delayTicks = getDelayTicks();
        double radius = getRadius();
        double radiusSq = radius * radius;
        for (var player : level.players()) {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) continue;
            if (sp.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                    worldPosition.getZ() + 0.5) > radiusSq) continue;

            if (def.isSimple()) {
                sendToPlayer(pack, def.fileName(), sp, delayTicks, radius);
            } else {
                for (String lang : new String[]{"zh"}) {
                    String tpl = "zh".equals(lang) ? def.zhTemplate() : def.enTemplate();
                    if (tpl != null) sendToPlayer(pack, lang + "/" + tpl, sp, delayTicks, radius);
                }
            }
        }
    }

    private void sendToPlayer(AudioPack pack, String filePath,
                               net.minecraft.server.level.ServerPlayer sp, int delayTicks, double radius) {
        java.nio.file.Path file = pack.directory().resolve(filePath);
        String hash = com.railway.railway_operations.audio.AudioHashRegistry.getHash(file);
        if (hash == null) return;

        double cx = worldPosition.getX() + 0.5;
        double cy = worldPosition.getY() + 0.5;
        double cz = worldPosition.getZ() + 0.5;

        var packet = new com.railway.railway_operations.network.ClientboundPlayBroadcastPacket(
                hash, cx, cy, cz, new int[0], delayTicks, radius);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, packet);
    }

    private AudioPack resolvePack() {
        String packId = config.getString(PACK_ID_KEY);
        if (!packId.isEmpty()) {
            AudioPack pack = AudioManager.getPack(packId);
            if (pack != null) return pack;
        }
        int idx = config.getInt(PACK_KEY);
        return getPackByIndex(idx);
    }

    private AudioPack.BroadcastDef getBroadcastDef(AudioPack pack) {
        if (pack == null || pack.broadcasts().isEmpty()) return null;
        String broadcastId = config.getString(BROADCAST_ID_KEY);
        if (!broadcastId.isEmpty()) {
            AudioPack.BroadcastDef def = pack.broadcasts().get(broadcastId);
            if (def != null) return def;
        }
        int idx = config.getInt(BROADCAST_KEY);
        if (idx < 0 || idx >= pack.broadcasts().size()) return null;
        int i = 0;
        for (var def : pack.broadcasts().values()) {
            if (i++ == idx) return def;
        }
        return null;
    }

    private AudioPack getPack() {
        return resolvePack();
    }

    private AudioPack getPackByIndex(int idx) {
        int i = 0;
        for (AudioPack pack : AudioManager.getPacks().values()) {
            if (i++ == idx) return pack;
        }
        return null;
    }

    private String getBroadcastKey(AudioPack pack, AudioPack.BroadcastDef target) {
        if (pack == null) return "";
        for (Map.Entry<String, AudioPack.BroadcastDef> entry : pack.broadcasts().entrySet()) {
            if (entry.getValue() == target) return entry.getKey();
        }
        return "";
    }

    // ---- sync ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("BroadcastConfig", config);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag saved = tag.getCompound("BroadcastConfig");
        for (String key : saved.getAllKeys()) config.put(key, saved.get(key));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("BroadcastConfig", config);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
