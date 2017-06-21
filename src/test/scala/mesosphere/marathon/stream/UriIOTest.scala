package mesosphere.marathon
package stream

import java.io.{ File, IOException }
import java.net.URI

import akka.stream.scaladsl.Source
import akka.util.ByteString
import mesosphere.AkkaUnitTest
import org.apache.commons.io.FileUtils

class UriIOTest extends AkkaUnitTest {

  "UriIO" should {

    "validate URIs as valid" in {
      UriIO.isValid(new URI("file:///path/to/file")) shouldBe true
      UriIO.isValid(new URI("s3://bucket.name/path/in/bucket")) shouldBe true
    }

    "validate URIs as invalid" in {
      UriIO.isValid(new URI("file:///")) shouldBe false
      UriIO.isValid(new URI("s3:///path/in/bucket")) shouldBe false
      UriIO.isValid(new URI("s3://bucket.name")) shouldBe false
      UriIO.isValid(new URI("unknown://bucket.name/foo/bla")) shouldBe false
    }

    "read from a file" in {
      val file = File.createTempFile("marathon-file", ".test")
      file.deleteOnExit()
      val content = s"Hello World ${System.currentTimeMillis()}"
      FileUtils.write(file, content)
      UriIO.reader(new URI(s"file://${file.getAbsolutePath}")).runWith(Sink.foreach[ByteString]{ bs =>
        bs.utf8String shouldBe content
      }).futureValue
      file.delete()
    }

    "write to a file" in {
      val file = File.createTempFile("marathon-file", ".test")
      file.deleteOnExit()
      val content = s"Hello World ${System.currentTimeMillis()}"
      Source.single(ByteString(content)).runWith(UriIO.writer(new URI(s"file://${file.getAbsolutePath}"))).futureValue
      FileUtils.readFileToString(file) shouldBe content
      file.delete()
    }

    "read from a file that is not readable fails" in {
      // note: we try to read from a directory, which should always fail
      // creating a file and disable reading does not work with root privileges.
      val tmpDir = new File(sys.props("java.io.tmpdir"))
      val future = UriIO.reader(new URI(s"file://${tmpDir.getAbsolutePath}")).runWith(Sink.ignore)
      future.failed.futureValue shouldBe a[IOException]
    }

    "write to a file that is not writable fails" in {
      // note: we try to write to a directory, which should always fail
      // creating a file and disable writing does not work with root privileges.
      val tmpDir = new File(sys.props("java.io.tmpdir"))
      val content = s"Hello World ${System.currentTimeMillis()}"
      val future = Source.single(ByteString(content)).runWith(UriIO.writer(new URI(s"file://${tmpDir.getAbsolutePath}")))
      future.failed.futureValue shouldBe a[IOException]
    }
  }
}
