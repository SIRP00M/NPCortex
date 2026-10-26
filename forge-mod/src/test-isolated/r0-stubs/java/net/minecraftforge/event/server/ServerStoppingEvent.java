package net.minecraftforge.event.server;
public class ServerStoppingEvent {
    private final net.minecraft.server.MinecraftServer server=new net.minecraft.server.MinecraftServer();
    public net.minecraft.server.MinecraftServer getServer(){return server;}
}
