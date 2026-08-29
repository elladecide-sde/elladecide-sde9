package com.elladecide.lola.emoji

import java.text.Normalizer

/**
 * Catálogo curado de emojis (set acotado, no el dataset completo de Emoji2).
 * Sirve para las categorías del panel; los recientes se resuelven contra este
 * mismo set. Puro y determinista, testeable en la JVM.
 */
object EmojiData {

    private val BY_CATEGORY: Map<EmojiCategory, List<Emoji>> = mapOf(
        EmojiCategory.SMILEYS to listOf(
            Emoji("😀", "cara sonriente"), Emoji("😁", "cara sonriente con ojos felices"),
            Emoji("😂", "cara llorando de risa"), Emoji("🤣", "rodando de risa"),
            Emoji("😊", "cara sonriente con ojos sonrientes"), Emoji("😍", "cara con corazones"),
            Emoji("😘", "cara tirando un beso"), Emoji("😜", "cara guiñando con lengua"),
            Emoji("🤪", "cara loca"), Emoji("🤩", "cara con estrellas"),
            Emoji("🥳", "cara de fiesta"), Emoji("😎", "cara con gafas de sol"),
            Emoji("🤓", "cara de nerd"), Emoji("🥺", "cara suplicante"),
            Emoji("😢", "cara triste"), Emoji("😭", "cara llorando fuerte"),
            Emoji("😡", "cara enfadada"), Emoji("🤯", "cabeza explotando"),
            Emoji("😱", "cara gritando de miedo"), Emoji("😴", "cara durmiendo"),
            Emoji("🤗", "cara abrazando"), Emoji("🤔", "cara pensando"),
            Emoji("🤷", "encogiéndose de hombros"), Emoji("🙃", "cara al revés"),
            Emoji("🥰", "cara con tres corazones"), Emoji("😇", "cara angelical"),
            Emoji("🤫", "cara pidiendo silencio"), Emoji("🤤", "cara babeando"),
            Emoji("😅", "cara con sudor frío"), Emoji("😉", "cara guiñando"),
        ),
        EmojiCategory.PEOPLE to listOf(
            Emoji("👍", "pulgar arriba", skinTone = true), Emoji("👎", "pulgar abajo", skinTone = true),
            Emoji("👏", "aplauso", skinTone = true), Emoji("🙏", "manos en oración", skinTone = true),
            Emoji("🤝", "apretón de manos", skinTone = true), Emoji("💪", "brazo flexionado", skinTone = true),
            Emoji("👌", "gesto OK", skinTone = true), Emoji("✌️", "señal de victoria", skinTone = true),
            Emoji("🤞", "dedos cruzados", skinTone = true), Emoji("👊", "puño", skinTone = true),
            Emoji("🙂", "cara ligeramente sonriente"), Emoji("🙈", "mono tapándose los ojos"),
        ),
        EmojiCategory.ANIMALS to listOf(
            Emoji("🐶", "perro"), Emoji("🐱", "gato"),
            Emoji("🐭", "ratón"), Emoji("🐹", "hámster"),
            Emoji("🐰", "conejo"), Emoji("🦊", "zorro"),
            Emoji("🐻", "oso"), Emoji("🐼", "panda"),
            Emoji("🐨", "koala"), Emoji("🐯", "tigre"),
            Emoji("🦁", "león"), Emoji("🐮", "vaca"),
            Emoji("🐷", "cerdo"), Emoji("🐸", "rana"),
            Emoji("🐵", "mono"), Emoji("🐔", "gallina"),
            Emoji("🐧", "pingüino"), Emoji("🐦", "pájaro"),
            Emoji("🦄", "unicornio"), Emoji("🐝", "abeja"),
        ),
        EmojiCategory.FOOD to listOf(
            Emoji("🍎", "manzana"), Emoji("🍐", "pera"),
            Emoji("🍊", "naranja"), Emoji("🍋", "limón"),
            Emoji("🍌", "banana"), Emoji("🍉", "sandía"),
            Emoji("🍇", "uvas"), Emoji("🍓", "frutilla"),
            Emoji("🍒", "cerezas"), Emoji("🍑", "durazno"),
            Emoji("🥑", "palta"), Emoji("🌽", "choclo"),
            Emoji("🍕", "pizza"), Emoji("🍔", "hamburguesa"),
            Emoji("🍟", "papas fritas"), Emoji("🌮", "taco"),
            Emoji("🍩", "dona"), Emoji("🎂", "torta"),
            Emoji("☕", "café"), Emoji("🧉", "mate"),
        ),
        EmojiCategory.ACTIVITY to listOf(
            Emoji("⚽", "pelota de fútbol"), Emoji("🏀", "pelota de básquet"),
            Emoji("🏈", "pelota de fútbol americano"), Emoji("⚾", "pelota de béisbol"),
            Emoji("🎾", "pelota de tenis"), Emoji("🏐", "pelota de vóley"),
            Emoji("🎮", "videojuego"), Emoji("🎲", "dado"),
            Emoji("🎯", "diana"), Emoji("🎳", "bolos"),
            Emoji("🏆", "trofeo"), Emoji("🥇", "medalla de oro"),
        ),
        EmojiCategory.TRAVEL to listOf(
            Emoji("🚗", "auto"), Emoji("🚕", "taxi"),
            Emoji("🚌", "colectivo"), Emoji("🏎", "auto de carrera"),
            Emoji("🚚", "camión"), Emoji("✈️", "avión"),
            Emoji("🚀", "cohete"), Emoji("⛵", "velero"),
            Emoji("🚲", "bicicleta"), Emoji("🏍", "moto"),
        ),
        EmojiCategory.OBJECTS to listOf(
            Emoji("📦", "caja"), Emoji("🛒", "carrito de compras"),
            Emoji("💡", "lamparita"), Emoji("🔑", "llave"),
            Emoji("🔒", "candado cerrado"), Emoji("🔓", "candado abierto"),
            Emoji("💻", "computadora"), Emoji("📱", "celular"),
            Emoji("📞", "teléfono"), Emoji("📷", "cámara"),
            Emoji("⏰", "despertador"), Emoji("📅", "calendario"),
            Emoji("📍", "alfiler de ubicación"), Emoji("✏️", "lápiz"),
        ),
        EmojiCategory.SYMBOLS to listOf(
            Emoji("❤️", "corazón rojo"), Emoji("💔", "corazón roto"),
            Emoji("🔥", "fuego"), Emoji("⭐", "estrella"),
            Emoji("✨", "destellos"), Emoji("🎉", "confeti"),
            Emoji("🎁", "regalo"), Emoji("💰", "bolsa de dinero"),
            Emoji("💵", "billete de dólar"), Emoji("💸", "dinero volando"),
            Emoji("✅", "check verde"), Emoji("❌", "cruz roja"),
            Emoji("⚠️", "advertencia"), Emoji("💬", "globo de diálogo"),
            Emoji("🏷️", "etiqueta"), Emoji("🧮", "ábaco"),
        ),
    )

    /** Emojis de una categoría (vacío para [EmojiCategory.RECENT], que es dinámico). */
    fun byCategory(category: EmojiCategory): List<Emoji> = BY_CATEGORY[category] ?: emptyList()

    /** Resuelve un glifo a su [Emoji] (para nombre/descripción), si está en el catálogo. */
    fun find(glyph: String): Emoji? = BY_CATEGORY.values.asSequence()
        .flatten()
        .firstOrNull { it.glyph == glyph }

    /**
     * Resuelve un glifo para mostrar en recientes: si es una variante con tono
     * de piel (ej. "👍🏽") que no está en el catálogo, le quita el modificador
     * y usa el emoji base para nombre/descripción (el glifo insertado se
     * preserva tal cual).
     */
    fun resolve(glyph: String): Emoji? {
        find(glyph)?.let { return it }
        val base = SKIN_TONES.filter { it.isNotEmpty() }
            .fold(glyph) { acc, mod -> acc.replace(mod, "") }
        return find(base)
    }

    /** Todo el catálogo, sin duplicados (cacheado — solo lectura). */
    private val ALL: List<Emoji> by lazy { BY_CATEGORY.values.flatten().distinctBy { it.glyph } }

    /** Nombres normalizados (minúsculas sin acentos) — precomputados para la búsqueda. */
    private val ALL_HAYSTACKS: List<String> by lazy { ALL.map { normalize(it.name) } }

    fun all(): List<Emoji> = ALL

    /**
     * Búsqueda por nombre: tokens en cualquier orden, sin distinguir
     * mayúsculas/acentos ("corazon roto" y "CORAZÓN ROTO" matchean igual).
     * Usa haystacks precomputados: O(emojis × tokens) con contains, sin NFD por llamada.
     */
    fun search(query: String): List<Emoji> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        return ALL.filterIndexed { i, _ -> tokens.all { ALL_HAYSTACKS[i].contains(it) } }
    }

    /**
     * Variantes de tono de piel para un glifo (estilo Gboard): base + 5 tonos.
     * Vacío si el emoji no soporta tonos.
     */
    fun skinToneVariants(glyph: String): List<String> {
        val emoji = find(glyph) ?: return emptyList()
        if (!emoji.skinTone) return emptyList()
        return SKIN_TONES.map { modifier -> glyph + modifier }
    }

    private fun tokenize(q: String): List<String> = q
        .lowercase()
        .split(Regex("\\s+"))
        .map(::normalize)
        .filter { it.isNotEmpty() }

    /** Normaliza a minúsculas sin acentos, para matchear "corazón" ↔ "corazon". */
    private fun normalize(s: String): String = Normalizer
        .normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

    /** Modificadores de tono de piel (Unicode): claro → oscuro. */
    val SKIN_TONES = listOf(
        "",                // base (sin tono)
        "\uD83C\uDFFB",    // 🏻 claro
        "\uD83C\uDFFC",    // 🏼 medio claro
        "\uD83C\uDFFD",    // 🏽 medio
        "\uD83C\uDFFE",    // 🏾 medio oscuro
        "\uD83C\uDFFF",    // 🏿 oscuro
    )
}
