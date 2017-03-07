package mesosphere.marathon
package core.storage.backup.impl

import java.io.File

import akka.stream.scaladsl.{ FileIO, Keep, Source }
import akka.util.ByteString
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.backup.BackupItem
import mesosphere.marathon.stream.Sink

class TarBackupFlowTest extends AkkaUnitTest {

  "TarBackupFlow" should {

    "write entries and read again from a tar file" in {
      Given("A tar file with 100 backup items")
      val file = File.createTempFile("marathon-tarfile", ".tar")
      file.deleteOnExit()
      val tarSink = TarBackupFlow.tar.toMat(FileIO.toPath(file.toPath))(Keep.right)
      val backupItems = 0.until(100).map(num => BackupItem("foo", s"name-$num", None, ByteString(s"data-$num")))
      val source = Source.fromIterator(() => backupItems.iterator)

      When("we use the tar file sink")
      source
        .runWith(tarSink)
        .futureValue
      file.length() should be >= 0L

      When("we read from the tar source")
      val sink = Sink.seq[BackupItem]
      val tarSource = FileIO.fromPath(file.toPath).via(TarBackupFlow.untar)
      val result = tarSource.runWith(sink).futureValue

      Then("The tar file has all the content")
      result shouldBe backupItems
      file.delete()
    }
  }
}
