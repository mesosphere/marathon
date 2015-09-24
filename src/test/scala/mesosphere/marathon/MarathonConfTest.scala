package mesosphere.marathon

import scala.util.{ Failure, Try }

class MarathonConfTest extends MarathonSpec {

  private[this] val principal = "foo"
  private[this] val secretFile = "/bar/baz"

  test("MesosAuthenticationIsOptional") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050"
    )
    assert(conf.mesosAuthenticationPrincipal.isEmpty)
    assert(conf.mesosAuthenticationSecretFile.isEmpty)
    assert(conf.checkpoint.get == Some(true))
  }

  test("MesosAuthenticationPrincipal") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--mesos_authentication_principal", principal
    )
    assert(conf.mesosAuthenticationPrincipal.isDefined)
    assert(conf.mesosAuthenticationPrincipal.get == Some(principal))
    assert(conf.mesosAuthenticationSecretFile.isEmpty)
  }

  test("MesosAuthenticationSecretFile") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--mesos_authentication_principal", principal,
      "--mesos_authentication_secret_file", secretFile
    )
    assert(conf.mesosAuthenticationPrincipal.isDefined)
    assert(conf.mesosAuthenticationPrincipal.get == Some(principal))
    assert(conf.mesosAuthenticationSecretFile.isDefined)
    assert(conf.mesosAuthenticationSecretFile.get == Some(secretFile))
  }

  test("HA mode is enabled by default") {
    val conf = defaultConfig()
    assert(conf.highlyAvailable())
  }

  test("Disable HA mode") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--disable_ha"
    )
    assert(!conf.highlyAvailable())
  }

  test("Checkpointing is enabled by default") {
    val conf = defaultConfig()
    assert(conf.checkpoint())
  }

  test("Disable checkpointing") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--disable_checkpoint"
    )
    assert(!conf.checkpoint())
  }

  test("MarathonStoreTimeOut") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--marathon_store_timeout", "5000"
    )
    assert(conf.marathonStoreTimeout.isDefined)
    assert(conf.marathonStoreTimeout.get == Some(5000))
  }

  test("--default_accepted_resource_roles *,marathon will fail without --mesos_role marathon") {
    val triedConfig = Try(makeConfig(
      "--master", "127.0.0.1:5050",
      "--default_accepted_resource_roles", "*,marathon"
    ))
    assert(triedConfig.isFailure)
    triedConfig match {
      case Failure(e) if e.getMessage ==
        "requirement failed: " +
        "--default_accepted_resource_roles contains roles for which we will not receive offers: marathon" =>
      case other =>
        fail(s"unexpected triedConfig: $other")
    }
  }

  test("--default_accepted_resource_roles *,marathon with --mesos_role marathon") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--mesos_role", "marathon",
      "--default_accepted_resource_roles", "*,marathon"
    )

    assert(conf.defaultAcceptedResourceRolesSet == Set("*", "marathon"))
  }

  test("--default_accepted_resource_roles *") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--default_accepted_resource_roles", "*"
    )
    assert(conf.defaultAcceptedResourceRolesSet == Set("*"))
  }

  test("--default_accepted_resource_roles default without --mesos_role") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050"
    )
    assert(conf.defaultAcceptedResourceRolesSet == Set("*"))
  }

  test("--default_accepted_resource_roles default with --mesos_role") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050",
      "--mesos_role", "marathon"
    )
    assert(conf.defaultAcceptedResourceRolesSet == Set("*", "marathon"))
  }
}

