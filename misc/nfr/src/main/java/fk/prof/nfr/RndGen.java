package fk.prof.nfr;

import java.util.Random;

/**
 * Created by gaurav.ashok on 12/04/17.
 */
public class RndGen {

    private final static int totalChars = 26 + 26 + 10;

    private final static char[] chars = new char[totalChars];

    byte[] bytes = new byte[1024 * 32];

    int idx = bytes.length;

    Random rnd = new Random(0);

    {
        for (int i = 0; i < 26; ++i) {
            chars[i] = (char) ('a' + i);
        }
        for (int i = 0; i < 26; ++i) {
            chars[26 + i] = (char) ('A' + i);
        }
        for (int i = 0; i < 10; ++i) {
            chars[52 + i] = (char) ('0' + i);
        }
    }

    public boolean check(float prob) {
        int limit = (int) (prob * 256.0f * 256.0f);
        int r = getShort();

        return r < limit;
    }

    public int getInt(int bound) {
        int r = getShort();
        return bound * r / 256 / 256;
    }

    public String getString(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; ++i) {
            sb.append(chars[getInt(totalChars)]);
        }
        return sb.toString();
    }

    public float getFloat() {
        float r = (float) getShort();
        return r / 256.0f / 256.0f;
    }

    private void ensureRndBytes() {
        if (idx == bytes.length) {
            rnd.nextBytes(bytes);
            idx = 0;
        }
    }

    private int getShort() {
        ensureRndBytes();
        int s = bytes[idx];
        s = (s & 0xff) << 8;
        s = s | (bytes[idx + 1] & 0xff);
        idx += 2;
        return s;
    }
}

