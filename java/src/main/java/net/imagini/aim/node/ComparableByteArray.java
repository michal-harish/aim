package net.imagini.aim.node;

import java.util.Arrays;

public class ComparableByteArray implements Comparable<ComparableByteArray> {
    public final byte[] bytes;
    private int classifier;

    public ComparableByteArray(byte[] data, int classifier) {
        if (data == null) {
            throw new NullPointerException();
        }
        this.classifier = classifier;
        this.bytes = data;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ComparableByteArray)) {
            return false;
        }
        return Arrays.equals(bytes, ((ComparableByteArray)other).bytes);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public int compareTo(ComparableByteArray val) {
        int i = 0;
        while (i<bytes.length) {
            if (bytes.length < val.bytes.length) {
                return -1;
            } else if (bytes.length > val.bytes.length) {
                return 1;
            } else if ((bytes[i] & 0xFF) < (val.bytes[i] & 0xFF)) {
                return -1;
            } else if ((bytes[i] & 0xFF) > (val.bytes[i] & 0xFF) ) {
                return  1;
            }
            i++;
        }
        return classifier == val.classifier ? 0 : (classifier > val.classifier ? 1 : -1);
    }

}