package ru.ifmo.orthant.domRank;

import java.util.function.BiFunction;

import ru.ifmo.orthant.DivideConquerOrthantSearch;

public class DomRankOrthantDivideConquerTestsParallel extends CorrectnessTestsBase {
    @Override
    protected BiFunction<Integer, Integer, DominanceRank> getFactory() {
        return (n, d) -> new OrthantImplementation(new DivideConquerOrthantSearch(n, d, false, -1));
    }
}
