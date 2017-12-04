package fk.prof.userapi.util;

import java.util.Objects;

/**
 * Created by gaurav.ashok on 27/06/17.
 */
public class Pair<T, U> {
    public final T first;
    public final U second;

    public Pair(T first, U second) {
        this.first = first;
        this.second = second;
    }

    public static <T, U> Pair<T, U> of(T first, U second) {
        return new Pair<>(first, second);
    }

    @Override
    public int hashCode() {
        int hash = 59;
        if (first != null) {
            hash = 31 * hash + first.hashCode();
        }
        if (second != null) {
            hash = 31 * hash + second.hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) return true;
        if (that == null || !(that instanceof Pair)) {
            return false;
        }
        return Objects.equals(this.first, ((Pair) that).first) && Objects.equals(this.second, ((Pair) that).second);
    }
}