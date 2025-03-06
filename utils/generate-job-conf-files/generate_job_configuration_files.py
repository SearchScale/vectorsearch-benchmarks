import json
import itertools
import os
import argparse

def generate_json_files(input_file):
    with open(input_file, 'r') as f:
        input_data = json.load(f)

    invariants = input_data['invariants']
    variants = input_data['variants']

    variant_keys = list(variants.keys())
    variant_values = list(variants.values())
    combinations = list(itertools.product(*variant_values))

    output_dir = 'jobs_' + invariants["benchmarkID"]
    os.makedirs(output_dir, exist_ok=True)

    for idx, combination in enumerate(combinations):
        result = invariants.copy()

        numdocs = None
        flushfreq = None

        if "numDocs" in invariants:
            numdocs = invariants["numDocs"]
        elif "numDocs" in variants:
            numdocs = variants["numDocs"]

        if "flushFreq" in invariants:
            flushfreq = invariants["flushFreq"]
        elif "flushFreq" in variants:
            flushfreq = variants["flushFreq"]

        idv = invariants["benchmarkID"] + "_D" + str(numdocs) + "_FF" + str(flushfreq)
        for key, value in zip(variant_keys, combination):
            result[key] = value
            idv += "_" + key + str(value)

        result["benchmarkID"] = idv
        filename = f'config_{idv}.json'
        filepath = os.path.join(output_dir, filename)
        
        with open(filepath, 'w') as json_file:
            json.dump(result, json_file, indent=4)

        print(f"Generated {filepath}")

    print("A total of " + str(idx + 1) + " job configuration files have been generated.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate JSON files based on input configuration.")
    parser.add_argument('input_file', help="Path to the input JSON file")
    args = parser.parse_args()
    generate_json_files(args.input_file)
