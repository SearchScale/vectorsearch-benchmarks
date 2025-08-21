package com.searchscale.lucene.cuvs.benchmarks;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams vectors directly from .fvecs files without creating intermediate MapDB files
 */
public class FvecsStreamingVectorProvider implements VectorProvider {
    private static final Logger log = LoggerFactory.getLogger(FvecsStreamingVectorProvider.class.getName());
    
    private final String filePath;
    private final int dimension;
    private final int vectorCount;
    private final long vectorSize; // size of one vector in bytes (4 + 4*dimension)
    private final boolean isCompressed;
    
    public FvecsStreamingVectorProvider(String filePath, int maxVectors) throws IOException {
        this.filePath = filePath;
        this.isCompressed = filePath.endsWith(".gz");
        
        // Read dimension from first vector
        try (FileInputStream fis = new FileInputStream(filePath)) {
            java.io.InputStream is = isCompressed ? new GZIPInputStream(fis) : fis;
            
            byte[] dimBytes = new byte[4];
            is.read(dimBytes);
            ByteBuffer bb = ByteBuffer.wrap(dimBytes);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            this.dimension = bb.getInt();
            
            log.info("Detected dimension: {} from file: {}", dimension, filePath);
            
            this.vectorSize = 4 + 4L * dimension; // dimension int + dimension floats
        }
        
        // Calculate total vector count
        if (isCompressed) {
            // For compressed files, we need to read through to count
            this.vectorCount = countVectorsInCompressedFile(maxVectors);
        } else {
            // For uncompressed files, we can calculate from file size
            try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
                long fileSize = raf.length();
                long totalVectors = fileSize / vectorSize;
                this.vectorCount = maxVectors > 0 ? Math.min((int)totalVectors, maxVectors) : (int)totalVectors;
            }
        }
        
        log.info("FvecsStreamingVectorProvider initialized: {} vectors, {} dimensions", vectorCount, dimension);
    }
    
    private int countVectorsInCompressedFile(int maxVectors) throws IOException {
        int count = 0;
        try (FileInputStream fis = new FileInputStream(filePath);
             GZIPInputStream gzis = new GZIPInputStream(fis)) {
            
            // Skip dimension of first vector (already read)
            gzis.skip(4L * dimension);
            
            byte[] buffer = new byte[4096];
            long bytesPerVector = vectorSize;
            long totalBytesRead = 4 + 4L * dimension; // dimension int + first vector data
            
            while (gzis.available() > 0 && (maxVectors <= 0 || count < maxVectors - 1)) {
                int bytesRead = gzis.read(buffer);
                if (bytesRead == -1) break;
                
                totalBytesRead += bytesRead;
                count = (int)(totalBytesRead / bytesPerVector);
                
                if (maxVectors > 0 && count >= maxVectors) {
                    count = maxVectors;
                    break;
                }
            }
        }
        return Math.max(1, count); // At least 1 vector (the first one we read dimension from)
    }
    
    @Override
    public float[] get(int index) throws IOException {
        if (index < 0 || index >= vectorCount) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds [0, " + vectorCount + ")");
        }
        
        if (isCompressed) {
            return getFromCompressedFile(index);
        } else {
            return getFromUncompressedFile(index);
        }
    }
    
    private float[] getFromUncompressedFile(int index) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r");
             FileChannel channel = raf.getChannel()) {
            
            long position = index * vectorSize;
            channel.position(position);
            
            ByteBuffer buffer = ByteBuffer.allocate((int)vectorSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            channel.read(buffer);
            buffer.flip();
            
            // Read and verify dimension
            int fileDimension = buffer.getInt();
            if (fileDimension != dimension) {
                throw new IOException("Dimension mismatch at vector " + index + 
                                    ": expected " + dimension + ", got " + fileDimension);
            }
            
            // Read vector data
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = buffer.getFloat();
            }
            
            return vector;
        }
    }
    
    private float[] getFromCompressedFile(int index) throws IOException {
        // For compressed files, we need to read sequentially from the beginning
        // This is less efficient but necessary for compressed streams
        try (FileInputStream fis = new FileInputStream(filePath);
             GZIPInputStream gzis = new GZIPInputStream(fis)) {
            
            ByteBuffer dimBuffer = ByteBuffer.allocate(4);
            dimBuffer.order(ByteOrder.LITTLE_ENDIAN);
            
            // Read through vectors until we reach the desired index
            for (int i = 0; i <= index; i++) {
                // Read dimension
                dimBuffer.clear();
                if (gzis.read(dimBuffer.array()) != 4) {
                    throw new IOException("Unexpected end of file reading vector " + i);
                }
                
                int fileDimension = dimBuffer.getInt();
                if (fileDimension != dimension) {
                    throw new IOException("Dimension mismatch at vector " + i + 
                                        ": expected " + dimension + ", got " + fileDimension);
                }
                
                // Read vector data
                byte[] vectorBytes = new byte[dimension * 4];
                if (gzis.read(vectorBytes) != vectorBytes.length) {
                    throw new IOException("Unexpected end of file reading vector data for vector " + i);
                }
                
                if (i == index) {
                    // This is the vector we want
                    ByteBuffer vectorBuffer = ByteBuffer.wrap(vectorBytes);
                    vectorBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    
                    float[] vector = new float[dimension];
                    for (int j = 0; j < dimension; j++) {
                        vector[j] = vectorBuffer.getFloat();
                    }
                    return vector;
                }
                // Otherwise, we just skip this vector and continue
            }
            
            throw new IOException("Could not read vector " + index);
        }
    }
    
    @Override
    public int size() {
        return vectorCount;
    }
    
    @Override
    public void close() throws IOException {
        // No resources to close for file-based streaming
    }
    
    public int getDimension() {
        return dimension;
    }
}