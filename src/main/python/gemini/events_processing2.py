import pandas as pd
import os

work_directory = '~/Data/GEMINI/2022-07-05/'
filename = '0.events.csv.gz'
full_filename = os.path.expanduser(work_directory + "events-raw/" + filename)
print("reading " + filename)
compression = None
if full_filename.endswith(".gz"):
    compression = 'gzip'
data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
print("filtering 1/2...")
data_filtered = data.loc[
    data.type.isin(["PathTraversal", "ModeChoice"])
]
print("filtering 2/2...")
data_filtered2 = data_filtered
print("writing...")
data_filtered2.to_csv(work_directory + "events/ptmc." + filename)
print("END")
