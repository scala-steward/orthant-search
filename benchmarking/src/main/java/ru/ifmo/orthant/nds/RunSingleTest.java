package ru.ifmo.orthant.nds;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import ru.ifmo.orthant.PointSets;
import ru.ifmo.orthant.DivideConquerOrthantSearch;
import ru.ifmo.orthant.NaiveOrthantSearch;

public class RunSingleTest {
    public static void main(String[] args) {
        int n = 10000, d = 6, nInstances = 10;
        double[][][] instance = PointSets.generateUniformHypercube(nInstances, n, d);
        int[] ranks = new int[n];

        NonDominatedSorting[] sortings = {
                new NaiveImplementation(n, d),
                new OrthantImplementation(new NaiveOrthantSearch(n, d)),
                new OrthantImplementation(new DivideConquerOrthantSearch(n, d, false, 1)),
                new OrthantImplementation(new DivideConquerOrthantSearch(n, d, true, 1))
        };
        String[] sortingNames = {
                "Naive NDS:      ",
                "Orthant, naive: ",
                "Orthant, D&C:   ",
                "Orthant, D&C th:"
        };

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        bean.setThreadCpuTimeEnabled(true);

        for (int times = 0; times < 10; ++times) {
            for (int sortingIdx = 0; sortingIdx < sortings.length; ++sortingIdx) {
                NonDominatedSorting sorting = sortings[sortingIdx];
                System.out.print("Run " + times + ", algo " + sortingNames[sortingIdx]);
                long t0 = bean.getCurrentThreadUserTime();
                int sumOfAllRanks = 0;
                for (double[][] points : instance) {
                    sorting.sort(points, ranks);
                    for (int rank : ranks) {
                        sumOfAllRanks += rank;
                    }
                }
                long time = bean.getCurrentThreadUserTime() - t0;
                System.out.println(" " + (time / 1e9)
                        + " seconds, sum of ranks " + sumOfAllRanks);
            }
        }
    }
}
