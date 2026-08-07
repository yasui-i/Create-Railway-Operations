package com.railway.railway_operations.broadcast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.AudioPack;
import com.railway.railway_operations.network.ClientboundPlayBroadcastPacket;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;

import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Schedule condition that plays a broadcast on all carriages
 * after an optional delay. Completes immediately after playing.
 */
public class BroadcastCondition extends ScheduleWaitCondition {

    private static final String PACK_KEY = "AudioPack";       // int index
    private static final String BROADCAST_KEY = "Broadcast";  // int index
    private static final String DELAY_KEY = "Delay";           // int value
    private static final String DELAY_UNIT_KEY = "DelayUnit";  // TimeUnit ordinal

    public BroadcastCondition() {
        super();
        data.putInt(PACK_KEY, 0);
        data.putInt(BROADCAST_KEY, 0);
        data.putInt(DELAY_KEY, 0);
        data.putInt(DELAY_UNIT_KEY, 0); // SECONDS
    }

    private int getDelaySeconds() {
        int val = data.getInt(DELAY_KEY);
        var unit = com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                .values()[Math.min(data.getInt(DELAY_UNIT_KEY), 2)];
        return val * (unit.ticksPer / 20);
    }

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath("railway_operations", "broadcast");
    }

    // ---- Resolve indices at runtime ----

    private AudioPack resolvePack() {
        int idx = data.getInt(PACK_KEY);
        int i = 0;
        for (AudioPack pack : AudioManager.getPacks().values()) {
            if (i++ == idx) return pack;
        }
        return null;
    }

    private String resolveBroadcastKey(AudioPack pack) {
        if (pack == null || pack.broadcasts().isEmpty()) return "";
        int idx = data.getInt(BROADCAST_KEY);
        int i = 0;
        for (String key : pack.broadcasts().keySet()) {
            if (i++ == idx) return key;
        }
        return pack.broadcasts().keySet().iterator().next();
    }

    private String getDisplayPackName() {
        AudioPack pack = resolvePack();
        return pack != null ? pack.name() : "(no pack)";
    }

    private String getDisplayBroadcastKey() {
        AudioPack pack = resolvePack();
        if (pack == null) return "...";
        String key = resolveBroadcastKey(pack);
        return key.isEmpty() ? "..." : key;
    }

    // ---- Tick ----

    @Override
    public boolean tickCompletion(Level level, Train train, CompoundTag context) {
        // Fire broadcast only once per schedule pass.
        // Create may call tickCompletion multiple times before advancing,
        // so track via context to prevent repeated audio spam.
        if (context.getBoolean("BroadcastFired")) {
            return true; // already fired, just complete
        }
        context.putBoolean("BroadcastFired", true);
        playBroadcastOnTrain(train, level, getDelaySeconds() * 20);
        return true;
    }

    private void playBroadcastOnTrain(Train train, Level level, int delayTicks) {
        if (level.isClientSide) return;
        AudioPack pack = resolvePack();
        if (pack == null) return;
        AudioPack.BroadcastDef def = pack.broadcasts().get(resolveBroadcastKey(pack));
        if (def == null) return;

        // Only send from the first available carriage — sending from every carriage
        // would cause duplicate broadcasts (echo) for players tracking multiple carriages.
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) continue;

            if (def.isSimple()) {
                sendPacket(pack, def.fileName(), entity, delayTicks);
            } else {
                for (String lang : new String[]{"zh"}) {
                    String tpl = "zh".equals(lang) ? def.zhTemplate() : def.enTemplate();
                    if (tpl != null) sendPacket(pack, lang + "/" + tpl, entity, delayTicks);
                    if (def.hasStation()) {
                        String sid = resolveStationId(train);
                        if (sid != null) sendPacket(pack,
                                lang + "/stations/" + sid + ".ogg", entity, delayTicks);
                    }
                }
            }
            break; // one carriage is enough — prevents echo
        }
    }

    private void sendPacket(AudioPack pack, String filePath,
                             CarriageContraptionEntity entity, int delayTicks) {
        java.nio.file.Path file = pack.directory().resolve(filePath);
        String hash = com.railway.railway_operations.audio.AudioHashRegistry.getHash(file);
        if (hash == null) return;
        var packet = new ClientboundPlayBroadcastPacket(hash, entity.getId(), delayTicks);
        net.neoforged.neoforge.network.PacketDistributor
                .sendToPlayersTrackingEntity(entity, packet);
    }

    private String resolveStationId(Train train) {
        var s = train.getCurrentStation();
        return s != null ? s.name : null;
    }

    /** Truncate a string to maxLen characters, appending "…" if cut. */
    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    // ---- GUI ----

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(new ItemStack(Items.NOTE_BLOCK),
                Component.literal(getDisplayBroadcastKey()
                        + " [" + getDisplayPackName() + "]"
                        + delaySuffix()));
    }

    @Override
    public List<Component> getTitleAs(String displayType) {
        return ImmutableList.of(
                Component.translatable("railway_operations.schedule.condition.broadcast")
                        .withStyle(ChatFormatting.GOLD),
                Component.literal(getDisplayPackName() + " - " + getDisplayBroadcastKey()
                        + delaySuffix())
                        .withStyle(ChatFormatting.DARK_AQUA));
    }

    private String delaySuffix() {
        int val = data.getInt(DELAY_KEY);
        if (val <= 0) return "";
        var unit = com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition.TimeUnit
                .values()[Math.min(data.getInt(DELAY_UNIT_KEY), 2)];
        return " +" + val + unit.suffix;
    }

    @Override
    public ItemStack getSecondLineIcon() {
        return new ItemStack(Items.NOTE_BLOCK);
    }

    @Override
    public List<Component> getSecondLineTooltip(int slot) {
        return ImmutableList.of();
    }

    @Override
    public MutableComponent getWaitingStatus(Level level, Train train, CompoundTag context) {
        return Component.literal(getDisplayBroadcastKey());
    }

    @Override
    public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
        // Layout: Pack(0,38) | Broadcast(40,42) | Delay(83,20) | "s"
        Map<String, AudioPack> packs = AudioManager.getPacks();

        List<String> packNames = new ArrayList<>();
        for (Map.Entry<String, AudioPack> e : packs.entrySet())
            packNames.add(e.getValue().name());
        if (packNames.isEmpty()) packNames.add("(no packs)");
        List<String> packNamesF = packNames;

        builder.addSelectionScrollInput(0, 32, (sel, label) -> {
            sel.forOptions(packNamesF.stream()
                    .map(s -> Component.literal(s)).toList());
            sel.setState(Math.min(data.getInt(PACK_KEY), packNamesF.size() - 1));
            sel.format(idx -> idx >= 0 && idx < packNamesF.size()
                    ? Component.literal(truncate(packNamesF.get(idx), 4))
                    : Component.literal("..."));
            sel.titled(Component.translatable("railway_operations.gui.select_pack"));
        }, PACK_KEY);

        builder.addSelectionScrollInput(34, 38, (sel, label) -> {
            AudioPack pack = resolvePack();
            List<String> bcNames = new ArrayList<>();
            if (pack != null) bcNames.addAll(pack.broadcasts().keySet());
            if (bcNames.isEmpty()) bcNames.add("...");
            List<String> bcNamesF = bcNames;
            sel.forOptions(bcNamesF.stream()
                    .map(s -> Component.literal(s)).toList());  // full names in tooltip
            sel.setState(Math.min(data.getInt(BROADCAST_KEY), bcNamesF.size() - 1));
            sel.format(idx -> idx >= 0 && idx < bcNamesF.size()
                    ? Component.literal(truncate(bcNamesF.get(idx), 4))
                    : Component.literal("..."));
            sel.titled(Component.translatable("railway_operations.gui.select_broadcast"));
        }, BROADCAST_KEY);

        builder.addScrollInput(73, 16, (scroll, label) -> {
            scroll.withRange(0, 60).titled(Component.translatable("railway_operations.gui.delay_value"));
            scroll.setState(data.getInt(DELAY_KEY));
        }, DELAY_KEY);

        builder.addSelectionScrollInput(91, 30, (sel, label) -> {
            sel.forOptions(com.simibubi.create.content.trains.schedule.condition
                    .TimedWaitCondition.TimeUnit.translatedOptions());
            sel.setState(Math.min(data.getInt(DELAY_UNIT_KEY), 2));
        }, DELAY_UNIT_KEY);
    }

    // ---- Persistence ----

    @Override
    protected void writeAdditional(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        tag.putInt(PACK_KEY, data.getInt(PACK_KEY));
        tag.putInt(BROADCAST_KEY, data.getInt(BROADCAST_KEY));
        tag.putInt(DELAY_KEY, data.getInt(DELAY_KEY));
        tag.putInt(DELAY_UNIT_KEY, data.getInt(DELAY_UNIT_KEY));
        super.writeAdditional(provider, tag);
    }

    @Override
    protected void readAdditional(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        if (tag.contains(PACK_KEY)) data.putInt(PACK_KEY, tag.getInt(PACK_KEY));
        if (tag.contains(BROADCAST_KEY)) data.putInt(BROADCAST_KEY, tag.getInt(BROADCAST_KEY));
        if (tag.contains(DELAY_KEY)) data.putInt(DELAY_KEY, tag.getInt(DELAY_KEY));
        if (tag.contains(DELAY_UNIT_KEY)) data.putInt(DELAY_UNIT_KEY, tag.getInt(DELAY_UNIT_KEY));
        super.readAdditional(provider, tag);
    }
}
