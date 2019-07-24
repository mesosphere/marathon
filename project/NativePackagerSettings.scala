object NativePackagerSettings {
  def debianSourceCommands: String = {
    val unstableRepo = if (Dependency.V.MesosDebian.contains(".pre")) {
      List("stretch-unstable")
    } else Nil

    val repos = List("stretch-testing", "stretch") ++ unstableRepo

    repos.map { repo =>
      s"""echo "deb http://repos.mesosphere.com/debian ${repo} main" | tee -a /etc/apt/sources.list.d/mesosphere.list"""
    }.mkString(" && \\\n")
  }
}
