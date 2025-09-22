package com.searchscale.lucene.cuvs.benchmarks;

import java.io.IOException;

/**
 * Interface for providing vectors from various sources (MapDB, direct file streaming, etc.)
 */
public interface VectorProvider {
    /**
     * Get the vector at the specified index
     */
    float[] get(int index) throws IOException;
    
    /**
     * Get the total number of vectors
     */
    int size();
    
    /**
     * Close any resources
     */
    void close() throws IOException;
}