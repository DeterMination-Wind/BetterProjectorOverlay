package betterprojectoroverlay.features;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.IntFloatMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Time;
import arc.util.Tmp;
import arc.util.pooling.Pools;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.logic.Ranged;
import mindustry.core.UI;
import mindustry.ui.Fonts;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.world.Block;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.power.PowerGraph;

import java.lang.reflect.Method;
import java.util.Locale;

import static mindustry.Vars.control;
import static mindustry.Vars.net;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

public class BetterProjectorOverlayFeature {
    private static final String overlayName = "bpo-overlay";

    private static final String keyEnabled = "bpo-enabled";
    private static final String keyPreviewEnabled = "bpo-preview-enabled";
    private static final String keyMarkerEnabled = "bpo-marker-enabled";
    private static final String keyChatEnabled = "bpo-chat-enabled";
    private static final String keyScanInterval = "bpo-scan-interval";

    private static final Interval interval = new Interval(6);
    private static final int idSettings = 0;
    private static final int idAttach = 1;
    private static final int idScan = 2;

    private static final float settingsRefreshTime = 0.5f;
    private static final float attachRefreshTime = 1f;

    private static boolean inited;

    private static boolean enabled;
    private static boolean previewEnabled;
    private static boolean markerEnabled;
    private static boolean chatEnabled;
    private static float scanIntervalSeconds;

    private static final PlacementPreview preview = new PlacementPreview();

    private static final IntSet markedPositions = new IntSet();
    private static final IntSet announcedPositions = new IntSet();

    private static final Seq<Building> sourceOverdrives = new Seq<>(false, 64, Building.class);
    private static final Seq<Building> targetProjectors = new Seq<>(false, 128, Building.class);

    private static final IntSet touchedGraphs = new IntSet();
    private static final IntFloatMap graphCurrentBalance = new IntFloatMap();
    private static final IntFloatMap graphDelta = new IntFloatMap();

    private static final MindustryXMarkers xMarkers = new MindustryXMarkers();

    private static boolean forceRescan = true;

    public static void init() {
        if (inited) return;
        inited = true;

        if (ui != null && ui.hudGroup != null) {
            Element old = ui.hudGroup.find(overlayName);
            if (old != null) old.remove();
        }

        Events.on(EventType.ClientLoadEvent.class, e -> {
            Core.settings.defaults(keyEnabled, true);
            Core.settings.defaults(keyPreviewEnabled, true);
            Core.settings.defaults(keyMarkerEnabled, true);
            Core.settings.defaults(keyChatEnabled, false);
            Core.settings.defaults(keyScanInterval, 8);

            xMarkers.tryInit();
            refreshSettings();
            ensureOverlayAttached();
            forceRescan = true;
        });

        Events.on(EventType.WorldLoadEvent.class, e -> {
            markedPositions.clear();
            announcedPositions.clear();
            touchedGraphs.clear();
            graphCurrentBalance.clear();
            graphDelta.clear();
            forceRescan = true;
        });

        Events.on(EventType.BlockBuildEndEvent.class, e -> forceRescan = true);
        Events.on(EventType.BlockDestroyEvent.class, e -> forceRescan = true);
        Events.on(EventType.BuildRotateEvent.class, e -> forceRescan = true);
        Events.on(EventType.BuildTeamChangeEvent.class, e -> forceRescan = true);
        Events.on(EventType.ConfigEvent.class, e -> forceRescan = true);

        Events.run(EventType.Trigger.update, () -> {
            if (interval.check(idSettings, settingsRefreshTime)) refreshSettings();
            if (interval.check(idAttach, attachRefreshTime)) ensureOverlayAttached();

            if (!enabled || !markerEnabled) return;
            if (forceRescan || interval.check(idScan, Math.max(1f, scanIntervalSeconds))) {
                forceRescan = false;
                scanAndMarkConflicts();
            }
        });

        Events.run(EventType.Trigger.draw, BetterProjectorOverlayFeature::drawPlacementPrediction);
    }

    public static void buildSettings(SettingsMenuDialog.SettingsTable table) {
        table.checkPref(keyEnabled, true);
        table.checkPref(keyPreviewEnabled, true);
        table.checkPref(keyMarkerEnabled, true);
        table.checkPref(keyChatEnabled, false);
        table.sliderPref(keyScanInterval, 8, 1, 30, 1, i -> i + "s");

        refreshSettings();
    }

    private static void refreshSettings() {
        enabled = Core.settings.getBool(keyEnabled, true);
        previewEnabled = Core.settings.getBool(keyPreviewEnabled, true);
        markerEnabled = Core.settings.getBool(keyMarkerEnabled, true);
        chatEnabled = Core.settings.getBool(keyChatEnabled, false);
        scanIntervalSeconds = Mathf.clamp(Core.settings.getInt(keyScanInterval, 8), 1f, 30f);
    }

    private static void ensureOverlayAttached() {
        if (ui == null || ui.hudGroup == null) return;
        if (!Core.settings.getBool("minimap")) return;

        Element minimap = ui.hudGroup.find("minimap");
        if (!(minimap instanceof Table)) return;

        Table table = (Table) minimap;
        if (table.find(overlayName) != null) return;

        if (table.getChildren().isEmpty()) return;
        Element base = table.getChildren().get(0);

        HudOverlay overlay = new HudOverlay(base);
        overlay.name = overlayName;
        overlay.touchable = Touchable.disabled;
        table.addChild(overlay);
        overlay.toFront();
    }

    private static void drawPlacementPrediction() {
        if (!enabled || !previewEnabled) return;
        if (state == null || !state.isGame() || world == null || world.isGenerating() || player == null) return;

        PlacementPreview p = computePlacementPreview();
        if (!p.active) return;

        Color mainColor = p.positive ? Pal.heal : Color.scarlet;

        Draw.z(Layer.overlayUI - 1f);
        Draw.color(mainColor, 0.16f);
        Fill.circle(p.worldX, p.worldY, p.range);
        Draw.color(mainColor);
        Lines.stroke(Scl.scl(1.6f));
        Lines.circle(p.worldX, p.worldY, p.range);
        Draw.reset();

        drawPlacementText(p, mainColor);
    }

    private static void drawPlacementText(PlacementPreview p, Color color) {
        Font font = Fonts.outline;
        GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);

        float scale = 0.85f / Scl.scl(1f);
        font.getData().setScale(scale);

        String stateText;
        if (p.graphCount <= 0) {
            stateText = Core.bundle.get("bpo.preview.none", "No powered graph in range");
        } else {
            String key = p.positive ? "bpo.preview.positive" : "bpo.preview.negative";
            String sign = Core.bundle.get(key, p.positive ? "Positive after placement" : "Negative after placement");
            stateText = sign + "  " + (p.balance >= 0f ? "+" : "") + UI.formatAmount((long) p.balance) + "/s";
        }

        layout.setText(font, stateText);
        Vec2 screen = Core.camera.project(Tmp.v1.set(p.worldX, p.worldY + p.range + 10f));

        Draw.z(Drawf.text() + 1f);
        Draw.color(0f, 0f, 0f, 0.42f);
        Fill.rect(screen.x, screen.y, layout.width + Scl.scl(12f), layout.height + Scl.scl(8f));
        Draw.color();

        font.setColor(color);
        font.draw(stateText, screen.x, screen.y + layout.height / 2f, 0f, Align.center, false);

        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
        Pools.free(layout);
    }

    private static PlacementPreview computePlacementPreview() {
        preview.reset();

        if (control == null || control.input == null) return preview;
        Block block = control.input.block;
        if (!(block instanceof OverdriveProjector)) return preview;
        if (world == null || world.width() <= 0 || world.height() <= 0) return preview;

        int tx = Mathf.clamp((int) (Core.input.mouseWorldX() / tilesize), 0, world.width() - 1);
        int ty = Mathf.clamp((int) (Core.input.mouseWorldY() / tilesize), 0, world.height() - 1);

        OverdriveProjector projector = (OverdriveProjector) block;

        float placeX = tx * tilesize + block.offset;
        float placeY = ty * tilesize + block.offset;
        float range = Math.max(1f, projector.range);
        float boost = Math.max(1f, projector.speedBoost);

        touchedGraphs.clear();
        graphCurrentBalance.clear();
        graphDelta.clear();

        int affectedBuildings = 0;

        for (int i = 0; i < Groups.build.size(); i++) {
            Building b = Groups.build.index(i);
            if (b == null || !b.isValid()) continue;
            if (b.team != player.team()) continue;
            if (!b.block.canOverdrive) continue;
            if (b.power == null || b.power.graph == null) continue;
            if (!Mathf.within(b.x, b.y, placeX, placeY, range)) continue;

            affectedBuildings++;

            PowerGraph graph = b.power.graph;
            int graphId = graph.getID();

            touchedGraphs.add(graphId);
            graphCurrentBalance.put(graphId, graph.getPowerBalance() * 60f);
            graphDelta.put(graphId, graphDelta.get(graphId, 0f) + estimatePowerDeltaPerSecond(b, boost));
        }

        int graphCount = touchedGraphs.size;

        if (graphCount > 0) {
            touchedGraphs.each(id -> {
                float current = graphCurrentBalance.get(id, 0f);
                float delta = graphDelta.get(id, 0f);
                float predicted = current + delta;
                if (predicted < preview.balance) {
                    preview.balance = predicted;
                }
            });

            if (preview.balance == Float.POSITIVE_INFINITY || preview.balance == Float.NEGATIVE_INFINITY) {
                preview.balance = 0f;
            }
        } else {
            preview.balance = 0f;
        }

        preview.active = true;
        preview.worldX = placeX;
        preview.worldY = placeY;
        preview.range = range;
        preview.graphCount = graphCount;
        preview.affectedBuildings = affectedBuildings;
        preview.positive = preview.balance >= 0f;

        return preview;
    }

    private static float estimatePowerDeltaPerSecond(Building b, float newBoost) {
        float oldScale = Math.max(0.001f, b.timeScale());
        float newScale = Math.max(oldScale, newBoost);
        if (newScale <= oldScale + 0.0001f) return 0f;

        float producedPerSecond = b.getPowerProduction() * 60f * oldScale;
        float consumedPerSecond = 0f;

        if (b.shouldConsumePower && b.block != null && b.block.consPower != null) {
            consumedPerSecond = b.block.consPower.requestedPower(b) * 60f * oldScale;
        }

        float netPerSecond = producedPerSecond - consumedPerSecond;
        float ratio = newScale / oldScale;
        return netPerSecond * (ratio - 1f);
    }

    private static void scanAndMarkConflicts() {
        if (!enabled || !markerEnabled) return;
        if (state == null || !state.isGame() || world == null || world.isGenerating() || player == null) return;

        sourceOverdrives.clear();
        targetProjectors.clear();

        for (int i = 0; i < Groups.build.size(); i++) {
            Building b = Groups.build.index(i);
            if (b == null || !b.isValid()) continue;
            if (b.team != player.team()) continue;

            if (isSourceOverdrive(b)) sourceOverdrives.add(b);
            if (isTargetProjector(b)) targetProjectors.add(b);
        }

        if (sourceOverdrives.isEmpty() || targetProjectors.isEmpty()) return;

        for (int i = 0; i < targetProjectors.size; i++) {
            Building target = targetProjectors.get(i);
            if (target == null || !target.isValid()) continue;
            if (!isCoveredBySource(target)) continue;

            int pos = target.pos();
            int tileX = target.tileX();
            int tileY = target.tileY();

            if (markedPositions.add(pos)) {
                xMarkers.markNeedRemove(tileX, tileY);
            }

            if (announcedPositions.add(pos)) {
                sendChatAlert(tileX, tileY);
            }
        }
    }

    private static boolean isTargetProjector(Building b) {
        return b.block instanceof OverdriveProjector && b.block != Blocks.overdriveDome;
    }

    private static boolean isSourceOverdrive(Building b) {
        if (!(b.block instanceof OverdriveProjector)) return false;
        if (b.block == Blocks.overdriveDome) return true;
        return isGaobuStyleOverdrive(b.block);
    }

    private static boolean isGaobuStyleOverdrive(Block block) {
        if (!(block instanceof OverdriveProjector)) return false;
        if (block == Blocks.overdriveProjector || block == Blocks.overdriveDome) return false;

        String name = block.name == null ? "" : block.name.toLowerCase(Locale.ROOT);
        String localized = block.localizedName == null ? "" : block.localizedName.toLowerCase(Locale.ROOT);

        if (name.contains("gaobu") || name.contains("gabu") || localized.contains("加布") || localized.contains("高布")) {
            return true;
        }

        // Treat non-vanilla overdrive projectors as compatible sources.
        return true;
    }

    private static boolean isCoveredBySource(Building target) {
        float targetPad = Math.max(0f, target.block.size * tilesize / 2f);

        for (int i = 0; i < sourceOverdrives.size; i++) {
            Building source = sourceOverdrives.get(i);
            if (source == null || !source.isValid()) continue;
            if (source == target) continue;

            float range = realOverdriveRange(source);
            if (range <= 0.001f) continue;

            if (Mathf.within(target.x, target.y, source.x, source.y, range + targetPad)) {
                return true;
            }
        }

        return false;
    }

    private static float realOverdriveRange(Building source) {
        if (source == null || source.block == null) return 0f;
        if (!(source.block instanceof OverdriveProjector)) return 0f;

        try {
            Method realRange = source.getClass().getMethod("realRange");
            Object out = realRange.invoke(source);
            if (out instanceof Number) {
                return Math.max(0f, ((Number) out).floatValue());
            }
        } catch (Throwable ignored) {
        }

        if (source instanceof Ranged) {
            return Math.max(0f, ((Ranged) source).range());
        }

        return ((OverdriveProjector) source.block).range;
    }

    private static void sendChatAlert(int tileX, int tileY) {
        if (!chatEnabled) return;
        if (player == null || state == null || !state.isGame()) return;

        String prefix = Core.bundle.get("bpo.chat.remove", "<BPO><Need remove overdrive>");
        String message = prefix + "(" + tileX + "," + tileY + ")";

        if (net != null && net.active()) {
            Call.sendChatMessage(message);
        } else if (ui != null && ui.hudfrag != null) {
            ui.hudfrag.showToast("[scarlet]" + message + "[]");
        }
    }

    private static class HudOverlay extends Element {
        private final Element base;

        HudOverlay(Element base) {
            this.base = base;
        }

        @Override
        public void act(float delta) {
            if (base != null) setBounds(base.x, base.y, base.getWidth(), base.getHeight());
            super.act(delta);
        }
    }

    private static class PlacementPreview {
        boolean active;
        boolean positive;
        float worldX;
        float worldY;
        float range;
        float balance;
        int graphCount;
        int affectedBuildings;

        void reset() {
            active = false;
            positive = true;
            worldX = 0f;
            worldY = 0f;
            range = 0f;
            balance = Float.POSITIVE_INFINITY;
            graphCount = 0;
            affectedBuildings = 0;
        }
    }

    /** Optional integration with MindustryX marker API. Uses reflection so missing MindustryX won't crash. */
    private static class MindustryXMarkers {
        private boolean initialized;
        private boolean available;
        private Method newMarkFromChat;

        void tryInit() {
            if (initialized) return;
            initialized = true;

            try {
                Class<?> markerType = Class.forName("mindustryX.features.MarkerType");
                newMarkFromChat = markerType.getMethod("newMarkFromChat", String.class, Vec2.class);
                available = true;
                Log.info("BPO: MindustryX marker API detected.");
            } catch (Throwable ignored) {
                available = false;
            }
        }

        void markNeedRemove(int tileX, int tileY) {
            if (!available || newMarkFromChat == null) return;

            try {
                String label = Core.bundle.get("bpo.mark.remove", "Need remove overdrive");
                String text = "[scarlet]" + label + "[] (" + tileX + "," + tileY + ")";
                newMarkFromChat.invoke(null, text, new Vec2(tileX, tileY));
            } catch (Throwable t) {
                available = false;
                Log.err("BPO: MindustryX marker call failed; disabling integration.", t);
            }
        }
    }
}
