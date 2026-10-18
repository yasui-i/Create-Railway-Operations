package com.railway.railway_operations.block;

import com.railway.railway_operations.audio.AudioManager;
import com.railway.railway_operations.audio.AudioPack;
import com.railway.railway_operations.network.PlatformBroadcastConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;

public class PlatformBroadcastScreen extends Screen {
    private static final int W = 220, H = 175, ROW = 22;
    private final BlockPos pos;
    private final CompoundTag config;
    private final List<AudioPack> packs;
    private int left, top;

    public PlatformBroadcastScreen(BlockPos pos, CompoundTag config) {
        super(Component.literal("站台广播器"));
        this.pos = pos;
        this.config = config;
        this.packs = new ArrayList<>(AudioManager.getPacks().values());
        migrateSelectionIds();
    }

    protected void init() { left = (width - W) / 2; top = (height - H) / 2; }

    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int d = -(int) Math.signum(sy);
        if (hit(my, 20)) { shiftPack(d); return true; }
        if (hit(my, 52)) { shiftBroadcast(d); return true; }
        if (hit(my, 84) && mx < left + W / 2 + 10) { shiftDelay(d); return true; }
        if (hit(my, 116)) { shiftRadius(d); return true; }
        if (hit(my, 142)) { shiftCycle(d); return true; }
        return super.mouseScrolled(mx, my, sx, sy);
    }
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return super.mouseClicked(mx, my, btn);
        if (hit(my, 84) && mx >= left + W / 2 + 16) { cycleUnit(); return true; }
        return super.mouseClicked(mx, my, btn);
    }
    private boolean hit(double my, int yOff) { return my >= top + yOff && my <= top + yOff + ROW; }

    private void shiftPack(int d) { if (packs.isEmpty()) return;
        int i = currentPackIndex();
        i = (i + d + packs.size()) % packs.size();
        AudioPack pack = packs.get(i);
        config.putInt("AudioPack", i);
        config.putString("AudioPackId", pack.id());
        config.putInt("Broadcast", 0);
        config.remove("BroadcastId"); }
    private void shiftBroadcast(int d) { AudioPack p = pack(); if (p == null || p.broadcasts().isEmpty()) return;
        int s = p.broadcasts().size();
        int i = currentBroadcastIndex(p);
        i = (i + d + s) % s;
        String key = new ArrayList<>(p.broadcasts().keySet()).get(i);
        config.putInt("Broadcast", i);
        config.putString("BroadcastId", key); }
    private void shiftDelay(int d) { config.putInt("Delay", Math.max(0, config.getInt("Delay") + d)); }
    private void shiftRadius(int d) {
        double r = Math.max(4, Math.min(128, config.getDouble("Radius") + d * 4));
        config.putDouble("Radius", r);
    }
    private void shiftCycle(int d) {
        int v = Math.max(0, Math.min(3600, config.getInt("CycleInterval") + d * 5));
        config.putInt("CycleInterval", v);
    }
    private void cycleUnit() { config.putInt("DelayUnit", (config.getInt("DelayUnit") + 1) % 3); }
    private AudioPack pack() {
        String id = config.getString("AudioPackId");
        if (!id.isEmpty()) {
            AudioPack selected = AudioManager.getPack(id);
            if (selected != null) return selected;
        }
        int i = config.getInt("AudioPack");
        return i >= 0 && i < packs.size() ? packs.get(i) : null;
    }

    private int currentPackIndex() {
        AudioPack selected = pack();
        int index = packs.indexOf(selected);
        return index >= 0 ? index : 0;
    }

    private void migrateSelectionIds() {
        AudioPack selected = pack();
        if (selected == null) return;
        config.putString("AudioPackId", selected.id());
        int broadcastIndex = currentBroadcastIndex(selected);
        if (broadcastIndex >= 0 && broadcastIndex < selected.broadcasts().size()) {
            config.putString("BroadcastId", new ArrayList<>(selected.broadcasts().keySet()).get(broadcastIndex));
        }
    }

    private int currentBroadcastIndex(AudioPack pack) {
        String id = config.getString("BroadcastId");
        if (!id.isEmpty()) {
            int index = 0;
            for (String key : pack.broadcasts().keySet()) {
                if (key.equals(id)) return index;
                index++;
            }
        }
        return config.getInt("Broadcast");
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xC0_000000);
        g.fill(left - 4, top - 4, left + W + 4, top + H + 4, 0xFF_252525);
        g.renderOutline(left - 4, top - 4, W + 8, H + 8, 0xFF_555555);
        g.fill(left - 4, top - 4, left + W + 4, top + 12, 0xFF_1A1A1A);
        g.drawString(font, "站台广播配置", left + 6, top, 0xCCCCCC);
        drawRow(g, 20, packLabel(), 0xFF_4488FF);
        drawRow(g, 52, bcLabel(), 0xFF_44CC44);
        drawDelay(g, 84);
        drawRadius(g, 116);
        drawCycle(g, 142);

        // Create-style hover tooltip: title in gold, options in gray, no blue highlight
        if (hit(my, 20)) {
            int cur = Math.min(config.getInt("AudioPack"), packs.size() - 1);
            var lines = new ArrayList<Component>();
            lines.add(Component.translatable("railway_operations.gui.select_pack").withStyle(ChatFormatting.GOLD));
            int start = Math.max(0, cur - 7);
            int end = Math.min(packs.size(), start + 16);
            if (start > 0) lines.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
            for (int i = start; i < end; i++) {
                String prefix = (i == cur) ? "> " : "  ";
                lines.add(Component.literal(prefix + packs.get(i).name()).withStyle(i == cur ? ChatFormatting.WHITE : ChatFormatting.GRAY));
            }
            if (end < packs.size()) lines.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
            if (packs.isEmpty()) lines.add(Component.literal("(no packs)").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("railway_operations.gui.scroll_to_modify").withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            g.renderTooltip(font, lines, java.util.Optional.empty(), mx, my);
        } else if (hit(my, 52)) {
            var p = pack();
            var keys = new ArrayList<String>();
            if (p != null) keys.addAll(p.broadcasts().keySet());
            int cur = Math.min(config.getInt("Broadcast"), keys.size() - 1);
            var lines = new ArrayList<Component>();
            lines.add(Component.translatable("railway_operations.gui.select_broadcast").withStyle(ChatFormatting.GOLD));
            int start = Math.max(0, cur - 7);
            int end = Math.min(keys.size(), start + 16);
            if (start > 0) lines.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
            for (int i = start; i < end; i++) {
                String prefix = (i == cur) ? "> " : "  ";
                lines.add(Component.literal(prefix + keys.get(i)).withStyle(i == cur ? ChatFormatting.WHITE : ChatFormatting.GRAY));
            }
            if (end < keys.size()) lines.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
            if (keys.isEmpty()) lines.add(Component.literal("...").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("railway_operations.gui.scroll_to_modify").withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
            g.renderTooltip(font, lines, java.util.Optional.empty(), mx, my);
        } else if (hit(my, 84) && mx < left + W / 2 + 10) {
            int d = config.getInt("Delay");
            String[] u = {"tick","秒","分"};
            g.renderTooltip(font, Component.translatable("railway_operations.gui.delay_value").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(": " + (d == 0 ? "无延时" : d + u[Math.min(config.getInt("DelayUnit"), 2)]))), mx, my);
        } else if (hit(my, 84) && mx >= left + W / 2 + 16) {
            g.renderTooltip(font, Component.literal("点击切换单位"), mx, my);
        }
    }

    private void drawRow(GuiGraphics g, int yOff, String text, int accent) {
        int y = top + yOff, x1 = left + 10, x2 = left + W - 10;
        g.fill(x1, y, x2, y + ROW, 0xFF_1A1A1A);
        g.renderOutline(x1, y, x2 - x1, ROW, 0xFF_555555);
        g.fill(x1, y, x1 + 3, y + ROW, accent);
        g.drawCenteredString(font, text, (x1 + x2) / 2, y + 6, 0xFFFFFF);
    }

    private void drawDelay(GuiGraphics g, int yOff) {
        int y = top + yOff, x1 = left + 10, xm = left + W / 2 + 10, x2 = left + W / 2 + 16, x3 = left + W - 10;
        g.fill(x1, y, xm, y + ROW, 0xFF_1A1A1A);
        g.renderOutline(x1, y, xm - x1, ROW, 0xFF_555555);
        g.fill(x1, y, x1 + 3, y + ROW, 0xFF_FFCC44);
        int d = config.getInt("Delay"); String[] u = {"t","s","m"};
        g.drawCenteredString(font, d == 0 ? "无延时" : d + u[Math.min(config.getInt("DelayUnit"), 2)], (x1 + xm) / 2, y + 6, 0xFFFFFF);
        g.fill(x2, y, x3, y + ROW, 0xFF_1A1A1A);
        g.renderOutline(x2, y, x3 - x2, ROW, 0xFF_555555);
        g.drawCenteredString(font, unitLabel(), (x2 + x3) / 2, y + 6, 0xCCCCCC);
    }

    private String packLabel() { AudioPack p = pack(); return p != null ? p.name() : "(no packs)"; }
    private String bcLabel() { AudioPack p = pack(); if (p == null || p.broadcasts().isEmpty()) return "...";
        int i = 0, idx = config.getInt("Broadcast"); for (String k : p.broadcasts().keySet()) if (i++ == idx) return k; return "..."; }
    private void drawRadius(GuiGraphics g, int yOff) {
        int y = top + yOff, x1 = left + 10, x2 = left + W - 10;
        g.drawString(font, "传播半径", x1, y - 10, 0x888888);
        g.fill(x1, y, x2, y + ROW, 0xFF_1A1A1A);
        g.renderOutline(x1, y, x2 - x1, ROW, 0xFF_555555);
        g.fill(x1, y, x1 + 3, y + ROW, 0xFF_8888FF);
        double r = Math.max(4, Math.min(128, config.getDouble("Radius")));
        g.drawCenteredString(font, (int) r + " 格", (x1 + x2) / 2, y + 6, 0xFFFFFF);
    }

    private void drawCycle(GuiGraphics g, int yOff) {
        int y = top + yOff, x1 = left + 10, x2 = left + W - 10;
        g.drawString(font, "循环间隔", x1, y - 10, 0x888888);
        g.fill(x1, y, x2, y + ROW, 0xFF_1A1A1A);
        g.renderOutline(x1, y, x2 - x1, ROW, 0xFF_555555);
        g.fill(x1, y, x1 + 3, y + ROW, 0xFF_88FF88);
        int v = Math.max(0, config.getInt("CycleInterval"));
        g.drawCenteredString(font, v == 0 ? "关闭" : v + " 秒", (x1 + x2) / 2, y + 6, 0xFFFFFF);
    }

    private String unitLabel() { return switch (Math.min(config.getInt("DelayUnit"), 2)) { case 0 -> "tick"; case 1 -> "秒"; default -> "分"; }; }

    public void onClose() {
        if (minecraft != null && minecraft.player != null)
            PacketDistributor.sendToServer(new PlatformBroadcastConfigPacket(pos, config.copy()));
        super.onClose();
    }
}
