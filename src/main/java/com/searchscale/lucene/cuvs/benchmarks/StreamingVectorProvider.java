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
 * Universal streaming vector provider that supports .fvecs, .fbin, .bvecs, and .ivecs files
 * without creating intermediate MapDB files
 */
public class StreamingVectorProvider implements VectorProvider {
    private static final Logger log = LoggerFactory.getLogger(StreamingVectorProvider.class.getName());

    private enum FileFormat {
        FVECS, FBIN, BVECS, IVECS
    }

    private final String filePath;
    private final int dimension;
    private final int vectorCount;
    private final long vectorSize; // size of one vector in bytes
    private final boolean isCompressed;
    private final FileFormat format;

    public StreamingVectorProvider(String filePath, int maxVectors) throws IOException {
        this.filePath = filePath;
        this.isCompressed = filePath.endsWith(".gz");

        // Determine file format
        if (filePath.contains("fvecs")) {
            this.format = FileFormat.FVECS;
        } else if (filePath.contains("fbin")) {
            this.format = FileFormat.FBIN;
        } else if (filePath.contains("bvecs")) {
            this.format = FileFormat.BVECS;
        } else if (filePath.contains("ivecs")) {
            this.format = FileFormat.IVECS;
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + filePath);
        }

        // Read dimension from first vector/header
        try (FileInputStream fis = new FileInputStream(filePath)) {
            java.io.InputStream is = isCompressed ? new GZIPInputStream(fis) : fis;
            this.dimension = FBIvecsReader.getDimension(is);
            log.info("Detected dimension: {} from file: {} (format: {})", dimension, filePath, format);

            // Calculate vector size based on format
            switch (format) {
                case FVECS:
                case IVECS:
                    this.vectorSize = 4 + 4L * dimension; // dimension int + dimension values
                    break;
                case FBIN:
                    this.vectorSize = 4L * dimension; // just dimension floats (no per-vector dimension)
                    break;
                case BVECS:
                    this.vectorSize = 4 + dimension; // dimension int + dimension bytes
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported format: " + format);
            }
        }

        // Calculate total vector count
        if (isCompressed) {
            this.vectorCount = countVectorsInCompressedFile(maxVectors);
        } else {
            this.vectorCount = calculateVectorCount(maxVectors);
        }

        log.info("StreamingVectorProvider initialized: {} vectors, {} dimensions, format: {}",
                 vectorCount, dimension, format);
    }

    private int calculateVectorCount(int maxVectors) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            long fileSize = raf.length();
            long totalVectors;

            if (format == FileFormat.FBIN) {
                // For .fbin: subtract 4 bytes for initial dimension
                long dataSize = fileSize - 4;
                totalVectors = dataSize / vectorSize;
            } else {
                // For .fvecs, .ivecs, .bvecs: each vector has its own dimension prefix
                totalVectors = fileSize / vectorSize;
            }

            return maxVectors > 0 ? Math.min((int)totalVectors, maxVectors) : (int)totalVectors;
        }
    }

    private int countVectorsInCompressedFile(int maxVectors) throws IOException {
        int count = 0;
        try (FileInputStream fis = new FileInputStream(filePath);
             GZIPInputStream gzis = new GZIPInputStream(fis)) {

            if (format == FileFormat.FBIN) {
                // Skip initial dimension for .fbin files
                gzis.skip(4L * dimension);
            } else {
                // Skip dimension of first vector (already read)
                gzis.skip(format == FileFormat.BVECS ? dimension : 4L * dimension);
            }

            byte[] buffer = new byte[4096];
            long bytesPerVector = vectorSize;
            long totalBytesRead = (format == FileFormat.FBIN ? 4 : 0) +
                                 (format == FileFormat.BVECS ? 4 + dimension : 4 + 4L * dimension);

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
            return Math.max(1, count);
        }
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

            long position;
            if (format == FileFormat.FBIN) {
                // Skip initial dimension (4 bytes) and go to vector position
                position = 4 + index * vectorSize;
            } else {
                // Standard position calculation for other formats
                position = index * vectorSize;
            }

            channel.position(position);
            ByteBuffer buffer = ByteBuffer.allocate((int)vectorSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(buffer);
            buffer.flip();

            // Read vector based on format
            return readVectorFromBuffer(buffer, index);
        }
    }

    private float[] readVectorFromBuffer(ByteBuffer buffer, int index) throws IOException {
        float[] vector = new float[dimension];

        switch (format) {
            case FVECS:
                // Read and verify dimension, then read floats
                int fileDimension = buffer.getInt();
                if (fileDimension != dimension) {
                    throw new IOException("Dimension mismatch at vector " + index +
                            ": expected " + dimension + ", got " + fileDimension);
                }
                for (int i = 0; i < dimension; i++) {
                    vector[i] = buffer.getFloat();
                }
                break;

            case FBIN:
                // No dimension prefix, just read floats
                for (int i = 0; i < dimension; i++) {
                    vector[i] = buffer.getFloat();
                }
                break;

            case BVECS:
                // Read and verify dimension, then read bytes as floats
                int bvecsDimension = buffer.getInt();
                if (bvecsDimension != dimension) {
                    throw new IOException("Dimension mismatch at vector " + index +
                            ": expected " + dimension + ", got " + bvecsDimension);
                }
                for (int i = 0; i < dimension; i++) {
                    vector[i] = buffer.get() & 0xff; // Convert byte to unsigned int as float
                }
                break;

            case IVECS:
                // Read and verify dimension, then read ints as floats
                int ivecsDimension = buffer.getInt();
                if (ivecsDimension != dimension) {
                    throw new IOException("Dimension mismatch at vector " + index +
                            ": expected " + dimension + ", got " + ivecsDimension);
                }
                for (int i = 0; i < dimension; i++) {
                    vector[i] = (float) buffer.getInt();
                }
                break;
        }

        return vector;
    }

    private float[] getFromCompressedFile(int index) throws IOException {
        // For compressed files, we need to read sequentially from the beginning
        try (FileInputStream fis = new FileInputStream(filePath);
             GZIPInputStream gzis = new GZIPInputStream(fis)) {

            if (format == FileFormat.FBIN) {
                // Skip initial dimension for .fbin files
                gzis.skip(4);

                // Read vectors until we reach the desired index
                for (int i = 0; i <= index; i++) {
                    byte[] vectorBytes = new byte[dimension * 4];
                    if (gzis.read(vectorBytes) != vectorBytes.length) {
                        throw new IOException("Unexpected end of file reading vector " + i);
                    }

                    if (i == index) {
                        ByteBuffer buffer = ByteBuffer.wrap(vectorBytes);
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        return readVectorFromBuffer(buffer, index);
                    }
                }
            } else {
                // Handle other formats (fvecs, bvecs, ivecs) with dimension prefix per vector
                ByteBuffer dimBuffer = ByteBuffer.allocate(4);
                dimBuffer.order(ByteOrder.LITTLE_ENDIAN);

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
                    int dataSize = format == FileFormat.BVECS ? dimension : dimension * 4;
                    byte[] vectorBytes = new byte[dataSize];
                    if (gzis.read(vectorBytes) != vectorBytes.length) {
                        throw new IOException("Unexpected end of file reading vector data for vector " + i);
                    }

                    if (i == index) {
                        ByteBuffer vectorBuffer = ByteBuffer.wrap(vectorBytes);
                        vectorBuffer.order(ByteOrder.LITTLE_ENDIAN);
                        return readVectorFromBuffer(vectorBuffer, index);
                    }
                }
            }
        }

        throw new IOException("Could not read vector " + index);
    }

    /**
     * Get raw integer array (useful for .ivecs/.ibin files used as ground truth)
     */
    public int[] getIntArray(int index) throws IOException {
        if (format != FileFormat.IVECS) {
            throw new UnsupportedOperationException("getIntArray() only supported for .ivecs files");
        }

        float[] floatVector = get(index);
        int[] intVector = new int[floatVector.length];
        for (int i = 0; i < floatVector.length; i++) {
            intVector[i] = (int) floatVector[i];
        }
        return intVector;
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

    public FileFormat getFormat() {
        return format;
    }
}
