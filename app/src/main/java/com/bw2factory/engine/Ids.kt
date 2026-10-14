package com.bw2factory.engine

/** Ability ids (pokebw2 data/constants/abilities.txt) for every ability a Battle Subway Pokémon can have. */
object Ab {
    const val NONE = 0
    const val STENCH = 1
    const val SPEED_BOOST = 3
    const val BATTLE_ARMOR = 4
    const val STURDY = 5
    const val DAMP = 6
    const val LIMBER = 7
    const val SAND_VEIL = 8
    const val STATIC = 9
    const val VOLT_ABSORB = 10
    const val WATER_ABSORB = 11
    const val OBLIVIOUS = 12
    const val COMPOUNDEYES = 14
    const val INSOMNIA = 15
    const val IMMUNITY = 17
    const val FLASH_FIRE = 18
    const val OWN_TEMPO = 20
    const val SUCTION_CUPS = 21
    const val INTIMIDATE = 22
    const val ROUGH_SKIN = 24
    const val LEVITATE = 26
    const val EFFECT_SPORE = 27
    const val SYNCHRONIZE = 28
    const val CLEAR_BODY = 29
    const val NATURAL_CURE = 30
    const val LIGHTNINGROD = 31
    const val SERENE_GRACE = 32
    const val SWIFT_SWIM = 33
    const val CHLOROPHYLL = 34
    const val ILLUMINATE = 35
    const val TRACE = 36
    const val POISON_POINT = 38
    const val INNER_FOCUS = 39
    const val WATER_VEIL = 41
    const val MAGNET_PULL = 42
    const val SOUNDPROOF = 43
    const val RAIN_DISH = 44
    const val SAND_STREAM = 45
    const val PRESSURE = 46
    const val THICK_FAT = 47
    const val EARLY_BIRD = 48
    const val FLAME_BODY = 49
    const val RUN_AWAY = 50
    const val KEEN_EYE = 51
    const val HYPER_CUTTER = 52
    const val PICKUP = 53
    const val TRUANT = 54
    const val HUSTLE = 55
    const val CUTE_CHARM = 56
    const val PLUS = 57
    const val MINUS = 58
    const val STICKY_HOLD = 60
    const val SHED_SKIN = 61
    const val GUTS = 62
    const val MARVEL_SCALE = 63
    const val LIQUID_OOZE = 64
    const val OVERGROW = 65
    const val BLAZE = 66
    const val TORRENT = 67
    const val SWARM = 68
    const val ROCK_HEAD = 69
    const val ARENA_TRAP = 71
    const val VITAL_SPIRIT = 72
    const val PURE_POWER = 74
    const val SHELL_ARMOR = 75
    const val MOTOR_DRIVE = 78
    const val RIVALRY = 79
    const val STEADFAST = 80
    const val SNOW_CLOAK = 81
    const val GLUTTONY = 82
    const val ANGER_POINT = 83
    const val UNBURDEN = 84
    const val HEATPROOF = 85
    const val DRY_SKIN = 87
    const val DOWNLOAD = 88
    const val IRON_FIST = 89
    const val POISON_HEAL = 90
    const val ADAPTABILITY = 91
    const val HYDRATION = 93
    const val QUICK_FEET = 95
    const val SNIPER = 97
    const val MAGIC_GUARD = 98
    const val NO_GUARD = 99
    const val TECHNICIAN = 101
    const val LEAF_GUARD = 102
    const val KLUTZ = 103
    const val MOLD_BREAKER = 104
    const val SUPER_LUCK = 105
    const val AFTERMATH = 106
    const val ANTICIPATION = 107
    const val FOREWARN = 108
    const val UNAWARE = 109
    const val TINTED_LENS = 110
    const val FILTER = 111
    const val SLOW_START = 112
    const val SCRAPPY = 113
    const val STORM_DRAIN = 114
    const val ICE_BODY = 115
    const val SOLID_ROCK = 116
    const val SNOW_WARNING = 117
    const val FRISK = 119
    const val RECKLESS = 120
    const val SHEER_FORCE = 125
    const val UNNERVE = 127
    const val DEFIANT = 128
    const val DEFEATIST = 129
    const val CURSED_BODY = 130
    const val HEALER = 131
    const val WEAK_ARMOR = 133
    const val TELEPATHY = 140
    const val OVERCOAT = 142
    const val POISON_TOUCH = 143
    const val REGENERATOR = 144
    const val BIG_PECKS = 145
    const val SAND_RUSH = 146
    const val WONDER_SKIN = 147
    const val ILLUSION = 149
    const val INFILTRATOR = 151
    const val MUMMY = 152
    const val MOXIE = 153
    const val JUSTIFIED = 154
    const val SAP_SIPPER = 157
    const val PRANKSTER = 158
    const val SAND_FORCE = 159
    const val IRON_BARBS = 160

    /** Abilities Mold Breaker ignores (ones that react to an incoming move). */
    val BREAKABLE = setOf(
        BATTLE_ARMOR, STURDY, DAMP, SAND_VEIL, VOLT_ABSORB, WATER_ABSORB, OBLIVIOUS, INSOMNIA, IMMUNITY,
        FLASH_FIRE, OWN_TEMPO, SUCTION_CUPS, LEVITATE, CLEAR_BODY, INNER_FOCUS, WATER_VEIL, SOUNDPROOF,
        THICK_FAT, KEEN_EYE, HYPER_CUTTER, STICKY_HOLD, MARVEL_SCALE, SHELL_ARMOR, MOTOR_DRIVE, SNOW_CLOAK,
        HEATPROOF, DRY_SKIN, LEAF_GUARD, FILTER, STORM_DRAIN, SOLID_ROCK, LIGHTNINGROD, SAP_SIPPER, OVERCOAT,
        BIG_PECKS, WONDER_SKIN, UNAWARE, VITAL_SPIRIT, LIMBER, IMMUNITY,
    )
}

/** Item ids (pokebw2 data/constants/items.txt). */
object It {
    const val NONE = 0
    const val CHERI_BERRY = 149
    const val CHESTO_BERRY = 150
    const val PECHA_BERRY = 151
    const val RAWST_BERRY = 152
    const val PERSIM_BERRY = 156
    const val LUM_BERRY = 157
    const val SITRUS_BERRY = 158
    const val LIECHI_BERRY = 201
    const val GANLON_BERRY = 202
    const val SALAC_BERRY = 203
    const val PETAYA_BERRY = 204
    const val CUSTAP_BERRY = 210
    const val JABOCA_BERRY = 211
    const val BRIGHT_POWDER = 213
    const val WHITE_HERB = 214
    const val QUICK_CLAW = 217
    const val CHOICE_BAND = 220
    const val KINGS_ROCK = 221
    const val FOCUS_BAND = 230
    const val SCOPE_LENS = 232
    const val LEFTOVERS = 234
    const val SHELL_BELL = 253
    const val LAX_INCENSE = 255
    const val THICK_CLUB = 258
    const val WIDE_LENS = 265
    const val MUSCLE_BAND = 266
    const val WISE_GLASSES = 267
    const val EXPERT_BELT = 268
    const val LIGHT_CLAY = 269
    const val LIFE_ORB = 270
    const val POWER_HERB = 271
    const val TOXIC_ORB = 272
    const val FLAME_ORB = 273
    const val ENIGMA_BERRY = 208
    const val FOCUS_SASH = 275
    const val ZOOM_LENS = 276
    const val METRONOME = 277
    const val IRON_BALL = 278
    const val DESTINY_KNOT = 280
    const val BLACK_SLUDGE = 281
    const val ICY_ROCK = 282
    const val SMOOTH_ROCK = 283
    const val HEAT_ROCK = 284
    const val DAMP_ROCK = 285
    const val GRIP_CLAW = 286
    const val CHOICE_SCARF = 287
    const val BIG_ROOT = 296
    const val CHOICE_SPECS = 297
    const val FULL_INCENSE = 316
    const val RAZOR_CLAW = 326
    const val RAZOR_FANG = 327
    const val ROCKY_HELMET = 540
    const val AIR_BALLOON = 541
    const val BINDING_BAND = 544

    /** Berries that trigger on being hit super-effectively (item id -> type weakened). From the extractor. */
    val RESIST_BERRIES = mapOf(
        184 to Types.FIRE, 185 to Types.WATER, 186 to Types.ELECTRIC, 187 to Types.GRASS, 188 to Types.ICE,
        189 to Types.FIGHTING, 191 to Types.GROUND, 192 to Types.FLYING, 193 to Types.PSYCHIC, 194 to Types.BUG,
        195 to Types.ROCK, 196 to Types.GHOST, 197 to Types.DRAGON, 198 to Types.DARK,
    )
    /** Type-boost items (plates, incenses, type items): item id -> type boosted ×1.2. */
    val TYPE_BOOST = mapOf(
        222 to Types.BUG, 233 to Types.STEEL, 237 to Types.GROUND, 238 to Types.ROCK, 239 to Types.GRASS,
        240 to Types.DARK, 241 to Types.FIGHTING, 242 to Types.ELECTRIC, 243 to Types.WATER, 244 to Types.FLYING,
        245 to Types.POISON, 246 to Types.ICE, 247 to Types.GHOST, 248 to Types.PSYCHIC, 249 to Types.FIRE,
        250 to Types.DRAGON, 251 to Types.NORMAL, 299 to Types.WATER, 304 to Types.POISON, 308 to Types.BUG,
        317 to Types.WATER, 318 to Types.GRASS,
    )
    /** Gems: item id -> type; consumed to boost one move ×1.5. */
    val GEMS = mapOf(
        548 to Types.FIRE, 549 to Types.WATER, 550 to Types.ELECTRIC, 551 to Types.GRASS, 552 to Types.ICE,
        554 to Types.POISON, 555 to Types.GROUND, 556 to Types.FLYING, 557 to Types.PSYCHIC, 559 to Types.ROCK,
        561 to Types.DRAGON, 562 to Types.DARK, 563 to Types.STEEL, 564 to Types.NORMAL,
    )

    /**
     * Natural Gift: berry item id -> (type, base power). Gen5 values. Only berries that can be
     * held in the Battle Subway pool need be present; anything missing makes Natural Gift fail.
     */
    val NATURAL_GIFT = mapOf(
        149 to (Types.FIRE to 60),      // Cheri
        150 to (Types.WATER to 60),     // Chesto
        151 to (Types.ELECTRIC to 60),  // Pecha
        152 to (Types.GRASS to 60),     // Rawst
        153 to (Types.ICE to 60),       // Aspear
        154 to (Types.FIGHTING to 60),  // Leppa
        155 to (Types.POISON to 60),    // Oran
        156 to (Types.GROUND to 60),    // Persim
        157 to (Types.FLYING to 60),    // Lum
        158 to (Types.PSYCHIC to 60),   // Sitrus
        159 to (Types.BUG to 60),       // Figy
        160 to (Types.ROCK to 60),      // Wiki
        161 to (Types.GHOST to 60),     // Mago
        162 to (Types.DRAGON to 60),    // Aguav
        163 to (Types.DARK to 60),      // Iapapa
        184 to (Types.FIRE to 80),      // Occa
        185 to (Types.WATER to 80),     // Passho
        186 to (Types.ELECTRIC to 80),  // Wacan
        187 to (Types.GRASS to 80),     // Rindo
        188 to (Types.ICE to 80),       // Yache
        189 to (Types.FIGHTING to 80),  // Chople
        191 to (Types.GROUND to 80),    // Shuca
        192 to (Types.FLYING to 80),    // Coba
        193 to (Types.PSYCHIC to 80),   // Payapa
        194 to (Types.BUG to 80),       // Tanga
        195 to (Types.ROCK to 80),      // Charti
        196 to (Types.GHOST to 80),     // Kasib
        197 to (Types.DRAGON to 80),    // Haban
        198 to (Types.DARK to 80),      // Colbur
        201 to (Types.GRASS to 80),     // Liechi
        202 to (Types.WATER to 80),     // Ganlon
        203 to (Types.FIGHTING to 80),  // Salac
        204 to (Types.POISON to 80),    // Petaya
        208 to (Types.BUG to 80),       // Enigma
        210 to (Types.GHOST to 80),     // Custap
        211 to (Types.DRAGON to 80),    // Jaboca
    )

    fun isBerry(id: Int) = id in 149..212
    fun isChoice(id: Int) = id == CHOICE_BAND || id == CHOICE_SCARF || id == CHOICE_SPECS
}

enum class Weather { NONE, SUN, RAIN, SAND, HAIL }
