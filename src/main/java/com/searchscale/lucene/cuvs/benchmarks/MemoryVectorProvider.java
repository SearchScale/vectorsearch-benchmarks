package com.searchscale.lucene.cuvs.benchmarks;

import java.io.IOException;
import java.util.List;

/**
 * In-memory vector provider that holds all vectors in RAM
 */
public class MemoryVectorProvider implements VectorProvider {
    private final List<float[]> vectors;
    
    public MemoryVectorProvider(List<float[]> vectors) {
        this.vectors = vectors;
    }
    
    @Override
    public float[] get(int index) throws IOException {
        if (index < 0 || index >= vectors.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds [0, " + vectors.size() + ")");
        }
        return vectors.get(index);
    }
    
    @Override
    public int size() {
        return vectors.size();
    }
    
    @Override
    public void close() throws IOException {
        // No resources to close
    }
}