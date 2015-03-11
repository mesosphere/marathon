package mesosphere.marathon.health

object HealthCounts {
  def newBuilder: HealthCounts.Builder = new Builder

  class Builder {
    private[this] var healthy = 0
    private[this] var unknown = 0
    private[this] var unhealthy = 0

    def incHealthy(): Unit = healthy += 1
    def incUnknown(): Unit = unknown += 1
    def incUnhealthy(): Unit = unhealthy += 1
    def result(): HealthCounts = HealthCounts(healthy, unknown, unhealthy)
  }
}

case class HealthCounts(
    healthy: Int,
    unknown: Int,
    unhealthy: Int) {
  def +(that: HealthCounts): HealthCounts =
    HealthCounts(
      this.healthy + that.healthy,
      this.unknown + that.unknown,
      this.unhealthy + that.unhealthy
    )
}
