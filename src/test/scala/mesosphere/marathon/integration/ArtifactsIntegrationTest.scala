package mesosphere.marathon.integration

import java.io.File

import com.google.common.io.Files

import mesosphere.marathon.integration.setup._
import org.scalatest.{ ConfigMap, GivenWhenThen, Matchers }

class ArtifactsIntegrationTest extends IntegrationFunSuite with SingleMarathonIntegrationTest with GivenWhenThen with Matchers {
  var artifactsDir: File = Files.createTempDir()

  override def extraMarathonParameters = List("--artifact_store", s"file://${artifactsDir.toString}")

  override protected def afterAll(configMap: ConfigMap): Unit = {
    super.afterAll(configMap)
    artifactsDir.delete()
  }

  test("upload and fetch an artifact") {
    val tempFile = File.createTempFile("marathon-integration", ".txt")
    try {
      Given("an artifact")
      Files.write("foobar".getBytes, tempFile)

      When("uploading the artifact")
      val result = marathon.uploadArtifact("/foo", tempFile)

      Then("the request should be successful")
      result.code should be (201) // created
    }
    finally {
      tempFile.delete()
    }

    When("fetching the artifact")
    val result = marathon.getArtifact("/foo")

    Then("the request should be successful")
    result.code should be (200)
    result.entityString should be ("foobar")
  }

  test("upload and delete an artifact") {
    val tempFile = File.createTempFile("marathon-integration", ".txt")
    try {
      Given("an uploaded artifact")
      Files.write("foobar".getBytes, tempFile)
      marathon.uploadArtifact("/foobar", tempFile)
    }
    finally {
      tempFile.delete()
    }

    When("the artifact has been removed")
    val result = marathon.deleteArtifact("/foobar")

    Then("the request should be successful")
    result.code should be(200)

    Then("the artifact should be gone")
    marathon.getArtifact("/foobar").code should be (404)
  }
}
