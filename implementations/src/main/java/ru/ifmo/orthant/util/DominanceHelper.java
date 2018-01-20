package ru.ifmo.orthant.util;

public final class DominanceHelper {
    private DominanceHelper() {}

    public static boolean strictlyDominates(double[] a, double[] b, int dimension) {
        boolean hasLess = false;
        for (int i = 0; i < dimension; ++i) {
            double aa = a[i], bb = b[i];
            if (aa > bb) {
                return false;
            }
            hasLess |= aa < bb;
        }
        return hasLess;
    }

    public static boolean weaklyDominates(double[] a, double[] b, int dimension) {
        for (int i = 0; i < dimension; ++i) {
            double aa = a[i], bb = b[i];
            if (aa > bb) {
                return false;
            }
        }
        return true;
    }

    public static boolean strictlyDominates(double[] a, double[] b, int dimension, boolean[] isStrict) {
        boolean isEqual = true;
        for (int i = 0; i < dimension; ++i) {
            double g = a[i], w = b[i];
            if (isStrict[i] ? g >= w : g > w) {
                return false;
            }
            isEqual &= g == w;
        }
        return !isEqual;
    }
}
