import pandas as pd
import sys

def generate(parquet_file_path: str, csv_out_file: str, col_name: str, wrap_in_b: bool):
    with open(csv_out_file, 'w', newline='') as file:
        print("Starting the parquet to csv conversion.")

        df = pd.read_parquet(parquet_file_path)
        keys = df.keys()
        rows = len(df[keys[0]])
        print("Available keys in the file: ", keys)
        print("Number of rows to read/write: ", rows)

        for i in range(rows):
            lst = df[col_name][i].tolist()
            x = ", ".join([str(x) for x in lst])
            if wrap_in_b:
                x = "[" + x + "]"
            file.write(x.strip() + "\n")
            if i % 100 == 0:
                print(".", end='')
                sys.stdout.flush()

        print("\nDone")

if __name__ == "__main__":
    generate('/data/openai/neighbors.parquet' ,'OpenAI_5Mx1536_groundtruth_1000.csv', 'neighbors_id', False)
    generate('/data/openai/test.parquet' ,'OpenAI_5Mx1536_query_1000.csv', 'emb', True)
