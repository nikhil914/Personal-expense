package app.ledgerly.core.insights

enum class ServiceCategory { VIDEO, MUSIC, SHOPPING, CLOUD, SOFTWARE, AI, NEWS, FITNESS, GAMING, PRODUCTIVITY, FOOD, TELECOM, EDUCATION, OTHER }

/**
 * A well-known subscription service.
 * @param patterns lowercase substrings matched against the normalised merchant key (word-boundary aware).
 * @param trialDays typical free-trial length, if the service commonly offers one.
 */
data class KnownService(
    val id: String,
    val name: String,
    val patterns: List<String>,
    val cancelUrl: String,
    val category: ServiceCategory,
    val trialDays: Int? = null,
)

/** Built-in catalogue of popular subscription services with cancel-page URLs. */
object KnownServices {
    val all: List<KnownService> = listOf(
        KnownService("netflix", "Netflix", listOf("netflix"), "https://www.netflix.com/cancelplan", ServiceCategory.VIDEO),
        KnownService("spotify", "Spotify", listOf("spotify"), "https://www.spotify.com/account/subscription/", ServiceCategory.MUSIC, 30),
        KnownService("prime", "Amazon Prime", listOf("amazon prime", "prime video", "primevideo", "amzn prime", "amazon digital"), "https://www.amazon.com/mc/pipelines/cancellation", ServiceCategory.SHOPPING, 30),
        KnownService("jiohotstar", "JioHotstar", listOf("jiohotstar", "hotstar", "disney hotstar", "disney+ hotstar", "novi digital"), "https://www.hotstar.com/in/subscribe/my-account", ServiceCategory.VIDEO),
        KnownService("disneyplus", "Disney+", listOf("disney plus", "disneyplus", "disney+"), "https://www.disneyplus.com/account/subscription", ServiceCategory.VIDEO, 7),
        KnownService("youtube_premium", "YouTube Premium", listOf("youtube premium", "youtube", "google youtube", "yt premium"), "https://www.youtube.com/paid_memberships", ServiceCategory.VIDEO, 30),
        KnownService("icloud", "iCloud+", listOf("icloud", "apple icloud"), "https://support.apple.com/en-us/HT207594", ServiceCategory.CLOUD),
        KnownService("apple_music", "Apple Music", listOf("apple music"), "https://support.apple.com/en-us/HT202039", ServiceCategory.MUSIC, 30),
        KnownService("apple_tv", "Apple TV+", listOf("apple tv"), "https://support.apple.com/en-us/HT202039", ServiceCategory.VIDEO, 7),
        KnownService("apple_one", "Apple One", listOf("apple one", "apple com bill", "apple bill", "itunes"), "https://support.apple.com/en-us/HT202039", ServiceCategory.OTHER),
        KnownService("google_one", "Google One", listOf("google one", "google storage", "googleone"), "https://one.google.com/settings", ServiceCategory.CLOUD),
        KnownService("google_play", "Google Play subscription", listOf("google play", "play store"), "https://play.google.com/store/account/subscriptions", ServiceCategory.OTHER),
        KnownService("chatgpt", "ChatGPT Plus", listOf("chatgpt", "openai"), "https://chatgpt.com/#settings/Subscription", ServiceCategory.AI),
        KnownService("claude", "Claude", listOf("claude ai", "claude", "anthropic"), "https://claude.ai/settings/billing", ServiceCategory.AI),
        KnownService("gemini", "Google AI Pro (Gemini)", listOf("gemini", "google ai pro"), "https://one.google.com/settings", ServiceCategory.AI),
        KnownService("perplexity", "Perplexity Pro", listOf("perplexity"), "https://www.perplexity.ai/settings/account", ServiceCategory.AI),
        KnownService("copilot", "GitHub Copilot", listOf("github", "copilot"), "https://github.com/settings/billing", ServiceCategory.SOFTWARE, 30),
        KnownService("adobe", "Adobe Creative Cloud", listOf("adobe"), "https://account.adobe.com/plans", ServiceCategory.SOFTWARE, 7),
        KnownService("microsoft365", "Microsoft 365", listOf("microsoft 365", "microsoft365", "office 365", "msft", "microsoft"), "https://account.microsoft.com/services", ServiceCategory.PRODUCTIVITY, 30),
        KnownService("xbox", "Xbox Game Pass", listOf("xbox", "game pass"), "https://account.microsoft.com/services", ServiceCategory.GAMING),
        KnownService("playstation", "PlayStation Plus", listOf("playstation", "psn", "sony interactive"), "https://www.playstation.com/en-us/support/subscriptions/cancel-playstation-subscription/", ServiceCategory.GAMING),
        KnownService("nintendo", "Nintendo Switch Online", listOf("nintendo"), "https://accounts.nintendo.com/", ServiceCategory.GAMING),
        KnownService("dropbox", "Dropbox", listOf("dropbox"), "https://www.dropbox.com/account/plan", ServiceCategory.CLOUD, 30),
        KnownService("notion", "Notion", listOf("notion"), "https://www.notion.so/my-account", ServiceCategory.PRODUCTIVITY),
        KnownService("canva", "Canva Pro", listOf("canva"), "https://www.canva.com/settings/billing-and-teams", ServiceCategory.SOFTWARE, 30),
        KnownService("zoom", "Zoom", listOf("zoom"), "https://zoom.us/billing", ServiceCategory.PRODUCTIVITY),
        KnownService("linkedin", "LinkedIn Premium", listOf("linkedin"), "https://www.linkedin.com/premium/manage", ServiceCategory.PRODUCTIVITY, 30),
        KnownService("audible", "Audible", listOf("audible"), "https://www.audible.com/account/overview", ServiceCategory.MUSIC, 30),
        KnownService("kindle_unlimited", "Kindle Unlimited", listOf("kindle"), "https://www.amazon.com/kindle-dbs/ku/ku-central", ServiceCategory.EDUCATION, 30),
        KnownService("sonyliv", "SonyLIV", listOf("sonyliv", "sony liv"), "https://www.sonyliv.com/myaccount", ServiceCategory.VIDEO),
        KnownService("zee5", "ZEE5", listOf("zee5"), "https://www.zee5.com/myaccount/subscription", ServiceCategory.VIDEO),
        KnownService("jiosaavn", "JioSaavn Pro", listOf("jiosaavn", "saavn"), "https://www.jiosaavn.com/settings", ServiceCategory.MUSIC),
        KnownService("gaana", "Gaana Plus", listOf("gaana"), "https://gaana.com/myaccount", ServiceCategory.MUSIC),
        KnownService("swiggy_one", "Swiggy One", listOf("swiggy one"), "https://www.swiggy.com/my-account", ServiceCategory.FOOD),
        KnownService("zomato_gold", "Zomato Gold", listOf("zomato gold"), "https://www.zomato.com/gold", ServiceCategory.FOOD),
        KnownService("hulu", "Hulu", listOf("hulu"), "https://secure.hulu.com/account/cancel", ServiceCategory.VIDEO, 30),
        KnownService("max", "Max (HBO)", listOf("hbo max", "hbomax", "hbo"), "https://auth.max.com/subscription", ServiceCategory.VIDEO),
        KnownService("paramount", "Paramount+", listOf("paramount"), "https://www.paramountplus.com/account/", ServiceCategory.VIDEO, 7),
        KnownService("nyt", "The New York Times", listOf("new york times", "nytimes", "nyt"), "https://myaccount.nytimes.com/seg/subscription", ServiceCategory.NEWS),
        KnownService("medium", "Medium", listOf("medium"), "https://medium.com/me/settings/membership", ServiceCategory.NEWS),
        KnownService("duolingo", "Duolingo Super", listOf("duolingo"), "https://www.duolingo.com/settings/super", ServiceCategory.EDUCATION, 14),
        KnownService("coursera", "Coursera Plus", listOf("coursera"), "https://www.coursera.org/my-purchases", ServiceCategory.EDUCATION, 7),
        KnownService("nordvpn", "NordVPN", listOf("nordvpn", "nord vpn"), "https://my.nordaccount.com/dashboard/nordvpn/", ServiceCategory.SOFTWARE),
        KnownService("1password", "1Password", listOf("1password", "agilebits"), "https://my.1password.com/settings/billing", ServiceCategory.SOFTWARE, 14),
        KnownService("cultfit", "cult.fit", listOf("cultfit", "cult fit", "curefit"), "https://www.cult.fit/me/memberships", ServiceCategory.FITNESS),
        KnownService("golds_gym", "Gold's Gym", listOf("gold s gym", "golds gym"), "https://www.goldsgym.com/", ServiceCategory.FITNESS),
        KnownService("anytime_fitness", "Anytime Fitness", listOf("anytime fitness"), "https://www.anytimefitness.com/", ServiceCategory.FITNESS),
        KnownService("planet_fitness", "Planet Fitness", listOf("planet fitness"), "https://www.planetfitness.com/my-account", ServiceCategory.FITNESS),
        KnownService("puregym", "PureGym", listOf("puregym", "pure gym"), "https://www.puregym.com/members/", ServiceCategory.FITNESS),
        KnownService("strava", "Strava", listOf("strava"), "https://www.strava.com/account", ServiceCategory.FITNESS, 30),
        KnownService("peloton", "Peloton", listOf("peloton"), "https://members.onepeloton.com/preferences/subscriptions", ServiceCategory.FITNESS, 30),
        KnownService("headspace", "Headspace", listOf("headspace"), "https://www.headspace.com/subscriptions", ServiceCategory.FITNESS, 14),
        KnownService("tinder", "Tinder", listOf("tinder"), "https://tinder.com/", ServiceCategory.OTHER),
        KnownService("uber_one", "Uber One", listOf("uber one"), "https://account.uber.com/", ServiceCategory.OTHER, 30),
    )

    /** Matches a raw or normalised merchant string to a known service (longest pattern wins). */
    fun match(merchant: String?): KnownService? {
        val key = MerchantNormalizer.normalize(merchant) ?: return null
        val padded = " $key "
        val rawPadded = " ${merchant!!.lowercase().replace(Regex("[^a-z0-9+ ]"), " ").replace(Regex("\\s+"), " ").trim()} "
        var best: KnownService? = null
        var bestLen = 0
        for (s in all) for (p in s.patterns) {
            if (p.length > bestLen && (padded.contains(" $p ") || rawPadded.contains(" $p "))) {
                best = s; bestLen = p.length
            }
        }
        return best
    }

    fun byId(id: String): KnownService? = all.firstOrNull { it.id == id }
}
