#!/usr/bin/env python2

import argparse

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="HAProxy Marathon Service Router")
    parser.add_argument("--marathon_endpoints", "-m", required=True, nargs="+",
                        help="Marathon endpoint, eg. marathon1 marathon2:8080")
    parser.add_argument("--callback_url", "-c",
                        help="Marathon callback URL")
    args = parser.parse_args()
    print args
