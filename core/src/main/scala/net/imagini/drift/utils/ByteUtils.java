package net.imagini.drift.utils;

public class ByteUtils {

    public static int parseIntRadix10(byte[] array, int offset, int limit) {
        int result = 0;
        boolean negative = false; 
        if (array[offset] == '-' ) {
            negative = true;
            offset++;
        }
        for(int i = offset; i<= limit; i++) {
            if (array[i] < 48 || array[i] > 57) {
                throw new IllegalArgumentException("Invalid numeric character " +  (char)array[i]);
            }
            result *= 10;
            result += (array[i] - 48) ;
        }
        return negative ? -result : result;
    }

    public static long parseLongRadix10(byte[] array, int offset, int limit) {
        long result = 0;
        for(int i = offset; i<= limit; i++) {
            if (array[i] < 48 || array[i] > 57) {
                throw new IllegalArgumentException("Invalid numeric character " +  (char)array[i]);
            }
            result *= 10;
            result += (array[i] - 48) ;
        }
        return result;
    }


    public static long parseLongRadix16(byte[] array, int offset, int limit) {
        long result = 0;
        for(int i = offset; i<= limit; i++) {
            result *= 16;
            if (array[i] >= 48 && array[i] <= 57) {
                result += (array[i] - 48) ;
            } else if (array[i] >= 'A' && array[i] <= 'F') {
                result += (array[i] - 55) ;
            } else if (array[i] >= 97 && array[i] <= 102) {
                result += (array[i] - 87) ;
            } else {
                throw new IllegalArgumentException("Invalid numeric character " +  (char)array[i]);
            }
        }
        return result;
    }

    public static int copy(byte[] src, int srcOffset, byte[]dest, int destOffset, int len) {
        int srcLimit = srcOffset + len;
        for(int i = srcOffset, j = destOffset; i< srcLimit; i++, j++) {
            dest[j] = src[i];
        }
        return len;
    }

    static public int asIntValue(byte[] value) {
        return asIntValue(value, 0);
    }

    static public long asLongValue(byte[] value) {
        return asLongValue(value, 0);
    }

    static public int asIntValue(byte[] value, int offset) {
        return (  (((int) value[offset + 0]) << 24)
                + (((int) value[offset + 1] & 0xff) << 16)
                + (((int) value[offset + 2] & 0xff) << 8) 
                + (((int) value[offset + 3] & 0xff) << 0));

    }

    static public long asLongValue(byte[] value, int o) {
        return (((long) value[o + 0] << 56)
                + (((long) value[o + 1] & 0xff) << 48)
                + (((long) value[o + 2] & 0xff) << 40)
                + (((long) value[o + 3] & 0xff) << 32)
                + (((long) value[o + 4] & 0xff) << 24)
                + (((long) value[o + 5] & 0xff) << 16)
                + (((long) value[o + 6] & 0xff) << 8) + (((long) value[o + 7] & 0xff) << 0));
    }

    public static void putIntValue(int value, byte[] result, int offset) {
        result[offset + 0] = (byte) ((value >>> 24) & 0xFF);
        result[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        result[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        result[offset + 3] = (byte) ((value >>> 0) & 0xFF);
    }

    public static void putLongValue(long value, byte[] result, int offset) {
        result[offset + 0] = (byte) ((value >>> 56) & 0xFF);
        result[offset + 1] = (byte) ((value >>> 48) & 0xFF);
        result[offset + 2] = (byte) ((value >>> 40) & 0xFF);
        result[offset + 3] = (byte) ((value >>> 32) & 0xFF);
        result[offset + 4] = (byte) ((value >>> 24) & 0xFF);
        result[offset + 5] = (byte) ((value >>> 16) & 0xFF);
        result[offset + 6] = (byte) ((value >>> 8) & 0xFF);
        result[offset + 7] = (byte) ((value >>> 0) & 0xFF);
    }

    /**
     * Compares the current buffer position if treated as given type with the
     * given value but does not advance
     */
    final public static boolean equals(byte[] lArray, int leftOffset, int lSize, byte[] rArray, int rightOffset, int rSize) {
        return compare(lArray, leftOffset, lSize, rArray, rightOffset, rSize) == 0;
    }
    final public static int compare(byte[] lArray, int leftOffset, int lSize, byte[] rArray, int rightOffset, int rSize) {
        if (lSize != rSize) {
            return lSize - rSize;
        }
        int i = leftOffset;
        int j = rightOffset;
        int n = lSize;
        for (int k = 0; k < n; k++, i++, j++) {
            int cmp = (lArray[i] & 0xFF) - (rArray[j] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Checks if the current buffer position if treated as given type would
     * contain the given value but does not advance
     */
    final public static boolean contains(byte[] cArray, int cOffset, int cSize, byte[] vArray, int vOffset, int vSize) {
        if (cSize == 0 || vSize == 0 || vSize > cSize) {
            return false;
        }
        int cLimit = cOffset + cSize -1;
        int vLimit = vOffset + vSize -1;
        int v = vOffset;
        for (int c = cOffset; c <= cLimit; c++) {
            if (vArray[v] != cArray[c]) {
                v = vOffset;
                if (c + vSize > cLimit) {
                    return false;
                }
            } else if (++v >= vLimit) {
                return true;
            }
        }
        return false;
    }

    public static int crc32(byte[] array, int offset, int size) {
        int crc = 0xFFFF;
        for (int pos = offset; pos < offset + size; pos++) {
            crc ^= (int) array[pos];
            for (int i = 8; i != 0; i--) {
                if ((crc & 0x0001) != 0) {
                    crc >>= 1;
                    crc ^= 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc;
    }

    public static int sum(byte[] array, int offset, int size) {
        int sum = 0;
        for (int i = offset; i< offset + size; i++) sum ^=  array[i];
        return sum;
    }

    public static byte[] parseUUID(String uuid) {
        byte[] result = new byte[16];
        parseUUID(uuid.getBytes(), 0, result, 0);
        return result;
    }

    public static void parseUUID(byte[] uuid, int uuidOffset, byte[] dest, int destOffset) {
        long mostSigBits = parseLongRadix16(uuid, uuidOffset, uuidOffset + 7);
        mostSigBits <<= 16;
        mostSigBits |= ByteUtils.parseLongRadix16(uuid, uuidOffset+9, uuidOffset + 12);
        mostSigBits <<= 16;
        mostSigBits |= ByteUtils.parseLongRadix16(uuid, uuidOffset+14, uuidOffset + 17);
        long leastSigBits = ByteUtils.parseLongRadix16(uuid, uuidOffset+19, uuidOffset + 22);
        leastSigBits <<= 48;
        leastSigBits |= ByteUtils.parseLongRadix16(uuid, uuidOffset+24, uuidOffset + 35);
        ByteUtils.putLongValue(mostSigBits, dest, destOffset);
        ByteUtils.putLongValue(leastSigBits, dest, destOffset + 8);
    }
}
