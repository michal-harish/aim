package net.imagini.aim.types;

import net.imagini.aim.utils.ByteUtils;
import net.imagini.aim.utils.View;

public class AimTypeLONG extends AimType {

    @Override
    public int getLen() {
        return 8;
    }

    @Override
    public int parse(View value, byte[] dest, int destOffset) {
        long result = ByteUtils.parseLongRadix10(value.array, value.offset, value.limit);
        ByteUtils.putLongValue(result, dest, destOffset);
        return 8;
    }

    @Override
    public int partition(View value, int numPartitions) {
        return (int)ByteUtils.asLongValue(value.array, value.offset) % numPartitions;
    }

    @Override
    public String asString(View value) {
        return String.valueOf(ByteUtils.asLongValue(value.array, value.offset));
    }

    @Override
    public String convert(byte[] value) {
        return String.valueOf(ByteUtils.asLongValue(value, 0));
    }

    @Override
    public String escape(String value) {
        return value;
    }

    @Override
    public int sizeOf(View value) {
        return 8;
    }

}