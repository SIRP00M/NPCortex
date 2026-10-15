package net.minecraft.server;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.function.Function;
import java.util.function.Supplier;
public class MinecraftServer {
    public ServerLevel overworld() { return new ServerLevel(); }
    public static class ServerLevel {
        public DataStorage getDataStorage() { return new DataStorage(); }
    }
    public static class DataStorage {
        public <T extends SavedData> T computeIfAbsent(Function<CompoundTag,T> loader, Supplier<T> creator, String id) { return creator.get(); }
    }
}
