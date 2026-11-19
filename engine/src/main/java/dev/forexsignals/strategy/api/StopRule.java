package dev.forexsignals.strategy.api;

/**
 * How far the stop is from the reference price (ask for longs, bid for shorts, at alert time). The strategy cannot see
 * the reference price (it is a tick), so it gives a rule and the engine computes the level:
 *  - without a structure level: distance = atrMultiple * ATR;
 *  - with a structure level (e.g. the 10-bar low): distance = clamp(|reference - level|, minAtr * ATR, maxAtr * ATR).
 */
public record StopRule(double atr, double atrMultiple, double structureLevel, double minAtr, double maxAtr) {

    public static StopRule atrMultiple(double atr, double multiple) {
        return new StopRule(atr, multiple, Double.NaN, Double.NaN, Double.NaN);
    }

    public static StopRule structure(double atr, double level, double minAtr, double maxAtr) {
        return new StopRule(atr, Double.NaN, level, minAtr, maxAtr);
    }

    public double distance(double reference) {
        if (Double.isNaN(structureLevel)) return atrMultiple * atr;
        double d = Math.abs(reference - structureLevel);
        return Math.max(minAtr * atr, Math.min(maxAtr * atr, d));
    }
}
