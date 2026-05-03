package com.oxclient.core.proxy;

/** Minecraft Bedrock Edition 1.21.x (protocol 712) packet ID constants. */
public final class PacketIds {
    private PacketIds() {}

    public static final int LOGIN                      = 0x01;
    public static final int PLAY_STATUS                = 0x02;
    public static final int DISCONNECT                 = 0x05;
    public static final int START_GAME                 = 0x0B;
    public static final int ADD_PLAYER                 = 0x0C;
    public static final int ADD_ENTITY                 = 0x0D;
    public static final int REMOVE_ENTITY              = 0x0E;
    public static final int MOVE_ENTITY_ABSOLUTE       = 0x12;
    public static final int MOVE_PLAYER                = 0x13;
    public static final int UPDATE_BLOCK               = 0x15;
    public static final int INVENTORY_TRANSACTION      = 0x1E;
    public static final int MOB_EQUIPMENT              = 0x1F;
    public static final int INTERACT                   = 0x21;
    public static final int PLAYER_ACTION              = 0x24;
    public static final int SET_HEALTH                 = 0x26;
    public static final int SET_ENTITY_DATA            = 0x27;
    public static final int SET_ENTITY_MOTION          = 0x28;
    public static final int ANIMATE                    = 0x2C;
    public static final int CONTAINER_OPEN             = 0x2E;
    public static final int CONTAINER_CLOSE            = 0x2F;
    public static final int INVENTORY_CONTENT          = 0x31;
    public static final int INVENTORY_SLOT             = 0x32;
    public static final int TEXT                       = 0x09;
    public static final int RESPAWN                    = 0x62;
    public static final int UPDATE_ATTRIBUTES          = 0x1D;
    public static final int ENTITY_EVENT               = 0x1B;
    public static final int MOB_EFFECT                 = 0x1C;
    public static final int LEVEL_CHUNK                = 0x3A;
    public static final int COMMAND_REQUEST            = 0x4D;
    public static final int AVAILABLE_COMMANDS         = 0x4C;
    public static final int MOVE_ENTITY_DELTA          = 0x6F;
    public static final int PLAYER_AUTH_INPUT          = 0x90;
    public static final int ITEM_STACK_REQUEST         = 0x93;
    public static final int ITEM_STACK_RESPONSE        = 0x94;
    public static final int NETWORK_STACK_LATENCY      = 0x64;
    public static final int BATCH                      = 0xFE;

    // InventoryTransaction sub-types
    public static final int TX_NORMAL                  = 0;
    public static final int TX_MISMATCH                = 1;
    public static final int TX_USE_ITEM                = 2;
    public static final int TX_USE_ITEM_ON_ENTITY      = 3;
    public static final int TX_RELEASE_ITEM            = 4;

    // PlayerAction IDs
    public static final int ACTION_START_BREAK         = 0;
    public static final int ACTION_ABORT_BREAK         = 1;
    public static final int ACTION_STOP_BREAK          = 2;
    public static final int ACTION_JUMP                = 4;
    public static final int ACTION_START_SPRINT        = 8;
    public static final int ACTION_STOP_SPRINT         = 9;
    public static final int ACTION_START_SNEAK         = 10;
    public static final int ACTION_STOP_SNEAK          = 11;

    // Entity metadata keys
    public static final int META_FLAGS                 = 0;
    public static final int META_HEALTH                = 2;
    public static final long FLAG_INVISIBLE            = 1L << 5;

    // Container IDs
    public static final int CONTAINER_INVENTORY        = 0;
    public static final int CONTAINER_OFFHAND          = 119;

    // Item IDs (Bedrock 1.21)
    public static final int ITEM_TOTEM_OF_UNDYING      = 470;
    public static final int ITEM_END_CRYSTAL           = 741;
}
