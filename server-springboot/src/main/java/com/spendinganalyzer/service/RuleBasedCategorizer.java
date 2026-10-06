package com.spendinganalyzer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks a built-in category for a transaction from its description and direction, using fixed
 * keyword rules -- no network call, no model, the same answer every time for the same input.
 *
 * <p>What it deliberately doesn't do is guess. A debit no rule recognises is left uncategorized
 * rather than filed under "Other": correcting it by hand teaches merchant memory, which then
 * answers for that merchant on every later import, so an honest blank is worth more than a
 * confident wrong answer that nobody notices.
 *
 * <p>Rules are tried in order and the first match wins, which is how overlaps are settled:
 * fees before everything on a debit (a "TRANSFER FEE" is spent, not moved), then transfers (a
 * "PAYPAL TRANSFER" isn't shopping), subscriptions before shopping
 * ("AMAZON PRIME" isn't an Amazon order), food delivery before transport ("UBER EATS" isn't a ride).
 * Keywords match whole words only, so "BP" (the fuel brand) never fires inside "BPAY" or "SUBPOENA".
 */
public final class RuleBasedCategorizer {

    public static final String INCOME = "Income";
    public static final String TRANSFER = "Transfer";

    private record Rule(String category, Pattern pattern) {}

    private static final List<Rule> TRANSFER_RULES = rules(TRANSFER,
            "TRANSFER", "XFER", "ZELLE", "VENMO", "CASH APP",
            "CREDIT CARD PAYMENT", "CARD PAYMENT", "PAYMENT THANK YOU", "AUTOPAY", "AUTO PAY",
            "ONLINE PAYMENT", "TO SAVINGS", "FROM SAVINGS", "TO CHECKING", "FROM CHECKING",
            "ATM WITHDRAWAL", "CASH WITHDRAWAL");

    /** Checked before transfers on a debit: a "TRANSFER FEE" is money spent, not money moved. */
    private static final List<Rule> FEE_RULES = rules("Fees & Charges",
            "FEE", "FEES", "OVERDRAFT", "INTEREST CHARGE", "FINANCE CHARGE", "LATE CHARGE",
            "SERVICE CHARGE", "MAINTENANCE CHARGE", "NSF");

    /** Debit rules after fees and transfers, in priority order -- see the class comment. */
    private static final List<Rule> DEBIT_RULES = concat(
            rules("Subscriptions",
                    "NETFLIX", "SPOTIFY", "HULU", "DISNEY PLUS", "DISNEYPLUS", "HBO", "MAX.COM",
                    "PARAMOUNT", "PEACOCK", "YOUTUBE PREMIUM", "APPLE.COM/BILL", "ICLOUD", "AMAZON PRIME",
                    "PRIME VIDEO", "AUDIBLE", "PATREON", "SUBSCRIPTION", "ADOBE", "DROPBOX",
                    "MICROSOFT 365", "OPENAI", "GITHUB", "NYTIMES", "MEMBERSHIP"),
            rules("Dining & Coffee",
                    "UBER EATS", "UBEREATS", "DOORDASH", "GRUBHUB", "DELIVEROO", "JUST EAT", "POSTMATES",
                    "STARBUCKS", "DUNKIN", "COSTA", "COFFEE", "CAFE", "ESPRESSO", "TEA HOUSE", "BAKERY",
                    "RESTAURANT", "BISTRO", "DINER", "GRILL", "PIZZA", "PIZZERIA", "BURGER", "MCDONALD'S",
                    "MCDONALDS", "CHIPOTLE", "KFC", "TACO BELL", "TACO", "SUSHI", "NANDO'S", "NANDOS",
                    "PRET", "WENDY'S", "WENDYS", "DOMINO'S", "DOMINOS", "SUBWAY SANDWICH", "BAR", "PUB",
                    "BREWERY", "KITCHEN", "EATERY"),
            rules("Transportation",
                    "UBER", "LYFT", "TAXI", "CAB", "SHELL", "CHEVRON", "EXXON", "MOBIL", "BP", "TEXACO",
                    "ARCO", "SUNOCO", "CITGO", "VALERO", "GAS STATION", "FUEL", "PETROL", "PARKING",
                    "PARKMOBILE", "TOLL", "E-ZPASS", "EZPASS", "TRANSIT", "METRO", "MTA", "TFL", "BART",
                    "AMTRAK", "RAIL", "TRAIN", "BUS", "CAR WASH", "JIFFY LUBE", "AUTO REPAIR", "DMV"),
            rules("Groceries",
                    "GROCERY", "GROCERIES", "SUPERMARKET", "WHOLE FOODS", "WHOLEFDS", "TRADER JOE'S",
                    "TRADER JOES", "SAFEWAY", "KROGER", "ALDI", "LIDL", "TESCO", "SAINSBURY'S",
                    "SAINSBURYS", "ASDA", "WAITROSE", "MORRISONS", "PUBLIX", "WEGMANS", "SPROUTS",
                    "FOOD LION", "GIANT EAGLE", "H-E-B", "HEB", "MEIJER", "ALBERTSONS", "VONS",
                    "RALPHS", "COSTCO", "SAM'S CLUB", "SAMS CLUB", "INSTACART", "FRESH MARKET", "BUTCHER"),
            rules("Travel",
                    "AIRLINE", "AIRLINES", "AIRWAYS", "AIR CANADA", "DELTA AIR", "UNITED AIRLINES",
                    "AMERICAN AIRLINES", "SOUTHWEST", "JETBLUE", "RYANAIR", "EASYJET", "LUFTHANSA",
                    "EMIRATES", "HOTEL", "HOTELS", "MOTEL", "MARRIOTT", "HILTON", "HYATT", "IHG",
                    "AIRBNB", "VRBO", "EXPEDIA", "BOOKING.COM", "TRIVAGO", "HERTZ", "AVIS", "ENTERPRISE RENT",
                    "BUDGET RENT", "TRAVEL"),
            rules("Utilities",
                    "ELECTRIC", "ELECTRICITY", "ENERGY", "WATER", "SEWER", "UTILITY", "UTILITIES",
                    "GAS & ELECTRIC", "PG&E", "CON EDISON", "DUKE ENERGY", "BRITISH GAS", "COMCAST",
                    "XFINITY", "SPECTRUM", "VERIZON", "AT&T", "T-MOBILE", "TMOBILE", "SPRINT", "VODAFONE",
                    "INTERNET", "BROADBAND", "PHONE BILL", "WIRELESS", "TRASH", "WASTE MANAGEMENT"),
            rules("Rent/Mortgage",
                    "RENT", "MORTGAGE", "LANDLORD", "LEASE", "PROPERTY MANAGEMENT", "PROPERTY MGMT",
                    "APARTMENTS", "HOA"),
            rules("Healthcare",
                    "PHARMACY", "CVS", "WALGREENS", "RITE AID", "BOOTS", "DENTAL", "DENTIST",
                    "ORTHODONT", "CLINIC", "HOSPITAL", "MEDICAL", "DOCTOR", "PHYSICIAN", "HEALTH",
                    "OPTOMETRY", "OPTICIAN", "VISION", "LABCORP", "QUEST DIAGNOSTICS", "THERAPY", "URGENT CARE"),
            rules("Entertainment",
                    "CINEMA", "CINEMAS", "MOVIES", "AMC", "REGAL", "THEATRE", "THEATER", "TICKETMASTER",
                    "LIVE NATION", "EVENTBRITE", "STUBHUB", "CONCERT", "STEAM", "STEAMGAMES",
                    "PLAYSTATION", "XBOX", "NINTENDO", "BOWLING", "MUSEUM", "ZOO", "GOLF"),
            rules("Personal Care",
                    "SALON", "BARBER", "BARBERSHOP", "SPA", "NAILS", "SEPHORA", "ULTA", "GYM", "FITNESS",
                    "YOGA", "PILATES", "PLANET FITNESS", "EQUINOX", "COSMETICS"),
            rules("Education",
                    "TUITION", "UNIVERSITY", "COLLEGE", "SCHOOL", "ACADEMY", "COURSERA", "UDEMY",
                    "EDX", "SKILLSHARE", "TEXTBOOK", "STUDENT LOAN"),
            rules("Shopping",
                    "AMAZON", "AMZN", "TARGET", "WALMART", "EBAY", "ETSY", "BEST BUY", "IKEA", "HOME DEPOT",
                    "LOWE'S", "LOWES", "APPLE STORE", "KOHL'S", "KOHLS", "MACY'S", "MACYS", "NORDSTROM",
                    "ZARA", "H&M", "UNIQLO", "NIKE", "ADIDAS", "SHEIN", "TEMU", "ALIEXPRESS", "WAYFAIR",
                    "ARGOS", "JOHN LEWIS", "DEPARTMENT STORE", "OUTLET")
    );

    private RuleBasedCategorizer() {}

    /**
     * @param validCategories the categories that exist right now -- a rule naming a built-in that
     *                        has since been renamed or deleted is skipped rather than writing a
     *                        category that no longer exists
     * @return the category, or empty when no rule recognises a debit
     */
    public static Optional<String> categorize(String description, String type, Set<String> validCategories) {
        String text = " " + description.toUpperCase(Locale.ROOT) + " ";

        if (!"credit".equals(type)) {
            Optional<String> fee = firstMatch(FEE_RULES, text, validCategories);
            if (fee.isPresent()) return fee;
        }

        Optional<String> transfer = firstMatch(TRANSFER_RULES, text, validCategories);
        if (transfer.isPresent()) return transfer;

        // Money coming in that isn't a transfer is income -- salary, deposits, interest, refunds.
        // A refund is money back, not negative spend -- the same call categorization has always made.
        if ("credit".equals(type)) {
            return validCategories.contains(INCOME) ? Optional.of(INCOME) : Optional.empty();
        }
        return firstMatch(DEBIT_RULES, text, validCategories);
    }

    private static Optional<String> firstMatch(List<Rule> rules, String text, Set<String> validCategories) {
        for (Rule rule : rules) {
            if (validCategories.contains(rule.category()) && rule.pattern().matcher(text).find()) {
                return Optional.of(rule.category());
            }
        }
        return Optional.empty();
    }

    /**
     * One pattern per keyword, bounded by anything that isn't a letter or digit -- so "BP" needs
     * to stand alone, while "MCDONALD'S #4521" still matches "MCDONALD'S". Quoted, so "AT&T",
     * "H&M" and "BOOKING.COM" are matched literally.
     */
    private static List<Rule> rules(String category, String... keywords) {
        List<Rule> list = new ArrayList<>();
        for (String keyword : keywords) {
            list.add(new Rule(category,
                    Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(keyword) + "(?![A-Z0-9])")));
        }
        return list;
    }

    @SafeVarargs
    private static List<Rule> concat(List<Rule>... groups) {
        List<Rule> all = new ArrayList<>();
        for (List<Rule> group : groups) all.addAll(group);
        return List.copyOf(all);
    }
}
