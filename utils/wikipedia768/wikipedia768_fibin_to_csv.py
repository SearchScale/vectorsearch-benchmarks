import numpy as np
import csv, sys

def read_fbin(filename, start_idx=0, chunk_size=None):
    with open(filename, "rb") as f:
        nvecs, dim = np.fromfile(f, count=2, dtype=np.int32)
        nvecs = (nvecs - start_idx) if chunk_size is None else chunk_size
        arr = np.fromfile(f, count=nvecs * dim, dtype=np.float32,
                          offset=start_idx * 4 * dim)
    return arr.reshape(nvecs, dim)

def read_ibin(filename, start_idx=0, chunk_size=None):
    with open(filename, "rb") as f:
        nvecs, dim = np.fromfile(f, count=2, dtype=np.int32)
        nvecs = (nvecs - start_idx) if chunk_size is None else chunk_size
        arr = np.fromfile(f, count=nvecs * dim, dtype=np.int32,
                          offset=start_idx * 4 * dim)
    return arr.reshape(nvecs, dim)

def genetate_dataset(source_file_path: str, dest_file_path: str, ):
    with open(dest_file_path, 'w', newline='') as ds_writer:
        print("Starting the fibn to csv conversion.")
        dswriter = csv.writer(ds_writer, delimiter=',', escapechar=' ', quoting=csv.QUOTE_ALL)
        dswriter.writerow(['id', 'vector'])

        raw_data = read_fbin(source_file_path)
        rows = len(raw_data)
        print("Number of rows to read/write: ", rows)

        for i in range(rows):
            dswriter.writerow([i, raw_data[i].tolist()])
            if i % 10000 == 0:
                print(".", end='')
                sys.stdout.flush()
        print("Done")

def generate(source_file_path: str, csv_out_file: str, wrap_in_b: bool):
    with open(csv_out_file, 'w', newline='') as file:
        print("Starting the f/i bin to csv conversion.")

        raw_data = None
        if source_file_path.endswith(".ibin"):
            raw_data = read_ibin(source_file_path)
        elif source_file_path.endswith(".fbin"):
            raw_data = read_fbin(source_file_path)
        rows = len(raw_data)
        print("Number of rows to read/write: ", rows)

        for i in range(rows):
            lst = raw_data[i].tolist()
            x = ", ".join([str(x) for x in lst])
            if wrap_in_b:
                x = "[" + x + "]"
            file.write(x.strip() + "\n")
            if i % 100 == 0:
                print(".", end='')
                sys.stdout.flush()
        print("\nDone")

if __name__ == "__main__":
    # To read the 10M dataset axel https://data.rapids.ai/raft/datasets/wiki_all_10M/wiki_all_10M.tar
    genetate_dataset("/data/wikipedia768/base.10M.fbin", "/data/wikipedia768/Wikipedia_10Mx768.csv")
    generate("/data/wikipedia768/queries.fbin", "/data/wikipedia768/Wikipedia_10Mx768_query.csv", True)
    generate("/data/wikipedia768/groundtruth.10M.neighbors.ibin", "/data/wikipedia768/Wikipedia_10Mx768_groundtruth.csv", False)
