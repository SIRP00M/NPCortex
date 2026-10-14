package io.github.sirp00m.npcortex.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/** Generic lifecycle contract. Registration is immutable once the runtime starts. */
public final class SkillRegistry<C> {
    public enum Target { NONE, BLOCK, DROP, ITEM, WAYPOINT }
    public record Skill<C>(String id, Target target, String description,
            BiPredicate<C, String> precondition, BiConsumer<C, String> execute,
            BiPredicate<C, String> verify, BiConsumer<C, String> recovery) {}
    private final Map<String, Skill<C>> skills = new LinkedHashMap<>();
    private boolean frozen;
    public void register(Skill<C> skill) {
        if (frozen) throw new IllegalStateException("Registry already frozen");
        if (skill == null || !skill.id().matches("[a-z][a-z0-9_]{0,31}")
                || skill.target() == null || skill.description() == null
                || skill.precondition() == null || skill.execute() == null
                || skill.verify() == null || skill.recovery() == null)
            throw new IllegalArgumentException("Incomplete skill contract");
        if (skills.putIfAbsent(skill.id(), skill) != null)
            throw new IllegalArgumentException("Duplicate skill: " + skill.id());
    }
    public void freeze() { frozen = true; }
    public Set<String> ids() { return Set.copyOf(skills.keySet()); }
    public Skill<C> get(String id) { return skills.get(id); }
    public boolean execute(String id, C context, String target) {
        Skill<C> skill = skills.get(id);
        if (skill == null || !skill.precondition().test(context, target)) return false;
        skill.execute().accept(context, target);
        return true;
    }
    public boolean verify(String id, C context, String target) {
        Skill<C> skill = skills.get(id);
        return skill != null && skill.verify().test(context, target);
    }
    public void recover(String id, C context, String target) {
        Skill<C> skill = skills.get(id);
        if (skill != null) skill.recovery().accept(context, target);
    }
}
