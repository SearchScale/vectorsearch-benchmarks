package com.searchscale.lucene.cuvs.benchmarks;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import org.mapdb.IndexTreeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//TODO: The three static methods have a lot of common logic, ideally should be combined as just one.
public class FBIvecsReader {

  private static final Logger log = LoggerFactory.getLogger(FBIvecsReader.class.getName());

  public static int getDimension(InputStream fc) throws IOException {
    byte[] b = fc.readNBytes(4);
    ByteBuffer bb = ByteBuffer.wrap(b);
    bb.order(ByteOrder.LITTLE_ENDIAN);
    int dimension = bb.getInt();
    return dimension;
  }

  public static void readFvecs(String filePath, int numRows, IndexTreeList<float[]> vectors) {
    log.info("Reading {} from file: {}", numRows, filePath);

    try {
      InputStream is = null;

      if (filePath.endsWith("fvecs.gz")) {
        is = new GZIPInputStream(new FileInputStream(filePath));
      } else if (filePath.endsWith(".fvecs")) {
        is = new FileInputStream(filePath);
      }

      int dimension = getDimension(is);
      float[] row = new float[dimension];
      int count = 0;
      int rc = 0;

      while (is.available() != 0) {
        ByteBuffer bbf = ByteBuffer.wrap(is.readNBytes(4));
        bbf.order(ByteOrder.LITTLE_ENDIAN);
        row[rc++] = bbf.getFloat();

        if (rc == dimension) {
          vectors.add(row);
          count += 1;
          rc = 0;
          row = new float[dimension];

          // Skip last 4 bytes.
          is.readNBytes(4);

          if (count % 1000 == 0) {
            System.out.print(".");
          }

          if (numRows != -1 && count == numRows) {
            break;
          }
        }
      }
      System.out.println();
      is.close();
      log.info("Reading complete. Read {} vectors.", count);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static ArrayList<int[]> readIvecs(String filePath, int numRows) {
    log.info("Reading {} from file: {}", numRows, filePath);
    ArrayList<int[]> vectors = new ArrayList<int[]>();

    try {
      InputStream is = null;

      if (filePath.endsWith("ivecs.gz")) {
        is = new GZIPInputStream(new FileInputStream(filePath));
      } else if (filePath.endsWith(".ivecs")) {
        is = new FileInputStream(filePath);
      }

      int dimension = getDimension(is);
      int[] row = new int[dimension];
      int count = 0;
      int rc = 0;

      while (is.available() != 0) {

        ByteBuffer bbf = ByteBuffer.wrap(is.readNBytes(4));
        bbf.order(ByteOrder.LITTLE_ENDIAN);
        row[rc++] = bbf.getInt();

        if (rc == dimension) {
          vectors.add(row);
          count += 1;
          rc = 0;
          row = new int[dimension];

          // Skip last 4 bytes.
          is.readNBytes(4);

          if (count % 1000 == 0) {
            System.out.print(".");
          }

          if (numRows != -1 && count == numRows) {
            break;
          }
        }
      }
      System.out.println();
      is.close();
      log.info("Reading complete. Read {} vectors.", count);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return vectors;
  }

  public static void readBvecs(String filePath, int numRows, IndexTreeList<float[]> vectors) {
    log.info("Reading {} from file: {}", numRows, filePath);

    try {
      InputStream is = null;

      if (filePath.endsWith("bvecs.gz")) {
        is = new GZIPInputStream(new FileInputStream(filePath));
      } else if (filePath.endsWith(".bvecs")) {
        is = new FileInputStream(filePath);
      }

      int dimension = getDimension(is);

      float[] row = new float[dimension];
      int count = 0;
      int rc = 0;

      while (is.available() != 0) {

        ByteBuffer bbf = ByteBuffer.wrap(is.readNBytes(1));
        bbf.order(ByteOrder.LITTLE_ENDIAN);
        row[rc++] = bbf.get() & 0xff;

        if (rc == dimension) {
          vectors.add(row);
          count += 1;
          rc = 0;
          row = new float[dimension];

          // Skip last 4 bytes.
          is.readNBytes(4);

          if (count % 1000 == 0) {
            System.out.print(".");
          }

          if (numRows != -1 && count == numRows) {
            break;
          }
        }
      }
      System.out.println();
      is.close();
      log.info("Reading complete. Read {} vectors.", count);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
