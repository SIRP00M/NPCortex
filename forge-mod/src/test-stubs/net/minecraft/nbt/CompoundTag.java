package net.minecraft.nbt;
import java.util.HashMap;
import java.util.UUID;
public class CompoundTag implements Tag {
    private final HashMap<String, Object> tags = new HashMap<>();
    public void put(String key, Tag tag) { tags.put(key, tag); }
    public void putFloat(String key, float v) { tags.put(key, v); }
    public float getFloat(String key) { return tags.get(key) instanceof Float f ? f : 0.0f; }
    public void putInt(String key, int v) { tags.put(key, v); }
    public void putLong(String key, long v) { tags.put(key, v); }
    public void putString(String key, String v) { tags.put(key, v); }
    public void putUUID(String key, UUID v) { tags.put(key, v); }
    public boolean hasUUID(String key) { return tags.get(key) instanceof UUID; }
    public UUID getUUID(String key) { return (UUID) tags.get(key); }
    public int getInt(String key) { return tags.get(key) instanceof Integer i ? i : 0; }
    public long getLong(String key) { return tags.get(key) instanceof Long v ? v : 0; }
    public String getString(String key) { return tags.get(key) instanceof String s ? s : ""; }
    public boolean contains(String key, int type) { return (type == TAG_LIST && tags.get(key) instanceof ListTag) || (type == TAG_FLOAT && tags.get(key) instanceof Float) || (type == TAG_STRING && tags.get(key) instanceof String); }
    public ListTag getList(String key, int ignored) { return tags.get(key) instanceof ListTag l ? l : new ListTag(); }
    public CompoundTag copy() {
        CompoundTag result = new CompoundTag();
        for (var entry : tags.entrySet()) {
            Object value = entry.getValue();
            result.tags.put(entry.getKey(), value instanceof ListTag l ? l.copy() : value instanceof CompoundTag c ? c.copy() : value);
        }
        return result;
    }
}
