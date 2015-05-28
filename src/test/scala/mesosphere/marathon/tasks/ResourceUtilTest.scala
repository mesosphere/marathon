package mesosphere.marathon.tasks

import org.apache.mesos.Protos._
import org.scalatest.{ Assertions, GivenWhenThen, FunSuite }
import scala.collection.JavaConverters._

class ResourceUtilTest extends FunSuite with GivenWhenThen with Assertions {
  test("no base resources") {
    val leftOvers = ResourceUtil.consumeResources(
      Seq(),
      Seq(ports("ports", 2 to 12))
    )
    assert(leftOvers == Seq())
  }

  test("resource mix") {
    val leftOvers = ResourceUtil.consumeResources(
      Seq(scalar("cpus", 3), ports("ports", 2 to 20), set("labels", Set("a", "b"))),
      Seq(scalar("cpus", 2), ports("ports", 2 to 12), set("labels", Set("a")))
    )
    assert(leftOvers == Seq(scalar("cpus", 1), ports("ports", 13 to 20), set("labels", Set("b"))))
  }

  test("resource repeated consumed resources with the same name/role") {
    val leftOvers = ResourceUtil.consumeResources(
      Seq(scalar("cpus", 3)),
      Seq(scalar("cpus", 2), scalar("cpus", 1))
    )
    assert(leftOvers == Seq())
  }

  test("resource consumption considers roles") {
    val leftOvers = ResourceUtil.consumeResources(
      Seq(scalar("cpus", 2), scalar("cpus", 2, role = "marathon")),
      Seq(scalar("cpus", 0.5), scalar("cpus", 1, role = "marathon"), scalar("cpus", 0.5, role = "marathon"))
    )
    assert(leftOvers == Seq(scalar("cpus", 1.5), scalar("cpus", 0.5, role = "marathon")))
  }

  // in the middle
  portsTest(consumedResource = Seq(10 to 10), baseResource = Seq(5 to 15), expectedResult = Some(Seq(5 to 9, 11 to 15)))
  portsTest(consumedResource = Seq(10 to 11), baseResource = Seq(5 to 15), expectedResult = Some(Seq(5 to 9, 12 to 15)))
  portsTest(consumedResource = Seq(10 to 11), baseResource = Seq(5 to 15, 30 to 31),
    expectedResult = Some(Seq(5 to 9, 12 to 15, 30 to 31)))

  portsTest(consumedResource = Seq(), baseResource = Seq(5 to 15), expectedResult = Some(Seq(5 to 15)))

  portsTest(
    consumedResource = Seq(31084 to 31084),
    baseResource = Seq(31000 to 31096, 31098 to 32000), expectedResult = Some(Seq(31000 to 31083, 31085 to 31096, 31098 to 32000)))

  // overlapping smaller
  portsTest(consumedResource = Seq(2 to 5), baseResource = Seq(5 to 15), expectedResult = Some(Seq(6 to 15)))
  portsTest(consumedResource = Seq(2 to 6), baseResource = Seq(5 to 15), expectedResult = Some(Seq(7 to 15)))

  // overlapping bigger
  portsTest(consumedResource = Seq(15 to 20), baseResource = Seq(5 to 15), expectedResult = Some(Seq(5 to 14)))
  portsTest(consumedResource = Seq(14 to 20), baseResource = Seq(5 to 15), expectedResult = Some(Seq(5 to 13)))

  // not contained in base resource
  portsTest(consumedResource = Seq(5 to 15), baseResource = Seq(), expectedResult = None)
  portsTest(consumedResource = Seq(2 to 4), baseResource = Seq(5 to 15), expectedResult = Some(Seq(5 to 15)))
  portsTest(consumedResource = Seq(16 to 20), baseResource = Seq(5 to 15), expectedResult = Some(Seq(5 to 15)))

  scalarTest(consumedResource = 3, baseResource = 10, expectedResult = Some(10.0 - 3.0))
  scalarTest(consumedResource = 3, baseResource = 2, expectedResult = None)

  setResourceTest(consumedResource = Set("a", "b"), baseResource = Set("a", "b", "c"), expectedResult = Some(Set("c")))
  setResourceTest(consumedResource = Set("a", "b", "c"), baseResource = Set("a", "b", "c"), expectedResult = None)

  private[this] def setResourceTest(
    consumedResource: Set[String],
    baseResource: Set[String],
    expectedResult: Option[Set[String]]): Unit = {

    test(s"consuming sets resource $consumedResource from $baseResource results in $expectedResult") {
      val r1 = set("cpus", consumedResource)
      val r2 = set("cpus", baseResource)
      val r3 = expectedResult.map(set("cpus", _))
      val result = ResourceUtil.consumeResource(r2, r1)
      assert(result == r3)
    }
  }

  private[this] def set(name: String, labels: Set[String]): Resource = {
    Resource
      .newBuilder()
      .setName(name)
      .setType(Value.Type.SET)
      .setSet(Value.Set.newBuilder().addAllItem(labels.asJava))
      .build()
  }

  private[this] def portsTest(
    consumedResource: Seq[Range.Inclusive],
    baseResource: Seq[Range.Inclusive],
    expectedResult: Option[Seq[Range.Inclusive]]): Unit = {

    test(s"consuming ports resource $consumedResource from $baseResource results in $expectedResult") {
      val r1 = ports("cpus", consumedResource: _*)
      val r2 = ports("cpus", baseResource: _*)
      val r3 = expectedResult.map(ports("cpus", _: _*))
      val result = ResourceUtil.consumeResource(r2, r1)
      assert(result == r3)
    }
  }

  private[this] def ports(name: String, ranges: Range.Inclusive*): Resource = {
    def toRange(range: Range.Inclusive): Value.Range =
      Value.Range
        .newBuilder()
        .setBegin(range.start.toLong).setEnd(range.end.toLong).build()

    Resource
      .newBuilder()
      .setName(name)
      .setType(Value.Type.RANGES)
      .setRanges(Value.Ranges.newBuilder().addAllRange(ranges.map(toRange).asJava))
      .build()
  }

  private[this] def scalarTest(consumedResource: Double, baseResource: Double, expectedResult: Option[Double]): Unit = {
    test(s"consuming scalar resource $consumedResource from $baseResource results in $expectedResult") {
      val r1 = scalar("cpus", consumedResource)
      val r2 = scalar("cpus", baseResource)
      val r3 = expectedResult.map(scalar("cpus", _))
      val result = ResourceUtil.consumeResource(r2, r1)
      assert(result == r3)
    }
  }

  private[this] def scalar(name: String, d: Double, role: String = "*"): Resource = {
    Resource
      .newBuilder()
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder().setValue(d))
      .setRole(role)
      .build()
  }
}
