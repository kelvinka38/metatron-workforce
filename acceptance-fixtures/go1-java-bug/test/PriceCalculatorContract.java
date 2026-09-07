public final class PriceCalculatorContract {
    private PriceCalculatorContract() {}

    public static void main(String[] args) {
        int actual = PriceCalculator.total(10, 3);
        if (actual != 13) {
            throw new AssertionError("expected total(10,3)=13 but was " + actual);
        }
        System.out.println("GO1_JAVA_FIXTURE=PASS");
    }
}
