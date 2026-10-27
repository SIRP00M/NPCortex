package io.github.sirp00m.npcortex;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Adds "NPCortex Manager" to the ESC menu. Anchored to a screen corner (not to vanilla widgets) so the
 * vanilla layout is untouched, and only on the real game menu (the one with "Back to Game"). Any failure
 * is swallowed: the Pause Menu must never break because of NPCortex.
 */
@Mod.EventBusSubscriber(modid = NPCortex.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PauseMenuHook {
    private static final Logger LOG = LogUtils.getLogger();

    private PauseMenuHook() {}

    @SubscribeEvent
    public static void onPauseInit(ScreenEvent.Init.Post event) {
        try {
            if (!(event.getScreen() instanceof PauseScreen screen)) return;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) return;
            boolean gameMenu = event.getListenersList().stream().anyMatch(PauseMenuHook::isBackToGame);
            if (!gameMenu) return;
            event.addListener(Button.builder(Component.translatable("npcortex.manager.button"),
                    button -> minecraft.setScreen(new ManagerScreen(screen))).bounds(6, 6, 118, 20).build());
        } catch (RuntimeException ex) {
            LOG.warn("NPCortex could not add the Manager button to the pause menu", ex);
        }
    }

    private static boolean isBackToGame(Object listener) {
        return listener instanceof Button button
                && button.getMessage().getContents() instanceof TranslatableContents contents
                && "menu.returnToGame".equals(contents.getKey());
    }
}
