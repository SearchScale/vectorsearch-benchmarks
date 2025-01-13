# Vectorsearch Benchmarks

## Prerequisites
- [nvcc - v12.6](https://developer.nvidia.com/cuda-downloads?target_os=Linux)
- [cmake - v3.28.1](https://github.com/Kitware/CMake/releases/tag/v3.28.1)
- [gcc - v11.4.0](https://gcc.gnu.org/onlinedocs/11.4.0/)
- [maven - v3.9.6](https://maven.apache.org/docs/3.9.6/release-notes.html)
- [miniforge](https://github.com/conda-forge/miniforge?tab=readme-ov-file#unix-like-platforms-macos--linux)


## Running
cd [repo root]

mvn clean package

java -jar target/lucene-cuvs-benchmarks-1.0-jar-with-dependencies.jar <dump_file> <vector_column_number> <vector_column_name> <number_of_documents_to_index> <vector_dimension> <query_file> <commit_at_number_of_documents> <topK> <no. of HNSW indexing threads> <no. of cuvs indexing threads> <merge_strategy options: NO_MERGE | TRIVIAL_MERGE | NON_TRIVIAL_MERGE> <queryThreads> <createIndexInMemory> <cleanIndexDirectory> <saveResultsOnDisk> <hnswMaxConn> <hnswBeamWidth> <hnswVisitedLimit> <cagraIntermediateGraphDegree> <cagraGraphDegree> <cagraITopK> <cagraSearchWidth>

Example:
java -jar target/lucene-cuvs-benchmarks-1.0-jar-with-dependencies.jar /data/wikipedia_vector_dump.csv 3 article_vector 1000 768 /data/query.txt 1000 5 32 32 NON_TRIVIAL_MERGE 1 true true true 16 100 10 18 64 5 1