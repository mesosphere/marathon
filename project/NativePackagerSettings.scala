import com.typesafe.sbt.SbtNativePackager.{ Debian, Rpm }
import com.typesafe.sbt.packager.Keys._
import sbt.Keys._
import sbt._

object NativePackagerSettings {
  /* This is to work around an issue with sbt-native-packager's start-debian-template, which caused no log output to be
   * captured. When https://github.com/sbt/sbt-native-packager/issues/1021 is fixed, then we can remove it (and the
   * template).
   */
  val debianSystemVSettings = Seq(
    (linuxStartScriptTemplate in Debian) :=
      (baseDirectory.value / "project" / "NativePackagerSettings/systemv/start-debian-template").toURI.toURL
  )

  /* This is to work around an issue with sbt-native-packager's start-template which caused /etc/default/marathon to be
   * ignored.  When https://github.com/sbt/sbt-native-packager/issues/1023 is fixed, then we can remove it (and the
   * template).
   */
  val ubuntuUpstartSettings = Seq(
    (linuxStartScriptTemplate in Debian) :=
      (baseDirectory.value / "project" / "NativePackagerSettings/upstart/start-template").toURI.toURL
  )
}
