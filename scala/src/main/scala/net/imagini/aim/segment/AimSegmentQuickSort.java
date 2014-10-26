package net.imagini.aim.segment;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imagini.aim.tools.PipeUtils;
import net.imagini.aim.types.AimDataType;
import net.imagini.aim.types.AimSchema;
import net.imagini.aim.types.SortOrder;
import net.imagini.aim.utils.BlockStorage;
import net.imagini.aim.utils.ByteKey;

/**
 * This segment type overrides the default segment behaviour by sorting the
 * record by a given sort column before compressing it.
 * 
 * @author mharis
 */
public class AimSegmentQuickSort extends AimSegmentAbstract {

    final private int sortColumn;
    private Map<ByteKey, List<ByteBuffer>> sortMap = new HashMap<>();
    private SortOrder sortOrder;

    public AimSegmentQuickSort(AimSchema schema,  Class<? extends BlockStorage> storageType)
    throws InstantiationException, IllegalAccessException {
        super(schema, schema.name(0), storageType);
        this.sortColumn = 0;
        this.sortOrder = SortOrder.ASC;
    }

    @Override
    public void appendRecord(ByteBuffer record) throws IOException {
        try {
            checkWritable(true);

            AimDataType sortType = schema.dataType(sortColumn);
            ByteKey sortValue = new ByteKey(
                Arrays.copyOfRange(record.array(), 
                record.position(), 
                record.position() + PipeUtils.sizeOf(record, sortType)) 
            );

            // close record
            if (sortValue != null) {
                if (!sortMap.containsKey(sortValue)) {
                    sortMap.put(sortValue, new ArrayList<ByteBuffer>());
                }
                List<ByteBuffer> keyspace = sortMap.get(sortValue);
                //TODO use slice and change recordBuffer to direct buffer
                keyspace.add(ByteBuffer.wrap(Arrays.copyOfRange(record.array(),
                        0, record.limit())));
            }

        } catch (IllegalAccessException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws IOException, IllegalAccessException {
        checkWritable(true);
        List<ByteKey> keys = new ArrayList<ByteKey>(sortMap.keySet());
        Collections.sort(keys);
        if (sortOrder.equals(SortOrder.DESC)) {
            Collections.reverse(keys);
        }
        for (ByteKey key : keys) {
            List<ByteBuffer> bucket = sortMap.get(key);
            for (ByteBuffer record : bucket) {
                try {
                    super.appendRecord(record);
                } catch (EOFException e) {
                    break;
                }
            }
        }
        sortMap = null;
        super.close();
    }

}