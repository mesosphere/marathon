package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.GroupCreation
import org.scalatest.prop.TableDrivenPropertyChecks

class PathMatchersTest extends UnitTest with GroupCreation with ScalatestRouteTest with TableDrivenPropertyChecks {
  import PathMatchers._
  import PathId.StringPathId

  class PathMatchersTestFixture {
    val app1 = AppDefinition("/test/group1/app1".toPath)
    val app2 = AppDefinition("/test/group2/app2".toPath)
    val app3 = AppDefinition("/test/group2/restart".toPath)
    val rootGroup = createRootGroup(
      groups = Set(
        createGroup("/test".toPath, groups = Set(
          createGroup("/test/group1".toPath, Map(app1.id -> app1)),
          createGroup("/test/group2".toPath, Map(app2.id -> app2)),
          createGroup("/test/group2".toPath, Map(app3.id -> app3))
        ))))

  }

  "ExistingAppPathId matcher" should {

    "not match groups" in new PathMatchersTestFixture {
      ExistingRunSpecId(() => rootGroup)(Path("test/group1")) shouldBe Unmatched
    }

    "match apps that exist" in new PathMatchersTestFixture {
      ExistingRunSpecId(() => rootGroup)(Path("test/group1/app1")) shouldBe Matched(Path(""), Tuple1("/test/group1/app1".toPath))
    }

    "match not match apps that don't exist" in new PathMatchersTestFixture {
      ExistingRunSpecId(() => rootGroup)(Path("test/group1/app3")) shouldBe Unmatched
    }

    "leave path components after matching appIds unconsumed" in new PathMatchersTestFixture {
      ExistingRunSpecId(() => rootGroup)(Path("test/group1/app1/restart/ponies")) shouldBe Matched(Path("/restart/ponies"), Tuple1("/test/group1/app1".toPath))
    }

    "match apps that contains 'restart'" in new PathMatchersTestFixture {
      ExistingRunSpecId(() => rootGroup)(Path("test/group2/restart/restart")) shouldBe Matched(Path("/restart"), Tuple1("/test/group2/restart".toPath))
    }
  }

  "AppPathIdLike matcher" should {
    "stop matching when it reaches a Marathon API keyword" in {
      AppPathIdLike(Path("test/group/restart/ponies")) shouldBe Matched(Path("/restart/ponies"), Tuple1("/test/group".toPath))
    }

    "match all the way to to the end" in {
      AppPathIdLike(Path("test/group1/app1")) shouldBe Matched(Path.Empty, Tuple1("/test/group1/app1".toPath))
    }

    "considers empty paths as non-matches" in {
      AppPathIdLike(Path("/")) shouldBe Unmatched
    }

    "considers it an unmatch if path starts with keyword" in {
      AppPathIdLike(Path("/restart")) shouldBe Unmatched
    }
  }

  "PodsPathIdLike matcher" should {
    val keywords = Table(
      ("instances"),
      ("versions"),
      ("status")
    )
    forAll(keywords) { (keyword) =>
      s"stop matching when it reaches ::$keyword" in {
        PodsPathIdLike(Path(s"test/group/pods_id::$keyword")) shouldBe Matched(Path(s"::$keyword"), Tuple1("test/group/pods_id"))
        PodsPathIdLike(Path(s"test/group/pods_id::$keyword/more/segments")) shouldBe Matched(Path(s"::$keyword/more/segments"), Tuple1("test/group/pods_id"))
      }

      s"not stop matching when keyword $keyword is part of path id" in {
        PodsPathIdLike(Path(s"test/group/$keyword/pods_id::$keyword")) shouldBe Matched(Path(s"::$keyword"), Tuple1(s"test/group/$keyword/pods_id"))
        PodsPathIdLike(Path(s"test/group/$keyword/pods_id::$keyword/more/segments")) shouldBe Matched(Path(s"::$keyword/more/segments"), Tuple1(s"test/group/$keyword/pods_id"))
      }
    }

    "match only path until :: even when it is not followed by keyword" in {
      PodsPathIdLike(Path(s"test/group/pods_id::other")) shouldBe Matched(Path(s"::other"), Tuple1(s"test/group/pods_id"))
    }

    "considers empty paths as non-matches" in {
      PodsPathIdLike(Path("/")) shouldBe Unmatched
    }
  }
}
