package mesosphere.marathon
package api.v2.validation

import com.wix.accord.{ Failure, Result }
import com.wix.accord.scalatest.ResultMatchers
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ App, Container, ContainerPortMapping, EngineType, Network, NetworkMode }

class AppValidationTest extends UnitTest with Validation with ResultMatchers {

  import Normalization._

  private implicit val normalizeResult: Normalization[Result] = Normalization {
    // normalize failures => human readable error messages
    case f: Failure => Failure(f.violations.flatMap(allRuleViolationsWithFullDescription(_)))
    case x => x
  }

  "canonical app validation" when {

    val basicValidator = AppValidation.validateCanonicalAppAPI(Set.empty)

    "multiple container networks are specified for an app" should {

      val app = App(id = "/foo", cmd = Some("bar"), networks = 1.to(2).map(i => Network(mode = NetworkMode.Container, name = Some(i.toString))))

      // we don't allow this yet because Marathon doesn't yet support per-network port-mapping (and it's not meaningful
      // for a single host port to map to the same container port on multiple network interfaces).
      "disallow containerPort to hostPort mapping" in {
        val ct = Container(`type` = EngineType.Mesos, portMappings = Some(Seq(ContainerPortMapping(hostPort = Option(0)))))
        val badApp = app.copy(container = Some(ct))
        basicValidator(badApp).normalize should failWith("/container/portMappings(0)/hostPort" -> "must be empty")
      }

      "allow portMappings that don't declare hostPort" in {
        val ct = Container(`type` = EngineType.Mesos, portMappings = Some(Seq(ContainerPortMapping())))
        val badApp = app.copy(container = Some(ct))
        basicValidator(badApp) should be(aSuccess)
      }
    }
  }
}
