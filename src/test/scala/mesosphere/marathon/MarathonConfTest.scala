package mesosphere.marathon

class MarathonConfTest extends MarathonSpec {

  private[this] val principal = "foo"
  private[this] val secretFile = "/bar/baz"

  test("MesosAuthenticationIsOptional") {
    val conf = makeConfig(
      "--master", "127.0.0.1:5050"
    )
    assert(conf.mesosAuthenticationPrincipal.isEmpty)
    assert(conf.mesosAuthenticationSecretFile.isEmpty)
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

}

