package net.imagini.aim.pipes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import net.imagini.aim.Aim;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;

public class PipeLZ4 extends Pipe {
     private LZ4BlockOutputStream lz4OutputStream;

     public PipeLZ4(OutputStream out) throws IOException {
        super(out);
     }
     public PipeLZ4(OutputStream out,  Protocol protocol) throws IOException {
         super(out, protocol);
     }
     public PipeLZ4(InputStream in) throws IOException {
         super(in);
     }

     public PipeLZ4(InputStream in, Protocol protocol) throws IOException {
        super(in, protocol);
    }
    public PipeLZ4(Socket socket, Protocol protocol) throws IOException {
        super(socket, protocol);
    }
    @Override protected OutputStream getOutputPipe(OutputStream out) throws IOException {
         lz4OutputStream = new LZ4BlockOutputStream(
             out, 
             Aim.LZ4_BLOCK_SIZE, 
             LZ4Factory.fastestInstance().highCompressor(),
             XXHashFactory.fastestInstance().newStreamingHash32(0x9747b28c).asChecksum(), 
             true
         );
         return lz4OutputStream;
     }

     @Override protected InputStream getInputPipe(InputStream in) throws IOException {
         return new LZ4BlockInputStream(in);
     }

}
