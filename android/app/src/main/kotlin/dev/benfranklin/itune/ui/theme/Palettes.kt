package dev.benfranklin.itune.ui.theme

import androidx.compose.ui.graphics.Color

/** One palette (names follow the mockup kit: BG, SIDE, SURF..SURF4, LINE..LINE3, TXT, ACC, T2..T4). */
data class Palette(
    val id: String,
    val name: String,
    val light: Boolean,
    val bg: Color, val side: Color,
    val surf: Color, val surf2: Color, val surf3: Color, val surf4: Color,
    val line: Color, val line2: Color, val line3: Color,
    val txt: Color, val acc: Color, val t2: Color, val t3: Color, val t4: Color,
)

private fun c(h: String) = Color(("FF$h").toLong(16))

private fun p(
    id: String, name: String, light: Boolean,
    bg: String, side: String, surf: String, surf2: String, surf3: String, surf4: String,
    line: String, line2: String, line3: String, txt: String, acc: String, t2: String, t3: String, t4: String,
) = Palette(id, name, light, c(bg), c(side), c(surf), c(surf2), c(surf3), c(surf4), c(line), c(line2), c(line3), c(txt), c(acc), c(t2), c(t3), c(t4))

object Palettes {
    val mono = p("mono", "Pure Mono", false, "09090A", "0D0D0E", "111112", "171718", "1F1F21", "2A2A2C", "1A1A1C", "222224", "333335", "F2F2F2", "E8E8E8", "9A9A9C", "626264", "48484A")
    val all: List<Palette> = listOf(
        mono,
        p("oled", "OLED Black", false, "000000", "000000", "0A0A0A", "101010", "181818", "262626", "141414", "1C1C1C", "2C2C2C", "F2F2F2", "E8E8E8", "9A9A9C", "626264", "48484A"),
        mono.copy(id = "adaptive", name = "Adaptive"),
        p("nightgreen", "Night Green", false, "121212", "0B0B0B", "181818", "1F1F1F", "282828", "333333", "1E1E1E", "282828", "3A3A3A", "FFFFFF", "1ED760", "B3B3B3", "727272", "535353"),
        p("nightred", "Night Red", false, "030303", "0F0F0F", "141414", "1C1C1C", "272727", "333333", "1A1A1A", "242424", "383838", "F1F1F1", "FF1F3D", "AAAAAA", "717171", "505050"),
        p("daylight", "Daylight", true, "FFFFFF", "F8F8FA", "F5F5F7", "F2F2F4", "E8E8EC", "DCDCE0", "ECECEF", "E3E3E7", "D2D2D7", "111113", "FA2D48", "6E6E73", "8E8E93", "B8B8BD"),
        p("midnight", "Midnight", false, "080A10", "0B0D14", "0F121A", "151925", "1D2230", "2A3042", "171B26", "1F2433", "2E3447", "EEF1F8", "A9BEFF", "949BB0", "5E6478", "454A5A"),
        p("violet", "Violet", false, "0B0A10", "0E0D14", "121119", "18171F", "211F2B", "2D2A3B", "1B1A23", "24222E", "35323F", "F1EFF8", "C7B8FF", "9C98AE", "645F75", "4A4658"),
        p("ocean", "Ocean", false, "060B0F", "081016", "0C141B", "111B24", "172431", "223344", "131D27", "1A2733", "283A4A", "EDF4F8", "8FD3FF", "8EA3B2", "587080", "3F5260"),
        p("rose", "Rose", false, "0F0A0C", "130D10", "161013", "1D1519", "281C22", "38272F", "1F161A", "291D23", "3B2A32", "F8EEF1", "F4B6C8", "B09AA2", "76626A", "54454B"),
        p("sand", "Sand", false, "0C0B09", "100F0C", "13120F", "1A1814", "23201B", "302C25", "1C1A16", "25221D", "36322B", "F3EEE6", "E8D9BE", "A39C90", "6A645B", "4C4740"),
        p("mocha", "Mocha", false, "0E0A08", "120D0A", "17110D", "1E1712", "291F18", "3A2C22", "1F1712", "2A1F18", "3D2E24", "F5EDE6", "D9B08C", "AD9A8A", "75655A", "554940"),
        p("slate", "Slate", false, "0B0D0F", "0E1013", "121519", "181C21", "20252C", "2C333C", "181B20", "20242A", "30363F", "EEF0F2", "C3CCD8", "98A0AA", "626A74", "474D55"),
        p("forest", "Forest", false, "08100B", "0B140E", "0E1912", "132119", "1A2C21", "26402F", "14211A", "1B2B21", "2A3F31", "ECF5EE", "7FD49A", "93A89A", "5E7365", "44564A"),
        p("sunset", "Sunset", false, "120A08", "170D0A", "1B110D", "231611", "2E1E17", "412A20", "21140F", "2C1B15", "402A21", "FBEFE9", "FF8A5B", "B59C90", "7A6359", "584640"),
        p("snow", "Snow", true, "FFFFFF", "FAFAFA", "F6F6F6", "F2F2F2", "EBEBEB", "D9D9D9", "EDEDED", "E6E6E6", "D6D6D6", "0A0A0A", "0A0A0A", "5A5A5A", "8C8C8C", "B4B4B4"),
        p("ice", "Ice", true, "EEF3F7", "E6EDF2", "FFFFFF", "F6F9FB", "E3EBF1", "CCD9E3", "E1E8EE", "D9E2E9", "C6D3DE", "0E1620", "2F6FA8", "52606E", "84919D", "AAB5BF"),
        p("paper", "Paper", true, "F7F5F0", "F1EEE8", "FFFEFB", "FBF9F5", "EDE9E1", "DCD6CB", "E9E5DD", "E2DDD4", "D2CBBF", "1B1915", "2B2823", "625C52", "938C80", "B9B2A6"),
        p("linen", "Linen", true, "F6F0E6", "F0E9DD", "FFFBF4", "FBF6ED", "EFE5D5", "E0D2BC", "EAE1D2", "E3D8C6", "D5C6AE", "231B12", "9A6B3F", "6B5D4C", "9A8B78", "BEB09C"),
        p("lavender", "Lavender", true, "F3F1F7", "ECE9F2", "FFFFFF", "F9F8FC", "E9E5F1", "D6D0E4", "E6E2EE", "DEDAE8", "CDC6DC", "16121F", "6B55B8", "5E5770", "8E879F", "B2ACC0"),
    )

    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: mono

    /** Pure black variant for dark palettes (the "Pure black" toggle). */
    fun pureBlack(p: Palette): Palette = if (p.light) p else p.copy(bg = Color.Black, side = Color.Black)
}
