package com.searchscale.lucene.cuvs.benchmarks;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.List;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.IndexTreeList;
import org.mapdb.QueueLong.Node.SERIALIZER;

public class MapDBToFvecs {
    
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: MapDBToFvecs <input_mapdb_file> <output_fvecs_file>");
            System.exit(1);
        }
        
        String inputMapDBFile = args[0];
        String outputFvecsFile = args[1];
        
        System.out.println("Opening MapDB file: " + inputMapDBFile);
        DB db = DBMaker.fileDB(inputMapDBFile).make();
        IndexTreeList<float[]> vectors = db.indexTreeList("vectors", SERIALIZER.FLOAT_ARRAY).createOrOpen();
        
        int numVectors = vectors.size();
        System.out.println("Found " + numVectors + " vectors in MapDB");
        
        if (numVectors == 0) {
            System.err.println("No vectors found in MapDB file!");
            db.close();
            return;
        }
        
        // Get dimension from first vector
        float[] firstVector = vectors.get(0);
        int dimension = firstVector.length;
        System.out.println("Vector dimension: " + dimension);
        
        // Write to fvecs format
        System.out.println("Writing to fvecs file: " + outputFvecsFile);
        try (FileOutputStream fos = new FileOutputStream(outputFvecsFile);
             FileChannel channel = fos.getChannel()) {
            
            ByteBuffer buffer = ByteBuffer.allocate(4 + 4 * dimension);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            for (int i = 0; i < numVectors; i++) {
                if (i % 10000 == 0) {
                    System.out.println("Progress: " + i + "/" + numVectors + " vectors written");
                }
                
                float[] vector = vectors.get(i);
                
                // Write dimension
                buffer.clear();
                buffer.putInt(dimension);
                
                // Write vector components
                for (float value : vector) {
                    buffer.putFloat(value);
                }
                
                buffer.flip();
                channel.write(buffer);
            }
            
            System.out.println("Successfully converted " + numVectors + " vectors to fvecs format");
        }
        
        db.close();
    }
}