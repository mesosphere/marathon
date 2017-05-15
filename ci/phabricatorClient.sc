#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import scalaj.http._
import upickle._

/**
 * Accept revision revisionId.
 */
def accept(revisionId: String): Unit = {
  val PHABRICATOR_API_TOKEN =
    sys.env.getOrElse("PHABRICATOR_API_TOKEN", throw new IllegalArgumentException("PHABRICATOR_API_TOKEN enviroment variable was not set."))

  val response = Http("https://phabricator.mesosphere.com/api/differential.revision.edit")
    .postForm(Seq(
      "params" -> Js.Obj(
         "__conduit__" -> Js.Obj("token" -> Js.Str(PHABRICATOR_API_TOKEN)),
        "transactions" -> Js.Arr(
          Js.Obj("type" -> Js.Str("accept"))
        ),
        "objectIdentifier" -> Js.Str(revisionId)
      ).toString
    ))
    .asString
    .throwError
}

/**
 * Reject revision revisionId.
 */
def reject(revisionId: String): Unit = {
  val PHABRICATOR_API_TOKEN =
    sys.env.getOrElse("PHABRICATOR_API_TOKEN", throw new IllegalArgumentException("PHABRICATOR_API_TOKEN enviroment variable was not set."))

  val response = Http("https://phabricator.mesosphere.com/api/differential.revision.edit")
    .postForm(Seq(
      "params" -> Js.Obj(
         "__conduit__" -> Js.Obj("token" -> Js.Str(PHABRICATOR_API_TOKEN)),
        "transactions" -> Js.Arr(
          Js.Obj("type" -> Js.Str("reject"))
        ),
        "objectIdentifier" -> Js.Str(revisionId)
      ).toString
    ))
    .asString
    .throwError
}

/**
 * Comment with msg on revision revisionId.
 */
def comment(revisionId: String, msg: String): Unit = {
  val PHABRICATOR_API_TOKEN =
    sys.env.getOrElse("PHABRICATOR_API_TOKEN", throw new IllegalArgumentException("PHABRICATOR_API_TOKEN enviroment variable was not set."))

  val response = Http("https://phabricator.mesosphere.com/api/differential.revision.edit")
    .postForm(Seq(
      "params" -> Js.Obj(
         "__conduit__" -> Js.Obj("token" -> Js.Str(PHABRICATOR_API_TOKEN)),
        "transactions" -> Js.Arr(
          Js.Obj(
            "type" -> Js.Str("comment"),
            "value" -> Js.Str(msg)
          )
        ),
        "objectIdentifier" -> Js.Str(revisionId)
      ).toString
    ))
    .asString
    .throwError
}

/**
 * Report success of diff build back to Phabricator.
 *
 * @param diffId The differential ID of the build.
 * @param revisionId The identifier for the Phabricator revision that was build.
 * @param buildUrl A link back to the build on Jenkins.
 * @param buildTag Identifies build.
 */
@main
def reportSuccess(diffId: String, revisionId: String, buildUrl: String, buildTag: String): Unit = {
  // We accept and comment in two different calls because Phabriactor won't
  // apply the comment if the diff is already accepted.
  accept(revisionId)
  comment(revisionId, s"\u2714 Build of $diffId completed [[ $buildUrl | $buildTag ]].")
}

/**
 * Report failue of diff build back to Phabricator.
 * @param diffId The differential ID of the build.
 * @param revisionId The identifier for the Phabricator revision that was build.
 * @param buildUrl A link back to the build on Jenkins.
 * @param msg The error message for the failure.
 * @param buildTag Identifies build.
 */
@main
def reportFailure(diffId: String, revisionId: String, buildUrl: String, buildTag: String, msg: String) : Unit = {
  // We reject and comment in two different calls because Phabriactor won't
  // apply the comment if the diff is already rejected.
  reject(revisionId)
  comment(revisionId, s"\u2717 Build of $diffId failed [[ $buildUrl | $buildTag ]].\n Error message: \n>$msg")
}
