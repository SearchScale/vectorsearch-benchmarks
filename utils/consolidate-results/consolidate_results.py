import os, json, sys, csv
from pathlib import Path

folder_path = sys.argv[1]
relative = Path(folder_path)
absolute_path = relative.absolute()

json_files = [pos_json for pos_json in os.listdir(folder_path) if pos_json.endswith('.json')]
header_written = False

out_file = "consolated_results.csv"
if len(sys.argv) == 3:
    out_file = sys.argv[2]

with open(out_file, 'w', newline='') as qs_writer:
    qswriter = csv.writer(qs_writer, delimiter=' ', escapechar=' ', quoting=csv.QUOTE_ALL)

    for file in json_files:
        jfile = f"{absolute_path}/{file}"
        with open(jfile) as json_file:
            json_data = json.load(json_file)

            # deleting the following k,v's from the dict as not useful in consolidated results csv file
            for key in ['datasetFile', 'groundTruthFile', 'queryFile']:
                del json_data["configuration"][key]

            if not header_written:
                header = list(json_data["configuration"].keys()) + list(json_data["metrics"].keys())
                qswriter.writerow(header)
                header_written = True

            row = list(json_data["configuration"].values()) + list(json_data["metrics"].values())
            qswriter.writerow(row)
