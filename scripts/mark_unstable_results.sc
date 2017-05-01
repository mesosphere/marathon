#!/usr/bin/env amm

import ammonite.ops._
import scala.xml._
import scala.xml.transform._

// change `//testsuite[@name]` to be prefixed with `UNSTABLE.`
@main
def main(directories: String*) = {
  directories.foreach { dir =>
    val files = ls.rec ! (pwd / RelPath(dir))
    files.foreach { file =>
      val xml = XML.loadFile(file.toString)
      val rewrite = new RewriteRule {
        override def transform(n: Node): Seq[Node] = n match {
          case Elem(prefix, "testsuite", attribs, scope, children@_*) =>
            val newAttributes = attribs.filter(_.key != "name").append(new UnprefixedAttribute("name", s"UNSTABLE.${attribs.get("name").head.text}", Null))
            Elem(prefix, "testsuite", newAttributes, scope, minimizeEmpty = false, children: _*)
          case Elem(prefix, "testcase", attribs, scope, children@_*) =>
            val newAttributes = attribs.filter(_.key != "classname").append(new UnprefixedAttribute("classname", s"UNSTABLE.${attribs.get("classname").head.text}", Null))
            Elem(prefix, "testcase", newAttributes, scope, minimizeEmpty = false, children: _*)
          case other => other
        }
      }
      val rt = new RuleTransformer(rewrite)
      val newXML = rt.transform(xml).head
      XML.save(file.toString, newXML, xmlDecl = true)
    }
  }
}