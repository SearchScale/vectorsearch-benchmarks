# Job configuration generator

This is a python utility for generating job configuration files with combinations of `variants` parameters (see `wikipedia_sweep_CAGRA.json` OR `wikipedia_sweep_HNSW.json`).

## Before running
- Create a copy of `wikipedia_sweep_<CAGRA|HNSW>.json` to create your own configuration template OR modify `wikipedia_sweep_<CAGRA|HNSW>.json` with your values (like file paths etc).

## To generate config set do:
```
python3 generate_job_configuration_files.py <your configuration template (eg wikipedia_sweep.json)>
```

A folder with a name `jobs_<benchmarkID>` should be created with all the job configuration files