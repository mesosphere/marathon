package mesosphere.raml.backend.liquid

import java.io.File

import liqp.filters.Filter

import scala.collection.JavaConverters._

object UpperCaseFilter extends Filter("upperCase") {

  override def apply(value: Any, params: Object*): Object = {
    value match {
      case s: String => s.toUpperCase
      case it: java.lang.Iterable[String] => it.asScala.map(_.toUpperCase).asJava
      case _ => throw new IllegalArgumentException(s"Value $value is not a String.")
    }
  }
}

object LowerCaseFilter extends Filter("lowerCase") {

  override def apply(value: Any, params: Object*): Object = {
    value match {
      case s: String => s.toLowerCase
      case it: java.lang.Iterable[String] => it.asScala.map(_.toLowerCase).asJava
      case _ => throw new IllegalArgumentException(s"Value $value is not a String.")
    }
  }
}

object Template {
  val classLoader: ClassLoader = getClass.getClassLoader

  /**
    * Loads a Liquid template from "/src/main/resources/templates/name".
    * @param name
    */
  def apply(name: String): liqp.Template = {
    val file: File = new File(classLoader.getResource(s"templates/$name").getFile)
    liqp.Template.parse(file).`with`(UpperCaseFilter).`with`(LowerCaseFilter)
  }
}
