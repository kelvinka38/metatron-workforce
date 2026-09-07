public final class PriceCalculator {
    private PriceCalculator() {}

    public static int total(int base, int serviceFee) {
        return base - serviceFee;
    }
}
