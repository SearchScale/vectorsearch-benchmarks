package com.searchscale.lucene.cuvs.benchmarks;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;

public class FBIvecsReader {

  @SuppressWarnings("resource")
  public static ArrayList<float[]> readFvecs(String filePath, int numRows) {

    ArrayList<float[]> vectors = new ArrayList<float[]>();

    try (FileChannel fc = new FileInputStream(filePath).getChannel()) {

      // First four bytes is the dimension of the vectors in the binary file.
      ByteBuffer bb = ByteBuffer.allocate(4);
      bb.order(ByteOrder.LITTLE_ENDIAN);
      fc.read(bb);
      bb.flip();
      int dimension = bb.getInt();
      System.out.println("Dimension: " + dimension);

      float[] row = new float[dimension];
      int count = 0;
      int rc = 0;

      while (true) {
        ByteBuffer bbf = ByteBuffer.allocate(4);
        bbf.order(ByteOrder.LITTLE_ENDIAN);

        int x = fc.read(bbf);
        if (x == -1) {
          break;
        }

        bbf.flip();
        float f = bbf.getFloat();
        row[rc++] = f;

        if (rc == dimension) {
          System.out.println(Arrays.toString(row));
          vectors.add(row);
          count += 1;
          rc = 0;
          row = new float[dimension];

          // Skip last 4 bytes.
          ByteBuffer bbfS = ByteBuffer.allocate(4);
          bbfS.order(ByteOrder.LITTLE_ENDIAN);
          x = fc.read(bbfS);

          if (numRows != -1 && count == numRows) {
            break;
          }
        }
      }
      fc.close();
    } catch (Exception e) {
      e.printStackTrace();
    }

    return vectors;
  }

  @SuppressWarnings("resource")
  public static ArrayList<int[]> readIvecs(String filePath, int numRows) {

    ArrayList<int[]> vectors = new ArrayList<int[]>();

    try (FileChannel fc = new FileInputStream(filePath).getChannel()) {

      // First four bytes is the dimension of the vectors in the binary file.
      ByteBuffer bb = ByteBuffer.allocate(4);
      bb.order(ByteOrder.LITTLE_ENDIAN);
      fc.read(bb);
      bb.flip();
      int dimension = bb.getInt();
      System.out.println("Dimension: " + dimension);

      int[] row = new int[dimension];
      int count = 0;
      int rc = 0;

      while (true) {
        ByteBuffer bbf = ByteBuffer.allocate(4);
        bbf.order(ByteOrder.LITTLE_ENDIAN);

        int x = fc.read(bbf);
        if (x == -1) {
          break;
        }

        bbf.flip();
        int f = bbf.getInt();
        row[rc++] = f;

        if (rc == dimension) {
          System.out.println(Arrays.toString(row));
          vectors.add(row);
          count += 1;
          rc = 0;
          row = new int[dimension];

          // Skip last 4 bytes.
          ByteBuffer bbfS = ByteBuffer.allocate(4);
          bbfS.order(ByteOrder.LITTLE_ENDIAN);
          x = fc.read(bbfS);

          if (numRows != -1 && count == numRows) {
            break;
          }
        }
      }
      fc.close();
    } catch (Exception e) {
      e.printStackTrace();
    }

    return vectors;
  }

}
