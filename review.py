#!/usr/bin/env python
import requests
import os
import pandas
from datetime import datetime
from tabulate import tabulate

ENDPOINT = "https://phabricator.mesosphere.com/api"


def pandas_frame_from(result):
    d = pandas.io.json.json_normalize(result, [['result', 'data']])

    # flatten 'fields' entry
    fields = d.pop("fields").apply(pandas.Series)
    return pandas.concat([d, fields], axis=1)


def life_time(data_frame):
    """
    Calculates the life time of each row. Defined as 'dateModified' -
    'dateCreated'.

    :data_frame Pandas data frame holding the dateModified and dateCreated
        columns
    :return Life time series
    """
    return data_frame.assign(
            lifeTime=lambda x: x.dateModified - x.dateCreated)['lifeTime']


def stats(series, name, percentiles=[.25, .5, .75, .9]):
    """
    Get statistics for series.

    :series The series which statistics are caluclated.
    :name Name for stats.
    :return Statistics
    """
    return series.describe(percentiles=percentiles).rename(name)


def beginning_of_this_month():
    """
    :return Date for first day of this month.
    """
    return datetime.now().replace(day=1, hour=0, minute=0, second=0)


def beginning_of_last_month():
    """
    :return datetime for first day of last month.
    """
    this_month = beginning_of_this_month()
    month = this_month.month-1 if this_month.month > 1 else 12
    if month < 12:
        return this_month.replace(month=month)
    else:
        return this_month.replace(month=12, year=this_month.year-1)


def data_between(data_frame, start, end=datetime.now()):
    """
    Selecte all rows that have 'dateCreated' after start and before end.

    :param data_frame Data to select from
    :param start The start date, including
    :param end The end date, excluding
    :return Sub series
    """
    return data_frame.loc[(data_frame['dateCreated'] >= start) &
                          (data_frame['dateCreated'] < end)]


def query_open_reviews():
    params = {'queryKey': 'active', 'order': 'newest',
              'api.token': os.getenv('CONDUIT_TOKEN')}
    result = requests.get(
            "{}/differential.revision.search".format(ENDPOINT), params).json()

    data_frame = pandas_frame_from(result)
    data_frame.pop('attachments')
    data_frame.pop('policy')
    data_frame.pop('type')
    data_frame.pop('jira.issues')

    # Convert dates and calculate age
    dates = data_frame[['dateCreated', 'dateModified']].applymap(
            lambda d: datetime.fromtimestamp(d))
    data_frame = data_frame.join(dates, rsuffix='.converted').assign(
            age=lambda x: datetime.now() - x['dateCreated.converted'])

    age_stats = data_frame[['age']].describe(percentiles=[.25, .5, .75, .9])
    print(age_stats)


def query_closed_reviews():
    conduit_token = os.getenv('CONDUIT_TOKEN')

    if not conduit_token:
        print("Please define a token with: CONDUIT_TOKEN=1234 ./review.py")
        exit(1)

    params = {'status': 'status-closed', 'api.token': conduit_token}
    result = requests.get(
            "{}/differential.query".format(ENDPOINT), params).json()

    data_frame = pandas.io.json.json_normalize(result, 'result')
    dates = data_frame[['dateCreated', 'dateModified']].apply(
        pandas.to_numeric).applymap(lambda d: datetime.fromtimestamp(d))

    total_life_time_stats = stats(life_time(dates), "All Time")

    last_month = beginning_of_last_month()
    this_month = beginning_of_this_month()

    life_time_last_month = life_time(
        data_between(dates, last_month, this_month))
    last_month_stats = stats(
        life_time_last_month, last_month.strftime("Last Month (%b)"))

    life_time_this_month = life_time(
        data_between(dates, this_month))
    this_month_stats = stats(
        life_time_this_month, this_month.strftime("This Month (%b)"))

    all_stats = pandas.concat(
        [total_life_time_stats, last_month_stats, this_month_stats], axis=1)

    print(all_stats)

if __name__ == "__main__":
    query_closed_reviews()
