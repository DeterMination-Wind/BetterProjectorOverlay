package betterprojectoroverlay;

import arc.Events;
import betterprojectoroverlay.features.BetterProjectorOverlayFeature;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.Mod;

import static mindustry.Vars.ui;

public class BetterProjectorOverlayMod extends Mod {

    private static boolean settingsAdded;

    @Override
    public void init() {
        BetterProjectorOverlayFeature.init();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (settingsAdded) return;
            settingsAdded = true;

            GithubUpdateCheck.applyDefaults();

            ui.settings.addCategory("@settings.bpo", Icon.map, BetterProjectorOverlayFeature::buildSettings);
            GithubUpdateCheck.checkOnce();
        });
    }
}
