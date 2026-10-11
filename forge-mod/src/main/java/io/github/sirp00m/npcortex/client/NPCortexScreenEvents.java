package io.github.sirp00m.npcortex.client;

import io.github.sirp00m.npcortex.NPCortex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Inject into Escape/PauseScreen without replacing vanilla menus or other mods. */
@Mod.EventBusSubscriber(modid = NPCortex.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NPCortexScreenEvents {
    private NPCortexScreenEvents() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen)) return;
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getGuiScaledWidth();
        event.addListener(Button.builder(Component.literal("NPCortex Manager"), button ->
                client.setScreen(new NPCortexManagerScreen(event.getScreen())))
                .bounds(Math.max(4, width - 137), 7, 132, 20).build());
    }
}
