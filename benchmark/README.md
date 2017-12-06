Benchmarks here are generally in src/main and can be run with `benchmark/jmh:run`
within SBT.

See here for more details:
[SBT-JMH](https://github.com/ktoso/sbt-jmh)
[JMS-Samples](http://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples/)

## Flamegraphs

[Flamegraphs](http://www.brendangregg.com/FlameGraphs/cpuflamegraphs.html) are a
great tool to figure out where processing time is spent. You can create one by
following these steps

### Prerequisites

You should have [jfr-flame-graph](https://github.com/chrishantha/jfr-flame-graph)
and [FlameGraph](https://github.com/brendangregg/FlameGraph) installed or rather
runnable on you machine.

You also need the environment variable `FLAMEGRAPH_DIR` set to the path of the
FlameGraph by Brandan Gregg.

### Creating Flamegraphs

1. Create a flight record of the benchmark run by passing `-prof jmh.extras.JFR`
  to the run, e.g. `sbt "benchmark/jmh:run -prof jmh.extras.JFR -i 1 -wi 3 -f1
  -t1 .*GroupManagerBenchmark"`. This will produce a `.jfr` file in the
  benchmark folder.
2. Create a flamegraph SVG file with `<path to jfr-flame-graph>/create_flamegraph.sh -f  <path to jfr file> -i > flamegraph.svg`.
