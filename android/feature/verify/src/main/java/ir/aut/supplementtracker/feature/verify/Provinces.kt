package ir.aut.supplementtracker.feature.verify

/**
 * Iran's provinces as the lowercase slugs the backend compares against party
 * regions (`risk.NormalizeRegion`); labels are shown in the app's language.
 */
data class ScanRegion(val slug: String, val english: String, val persian: String)

val ScanRegions: List<ScanRegion> =
    listOf(
        ScanRegion("tehran", "Tehran", "تهران"),
        ScanRegion("alborz", "Alborz", "البرز"),
        ScanRegion("isfahan", "Isfahan", "اصفهان"),
        ScanRegion("fars", "Fars", "فارس"),
        ScanRegion("khorasan-razavi", "Razavi Khorasan", "خراسان رضوی"),
        ScanRegion("east-azerbaijan", "East Azerbaijan", "آذربایجان شرقی"),
        ScanRegion("west-azerbaijan", "West Azerbaijan", "آذربایجان غربی"),
        ScanRegion("khuzestan", "Khuzestan", "خوزستان"),
        ScanRegion("mazandaran", "Mazandaran", "مازندران"),
        ScanRegion("gilan", "Gilan", "گیلان"),
        ScanRegion("kerman", "Kerman", "کرمان"),
        ScanRegion("qom", "Qom", "قم"),
        ScanRegion("markazi", "Markazi", "مرکزی"),
        ScanRegion("hamadan", "Hamadan", "همدان"),
        ScanRegion("kermanshah", "Kermanshah", "کرمانشاه"),
        ScanRegion("lorestan", "Lorestan", "لرستان"),
        ScanRegion("golestan", "Golestan", "گلستان"),
        ScanRegion("ardabil", "Ardabil", "اردبیل"),
        ScanRegion("qazvin", "Qazvin", "قزوین"),
        ScanRegion("zanjan", "Zanjan", "زنجان"),
        ScanRegion("semnan", "Semnan", "سمنان"),
        ScanRegion("yazd", "Yazd", "یزد"),
        ScanRegion("hormozgan", "Hormozgan", "هرمزگان"),
        ScanRegion("sistan-and-baluchestan", "Sistan and Baluchestan", "سیستان و بلوچستان"),
        ScanRegion("kurdistan", "Kurdistan", "کردستان"),
        ScanRegion("bushehr", "Bushehr", "بوشهر"),
        ScanRegion("ilam", "Ilam", "ایلام"),
        ScanRegion("chaharmahal-and-bakhtiari", "Chaharmahal and Bakhtiari", "چهارمحال و بختیاری"),
        ScanRegion("kohgiluyeh-and-boyer-ahmad", "Kohgiluyeh and Boyer-Ahmad", "کهگیلویه و بویراحمد"),
        ScanRegion("north-khorasan", "North Khorasan", "خراسان شمالی"),
        ScanRegion("south-khorasan", "South Khorasan", "خراسان جنوبی"),
    )

fun ScanRegion.label(persian: Boolean): String = if (persian) this.persian else english

/** Display name for a stored slug; unknown slugs (e.g. typed in by an admin) are shown as-is. */
fun regionLabel(slug: String?, persian: Boolean): String? {
    if (slug.isNullOrBlank()) return null
    return ScanRegions.firstOrNull { it.slug == slug.lowercase() }?.label(persian) ?: slug
}
