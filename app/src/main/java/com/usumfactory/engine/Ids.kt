package com.usumfactory.engine

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
    const val SKILL_LINK = 92  // Multi-hit (2-5) moves always hit the maximum number of times.
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
    const val ZEN_MODE = 161  // Darmanitan: flips to the Zen forme while HP <= 1/2.
    // "-ate" / skin abilities: a Normal-type move becomes the ability's type and gains x1.2 power (Gen 7).
    const val REFRIGERATE = 174  // 프리즈스킨: Normal -> Ice
    const val PIXILATE = 182     // 페어리스킨: Normal -> Fairy
    const val AERILATE = 184     // 스카이스킨: Normal -> Flying
    const val GALVANIZE = 206    // 일렉트릭스킨: Normal -> Electric
    const val DISGUISE = 209  // Mimikyu: nullifies the first damaging hit, then busts.

    /** Normal-type move -> this type for the "-ate"/skin ability. */
    val ATE_TYPE = mapOf(
        REFRIGERATE to 14 /*Ice*/, PIXILATE to 17 /*Fairy*/, AERILATE to 2 /*Flying*/, GALVANIZE to 12 /*Electric*/,
    )

    /** Abilities Mold Breaker ignores (ones that react to an incoming move). */
    val BREAKABLE = setOf(
        BATTLE_ARMOR, STURDY, DAMP, SAND_VEIL, VOLT_ABSORB, WATER_ABSORB, OBLIVIOUS, INSOMNIA, IMMUNITY,
        FLASH_FIRE, OWN_TEMPO, SUCTION_CUPS, LEVITATE, CLEAR_BODY, INNER_FOCUS, WATER_VEIL, SOUNDPROOF,
        THICK_FAT, KEEN_EYE, HYPER_CUTTER, STICKY_HOLD, MARVEL_SCALE, SHELL_ARMOR, MOTOR_DRIVE, SNOW_CLOAK,
        HEATPROOF, DRY_SKIN, LEAF_GUARD, FILTER, STORM_DRAIN, SOLID_ROCK, LIGHTNINGROD, SAP_SIPPER, OVERCOAT,
        BIG_PECKS, WONDER_SKIN, UNAWARE, VITAL_SPIRIT, LIMBER, IMMUNITY,
    )
}

/** Item ids (PokeAPI item ids, matching the extractor's items.json). */
object It {
    const val NONE = 0
    const val CHERI_BERRY = 126
    const val CHESTO_BERRY = 127
    const val PECHA_BERRY = 128
    const val RAWST_BERRY = 129
    const val PERSIM_BERRY = 133
    const val LUM_BERRY = 134
    const val SITRUS_BERRY = 135
    const val LIECHI_BERRY = 178
    const val GANLON_BERRY = 179
    const val SALAC_BERRY = 180
    const val PETAYA_BERRY = 181
    const val CUSTAP_BERRY = 187
    const val JABOCA_BERRY = 188
    const val ENIGMA_BERRY = 185
    const val BRIGHT_POWDER = 190
    const val WHITE_HERB = 191
    const val QUICK_CLAW = 194
    const val CHOICE_BAND = 197
    const val KINGS_ROCK = 198
    const val FOCUS_BAND = 207
    const val SCOPE_LENS = 209
    const val LEFTOVERS = 211
    const val SHELL_BELL = 230
    const val LAX_INCENSE = 232
    const val THICK_CLUB = 235
    const val WIDE_LENS = 242
    const val MUSCLE_BAND = 243
    const val WISE_GLASSES = 244
    const val EXPERT_BELT = 245
    const val LIGHT_CLAY = 246
    const val LIFE_ORB = 247
    const val POWER_HERB = 248
    const val TOXIC_ORB = 249
    const val FLAME_ORB = 250
    const val FOCUS_SASH = 252
    const val ZOOM_LENS = 253
    const val METRONOME = 254
    const val IRON_BALL = 255
    const val DESTINY_KNOT = 257
    const val BLACK_SLUDGE = 258
    const val ICY_ROCK = 259
    const val SMOOTH_ROCK = 260
    const val HEAT_ROCK = 261
    const val DAMP_ROCK = 262
    const val GRIP_CLAW = 263
    const val CHOICE_SCARF = 264
    const val BIG_ROOT = 273
    const val CHOICE_SPECS = 274
    const val FULL_INCENSE = 293
    const val RAZOR_CLAW = 303
    const val RAZOR_FANG = 304
    const val ROCKY_HELMET = 583
    const val AIR_BALLOON = 584
    const val BINDING_BAND = 587

    /** Berries that weaken a super-effective hit of their type (item id -> type weakened). */
    val RESIST_BERRIES = mapOf(
        161 to Types.FIRE, 162 to Types.WATER, 163 to Types.ELECTRIC, 164 to Types.GRASS, 165 to Types.ICE,
        166 to Types.FIGHTING, 167 to Types.POISON, 168 to Types.GROUND, 169 to Types.FLYING, 170 to Types.PSYCHIC,
        171 to Types.BUG, 172 to Types.ROCK, 173 to Types.GHOST, 174 to Types.DRAGON, 175 to Types.DARK,
        176 to Types.STEEL, 177 to Types.NORMAL, 2279 to Types.FAIRY,
    )
    /** Type-boost items (plates + classic type items): item id -> type boosted ×1.2. */
    val TYPE_BOOST = mapOf(
        275 to Types.FIRE, 276 to Types.WATER, 277 to Types.ELECTRIC, 278 to Types.GRASS, 279 to Types.ICE,
        280 to Types.FIGHTING, 281 to Types.POISON, 282 to Types.GROUND, 283 to Types.FLYING, 284 to Types.PSYCHIC,
        285 to Types.BUG, 286 to Types.ROCK, 287 to Types.GHOST, 288 to Types.DRAGON, 289 to Types.DARK,
        290 to Types.STEEL, 684 to Types.FAIRY,
        226 to Types.FIRE, 220 to Types.WATER, 219 to Types.ELECTRIC, 216 to Types.GRASS, 223 to Types.ICE,
        218 to Types.FIGHTING, 222 to Types.POISON, 214 to Types.GROUND, 221 to Types.FLYING, 225 to Types.PSYCHIC,
        199 to Types.BUG, 215 to Types.ROCK, 224 to Types.GHOST, 227 to Types.DRAGON, 217 to Types.DARK,
        210 to Types.STEEL, 228 to Types.NORMAL,
    )
    /** Gems: item id -> type; consumed to boost one move ×1.5. */
    val GEMS = mapOf(
        591 to Types.FIRE, 592 to Types.WATER, 593 to Types.ELECTRIC, 594 to Types.GRASS, 595 to Types.ICE,
        596 to Types.FIGHTING, 597 to Types.POISON, 598 to Types.GROUND, 599 to Types.FLYING, 600 to Types.PSYCHIC,
        601 to Types.BUG, 602 to Types.ROCK, 603 to Types.GHOST, 668 to Types.DRAGON, 604 to Types.DARK,
        605 to Types.STEEL, 669 to Types.NORMAL, 731 to Types.FAIRY,
    )

    /** Natural Gift: berry item id -> (type, base power). Flavor berries 60, resist/pinch berries 80. */
    val NATURAL_GIFT = mapOf(
        126 to (Types.FIRE to 60), 127 to (Types.WATER to 60), 128 to (Types.ELECTRIC to 60),
        129 to (Types.GRASS to 60), 130 to (Types.ICE to 60), 131 to (Types.FIGHTING to 60),
        132 to (Types.POISON to 60), 133 to (Types.GROUND to 60), 134 to (Types.FLYING to 60),
        135 to (Types.PSYCHIC to 60), 136 to (Types.BUG to 60), 137 to (Types.ROCK to 60),
        138 to (Types.GHOST to 60), 139 to (Types.DRAGON to 60), 140 to (Types.DARK to 60),
    ) + RESIST_BERRIES.mapValues { it.value to 80 } + mapOf(
        178 to (Types.GRASS to 80), 179 to (Types.ICE to 80), 180 to (Types.FIGHTING to 80),
        181 to (Types.POISON to 80), 182 to (Types.GROUND to 80), 183 to (Types.FLYING to 80),
        184 to (Types.PSYCHIC to 80), 185 to (Types.BUG to 80), 186 to (Types.ROCK to 80),
        187 to (Types.GHOST to 80), 188 to (Types.DRAGON to 80), 189 to (Types.DARK to 80),
    )

    /** Z-crystals use synthetic ids 9000+ (type = id - 9000), matching the extractor's items.json. */
    const val Z_BASE = 9000
    fun zCrystalForType(type: Int) = Z_BASE + type
    fun isZCrystal(id: Int) = id in Z_BASE until (Z_BASE + 18)
    /** The type a Z-crystal powers up, or -1 if the item isn't a Z-crystal. */
    fun zCrystalType(id: Int) = if (isZCrystal(id)) id - Z_BASE else -1

    fun isBerry(id: Int) = id in 126..189 || id == 2278 || id == 2279
    fun isChoice(id: Int) = id == CHOICE_BAND || id == CHOICE_SCARF || id == CHOICE_SPECS
}

enum class Weather { NONE, SUN, RAIN, SAND, HAIL }
