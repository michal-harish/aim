package net.imagini.aim.segment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.imagini.aim.types.AimSchema;
import net.imagini.aim.utils.BlockStorage;
import net.imagini.aim.utils.View;

/**
 * zero-copy open methods, i.e. multiple stream readers should be able to
 * operate without doubling the memory foot-print.
 */
abstract public class AimSegmentAbstract implements AimSegment {

    final public AimSchema schema;
    protected LinkedHashMap<Integer, BlockStorage> columnar = new LinkedHashMap<>();
    private boolean writable;
    private LinkedHashMap<Integer, ByteBuffer> writers = null;
    private AtomicLong count = new AtomicLong(0);
    private AtomicLong size = new AtomicLong(0);
    protected AtomicLong originalSize = new AtomicLong(0);

    public AimSegmentAbstract(AimSchema schema,
            Class<? extends BlockStorage> storageType)
            throws InstantiationException, IllegalAccessException {
        this.schema = schema;
        this.writable = true;
        writers = new LinkedHashMap<>();
        for (int col = 0; col < schema.size(); col++) {
            BlockStorage blockStorage = storageType.newInstance();
            columnar.put(col, blockStorage);
            writers.put(col, blockStorage.newBlock());
        }
    }

    @Override
    final public BlockStorage getBlockStorage(int column) {
        return columnar.get(column);
    }

    @Override
    final public AimSchema getSchema() {
        return schema;
    }

    @Override
    final public long getCompressedSize() {
        return size.get();
    }

    @Override
    final public long getOriginalSize() {
        return originalSize.get();
    }

    final protected void checkWritable(boolean canBe)
            throws IllegalAccessException {
        if (this.writable != canBe) {
            throw new IllegalAccessException(
                    "Segment state is not valid for the operation");
        }
    }

    @Override
    final public AimSegment appendRecord(String... values) throws IOException {
        if (values.length != schema.size()) {
            throw new IllegalArgumentException(
                    "Number of values doesn't match the number of fields in the schema");
        }
        View[] record = new View[schema.size()];
        for (int col = 0; col < schema.size(); col++) {
            record[col] = new View(schema.get(col).convert(values[col]));
        }
        return appendRecord(record);
    }

    @Override
    final public AimSegment appendRecord(View[] record) throws IOException {
        byte[][] byteRecord = new byte[record.length][];
        for (int col = 0; col < schema.size(); col++) {
            byteRecord[col] = Arrays.copyOfRange(record[col].array, record[col].offset, record[col].offset + schema.get(col).getDataType().sizeOf(record[col]));
        }
        return appendRecord(byteRecord);
    }

    protected AimSegment commitRecord(byte[][] record) throws IOException {
        try {
            checkWritable(true);
            for (int col = 0; col < record.length; col++) {
                ByteBuffer block = writers.get(col);
                if (block.position() + record[col].length > block.capacity()) {
                    block.flip();
                    size.addAndGet(columnar.get(col).addBlock(block));
                    block.clear();
                }
                block.put(record[col]);
                originalSize.addAndGet(record[col].length);
            }
            count.incrementAndGet();
            return this;
        } catch (IllegalAccessException e1) {
            throw new IOException(e1);
        }
    }

    @Override
    public AimSegment close() throws IOException, IllegalAccessException {
        checkWritable(true);
        // check open writer blocks and add them if available
        for (int col = 0; col < schema.size(); col++) {
            ByteBuffer block = writers.get(col);
            if (block.position() > 0) {
                block.flip();
                size.addAndGet(columnar.get(col).addBlock(block));
                block.clear();
            }
        }
        this.writers = null;
        this.writable = false;
        return this;
    }

    @Override
    final public long count() {
        return count.get();
    }

}
