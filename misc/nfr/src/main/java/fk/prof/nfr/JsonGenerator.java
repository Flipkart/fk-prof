package fk.prof.nfr;

import fk.prof.ClosablePerfCtx;
import fk.prof.MergeSemantics;
import fk.prof.PerfCtx;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by gaurav.ashok on 03/04/17.
 */
public class JsonGenerator {

    private static RndGen rnd;

    private static PerfCtx childCtx = new PerfCtx("hashmap-create-ctx", 20,
                                                  MergeSemantics.PARENT_SCOPED);

    public JsonGenerator(RndGen rndGen) {
        this.rnd = rndGen;
    }

    public Map<String, Object> genJsonMap(int size, float objProb, float arrayProb) {
        try (ClosablePerfCtx ctx = childCtx.open()) {
            Map<String, Object> map = new HashMap<>();
            if (size == 0) {
                return map;
            }
            genFields(map, size, 0, objProb, arrayProb);
            return map;
        }
    }

    private String randomString(int bound) {
        int len = rnd.getInt(bound);
        return rnd.getString(len);
    }

    private void genFields(Map<String, Object> map, int size, int idx, float objProb,
                           float arrayProb) {

        if (idx < size) {
            map.put(randomString(32), genRndmObject(size / 2, objProb, arrayProb));
            genFields(map, size, idx + 1, objProb, arrayProb);
        }
        return;
    }

    private Object genRndmObject(int size, float objProb, float arrayProb) {
        float rndFloat = rnd.getFloat();
        if (rndFloat < objProb) {
            return genJsonMap(size, objProb, arrayProb);
        } else if (rndFloat < arrayProb + objProb) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < size; ++i) {
                list.add(genRndmObject(size / 2, objProb, arrayProb));
            }
            return list;
        } else {
            int rndmInt = rnd.getInt(2);
            if (rndmInt == 0) {
                return randomString(32);
            } else {
                return ZonedDateTime.now(Clock.systemUTC());
            }
        }
    }
}
