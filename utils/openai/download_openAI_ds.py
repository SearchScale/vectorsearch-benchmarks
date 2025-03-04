import s3fs
import pathlib

local_root = pathlib.Path("/tmp/data/openai_5M")
if not local_root.exists():
    local_root.mkdir(parents=True)

remote_root = 'assets.zilliz.com/benchmark/openai_large_5m'
fs = s3fs.S3FileSystem(anon=True, client_kwargs={"region_name": "us-west-2"})
# print("List of files")
# fs.ls(remote_root)

# Dataset files and query set
files = ["train-{:02d}-of-10.parquet".format(i) for i in range(10)]
files.append("test.parquet")
files.append("neighbors.parquet")

downloads = [(pathlib.PurePosixPath(remote_root, f), local_root.joinpath(f)) for f in files]

for remote_file, local_file in downloads:
    if local_file.exists():
        print("Skipping downloaded file", local_file)
    else:
        print("Downloading", remote_file, "to", local_file)
        fs.download(remote_file.as_posix(), local_file.as_posix())
