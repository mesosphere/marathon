import ammonite.ops.Path


case class BuildContext(workingDirectory: WorkingDirectory,
                        marathon: MarathonContext,
                        universe: UniverseContext,
                        dcos: DcosContext,
                        dcosEe: DcosEeContext)

case class MarathonContext(repository: GitRepository, directory: MarathonRepoDirectory)
case class UniverseContext(repository: GitRepository, directory: UniverseRepoDirectory)
case class DcosContext(repository: GitRepository, directory: DcosOssRepoDirectory)
case class DcosEeContext(repository: GitRepository, directory: DcosEeRepoDirectory)

case class GitRepository(owner: String, name: String)

case class WorkingDirectory(path: Path)

//to be extra type-safe
case class MarathonRepoDirectory(path: Path)
case class UniverseRepoDirectory(path: Path)
case class DcosOssRepoDirectory(path: Path)
case class DcosEeRepoDirectory(path: Path)