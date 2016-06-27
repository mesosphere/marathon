package mesosphere.marathon.benchmarks

import org.scalameter.Bench.HTMLReport
import org.scalameter.Measurer
import org.scalameter.Measurer.Default
import org.scalameter.execution.LocalExecutor
import org.scalameter.picklers.Implicits._

trait Benchmark extends HTMLReport {
  import org.scalameter.reporting._
  override def measurer: Default = new Measurer.Default
  // The non-local benchmarker doesn't seem to work when we fork a process?
  override def executor: LocalExecutor[Double] = new LocalExecutor(warmer, aggregator, measurer)
  def tester: RegressionReporter.Tester =
    RegressionReporter.Tester.OverlapIntervals()
  def historian: RegressionReporter.Historian =
    RegressionReporter.Historian.ExponentialBackoff()
  def online: Boolean = false

  protected def beforeAll(): Unit = {}
  protected def afterAll(): Unit = {}

  override def executeTests(): Boolean = {
    beforeAll()
    val result = super.executeTests()
    afterAll()
    result
  }
}
