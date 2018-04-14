package ru.ifmo.orthant.nds.extra;

import java.util.Arrays;

import ru.ifmo.orthant.nds.NonDominatedSorting;
import ru.ifmo.orthant.nds.extra.util.ArrayHelper;
import ru.ifmo.orthant.nds.extra.util.DoubleArraySorter;
import ru.ifmo.orthant.nds.extra.util.RedBlackTree;
import ru.ifmo.orthant.nds.extra.util.SplitMergeHelper;

/**
 * This class was imported from the following GitHub repository:
 * https://github.com/mbuzdalov/non-dominated-sorting
 * and adapted according to the needs of this repository.
 *
 * This particular class is a result of merge of the following two classes:
 * <ul>
 *     <li>ru.ifmo.nds.jfb.AbstractJFBSorting</li>
 *     <li>ru.ifmo.nds.jfb.RedBlackTreeSweepHybridENS</li>
 * </ul>
 *
 * The particular revision location is:
 * https://github.com/mbuzdalov/non-dominated-sorting/tree/56fcfc61f5a4009e8ed02c0c3a4b00d390ba6aff
 */
public class HybridSorting extends NonDominatedSorting {
    private static final int STORAGE_MULTIPLE = 5;
    private static final int THRESHOLD_3D = 100;
    private static final int THRESHOLD_ALL = 200;

    // Shared resources
    private final int[] indices;
    private final int[] ranks;
    private final int[] space;

    // Data which is immutable throughout the actual sorting.
    private final double[][] points;
    private final double[][] transposedPoints;

    // This is used in preparation phase or in 2D-only sweep.
    private final DoubleArraySorter sorter;
    private final int[] internalIndices;
    private final double[] lastFrontOrdinates;

    // Data which is interval-shared between threads.
    private final double[] medianSwap;
    private final RedBlackTree rankQuery;
    private final SplitMergeHelper splitMerge;

    public HybridSorting(int maximumPoints, int maximumDimension) {
        sorter = new DoubleArraySorter(maximumPoints);
        medianSwap = new double[maximumPoints];
        indices = new int[maximumPoints];
        ranks = new int[maximumPoints];
        points = new double[maximumPoints][];
        transposedPoints = new double[maximumDimension][maximumPoints];
        rankQuery = new RedBlackTree(maximumPoints);

        internalIndices = new int[maximumPoints];
        lastFrontOrdinates = new double[maximumPoints];
        splitMerge = new SplitMergeHelper(maximumPoints);
        space = new int[maximumPoints * STORAGE_MULTIPLE];
    }

    @Override
    public int getMaximumPoints() {
        return points.length;
    }

    @Override
    public int getMaximumDimension() {
        return transposedPoints.length;
    }

    @Override
    public final void sort(double[][] points, int[] ranks) {
        final int n = points.length;
        final int dim = points[0].length;
        Arrays.fill(ranks, 0);
        if (dim == 0) {
            return;
        }
        ArrayHelper.fillIdentity(internalIndices, n);
        sorter.lexicographicalSort(points, internalIndices, 0, n, dim);

        if (dim == 1) {
            // 1: This is equivalent to ordinary sorting.
            for (int i = 0, r = 0; i < n; ++i) {
                int ii = internalIndices[i];
                ranks[ii] = r;
                if (i + 1 < n && points[ii][0] != points[internalIndices[i + 1]][0]) {
                    ++r;
                }
            }
        } else if (dim == 2) {
            // 2: Special case: binary search.
            twoDimensionalCase(points, ranks);
        } else {
            // 3: General case.
            // 3.1: Moving points in a sorted order to internal structures
            final int newN = DoubleArraySorter.retainUniquePoints(points, internalIndices, this.points, ranks);
            Arrays.fill(this.ranks, 0, newN, 0);
            ArrayHelper.fillIdentity(this.indices, newN);

            // 3.2: Transposing points. This should fit in cache for reasonable dimensions.
            for (int i = 0; i < newN; ++i) {
                for (int j = 0; j < dim; ++j) {
                    transposedPoints[j][i] = this.points[i][j];
                }
            }

            // 3.3: Calling the actual sorting
            helperA(0, newN, dim - 1);

            // 3.4: Applying the results back. After that, the argument "ranks" array stops being abused.
            for (int i = 0; i < n; ++i) {
                ranks[i] = this.ranks[ranks[i]];
                this.points[i] = null;
            }
        }
    }

    private boolean strictlyDominatesAssumingNotSame(int goodIndex, int weakIndex, int maxObj) {
        double[] goodPoint = points[goodIndex];
        double[] weakPoint = points[weakIndex];
        // Comparison in 0 makes no sense, as due to goodIndex < weakIndex the points are <= in this coordinate.
        for (int i = maxObj; i > 0; --i) {
            if (goodPoint[i] > weakPoint[i]) {
                return false;
            }
        }
        return true;
    }

    private void tryUpdateRank(int goodIndex, int weakIndex) {
        int rg = ranks[goodIndex];
        if (ranks[weakIndex] <= rg) {
            ranks[weakIndex] = 1 + rg;
        }
    }

    private void sweepA(int from, int until) {
        double[] local = transposedPoints[1];
        RedBlackTree.RangeHandle rankQuery = this.rankQuery.createHandle(from);
        for (int i = from; i < until; ++i) {
            int curr = indices[i];
            double currY = local[curr];
            int result = Math.max(ranks[curr], rankQuery.getMaximumWithKeyAtMost(currY) + 1);
            ranks[curr] = result;
            rankQuery.put(currY, result);
        }
    }

    private void sweepB(int goodFrom, int goodUntil, int weakFrom, int weakUntil, int tempFrom) {
        double[] local = transposedPoints[1];
        RedBlackTree.RangeHandle rankQuery = this.rankQuery.createHandle(tempFrom);
        int goodI = goodFrom;
        for (int weakI = weakFrom; weakI < weakUntil; ++weakI) {
            int weakCurr = indices[weakI];
            while (goodI < goodUntil && indices[goodI] < weakCurr) {
                int goodCurr = indices[goodI++];
                rankQuery.put(local[goodCurr], ranks[goodCurr]);
            }
            int result = Math.max(ranks[weakCurr],
                    rankQuery.getMaximumWithKeyAtMost(local[weakCurr]) + 1);
            ranks[weakCurr] = result;
        }
    }

    private boolean helperAHookCondition(int size, int obj) {
        switch (obj) {
            case 1: return false;
            case 2: return size < THRESHOLD_3D;
            default: return size < THRESHOLD_ALL;
        }
    }

    private boolean checkIfDominatesA(int sliceIndex, int obj, int weakIndex) {
        int sliceRank = space[sliceIndex];
        if (ranks[weakIndex] > sliceRank) {
            return true;
        }
        int virtualGoodIndex = space[sliceIndex + 2];
        while (virtualGoodIndex != -1) {
            int realGoodIndex = space[virtualGoodIndex];
            if (strictlyDominatesAssumingNotSame(realGoodIndex, weakIndex, obj)) {
                ranks[weakIndex] = 1 + sliceRank;
                return true;
            }
            virtualGoodIndex = space[virtualGoodIndex + 1];
        }
        return false;
    }

    private void initNewSliceA(int prevSlice, int currSlice, int nextSlice, int rank, int firstPointIndex) {
        space[currSlice] = rank;
        space[currSlice + 1] = nextSlice;
        space[currSlice + 2] = firstPointIndex;
        if (prevSlice != -1) {
            space[prevSlice + 1] = currSlice;
        }
    }

    private void helperAHook(int from, int until, int obj) {
        int sliceOffset = from * STORAGE_MULTIPLE;
        int pointOffset = sliceOffset + 3 * (until - from);

        int sliceCurrent = sliceOffset - 3;
        int sliceFirst = -1;

        for (int i = from, pointIndex = pointOffset; i < until; ++i) {
            int ii = indices[i];
            if (sliceFirst == -1 || checkIfDominatesA(sliceFirst, obj, ii)) {
                sliceCurrent += 3;
                initNewSliceA(-1, sliceCurrent, sliceFirst, ranks[ii], pointIndex);
                space[pointIndex] = ii;
                space[pointIndex + 1] = -1;
                sliceFirst = sliceCurrent;
                pointIndex += 2;
            } else {
                int prevSlice = sliceFirst, nextSlice;
                while ((nextSlice = space[prevSlice + 1]) != -1) {
                    if (checkIfDominatesA(nextSlice, obj, ii)) {
                        break;
                    }
                    prevSlice = nextSlice;
                }
                // prevSlice does not dominate, nextSlice already dominates
                space[pointIndex] = ii;
                int currRank = ranks[ii];
                if (currRank == space[prevSlice]) {
                    // insert the current point into prevSlice
                    space[pointIndex + 1] = space[prevSlice + 2];
                    space[prevSlice + 2] = pointIndex;
                } else {
                    sliceCurrent += 3;
                    // create a new slice and insert it between prevSlice and nextSlice
                    initNewSliceA(prevSlice, sliceCurrent, nextSlice, currRank, pointIndex);
                    space[pointIndex + 1] = -1;
                }
                pointIndex += 2;
            }
        }
    }

    private void helperAMain(int from, int until, int obj) {
        ArrayHelper.transplant(transposedPoints[obj], indices, from, until, medianSwap, from);
        double objMin = ArrayHelper.min(medianSwap, from, until);
        double objMax = ArrayHelper.max(medianSwap, from, until);
        if (objMin == objMax) {
            helperA(from, until, obj - 1);
        } else {
            double median = ArrayHelper.destructiveMedian(medianSwap, from, until);
            long split = splitMerge.splitInThree(transposedPoints[obj], indices,
                    from, from, until, median, objMin, objMax);
            int startMid = SplitMergeHelper.extractMid(split);
            int startRight = SplitMergeHelper.extractRight(split);

            helperA(from, startMid, obj);
            helperB(from, startMid, startMid, startRight, obj - 1, from);
            helperA(startMid, startRight, obj - 1);
            helperB(from, startMid, startRight, until, obj - 1, from);
            helperB(startMid, startRight, startRight, until, obj - 1, from);
            helperA(startRight, until, obj);

            splitMerge.mergeThree(indices, from, from, startMid, startMid, startRight, startRight, until);
        }
    }

    private void ifDominatesUpdateRank(int good, int weak, int obj) {
        if (strictlyDominatesAssumingNotSame(good, weak, obj)) {
            tryUpdateRank(good, weak);
        }
    }

    private void helperA(int from, int until, int obj) {
        int n = until - from;
        if (n <= 2) {
            if (n == 2) {
                int goodIndex = indices[from];
                int weakIndex = indices[from + 1];
                ifDominatesUpdateRank(goodIndex, weakIndex, obj);
            }
        } else if (obj == 1) {
            sweepA(from, until);
        } else if (helperAHookCondition(until - from, obj)) {
            helperAHook(from, until, obj);
        } else {
            helperAMain(from, until, obj);
        }
    }

    private void updateByPoint(int pointIndex, int from, int until, int obj) {
        updateByPointNormal(pointIndex, ranks[pointIndex], from, until, obj);
    }

    private void updateByPointNormal(int pointIndex, int pointRank, int from, int until, int obj) {
        for (int i = from; i < until; ++i) {
            int ii = indices[i];
            if (ranks[ii] <= pointRank && strictlyDominatesAssumingNotSame(pointIndex, ii, obj)) {
                ranks[ii] = pointRank + 1;
            }
        }
    }

    private void helperBWeak1(int goodFrom, int goodUntil, int weak, int obj) {
        int wi = indices[weak];
        for (int i = goodFrom; i < goodUntil; ++i) {
            int gi = indices[i];
            ifDominatesUpdateRank(gi, wi, obj);
        }
    }

    private boolean helperBHookCondition(int goodFrom, int goodUntil, int weakFrom, int weakUntil, int obj) {
        int size = goodUntil - goodFrom + weakUntil - weakFrom;
        switch (obj) {
            case 1: return false;
            case 2: return size < THRESHOLD_3D;
            default: return size < THRESHOLD_ALL;
        }
    }

    private void sortIndicesByRanks(int from, int to) {
        int left = from, right = to;
        int pivot = (space[space[from]] + space[space[to]]) / 2;
        while (left <= right) {
            int sl, sr;
            while (space[sl = space[left]] < pivot) ++left;
            while (space[sr = space[right]] > pivot) --right;
            if (left <= right) {
                space[left] = sr;
                space[right] = sl;
                ++left;
                --right;
            }
        }
        if (from < right) {
            sortIndicesByRanks(from, right);
        }
        if (left < to) {
            sortIndicesByRanks(left, to);
        }
    }

    private boolean checkWhetherDominates(int[] array, int goodFrom, int goodUntil, int weakIndex, int obj) {
        for (int good = goodUntil - 1; good >= goodFrom; --good) {
            int goodIndex = array[good];
            if (strictlyDominatesAssumingNotSame(goodIndex, weakIndex, obj)) {
                return true;
            }
        }
        return false;
    }

    private void helperBSingleRank(int rank, int goodFrom, int goodUntil, int weakFrom, int weakUntil, int obj) {
        for (int weak = weakFrom, good = goodFrom; weak < weakUntil; ++weak) {
            int wi = indices[weak];
            if (ranks[wi] > rank) {
                continue;
            }
            while (good < goodUntil && indices[good] < wi) {
                ++good;
            }
            if (checkWhetherDominates(indices, goodFrom, good, wi, obj)) {
                ranks[wi] = rank + 1;
            }
        }
    }

    private void helperBHook(int goodFrom, int goodUntil, int weakFrom, int weakUntil, int obj, int tempFrom) {
        if (goodFrom == goodUntil || weakFrom == weakUntil) {
            return;
        }
        int goodSize = goodUntil - goodFrom;

        int sortedIndicesOffset = tempFrom * STORAGE_MULTIPLE;
        int ranksAndSlicesOffset = sortedIndicesOffset + goodSize;
        int sliceOffset = ranksAndSlicesOffset + goodSize;
        int pointsBySlicesOffset = sliceOffset + 2 * goodSize;

        int minRank = Integer.MAX_VALUE, maxRank = Integer.MIN_VALUE;
        for (int i = goodFrom, ri = ranksAndSlicesOffset, si = sortedIndicesOffset; i < goodUntil; ++i, ++ri, ++si) {
            int rank = ranks[indices[i]];
            if (minRank > rank) {
                minRank = rank;
            }
            if (maxRank < rank) {
                maxRank = rank;
            }
            space[ri] = rank;
            space[si] = ri;
        }

        if (minRank == maxRank) {
            // single front, let's do the simple stuff
            helperBSingleRank(minRank, goodFrom, goodUntil, weakFrom, weakUntil, obj);
        } else {
            sortIndicesByRanks(sortedIndicesOffset, sortedIndicesOffset + goodSize - 1);
            int sliceLast = sliceOffset - 2;
            int prevRank = -1;
            for (int i = 0; i < goodSize; ++i) {
                int currIndex = space[sortedIndicesOffset + i];
                int currRank = space[currIndex];
                if (prevRank != currRank) {
                    prevRank = currRank;
                    sliceLast += 2;
                    space[sliceLast] = 0;
                }
                ++space[sliceLast];
                space[currIndex] = sliceLast;
            }
            for (int i = sliceOffset, collected = pointsBySlicesOffset; i <= sliceLast; i += 2) {
                int current = space[i];
                space[i] = collected;
                space[i + 1] = collected;
                collected += current;
            }

            for (int weak = weakFrom, good = goodFrom; weak < weakUntil; ++weak) {
                int wi = indices[weak];
                int gi;
                while (good < goodUntil && (gi = indices[good]) < wi) {
                    int sliceTailIndex = space[ranksAndSlicesOffset + good - goodFrom] + 1;
                    space[space[sliceTailIndex]] = gi;
                    ++space[sliceTailIndex];
                    ++good;
                }
                int currSlice = sliceOffset;
                int weakRank = ranks[wi];
                while (currSlice <= sliceLast) {
                    int from = space[currSlice];
                    int until = space[currSlice + 1];
                    if (from == until) {
                        currSlice += 2;
                    } else {
                        int currRank = ranks[space[until - 1]];
                        if (currRank < weakRank) {
                            currSlice += 2;
                        } else if (checkWhetherDominates(space, from, until, wi, obj)) {
                            currSlice += 2;
                            weakRank = currRank + 1;
                        } else {
                            break;
                        }
                    }
                }
                ranks[wi] = weakRank;
            }
        }
    }

    private void helperBMain(int goodFrom, int goodUntil, int weakFrom, int weakUntil, int obj, int tempFrom) {
        double[] currentTransposed = transposedPoints[obj];
        int medianGood = ArrayHelper.transplant(currentTransposed, indices, goodFrom, goodUntil, medianSwap, tempFrom);
        double goodMinObj = ArrayHelper.min(medianSwap, tempFrom, medianGood);
        int medianWeak = ArrayHelper.transplant(currentTransposed, indices, weakFrom, weakUntil, medianSwap, medianGood);
        double weakMaxObj = ArrayHelper.max(medianSwap, medianGood, medianWeak);
        if (weakMaxObj < goodMinObj) {
            return;
        }
        double goodMaxObj = ArrayHelper.max(medianSwap, tempFrom, medianGood);
        double weakMinObj = ArrayHelper.min(medianSwap, medianGood, medianWeak);
        if (goodMaxObj <= weakMinObj) {
            helperB(goodFrom, goodUntil, weakFrom, weakUntil, obj - 1, tempFrom);
            return;
        }
        double median = ArrayHelper.destructiveMedian(medianSwap, tempFrom, medianWeak);
        long goodSplit = splitMerge.splitInThree(currentTransposed, indices, tempFrom, goodFrom, goodUntil, median, goodMinObj, goodMaxObj);
        int goodMidL = SplitMergeHelper.extractMid(goodSplit);
        int goodMidR = SplitMergeHelper.extractRight(goodSplit);
        long weakSplit = splitMerge.splitInThree(currentTransposed, indices, tempFrom, weakFrom, weakUntil, median, weakMinObj, weakMaxObj);
        int weakMidL = SplitMergeHelper.extractMid(weakSplit);
        int weakMidR = SplitMergeHelper.extractRight(weakSplit);
        int tempMid = tempFrom + ((goodUntil - goodFrom + weakUntil - weakFrom) >>> 1);

        helperB(goodFrom, goodMidL, weakMidR, weakUntil, obj - 1, tempFrom);
        helperB(goodMidL, goodMidR, weakMidR, weakUntil, obj - 1, tempFrom);

        helperB(goodFrom, goodMidL, weakMidL, weakMidR, obj - 1, tempFrom);
        helperB(goodMidL, goodMidR, weakMidL, weakMidR, obj - 1, tempFrom);

        helperB(goodMidR, goodUntil, weakMidR, weakUntil, obj, tempMid);
        helperB(goodFrom, goodMidL, weakFrom, weakMidL, obj, tempFrom);

        splitMerge.mergeThree(indices, tempFrom, goodFrom, goodMidL, goodMidL, goodMidR, goodMidR, goodUntil);
        splitMerge.mergeThree(indices, tempFrom, weakFrom, weakMidL, weakMidL, weakMidR, weakMidR, weakUntil);
    }

    private void helperB(int goodFrom, int goodUntil, int weakFrom, int weakUntil, int obj, int tempFrom) {
        if (goodUntil - goodFrom > 0 && weakUntil - weakFrom > 0) {
            int lastWeakIdx = indices[weakUntil - 1];
            while (goodFrom < goodUntil && indices[goodUntil - 1] > lastWeakIdx) {
                --goodUntil;
            }
            int firstGoodIdx = indices[goodFrom];
            while (weakFrom < weakUntil && indices[weakFrom] < firstGoodIdx) {
                ++weakFrom;
            }
        }
        int goodN = goodUntil - goodFrom;
        int weakN = weakUntil - weakFrom;
        if (goodN > 0 && weakN > 0) {
            if (goodN == 1) {
                updateByPoint(indices[goodFrom], weakFrom, weakUntil, obj);
            } else if (weakN == 1) {
                helperBWeak1(goodFrom, goodUntil, weakFrom, obj);
            } else if (obj == 1) {
                sweepB(goodFrom, goodUntil, weakFrom, weakUntil, tempFrom);
            } else if (helperBHookCondition(goodFrom, goodUntil, weakFrom, weakUntil, obj)) {
                helperBHook(goodFrom, goodUntil, weakFrom, weakUntil, obj, tempFrom);
            } else {
                helperBMain(goodFrom, goodUntil, weakFrom, weakUntil, obj, tempFrom);
            }
        }
    }

    private void twoDimensionalCase(double[][] points, int[] ranks) {
        // Also uses internalIndices and lastFrontOrdinates
        int maxRank = 1;
        int n = ranks.length;

        int lastII = internalIndices[0];
        double lastX = points[lastII][0];
        double lastY = points[lastII][1];
        double minY = lastY;

        // Point 0 always has rank 0.
        lastFrontOrdinates[0] = lastY;

        for (int i = 1; i < n; ++i) {
            int ii = internalIndices[i];
            double cx = points[ii][0];
            double cy = points[ii][1];

            if (cx == lastX && cy == lastY) {
                // Same point as the previous one.
                // The rank is the same as well.
                ranks[ii] = ranks[lastII];
            } else if (cy < minY) {
                // Y smaller than the smallest Y previously seen.
                // The rank is thus zero.
                minY = cy;
            } else {
                // At least the Y-smallest point dominates our point.
                int left, right;
                if (cy < lastY) {
                    // We are better than the previous point in Y.
                    // This means that we are at least that good.
                    left = 0;
                    right = ranks[lastII];
                } else {
                    // We are worse (or equal) than the previous point in Y.
                    // This means that we are worse than this point.
                    left = ranks[lastII];
                    right = maxRank;
                }
                // Running the binary search.
                while (right - left > 1) {
                    int mid = (left + right) >>> 1;
                    double midY = lastFrontOrdinates[mid];
                    if (cy < midY) {
                        right = mid;
                    } else {
                        left = mid;
                    }
                }
                // "right" is now our rank.
                ranks[ii] = right;
                lastFrontOrdinates[right] = cy;
                if (right == maxRank) {
                    ++maxRank;
                }
            }

            lastII = ii;
            lastX = cx;
            lastY = cy;
        }
    }
}
