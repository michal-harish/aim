package net.imagini.aim.types;

import net.imagini.aim.utils.ByteUtils;
import net.imagini.aim.utils.View;

public class AimTypeINT extends AimType {

    @Override
    public int getLen() {
        return 4;
    }

    @Override
    public int parse(View value, byte[] dest, int destOffset) {
        int result = ByteUtils.parseIntRadix10(value.array, value.offset, value.limit);
        ByteUtils.putIntValue(result, dest, destOffset);
        return 4;
    }

    @Override
    public int partition(View value, int numPartitions) {
        return ByteUtils.asIntValue(value.array, value.offset) % numPartitions;
    }

    @Override
    public String asString(View value) {
        return String.valueOf(ByteUtils.asIntValue(value.array, value.offset));
    }

    @Override
    public String convert(byte[] value) {
        return String.valueOf(ByteUtils.asIntValue(value));
    }

    @Override
    public String escape(String value) {
        return value;
    }

    @Override
    public int sizeOf(View value) {
        return 4;
    }

}


