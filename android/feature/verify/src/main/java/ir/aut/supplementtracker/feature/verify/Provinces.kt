package ir.aut.supplementtracker.feature.verify

/**
 * Iran's provinces as the lowercase slugs the backend stores for a custodian's
 * public region (`roles.normalizeRegion`); labels are shown in the app's language.
 */
data class Province(val slug: String, val english: String, val persian: String)

val Provinces: List<Province> =
    listOf(
        Province("tehran", "Tehran", "تهران"),
        Province("alborz", "Alborz", "البرز"),
        Province("isfahan", "Isfahan", "اصفهان"),
        Province("fars", "Fars", "فارس"),
        Province("khorasan-razavi", "Razavi Khorasan", "خراسان رضوی"),
        Province("east-azerbaijan", "East Azerbaijan", "آذربایجان شرقی"),
        Province("west-azerbaijan", "West Azerbaijan", "آذربایجان غربی"),
        Province("khuzestan", "Khuzestan", "خوزستان"),
        Province("mazandaran", "Mazandaran", "مازندران"),
        Province("gilan", "Gilan", "گیلان"),
        Province("kerman", "Kerman", "کرمان"),
        Province("qom", "Qom", "قم"),
        Province("markazi", "Markazi", "مرکزی"),
        Province("hamadan", "Hamadan", "همدان"),
        Province("kermanshah", "Kermanshah", "کرمانشاه"),
        Province("lorestan", "Lorestan", "لرستان"),
        Province("golestan", "Golestan", "گلستان"),
        Province("ardabil", "Ardabil", "اردبیل"),
        Province("qazvin", "Qazvin", "قزوین"),
        Province("zanjan", "Zanjan", "زنجان"),
        Province("semnan", "Semnan", "سمنان"),
        Province("yazd", "Yazd", "یزد"),
        Province("hormozgan", "Hormozgan", "هرمزگان"),
        Province("sistan-and-baluchestan", "Sistan and Baluchestan", "سیستان و بلوچستان"),
        Province("kurdistan", "Kurdistan", "کردستان"),
        Province("bushehr", "Bushehr", "بوشهر"),
        Province("ilam", "Ilam", "ایلام"),
        Province("chaharmahal-and-bakhtiari", "Chaharmahal and Bakhtiari", "چهارمحال و بختیاری"),
        Province("kohgiluyeh-and-boyer-ahmad", "Kohgiluyeh and Boyer-Ahmad", "کهگیلویه و بویراحمد"),
        Province("north-khorasan", "North Khorasan", "خراسان شمالی"),
        Province("south-khorasan", "South Khorasan", "خراسان جنوبی"),
    )

fun Province.label(persian: Boolean): String = if (persian) this.persian else english

/** Display name for a stored slug; unknown slugs (e.g. typed in by an admin) are shown as-is. */
fun regionLabel(slug: String?, persian: Boolean): String? {
    if (slug.isNullOrBlank()) return null
    return Provinces.firstOrNull { it.slug == slug.lowercase() }?.label(persian) ?: slug
}
