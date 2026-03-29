package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.PatternMapping;
import com.multi.vidulum.bank_data_ingestion.domain.PatternMappingRepository;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the database with known global patterns at application startup.
 *
 * Global patterns are well-known brands, institutions, and services
 * that can be categorized with high confidence without AI assistance.
 * This reduces AI costs and provides instant categorization.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GlobalPatternSeeder {

    private final PatternMappingRepository patternMappingRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void seedGlobalPatterns() {
        long existingCount = patternMappingRepository.countGlobal();

        if (existingCount > 0) {
            log.info("Global patterns already seeded ({} patterns found). Skipping.", existingCount);
            return;
        }

        List<PatternMapping> patterns = createGlobalPatterns();
        patternMappingRepository.saveAll(patterns);

        log.info("Seeded {} global patterns for AI categorization", patterns.size());
    }

    private List<PatternMapping> createGlobalPatterns() {
        List<PatternMapping> patterns = new ArrayList<>();

        // ═══════════════════════════════════════════════════════════════════════
        // POLISH GROCERY STORES
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("BIEDRONKA", "Groceries", "Food", 0.99));
        patterns.add(globalOutflow("LIDL", "Groceries", "Food", 0.99));
        patterns.add(globalOutflow("ŻABKA", "Groceries", "Food", 0.98));
        patterns.add(globalOutflow("ZABKA", "Groceries", "Food", 0.98));
        patterns.add(globalOutflow("KAUFLAND", "Groceries", "Food", 0.99));
        patterns.add(globalOutflow("AUCHAN", "Groceries", "Food", 0.99));
        patterns.add(globalOutflow("CARREFOUR", "Groceries", "Food", 0.99));
        patterns.add(globalOutflow("TESCO", "Groceries", "Food", 0.99));
        patterns.add(globalOutflow("NETTO", "Groceries", "Food", 0.98));
        patterns.add(globalOutflow("DINO", "Groceries", "Food", 0.98));
        patterns.add(globalOutflow("STOKROTKA", "Groceries", "Food", 0.98));
        patterns.add(globalOutflow("LEWIATAN", "Groceries", "Food", 0.97));
        patterns.add(globalOutflow("MAKRO", "Groceries", "Food", 0.95));
        patterns.add(globalOutflow("SELGROS", "Groceries", "Food", 0.95));

        // ═══════════════════════════════════════════════════════════════════════
        // POLISH MANDATORY FEES & GOVERNMENT
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("ZUS", "Social Security (ZUS)", "Mandatory Fees", 0.99));
        patterns.add(globalOutflow("ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH", "Social Security (ZUS)", "Mandatory Fees", 0.99));
        patterns.add(globalOutflow("URZĄD SKARBOWY", "Tax", "Mandatory Fees", 0.95));
        patterns.add(globalOutflow("US SKARBOWY", "Tax", "Mandatory Fees", 0.95));
        patterns.add(globalOutflow("PIT", "Income Tax (PIT)", "Mandatory Fees", 0.90));
        patterns.add(globalOutflow("VAT", "VAT", "Mandatory Fees", 0.90));
        patterns.add(globalOutflow("KRUS", "KRUS Insurance", "Mandatory Fees", 0.99));
        patterns.add(globalOutflow("NFZ", "Health Insurance (NFZ)", "Mandatory Fees", 0.99));

        // ═══════════════════════════════════════════════════════════════════════
        // STREAMING & ENTERTAINMENT
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("NETFLIX", "Streaming", "Entertainment", 0.99));
        patterns.add(globalOutflow("SPOTIFY", "Streaming", "Entertainment", 0.99));
        patterns.add(globalOutflow("HBO", "Streaming", "Entertainment", 0.98));
        patterns.add(globalOutflow("DISNEY", "Streaming", "Entertainment", 0.98));
        patterns.add(globalOutflow("AMAZON PRIME", "Streaming", "Entertainment", 0.98));
        patterns.add(globalOutflow("APPLE MUSIC", "Streaming", "Entertainment", 0.98));
        patterns.add(globalOutflow("YOUTUBE PREMIUM", "Streaming", "Entertainment", 0.98));
        patterns.add(globalOutflow("YOUTUBE", "Streaming", "Entertainment", 0.90));
        patterns.add(globalOutflow("TIDAL", "Streaming", "Entertainment", 0.98));
        patterns.add(globalOutflow("PLAYER", "Streaming", "Entertainment", 0.95));
        patterns.add(globalOutflow("CANAL+", "Streaming", "Entertainment", 0.98));
        patterns.add(globalOutflow("POLSAT BOX", "Streaming", "Entertainment", 0.98));

        // ═══════════════════════════════════════════════════════════════════════
        // ONLINE SHOPPING
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("ALLEGRO", "Online Shopping", "Shopping", 0.98));
        patterns.add(globalOutflow("AMAZON", "Online Shopping", "Shopping", 0.98));
        patterns.add(globalOutflow("ALIEXPRESS", "Online Shopping", "Shopping", 0.98));
        patterns.add(globalOutflow("ZALANDO", "Online Shopping", "Shopping", 0.98));
        patterns.add(globalOutflow("EMPIK", "Online Shopping", "Shopping", 0.97));
        patterns.add(globalOutflow("MEDIAEXPERT", "Electronics", "Shopping", 0.97));
        patterns.add(globalOutflow("MEDIA EXPERT", "Electronics", "Shopping", 0.97));
        patterns.add(globalOutflow("RTV EURO AGD", "Electronics", "Shopping", 0.97));
        patterns.add(globalOutflow("MEDIAMARKT", "Electronics", "Shopping", 0.97));
        patterns.add(globalOutflow("MEDIA MARKT", "Electronics", "Shopping", 0.97));
        patterns.add(globalOutflow("X-KOM", "Electronics", "Shopping", 0.97));
        patterns.add(globalOutflow("MORELE", "Electronics", "Shopping", 0.97));
        patterns.add(globalOutflow("KOMPUTRONIK", "Electronics", "Shopping", 0.97));

        // ═══════════════════════════════════════════════════════════════════════
        // FUEL & TRANSPORT
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("ORLEN", "Fuel", "Transport", 0.98));
        patterns.add(globalOutflow("PKN ORLEN", "Fuel", "Transport", 0.98));
        patterns.add(globalOutflow("BP", "Fuel", "Transport", 0.97));
        patterns.add(globalOutflow("SHELL", "Fuel", "Transport", 0.98));
        patterns.add(globalOutflow("CIRCLE K", "Fuel", "Transport", 0.97));
        patterns.add(globalOutflow("LOTOS", "Fuel", "Transport", 0.98));
        patterns.add(globalOutflow("MOYA", "Fuel", "Transport", 0.95));
        patterns.add(globalOutflow("AMIC", "Fuel", "Transport", 0.95));

        patterns.add(globalOutflow("PKP", "Public Transport", "Transport", 0.97));
        patterns.add(globalOutflow("INTERCITY", "Public Transport", "Transport", 0.97));
        patterns.add(globalOutflow("FLIXBUS", "Public Transport", "Transport", 0.98));
        patterns.add(globalOutflow("BOLT", "Taxi/Rideshare", "Transport", 0.98));
        patterns.add(globalOutflow("UBER", "Taxi/Rideshare", "Transport", 0.98));
        patterns.add(globalOutflow("FREE NOW", "Taxi/Rideshare", "Transport", 0.98));
        patterns.add(globalOutflow("FREENOW", "Taxi/Rideshare", "Transport", 0.98));
        patterns.add(globalOutflow("ITAXI", "Taxi/Rideshare", "Transport", 0.97));

        // ═══════════════════════════════════════════════════════════════════════
        // TELECOMS & INTERNET
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("ORANGE", "Mobile/Internet", "Utilities", 0.95));
        patterns.add(globalOutflow("T-MOBILE", "Mobile/Internet", "Utilities", 0.97));
        patterns.add(globalOutflow("PLAY", "Mobile/Internet", "Utilities", 0.95));
        patterns.add(globalOutflow("PLUS", "Mobile/Internet", "Utilities", 0.90));
        patterns.add(globalOutflow("VECTRA", "Internet/TV", "Utilities", 0.97));
        patterns.add(globalOutflow("UPC", "Internet/TV", "Utilities", 0.97));
        patterns.add(globalOutflow("NETIA", "Internet/TV", "Utilities", 0.97));
        patterns.add(globalOutflow("INEA", "Internet/TV", "Utilities", 0.97));

        // ═══════════════════════════════════════════════════════════════════════
        // UTILITIES
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("PGE", "Electricity", "Utilities", 0.97));
        patterns.add(globalOutflow("TAURON", "Electricity", "Utilities", 0.97));
        patterns.add(globalOutflow("ENEA", "Electricity", "Utilities", 0.97));
        patterns.add(globalOutflow("ENERGA", "Electricity", "Utilities", 0.97));
        patterns.add(globalOutflow("INNOGY", "Electricity", "Utilities", 0.97));
        patterns.add(globalOutflow("RWE", "Electricity", "Utilities", 0.97));
        patterns.add(globalOutflow("PGNIG", "Gas", "Utilities", 0.97));
        patterns.add(globalOutflow("PSG", "Gas", "Utilities", 0.95));
        patterns.add(globalOutflow("MPWIK", "Water", "Utilities", 0.97));
        patterns.add(globalOutflow("WODOCIĄGI", "Water", "Utilities", 0.95));
        patterns.add(globalOutflow("VEOLIA", "Utilities", "Utilities", 0.95));

        // ═══════════════════════════════════════════════════════════════════════
        // RESTAURANTS & FOOD DELIVERY
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("MCDONALD", "Fast Food", "Food", 0.98));
        patterns.add(globalOutflow("KFC", "Fast Food", "Food", 0.98));
        patterns.add(globalOutflow("BURGER KING", "Fast Food", "Food", 0.98));
        patterns.add(globalOutflow("SUBWAY", "Fast Food", "Food", 0.98));
        patterns.add(globalOutflow("PIZZA HUT", "Fast Food", "Food", 0.98));
        patterns.add(globalOutflow("DOMINOS", "Fast Food", "Food", 0.98));
        patterns.add(globalOutflow("STARBUCKS", "Coffee", "Food", 0.98));
        patterns.add(globalOutflow("COSTA COFFEE", "Coffee", "Food", 0.98));
        patterns.add(globalOutflow("COFFEEHEAVEN", "Coffee", "Food", 0.97));
        patterns.add(globalOutflow("PYSZNE.PL", "Food Delivery", "Food", 0.98));
        patterns.add(globalOutflow("PYSZNE", "Food Delivery", "Food", 0.98));
        patterns.add(globalOutflow("WOLT", "Food Delivery", "Food", 0.98));
        patterns.add(globalOutflow("GLOVO", "Food Delivery", "Food", 0.98));
        patterns.add(globalOutflow("UBER EATS", "Food Delivery", "Food", 0.98));

        // ═══════════════════════════════════════════════════════════════════════
        // HOME & FURNITURE
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("IKEA", "Furniture", "Home", 0.99));
        patterns.add(globalOutflow("CASTORAMA", "Home Improvement", "Home", 0.98));
        patterns.add(globalOutflow("LEROY MERLIN", "Home Improvement", "Home", 0.98));
        patterns.add(globalOutflow("OBI", "Home Improvement", "Home", 0.97));
        patterns.add(globalOutflow("BRICOMAN", "Home Improvement", "Home", 0.97));
        patterns.add(globalOutflow("JYSK", "Furniture", "Home", 0.98));
        patterns.add(globalOutflow("AGATA MEBLE", "Furniture", "Home", 0.98));
        patterns.add(globalOutflow("BLACK RED WHITE", "Furniture", "Home", 0.98));
        patterns.add(globalOutflow("BRW", "Furniture", "Home", 0.97));

        // ═══════════════════════════════════════════════════════════════════════
        // HEALTH & PHARMACY
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("APTEKA", "Pharmacy", "Health", 0.95));
        patterns.add(globalOutflow("DOZ", "Pharmacy", "Health", 0.97));
        patterns.add(globalOutflow("GEMINI", "Pharmacy", "Health", 0.95));
        patterns.add(globalOutflow("SUPER-PHARM", "Pharmacy", "Health", 0.97));
        patterns.add(globalOutflow("ROSSMANN", "Drugstore", "Health", 0.98));
        patterns.add(globalOutflow("HEBE", "Drugstore", "Health", 0.98));
        patterns.add(globalOutflow("DROGERIA", "Drugstore", "Health", 0.90));
        patterns.add(globalOutflow("MEDICOVER", "Medical", "Health", 0.98));
        patterns.add(globalOutflow("LUX MED", "Medical", "Health", 0.98));
        patterns.add(globalOutflow("LUXMED", "Medical", "Health", 0.98));
        patterns.add(globalOutflow("ENEL-MED", "Medical", "Health", 0.98));
        patterns.add(globalOutflow("CENTRUM MEDYCZNE", "Medical", "Health", 0.90));

        // ═══════════════════════════════════════════════════════════════════════
        // INSURANCE
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("PZU", "Insurance", "Insurance", 0.98));
        patterns.add(globalOutflow("WARTA", "Insurance", "Insurance", 0.97));
        patterns.add(globalOutflow("ALLIANZ", "Insurance", "Insurance", 0.98));
        patterns.add(globalOutflow("AXA", "Insurance", "Insurance", 0.98));
        patterns.add(globalOutflow("ERGO HESTIA", "Insurance", "Insurance", 0.98));
        patterns.add(globalOutflow("GENERALI", "Insurance", "Insurance", 0.98));
        patterns.add(globalOutflow("UNIQA", "Insurance", "Insurance", 0.97));
        patterns.add(globalOutflow("LINK4", "Insurance", "Insurance", 0.97));

        // ═══════════════════════════════════════════════════════════════════════
        // BANKS & FINANCIAL (OUTFLOW - fees)
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("PROWIZJA", "Bank Fees", "Banking", 0.95));
        patterns.add(globalOutflow("OPŁATA ZA", "Bank Fees", "Banking", 0.90));
        patterns.add(globalOutflow("ODSETKI", "Interest", "Banking", 0.90));
        patterns.add(globalOutflow("RATA KREDYTU", "Loan Payment", "Banking", 0.95));
        patterns.add(globalOutflow("SPŁATA KREDYTU", "Loan Payment", "Banking", 0.95));

        // ═══════════════════════════════════════════════════════════════════════
        // CLOTHING & FASHION
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("ZARA", "Clothing", "Shopping", 0.98));
        patterns.add(globalOutflow("H&M", "Clothing", "Shopping", 0.98));
        patterns.add(globalOutflow("RESERVED", "Clothing", "Shopping", 0.98));
        patterns.add(globalOutflow("SINSAY", "Clothing", "Shopping", 0.98));
        patterns.add(globalOutflow("CROPP", "Clothing", "Shopping", 0.98));
        patterns.add(globalOutflow("HOUSE", "Clothing", "Shopping", 0.95));
        patterns.add(globalOutflow("CCC", "Shoes", "Shopping", 0.97));
        patterns.add(globalOutflow("DEICHMANN", "Shoes", "Shopping", 0.98));
        patterns.add(globalOutflow("PEPCO", "Clothing", "Shopping", 0.97));
        patterns.add(globalOutflow("KIKI", "Clothing", "Shopping", 0.95));
        patterns.add(globalOutflow("TK MAXX", "Clothing", "Shopping", 0.98));

        // ═══════════════════════════════════════════════════════════════════════
        // EDUCATION
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("CZESNE", "Tuition", "Education", 0.95));
        patterns.add(globalOutflow("SZKOŁA", "School", "Education", 0.90));
        patterns.add(globalOutflow("PRZEDSZKOLE", "Kindergarten", "Education", 0.95));
        patterns.add(globalOutflow("UNIWERSYTET", "University", "Education", 0.95));
        patterns.add(globalOutflow("POLITECHNIKA", "University", "Education", 0.95));
        patterns.add(globalOutflow("KURS", "Course", "Education", 0.85));
        patterns.add(globalOutflow("SZKOLENIE", "Training", "Education", 0.90));

        // ═══════════════════════════════════════════════════════════════════════
        // SOFTWARE & SUBSCRIPTIONS
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalOutflow("MICROSOFT", "Software", "Subscriptions", 0.95));
        patterns.add(globalOutflow("GOOGLE", "Software", "Subscriptions", 0.90));
        patterns.add(globalOutflow("APPLE", "Software", "Subscriptions", 0.90));
        patterns.add(globalOutflow("ADOBE", "Software", "Subscriptions", 0.98));
        patterns.add(globalOutflow("DROPBOX", "Cloud Storage", "Subscriptions", 0.98));
        patterns.add(globalOutflow("ICLOUD", "Cloud Storage", "Subscriptions", 0.98));
        patterns.add(globalOutflow("GITHUB", "Software", "Subscriptions", 0.98));
        patterns.add(globalOutflow("JETBRAINS", "Software", "Subscriptions", 0.98));
        patterns.add(globalOutflow("OPENAI", "AI Services", "Subscriptions", 0.98));
        patterns.add(globalOutflow("CHATGPT", "AI Services", "Subscriptions", 0.98));
        patterns.add(globalOutflow("ANTHROPIC", "AI Services", "Subscriptions", 0.98));
        patterns.add(globalOutflow("CLAUDE", "AI Services", "Subscriptions", 0.95));

        // ═══════════════════════════════════════════════════════════════════════
        // COMMON INFLOW PATTERNS
        // ═══════════════════════════════════════════════════════════════════════
        patterns.add(globalInflow("WYNAGRODZENIE", "Salary", "Income", 0.95));
        patterns.add(globalInflow("PENSJA", "Salary", "Income", 0.95));
        patterns.add(globalInflow("PREMIA", "Bonus", "Income", 0.95));
        patterns.add(globalInflow("ZWROT", "Refund", "Income", 0.85));
        patterns.add(globalInflow("ZWROT PODATKU", "Tax Refund", "Income", 0.95));
        patterns.add(globalInflow("DYWIDENDA", "Dividend", "Investment Income", 0.95));
        patterns.add(globalInflow("ODSETKI", "Interest", "Investment Income", 0.85));
        patterns.add(globalInflow("EMERYTURA", "Pension", "Income", 0.98));
        patterns.add(globalInflow("RENTA", "Disability Benefit", "Income", 0.98));
        patterns.add(globalInflow("500+", "Child Benefit", "Income", 0.99));
        patterns.add(globalInflow("800+", "Child Benefit", "Income", 0.99));
        patterns.add(globalInflow("RODZINA", "Child Benefit", "Income", 0.85));
        patterns.add(globalInflow("ZASIŁEK", "Benefit", "Income", 0.90));

        return patterns;
    }

    private static PatternMapping globalOutflow(String pattern, String category, String parent, double confidence) {
        return PatternMapping.createGlobal(pattern, category, parent, Type.OUTFLOW, confidence);
    }

    private static PatternMapping globalInflow(String pattern, String category, String parent, double confidence) {
        return PatternMapping.createGlobal(pattern, category, parent, Type.INFLOW, confidence);
    }
}
