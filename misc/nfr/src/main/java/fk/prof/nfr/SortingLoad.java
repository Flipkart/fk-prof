package fk.prof.nfr;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by gaurav.ashok on 08/04/17.
 */
public class SortingLoad {

    int size;

    List<String> list = new ArrayList<>();

    RndGen rndGen;

    public SortingLoad(int size, RndGen rndGen) {
        this.size = size;
        this.rndGen = rndGen;
    }

    public int doWork() {
        list.clear();

        for (int i = 0; i < size; ++i) {
            list.add(rndGen.getString(64));
        }

        List<String> sortedList = list.stream().sorted().collect(Collectors.toList());

        return findLocation(sortedList, "pointy needle", 0);
    }

    private int findLocation(List<String> haystack, String needle, int i) {
        if (i >= haystack.size()) {
            return i;
        }
        return haystack.get(i).compareTo(needle) >= 0 ? i : findLocation(haystack, needle, i + 1);
    }
}
