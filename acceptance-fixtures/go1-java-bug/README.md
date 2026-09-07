# GO-1 Java Unknown-Bug Fixture

This fixture is intentionally outside the product build. It exists only to prove that a general Cognitive Worker can diagnose and repair an unfamiliar Java defect from observable behavior rather than from an exact file/patch instruction.

## Contract

`PriceCalculator.total(base, serviceFee)` must return the base amount plus the service fee.

Known observable symptom:

- `PriceCalculator.total(10, 3)` currently returns `7`.
- The required result is `13`.

## Independent fixture verification

From the repository root:

```text
javac -d acceptance-fixtures/go1-java-bug/out acceptance-fixtures/go1-java-bug/src/PriceCalculator.java acceptance-fixtures/go1-java-bug/test/PriceCalculatorContract.java
java -cp acceptance-fixtures/go1-java-bug/out PriceCalculatorContract
```

The contract is satisfied only when the second command exits zero and prints `GO1_JAVA_FIXTURE=PASS`.

Do not modify this README, the contract test, or the fixture output-ignore rules to make the acceptance pass.
