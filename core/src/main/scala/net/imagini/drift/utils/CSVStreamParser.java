package net.imagini.drift.utils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class CSVStreamParser {

    private InputStream input;
    private char separator;
    private byte[] buffer = new byte[65535];
    private View bufferView = new View(buffer);
    private int position = -1;
    private int limit = -1;

    public CSVStreamParser(InputStream input, char separator) {
        this.input = input;
        this.separator = separator;
    }

    public View nextValue() throws IOException {
        return nextValue(false);
    }
    public void skipLine()  throws IOException {
        nextValue(true);
    }
    private View nextValue(boolean skipLine) throws IOException {
        int start = -1;
        int end = -1;
        char ch;
        try {
            while (true) {
                ++position;
                if (position >= limit) {
                    if (start >= 0 && end >= 0) {
                        ByteUtils.copy(buffer, start, buffer, 0, end - start + 1);
                        position = end - start + 1;
                        limit = position;
                        start = 0;
                    } else {
                        position = 0;
                        start = -1;
                    }
                    loadBuffer();
                    end = position - 1;
                }
                ch = (char) buffer[position];
                if (start == -1) {
                    if (ch == '\r' || ch == ' ') {
                        continue;
                    } else {
                        start = position;
                    }
                }
                if (ch == '\n') {
                    break;
                } else if (!skipLine && ch == separator) {
                    break;
                } else {
                    if (ch != ' ' && ch != '\r' && ch != 0) {
                        end = position;
                    }
                }
            }
        } catch (EOFException e) {
            if (start == -1) {
                input.close();
                throw e;
            }
        }
        if (start < 0)
            start = 0;
        if (end < start)
            end = start - 1;
        bufferView.offset = start;
        bufferView.limit = end;
        return bufferView;
    }

//    private int filepos = 0;

    private void loadBuffer() throws IOException {
        int read = input.read(buffer, position, buffer.length - position);
//        filepos += read;
        if (read < 0)
            throw new EOFException();
        limit = position + read;
    }
}
