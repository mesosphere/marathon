package mesosphere.marathon.api.validation

import org.junit.Assert._
import org.junit.Test
import org.scalatest.mock.MockitoSugar
import javax.validation.{Validation, ConstraintValidatorContext}


/**
 * Test URI annotation and parsing logic.
 */
class URIValidatorTest extends MockitoSugar {

  @Test
  def validAbsoluteURICanBeParsed() {
    assertTrue(validate(""))
    assertTrue(validate("http://www.test.org"))
    assertTrue(validate("http://www.test.org:9000"))
    assertTrue(validate("http://www.test.org:9000/context/asset.mfe"))
  }

  @Test
  def validRelativeURICanBeParsed() {
    assertTrue(validate("//cmd"))
    assertTrue(validate("test"))
    assertTrue(validate("context/asset.mfe"))
  }

  @Test
  def invalidURIThrowsError() {
    assertFalse(validate(null))
    assertFalse(validate("test test"))
  }

  @Test
  def annotatedClassFails() {
    val missing = new Object { @URI val uri = "test test" }
    val factory = Validation.buildDefaultValidatorFactory()
    val result = factory.getValidator.validate(missing)
    assertFalse(result.isEmpty)
  }

  val context = mock[ConstraintValidatorContext]
  def validate(string:String) = new URIValidator().isValid(string, context)
}
