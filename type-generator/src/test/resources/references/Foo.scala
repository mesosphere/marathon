// format: OFF
package mesosphere.raml.test

/**
 */
case class Foo(content: String) extends RamlGenerated

object Foo {
  implicit val playJsonFormat = play.api.libs.json.Json.format[Foo]
}