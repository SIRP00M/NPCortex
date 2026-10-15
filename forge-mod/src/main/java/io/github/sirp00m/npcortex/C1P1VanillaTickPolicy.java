package io.github.sirp00m.npcortex;

/** Pure decision for world-tick support loss; no Minecraft dependencies.
 *  Does not choose an AI goal, move the player or load any world chunks.
 */
final class C1P1VanillaTickPolicy {
    private C1P1VanillaTickPolicy() {}

    static boolean needsSettling(boolean live, boolean activeMotion,
                                 boolean chunkLoaded, boolean onGround, boolean supported) {
        return live && !activeMotion && chunkLoaded && (!onGround || !supported);
    }
}
