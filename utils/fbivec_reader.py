import sys
import time
import numpy as np
import csv

# Code is borrowed from faiss repo and modified for our purpose.

def ivecs_read(fname):
    a = np.fromfile(fname, dtype='int32')
    d = a[0]
    print("dimension: " + str(d))
    return a.reshape(-1, d + 1)[:, 1:].copy()

def fvecs_read(fname):
    return ivecs_read(fname).view('float32')

def bvecs_mmap(fname):
    x = np.memmap(fname, dtype='uint8', mode='r')
    if sys.byteorder == 'big':
        da = x[:4][::-1].copy()
        d = da.view('int32')[0]
        print("dimension: " + str(d))
    else:
        d = x[:4].view('int32')[0]
        print("dimension: " + str(d))
    return x.reshape(-1, d + 4)[:, 4:]

# To run do for example: "python fbivec_reader.py /data/faiss/siftsmall/siftsmall_groundtruth.ivecs sift_ground.csv"
if __name__ == "__main__":

    args = sys.argv
    if len(args) == 1:
        print("Please provide source file and destination file path")
        sys.exit()

    source = args[1]
    dest = args[2]
    ext = source.split(".")[1]
    print(ext)
    d = []

    match ext:
        case "fvecs":
            d = fvecs_read(source)
        case "bvecs":
            d = bvecs_mmap(source)
        case "ivecs":
            d = ivecs_read(source)
        case _:
            print("Not parsing. Supported file format [`*.fvecs`,`*.bvecs`, `*.ivecs`]. Please check input file path.")
            sys.exit()

    with open(dest, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile, delimiter=' ', quotechar='|', quoting=csv.QUOTE_MINIMAL)
        count = 0
        for i, r in enumerate(d):
            rx = "[" + ",".join( map( str, r )) + "]"
            writer.writerow([rx])
            count += 1
        
        print("Rows written: " + str(count))
    
    print("Destination file: " + dest)
