package com.oxclient.core.entity;

import java.util.UUID;

/** Data holder for one live entity tracked by EntityTracker. */
public final class TrackedEntity {
    public enum Type { PLAYER, MOB, ITEM, UNKNOWN }

    public final long runtimeId;
    public final long uniqueId;
    public final Type type;

    public volatile String  name       = "";
    public volatile UUID    uuid       = null;
    public volatile String  entityType = "";
    public volatile float   x = 0, y = 0, z = 0;
    public volatile float   pitch = 0, yaw = 0;
    public volatile boolean invisible  = false;
    public volatile boolean dead       = false;
    public volatile float   health     = 20f;
    public volatile float   maxHealth  = 20f;
    public volatile float   cachedDistSq = 0f;
    public volatile long    lastAttackedMs = 0L;

    public TrackedEntity(long runtimeId, long uniqueId, Type type) {
        this.runtimeId = runtimeId;
        this.uniqueId  = uniqueId;
        this.type      = type;
    }

    public double distanceTo(float ox, float oy, float oz) {
        double dx=x-ox, dy=y-oy, dz=z-oz;
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    public boolean isPlayer() { return type == Type.PLAYER; }
    public boolean isMob()    { return type == Type.MOB; }
    public boolean isAlive()  { return health > 0 && !dead; }

    @Override public String toString() {
        return "Entity{rid=" + runtimeId + " " + type
            + (name.isEmpty() ? "" : " " + name)
            + " [" + String.format("%.1f",x) + "," + String.format("%.1f",y) + "," + String.format("%.1f",z) + "]"
            + " hp=" + String.format("%.1f",health) + "}";
    }
}
