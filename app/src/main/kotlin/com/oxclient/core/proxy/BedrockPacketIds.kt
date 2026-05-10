package com.oxclient.core.proxy

object BedrockPacketIds {

    // ── RakNet ────────────────────────────────────────────────────────────
    const val RAKNET_ACK  = 0xC0
    const val RAKNET_NACK = 0xA0

    // ── Bedrock Oyun Paketleri ────────────────────────────────────────────
    const val LOGIN                   = 0x01
    const val PLAY_STATUS             = 0x02
    const val SERVER_TO_CLIENT_HANDSHAKE = 0x03
    const val CLIENT_TO_SERVER_HANDSHAKE = 0x04
    const val DISCONNECT              = 0x05
    const val RESOURCE_PACKS_INFO     = 0x06
    const val RESOURCE_PACK_STACK     = 0x07
    const val RESOURCE_PACK_CLIENT_RESPONSE = 0x08
    const val TEXT                    = 0x09
    const val SET_TIME                = 0x0A
    const val START_GAME              = 0x0B
    const val ADD_PLAYER              = 0x0C
    const val ADD_ENTITY              = 0x0D
    const val REMOVE_ENTITY           = 0x0E
    const val ADD_ITEM_ENTITY         = 0x0F
    const val TAKE_ITEM_ENTITY        = 0x11
    const val MOVE_ENTITY_ABSOLUTE    = 0x12
    const val MOVE_PLAYER             = 0x13
    const val RIDER_JUMP              = 0x14
    const val UPDATE_BLOCK            = 0x15
    const val LEVEL_SOUND_EVENT_V1    = 0x18
    const val ENTITY_EVENT            = 0x1B
    const val MOB_EFFECT              = 0x1C
    const val UPDATE_ATTRIBUTES       = 0x1D
    const val INVENTORY_TRANSACTION   = 0x1E
    const val MOB_EQUIPMENT           = 0x1F
    const val MOB_ARMOR_EQUIPMENT     = 0x20
    const val INTERACT                = 0x21
    const val BLOCK_PICK_REQUEST      = 0x22
    const val ENTITY_PICK_REQUEST     = 0x23
    const val PLAYER_ACTION           = 0x24
    const val ENTITY_FALL             = 0x26
    const val HURT_ARMOR              = 0x27
    const val SET_ENTITY_DATA         = 0x28
    const val SET_ENTITY_MOTION       = 0x29
    const val ANIMATE                 = 0x2C
    const val RESPAWN                 = 0x2D
    const val CONTAINER_OPEN          = 0x2E
    const val CONTAINER_CLOSE         = 0x2F
    const val PLAYER_HOTBAR           = 0x30
    const val INVENTORY_CONTENT       = 0x31
    const val INVENTORY_SLOT          = 0x32
    const val CONTAINER_SET_DATA      = 0x33
    const val CRAFTING_DATA           = 0x34
    const val CRAFTING_EVENT          = 0x35
    const val SET_ENTITY_LINK         = 0x3C
    const val SET_HEALTH              = 0x3D
    const val SET_SPAWN_POSITION      = 0x3E
    const val ANIMATE_ENTITY          = 0x3F
    const val LEVEL_EVENT             = 0x45
    const val BLOCK_EVENT             = 0x46
    const val ENTITY_EVENT2           = 0x47
    const val CLIENT_BOUND_MAP_ITEM_DATA = 0x43
    // USE_ITEM alias kaldırıldı — 0x1F MOB_EQUIPMENT ile çakışıyordu
    const val GAME_RULES_CHANGED      = 0x48
    const val UPDATE_BLOCK_SYNCED     = 0x6E
    const val MOVE_ENTITY_DELTA       = 0x6F
    const val SET_ACTOR_MOTION        = 0x28  // alias
    // ✅ FIX: 1.21.60'da 0x91 — eski 0x90 yüzünden EntityTracker konum almıyordu
    const val PLAYER_AUTH_INPUT       = 0x91
    const val LEVEL_CHUNK             = 0x3A
    const val FULL_CHUNK_DATA         = 0x3A  // alias
    const val SET_COMMANDS_ENABLED    = 0x3B
    const val NETWORK_CHUNK_PUBLISHER_UPDATE = 0x79
    const val BOSS_EVENT              = 0x4A
    const val COMMAND_REQUEST         = 0x4D
    const val COMMAND_BLOCK_UPDATE    = 0x4E
    const val AVAILABLE_COMMANDS      = 0x4C
    const val TRANSFER_SERVER         = 0x55
    const val CHUNK_RADIUS_UPDATE     = 0x46  // ✅ FIX: 0x45 LEVEL_EVENT ile çakışıyordu
    const val TICK_SYNC               = 0x17
    const val ENTITY_IDENTIFIER_LIST  = 0x77
    const val LEVEL_SOUND_EVENT_V2    = 0x86
    const val LEVEL_SOUND_EVENT       = 0x7B
    const val STRUCTURE_TEMPLATE_DATA = 0x7A
    const val RESPAWN_POSITION        = 0x3E  // alias
    const val DEATH_INFO              = 0xA1

    // ── Kristal ───────────────────────────────────────────────────────────
    const val END_CRYSTAL_BLOCK_ID = 0xE1  // obsidyen üzerine kristal
}