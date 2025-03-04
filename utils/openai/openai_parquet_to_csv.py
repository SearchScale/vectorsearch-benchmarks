import pandas as pd
import csv
import sys

with open('OpenAI_5Mx1536.csv', 'w', newline='') as ds_writer:
    print("Starting the parquet to csv conversion.")
    dswriter = csv.writer(ds_writer, delimiter=',', escapechar=' ', quoting=csv.QUOTE_ALL)
    dswriter.writerow(['id', 'vector'])
    for file_name in ["/data/openai/train-{:02d}-of-10.parquet".format(i) for i in range(10)]:
        print("Reading file: ", file_name)
        df = pd.read_parquet(file_name)

        keys = df.keys()
        rows = len(df[keys[0]])
        print("Keys in the file: ", keys)
        print("Number of rows to read/write: ", rows)
        print("Writing to the csv file")
        for i in range(rows):
            r = []
            for k in keys:
                r.append(df[k][i].tolist())
            dswriter.writerow(r)
            if i % 10000 == 0:
                print(".", end='')
                sys.stdout.flush()
        print(f"Data from file {file_name} is written to the csv.")
    print("Done")
