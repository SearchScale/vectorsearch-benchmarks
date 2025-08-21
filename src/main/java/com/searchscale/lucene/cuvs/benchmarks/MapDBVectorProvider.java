package com.searchscale.lucene.cuvs.benchmarks;

import java.io.IOException;

import org.mapdb.DB;
import org.mapdb.IndexTreeList;

/**
 * Vector provider that uses MapDB as the backing store
 */
public class MapDBVectorProvider implements VectorProvider {
    private final IndexTreeList<float[]> vectors;
    private final DB db;
    
    public MapDBVectorProvider(IndexTreeList<float[]> vectors, DB db) {
        this.vectors = vectors;
        this.db = db;
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
        if (db != null) {
            db.close();
        }
    }
}