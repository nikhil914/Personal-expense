package app.ledgerly.data.db

import app.ledgerly.core.model.AccountType
import app.ledgerly.data.mapper.SetCodec

/** Default data inserted on first launch. Category ids are stable slugs ("food.delivery"). */
object SeedData {

    private data class Node(val id: String, val name: String, val icon: String, val color: String, val children: List<Pair<String, String>>, val income: Boolean = false)

    private val tree = listOf(
        Node("food", "Food & Drink", "restaurant", "#E57373", listOf("groceries" to "Groceries", "restaurants" to "Restaurants", "delivery" to "Food delivery", "coffee" to "Coffee & snacks")),
        Node("transport", "Transport", "directions_car", "#64B5F6", listOf("fuel" to "Fuel", "cab" to "Cabs & ride-hailing", "public" to "Public transport", "parking" to "Parking & tolls")),
        Node("shopping", "Shopping", "shopping_bag", "#BA68C8", listOf("clothing" to "Clothing", "electronics" to "Electronics", "online" to "Online shopping")),
        Node("bills", "Bills & Utilities", "receipt", "#FFB74D", listOf("rent" to "Rent", "electricity" to "Electricity", "mobile" to "Mobile & internet", "water_gas" to "Water & gas")),
        Node("entertainment", "Entertainment", "movie", "#F06292", listOf("streaming" to "Streaming & subscriptions", "events" to "Movies & events", "games" to "Games")),
        Node("health", "Health", "favorite", "#4DB6AC", listOf("pharmacy" to "Pharmacy", "doctor" to "Doctor & hospital", "fitness" to "Fitness")),
        Node("travel", "Travel", "flight", "#4FC3F7", listOf("flights" to "Flights", "hotels" to "Hotels", "fx_fees" to "Forex & card fees")),
        Node("education", "Education", "school", "#9575CD", listOf("courses" to "Courses", "books" to "Books")),
        Node("personal", "Personal care", "spa", "#A1887F", listOf("salon" to "Salon & grooming")),
        Node("home", "Home", "home", "#81C784", listOf("household" to "Household supplies", "maintenance" to "Repairs & maintenance")),
        Node("finance", "Financial", "account_balance", "#90A4AE", listOf("fees" to "Bank fees & charges", "interest" to "Interest paid", "emi" to "EMI & loans", "insurance" to "Insurance", "invest" to "Investments")),
        Node("cash", "Cash", "payments", "#AED581", listOf("atm" to "ATM withdrawal")),
        Node("transfer", "Transfers", "swap_horiz", "#B0BEC5", listOf("self" to "Between own accounts", "people" to "To people")),
        Node("income", "Income", "trending_up", "#66BB6A", listOf("salary" to "Salary", "refund" to "Refunds & cashback", "interest" to "Interest earned", "other" to "Other income"), income = true),
        Node("other", "Other", "category", "#BDBDBD", emptyList()),
    )

    val categories: List<CategoryEntity> = buildList {
        tree.forEachIndexed { i, node ->
            add(CategoryEntity(id = node.id, name = node.name, isIncome = node.income, icon = node.icon, colorHex = node.color, sortOrder = i * 100))
            node.children.forEachIndexed { j, (slug, name) ->
                add(CategoryEntity(id = "${node.id}.$slug", name = name, parentId = node.id, isIncome = node.income, icon = node.icon, colorHex = node.color, sortOrder = i * 100 + j + 1))
            }
        }
    }

    /** Common merchants with aliases (lowercase substrings) and default categories. */
    val merchants: List<MerchantEntity> = listOf(
        m("Zomato", "food.delivery", "zomato"),
        m("Swiggy", "food.delivery", "swiggy", "bundl technologies"),
        m("Uber", "transport.cab", "uber"),
        m("Ola", "transport.cab", "olacabs", "ola cabs", "ani technologies"),
        m("Rapido", "transport.cab", "rapido"),
        m("Amazon", "shopping.online", "amazon", "amzn"),
        m("Flipkart", "shopping.online", "flipkart"),
        m("Myntra", "shopping.clothing", "myntra"),
        m("Netflix", "entertainment.streaming", "netflix"),
        m("Spotify", "entertainment.streaming", "spotify"),
        m("YouTube Premium", "entertainment.streaming", "youtube", "google youtube"),
        m("Disney+ Hotstar", "entertainment.streaming", "hotstar", "disney"),
        m("Starbucks", "food.coffee", "starbucks", "tata starbucks"),
        m("BigBasket", "food.groceries", "bigbasket", "innovative retail"),
        m("Blinkit", "food.groceries", "blinkit", "grofers"),
        m("Zepto", "food.groceries", "zepto", "kiranakart"),
        m("IRCTC", "transport.public", "irctc"),
        m("MakeMyTrip", "travel.flights", "makemytrip"),
        m("Airtel", "bills.mobile", "airtel", "bharti"),
        m("Jio", "bills.mobile", "jio", "reliance jio"),
        m("BookMyShow", "entertainment.events", "bookmyshow", "bigtree"),
        m("Apollo Pharmacy", "health.pharmacy", "apollo pharmacy", "apollo"),
        m("Shell", "transport.fuel", "shell"),
        m("Indian Oil", "transport.fuel", "indian oil", "iocl"),
        m("Tesco", "food.groceries", "tesco"),
        m("Lidl", "food.groceries", "lidl"),
        m("Booking.com", "travel.hotels", "booking.com"),
        m("Airbnb", "travel.hotels", "airbnb"),
    )

    private fun m(name: String, category: String, vararg aliases: String) = MerchantEntity(
        id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
        name = name,
        aliases = SetCodec.encode(aliases.toList()),
        defaultCategoryId = category,
    )

    fun cashAccount(baseCurrency: String) = AccountEntity(id = "cash-$baseCurrency".lowercase(), name = "Cash", type = AccountType.CASH, currency = baseCurrency)

    fun monthlyBudget(baseCurrency: String) = BudgetEntity(id = "monthly", name = "Monthly budget", currency = baseCurrency)
}
