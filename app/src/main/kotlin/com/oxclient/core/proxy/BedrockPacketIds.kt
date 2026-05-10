package com.oxclient.core.proxy

/**
 * BedrockPacketIds
 *
 * Bedrock Edition 1.21.60 paket ID'leri.
 * Kaynak: gophertunnel/sandertv + CloudburstMC Protocol referansları.
 *
 * Modüllerin ihtiyaç duyduğu tüm ID'ler burada toplanmıştır.
 * Yeni paket eklenirse buraya ekle, modüllere dokunma.
 *
 * ── Sık kullanılanlar (modüller) ─────────────────────────────────────────
 *   START_GAME             = 0x0B  → EntityTracker (selfId)
 *   ADD_PLAYER             = 0x0C  → EntityTracker
 *   ADD_ENTITY             = 0x0D  → EntityTracker, CrystalAura
 *   REMOVE_ENTITY          = 0x0E  → EntityTracker, CrystalAura
 *   MOVE_PLAYER            = 0x13  → EntityTracker, KillAura, Jetpack
 *   MOVE_ENTITY_ABSOLUTE   = 0x12  → EntityTracker
 *   MOB_EFFECT             = 0x1C  → FullBright
 *   UPDATE_ATTRIBUTES      = 0x1D  → AutoTotem
 *   INVENTORY_TRANSACTION  = 0x1E  → Criticals, CrystalAura
 *   MOB_EQUIPMENT          = 0x1F  → AutoTotem
 *   INTERACT               = 0x21  → RelayPacketBuilder
 *   SET_HEALTH             = 0x2A  → RelayPacketBuilder
 *   ANIMATE                = 0x2C  → KillAura, CrystalAura
 *   CONTAINER_CLOSE        = 0x2F  → PacketHelper
 *   INVENTORY_CONTENT      = 0x31  → AutoTotem
 *   INVENTORY_SLOT         = 0x32  → AutoTotem
 *   LEVEL_EVENT            = 0x19  → CrystalAura
 *   ENTITY_EVENT           = 0x1B  → AutoTotem
 *   LEVEL_CHUNK            = 0x3A  → FullBright
 *   SET_TIME               = 0x0A  → FullBright
 *   PLAYER_AUTH_INPUT      = 0x90  → Jetpack
 *   NETWORK_SETTINGS       = 0x8F  → RelaySession (sıkıştırma)
 */
object BedrockPacketIds {

    // ── Login / Handshake ─────────────────────────────────────────────────
    const val LOGIN                              = 0x01
    const val PLAY_STATUS                        = 0x02
    const val SERVER_TO_CLIENT_HANDSHAKE         = 0x03
    const val CLIENT_TO_SERVER_HANDSHAKE         = 0x04
    const val DISCONNECT                         = 0x05
    const val RESOURCE_PACKS_INFO               = 0x06
    const val RESOURCE_PACK_STACK               = 0x07
    const val RESOURCE_PACK_CLIENT_RESPONSE     = 0x08

    // ── World / Time ──────────────────────────────────────────────────────
    const val TEXT                               = 0x09
    const val SET_TIME                           = 0x0A  // FullBright Gamma modu
    const val START_GAME                         = 0x0B  // EntityTracker selfId

    // ── Entity spawn ──────────────────────────────────────────────────────
    const val ADD_PLAYER                         = 0x0C
    const val ADD_ENTITY                         = 0x0D
    const val REMOVE_ENTITY                      = 0x0E
    const val ADD_ITEM_ENTITY                    = 0x0F
    const val TAKE_ITEM_ENTITY                   = 0x11

    // ── Movement ──────────────────────────────────────────────────────────
    const val MOVE_ENTITY_ABSOLUTE               = 0x12
    const val MOVE_PLAYER                        = 0x13
    const val RIDER_JUMP                         = 0x14

    // ── Block ─────────────────────────────────────────────────────────────
    const val UPDATE_BLOCK                       = 0x15
    const val ADD_PAINTING                       = 0x16
    const val TICK_SYNC                          = 0x17
    const val LEVEL_SOUND_EVENT_V1               = 0x18

    // ── Level events ──────────────────────────────────────────────────────
    const val LEVEL_EVENT                        = 0x19  // CrystalAura parçacık bastırma
    const val BLOCK_EVENT                        = 0x1A
    const val ENTITY_EVENT                       = 0x1B  // AutoTotem (totem kullanımı)

    // ── Entity attributes ─────────────────────────────────────────────────
    const val MOB_EFFECT                         = 0x1C  // FullBright NightVision
    const val UPDATE_ATTRIBUTES                  = 0x1D  // AutoTotem can takibi

    // ── Inventory / Interaction ───────────────────────────────────────────
    const val INVENTORY_TRANSACTION              = 0x1E  // KillAura, Criticals, CrystalAura
    const val MOB_EQUIPMENT                      = 0x1F  // AutoTotem offhand
    const val MOB_ARMOR_EQUIPMENT                = 0x20
    const val INTERACT                           = 0x21

    // ── Pick requests ─────────────────────────────────────────────────────
    const val BLOCK_PICK_REQUEST                 = 0x22
    const val ENTITY_PICK_REQUEST                = 0x23
    const val PLAYER_ACTION                      = 0x24
    const val HURT_ARMOR                         = 0x26

    // ── Entity data ───────────────────────────────────────────────────────
    const val SET_ENTITY_DATA                    = 0x27
    const val SET_ENTITY_MOTION                  = 0x28
    const val SET_ENTITY_LINK                    = 0x29
    const val SET_HEALTH                         = 0x2A
    const val SET_SPAWN_POSITION                 = 0x2B
    const val ANIMATE                            = 0x2C  // Swing animasyonu
    const val RESPAWN                            = 0x2D

    // ── Container ────────────────────────────────────────────────────────
    const val CONTAINER_OPEN                     = 0x2E
    const val CONTAINER_CLOSE                    = 0x2F
    const val PLAYER_HOTBAR                      = 0x30
    const val INVENTORY_CONTENT                  = 0x31  // AutoTotem envanter taraması
    const val INVENTORY_SLOT                     = 0x32  // AutoTotem slot takibi
    const val CONTAINER_SET_DATA                 = 0x33
    const val CRAFTING_DATA                      = 0x34
    const val CRAFTING_EVENT                     = 0x35
    const val GUI_DATA_PICK_ITEM                 = 0x36

    // ── Game settings ─────────────────────────────────────────────────────
    const val ADVENTURE_SETTINGS                 = 0x37
    const val BLOCK_ENTITY_DATA                  = 0x38
    const val PLAYER_INPUT                       = 0x39
    const val LEVEL_CHUNK                        = 0x3A  // FullBright Lighting modu
    const val SET_COMMANDS_ENABLED               = 0x3B
    const val SET_DIFFICULTY                     = 0x3C
    const val CHANGE_DIMENSION                   = 0x3D
    const val SET_PLAYER_GAME_TYPE               = 0x3E
    const val PLAYER_LIST                        = 0x3F
    const val SIMPLE_EVENT                       = 0x40
    const val EVENT                              = 0x41
    const val SPAWN_EXPERIENCE_ORB               = 0x42
    const val MAP_ITEM_DATA                      = 0x43
    const val MAP_INFO_REQUEST                   = 0x44
    const val REQUEST_CHUNK_RADIUS               = 0x45
    const val CHUNK_RADIUS_UPDATE                = 0x46
    const val ITEM_FRAME_DROP_ITEM               = 0x47
    const val GAME_RULES_CHANGED                 = 0x48
    const val CAMERA                             = 0x49
    const val BOSS_EVENT                         = 0x4A
    const val SHOW_CREDITS                       = 0x4B
    const val AVAILABLE_COMMANDS                 = 0x4C
    const val COMMAND_REQUEST                    = 0x4D
    const val COMMAND_BLOCK_UPDATE               = 0x4E
    const val COMMAND_OUTPUT                     = 0x4F
    const val UPDATE_TRADE                       = 0x50
    const val UPDATE_EQUIPMENT                   = 0x51
    const val RESOURCE_PACK_DATA_INFO            = 0x52
    const val RESOURCE_PACK_CHUNK_DATA           = 0x53
    const val RESOURCE_PACK_CHUNK_REQUEST        = 0x54
    const val TRANSFER                           = 0x55
    const val PLAY_SOUND                         = 0x56
    const val STOP_SOUND                         = 0x57
    const val SET_TITLE                          = 0x58
    const val ADD_BEHAVIOR_TREE                  = 0x59
    const val STRUCTURE_BLOCK_UPDATE             = 0x5A
    const val SHOW_STORE_OFFER                   = 0x5B
    const val PURCHASE_RECEIPT                   = 0x5C
    const val PLAYER_SKIN                        = 0x5D
    const val SUB_CLIENT_LOGIN                   = 0x5E
    const val AUTOMATION_CLIENT_CONNECT          = 0x5F
    const val SET_LAST_HURT_BY                   = 0x60
    const val BOOK_EDIT                          = 0x61
    const val NPC_REQUEST                        = 0x62
    const val PHOTO_TRANSFER                     = 0x63
    const val MODAL_FORM_REQUEST                 = 0x64
    const val MODAL_FORM_RESPONSE                = 0x65
    const val SERVER_SETTINGS_REQUEST            = 0x66
    const val SERVER_SETTINGS_RESPONSE           = 0x67
    const val SHOW_PROFILE                       = 0x68
    const val SET_DEFAULT_GAME_TYPE              = 0x69
    const val REMOVE_OBJECTIVE                   = 0x6A
    const val SET_DISPLAY_OBJECTIVE              = 0x6B
    const val SET_SCORE                          = 0x6C
    const val LAB_TABLE                          = 0x6D
    const val UPDATE_BLOCK_SYNCED                = 0x6E
    const val MOVE_ENTITY_DELTA                  = 0x6F
    const val SET_SCOREBOARD_IDENTITY            = 0x70
    const val SET_LOCAL_PLAYER_AS_INITIALIZED    = 0x71
    const val UPDATE_SOFT_ENUM                   = 0x72
    const val NETWORK_STACK_LATENCY              = 0x73
    const val SCRIPT_CUSTOM_EVENT                = 0x75
    const val SPAWN_PARTICLE_EFFECT              = 0x76
    const val AVAILABLE_ENTITY_IDENTIFIERS       = 0x77
    const val LEVEL_SOUND_EVENT_V2               = 0x78
    const val NETWORK_CHUNK_PUBLISHER_UPDATE     = 0x79
    const val BIOME_DEFINITION_LIST              = 0x7A
    const val LEVEL_SOUND_EVENT                  = 0x7B
    const val LEVEL_EVENT_GENERIC                = 0x7C
    const val LECTERN_UPDATE                     = 0x7D
    const val VIDEO_STREAM_CONNECT               = 0x7E
    const val CLIENT_CACHE_STATUS                = 0x81
    const val ON_SCREEN_TEXTURE_ANIMATION        = 0x82
    const val MAP_CREATE_LOCKED_COPY             = 0x83
    const val STRUCTURE_TEMPLATE_EXPORT_REQUEST  = 0x84
    const val STRUCTURE_TEMPLATE_EXPORT_RESPONSE = 0x85
    const val UPDATE_BLOCK_PROPERTIES            = 0x86
    const val CLIENT_CACHE_BLOB_STATUS           = 0x87
    const val CLIENT_CACHE_MISS_RESPONSE         = 0x88
    const val EDUCATION_SETTINGS                 = 0x89
    const val EMOTE                              = 0x8A
    const val MULTIPLAYER_SETTINGS               = 0x8B
    const val SETTINGS_COMMAND                   = 0x8C
    const val ANVIL_DAMAGE                       = 0x8D
    const val COMPLETED_USING_ITEM               = 0x8E

    /**
     * NetworkSettings (0x8F)
     * [threshold uint16LE][algorithm uint16LE]
     * algorithm: 0=zlib, 1=snappy, 0xFF=none
     * RelaySession bu paketi yakalayarak sıkıştırmayı aktif eder.
     */
    const val NETWORK_SETTINGS                   = 0x8F

    /**
     * PlayerAuthInput (0x90)
     * C→S hareket paketi. Jetpack modülü intercept eder.
     * Format (1.21.60): [pitch f32][yaw f32][headYaw f32][x f32][y f32][z f32][velX f32][velZ f32][inputFlags varlong]...
     */
    const val PLAYER_AUTH_INPUT                  = 0x90

    const val CREATIVE_CONTENT                   = 0x91
    const val PLAYER_ENCHANT_OPTIONS             = 0x92
    const val ITEM_STACK_REQUEST                 = 0x93
    const val ITEM_STACK_RESPONSE                = 0x94
    const val PLAYER_ARMOR_DAMAGE                = 0x95
    const val CODE_BUILDER                       = 0x96
    const val UPDATE_PLAYER_GAME_TYPE            = 0x97
    const val EMOTE_LIST                         = 0x98
    const val POSITION_TRACKING_DB_SERVER_BROADCAST = 0x99
    const val POSITION_TRACKING_DB_CLIENT_REQUEST   = 0x9A
    const val DEBUG_INFO                         = 0x9B
    const val PACKET_VIOLATION_WARNING           = 0x9C
    const val MOTION_PREDICTION_HINTS            = 0x9D
    const val ANIMATE_ENTITY                     = 0x9E
    const val CAMERA_SHAKE                       = 0x9F
    const val PLAYER_FOG                         = 0xA0
    const val CORRECT_PLAYER_MOVE_PREDICTION     = 0xA1
    const val ITEM_COMPONENT                     = 0xA2
    const val FILTER_TEXT                        = 0xA3
    const val CLIENTBOUND_DEBUG_RENDERER         = 0xA4
    const val SYNC_ENTITY_PROPERTY               = 0xA5
    const val ADD_VOLUME_ENTITY                  = 0xA6
    const val REMOVE_VOLUME_ENTITY               = 0xA7
    const val SIMULATION_TYPE                    = 0xA8
    const val NPC_DIALOGUE                       = 0xA9
    const val EDU_URI_RESOURCE                   = 0xAA
    const val CREATE_PHOTO                       = 0xAB
    const val UPDATE_SUB_CHUNK_BLOCKS            = 0xAC
    const val SUB_CHUNK                          = 0xAE
    const val SUB_CHUNK_REQUEST                  = 0xAF
    const val PLAYER_START_ITEM_COOL_DOWN        = 0xB0
    const val SCRIPT_MESSAGE                     = 0xB1
    const val CODE_BUILDER_SOURCE                = 0xB2
    const val TOAST_REQUEST                      = 0xB3
    const val UPDATE_ABILITIES                   = 0xB4
    const val UPDATE_ADVENTURE_SETTINGS          = 0xB5
    const val DEATH_INFO                         = 0xB6
    const val EDITOR_NETWORK                     = 0xB7
    const val FEATURE_REGISTRY                   = 0xB8
    const val SERVER_STATS                       = 0xB9
    const val REQUEST_NETWORK_SETTINGS           = 0xC0
}
