#import json
#import datetime
import sys

"""
Stats publisher module

It transform the results of stats module (i.e. hourly presences computation given a spatial region) into
json files compatible with weblyzard API

Usage: stats_publisher.py <spatial_division> <region> <timeframe>


--spatial division: csv file with the format GSM tower id --> spatial region
--region,timeframe: file name desired for the stored results. E.g. Roma 11-2015

Note: this sample is only valid for spatial regions <torri_roma.csv>

example: pyspark stats_publisher.py  ../spatial_regions/torri_roma.csv roma 06-2015

Results are stored into file: timeseries-<region>-<timeframe>-<spatial_division>.csv
"""

spatial_division = sys.argv[1]
region = sys.argv[2]
timeframe = sys.argv[3]

with open('spatial_regions/roma_gsm.csv') as geo:
    coord = {x.split(";")[0][:5]: (x.split(";")[1:]) for x in geo.readlines()}

suffix = spatial_division.split("/")[-1]
with open('timeseries%s-%s-%s' %
          (region, timeframe, suffix)) as peaks:
    with open("wl_timeseries%s-%s-%sarea_presence.json" %
            (region, timeframe, spatial_division.split("/")[-1]), "w") as file:
        for i, p in enumerate(peaks.readlines()):
            s = p.split(",")
            target_location = s[0]
            loc = coord[s[0][:-2]]
            value = s[-1]
            d = {}
            d["_id"] = i
            d["value"] = str(value).strip("\n")
            d["date"] = '%s %s' % (s[1], s[2])
            d["target_location"] = [{"name": target_location, "point": {
                "lat": loc[0], "lon":loc[1].strip("\n")}}]
            d["indicator_id"] = "hourly presence"
            d["indicator_name"] = "hourly presence"
            file.write('%s\n' % d)

