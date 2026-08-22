package com.elladecide.lola.emoji

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
            Emoji("👍", "pulgar arriba"), Emoji("👎", "pulgar abajo"),
            Emoji("👏", "aplauso"), Emoji("🙏", "manos en oración"),
            Emoji("🤝", "apretón de manos"), Emoji("💪", "brazo flexionado"),
            Emoji("👌", "gesto OK"), Emoji("✌️", "señal de victoria"),
            Emoji("🤞", "dedos cruzados"), Emoji("👊", "puño"),
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
}
