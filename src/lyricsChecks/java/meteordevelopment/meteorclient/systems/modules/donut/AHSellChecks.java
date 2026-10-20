package meteordevelopment.meteorclient.systems.modules.donut;

/** Regression cases for bulk-vs-single pricing and auction confirmation parsing, without connecting to a server. */
public final class AHSellChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        double bulk = AHSell.comparablePrice(177_000, 64, 1, true);
        double single = AHSell.comparablePrice(49_000, 1, 1, true);
        check(bulk == Double.MAX_VALUE, "A bulk stack must not price a single Note Block at 2765");
        check(AHSell.undercutPrice(Math.min(bulk, single), 1, 1, 21_500) == 48_999, "Undercut the matching single-item listing");
        check(AHSell.comparablePrice(177_000, 64, 64, true) == 2765.625, "Bulk comparisons remain available for bulk sales");
        check(AHSell.undercutPrice(2765.625, 64, 1, 0) == 176_999, "Undercut applies to the whole listing");
        check(AHSell.comparablePrice(120_000, 64, 1, false) == 1875, "Explicit all-size mode retains per-unit comparisons");
        check(AHSell.undercutPrice(2765.625, 1, 1, 21_500) == 21_500, "Respect the per-item minimum");
        check(AHSell.fallbackPrice(1000, 1, 21_500) == 21_500, "No matching auction cannot bypass the minimum");
        check(AHSell.fallbackPrice(38_000, 3, 21_500) == 114_000, "Fallback uses the configured per-item price and actual quantity");
        check(AHSell.undercutPrice(10, 4, 100, 0) == 1, "Never create zero or negative listing prices");
        check(AHSell.comparablePrice(100, 0, 1, true) == Double.MAX_VALUE, "Ignore empty or invalid stacks");
        check(AHSell.undercutPrice(Double.NaN, 1, 1, 0) == -1, "Reject damaged market prices");

        check(AHSell.totalPriceToken("Price Each: $2.7K") == null, "Each price is not a whole-stack total");
        check(AHSell.totalPriceToken("WORTH: $120,000") == null, "Ignore worth regardless of capitalization");
        check(AHSell.totalPriceToken("Your balance: $1M") == null, "Balance is not a listing price");
        check(AHSell.totalPriceToken("Tax: $49") == null, "Tax is not a listing price");
        String token = AHSell.totalPriceToken("\u00a7aPrice: $\u00a049.0K");
        check(token != null && AHSell.parsePrice(token) == 49_000, "Read formatted price with nonbreaking space");
        check(AHSell.parsePrice("1,500,000") == 1_500_000 && AHSell.parsePrice("1.5m") == 1_500_000, "Full and abbreviated prices agree");
        check(AHSell.parsePrice("NaN") == -1 && AHSell.parsePrice("Infinity") == -1, "Reject nonfinite prices");

        for (String message : new String[]{"You listed 1 Note Block for $48,999", "[Auction] You have listed a Note Block", "\u00bb You listed an item", "\u00a7aYOU LISTED an item"})
            check(AHSell.confirmsListing(message), "Accept server listing confirmation: " + message);
        for (String message : new String[]{"You have too many listed items", "Player: You listed a Note Block", "[VIP] Player: You listed an item", "No response", "You bought an item"})
            check(!AHSell.confirmsListing(message), "Do not confirm a refusal or player chat: " + message);
        System.out.println("AH Sell checks passed: bulk/single comparisons, listing totals, minimums, price lore and server confirmation parsing.");
    }
}
