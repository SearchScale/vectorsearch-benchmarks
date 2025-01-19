package com.searchscale.lucene.cuvs.benchmarks;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;

public class FBIvecsReader {

  public static int getDimension(FileChannel fc) throws IOException {
    ByteBuffer bb = ByteBuffer.allocate(4);
    bb.order(ByteOrder.LITTLE_ENDIAN);
    fc.read(bb);
    bb.flip();
    int dimension = bb.getInt();
    System.out.println("Dimension: " + dimension);
    return dimension;
  }

  public static void skipBytes(FileChannel fc) throws IOException {
    ByteBuffer bbfS = ByteBuffer.allocate(4);
    bbfS.order(ByteOrder.LITTLE_ENDIAN);
    fc.read(bbfS);
  }

  @SuppressWarnings("resource")
  public static ArrayList<float[]> readFvecs(String filePath, int numRows) {

    ArrayList<float[]> vectors = new ArrayList<float[]>();

    try (FileChannel fc = new FileInputStream(filePath).getChannel()) {

      int dimension = getDimension(fc);
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
          skipBytes(fc);

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

      int dimension = getDimension(fc);
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
          skipBytes(fc);

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
  public static ArrayList<int[]> readBvecs(String filePath, int numRows) {

    ArrayList<int[]> vectors = new ArrayList<int[]>();

    try (FileChannel fc = new FileInputStream(filePath).getChannel()) {

      int dimension = getDimension(fc);

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
        int f = bbf.get() & 0xff; // TODO: This is not returning the correct numbers, needs a fix.
        row[rc++] = f;

        if (rc == dimension) {
          System.out.println(Arrays.toString(row));
          vectors.add(row);
          count += 1;
          rc = 0;
          row = new int[dimension];

          // Skip last 4 bytes.
          skipBytes(fc);

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
