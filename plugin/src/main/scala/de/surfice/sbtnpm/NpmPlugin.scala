//     Project: SBT NPM
//      Module:
// Description:
package de.surfice.sbtnpm

import java.io.{File => _}

import com.typesafe.config.Config
import de.surfice.sbtnpm.utils.{ExternalCommand, FileWithLastrun}
import sbt.Keys._
import sbt._
import utils._
import collection.JavaConverters._
import sjsonnew.BasicJsonProtocol._

object NpmPlugin extends AutoPlugin {
  type NpmDependency = (String,String)

  override lazy val requires = ConfigPlugin

  // Exported keys
  /**
   * @groupname tasks Tasks
   * @groupname settings Settings
   */
  object autoImport {
    /**
     * Defines the directory in which the npm `node_modules` resides.
     *
     * Defaults to `baseDirectory.value`.
     *
     * @group settings
     */
    val npmTargetDir: SettingKey[File] =
      settingKey[File]("Root directory of the npm project")

    val npmNodeModulesDir: SettingKey[File] =
      settingKey("Path to the node_modules dir")

    /**
      * List of the NPM packages (name and version) your application depends on.
      * You can use [semver](https://docs.npmjs.com/misc/semver) versions:
      *
      * {{{
      *   npmDependencies += "uuid" -> "~3.0.0"
      * }}}
      *
      * @group settings
      */
    val npmDependencies: SettingKey[Seq[NpmDependency]] =
      settingKey[Seq[NpmDependency]]("NPM dependencies (libraries that your program uses)")

    /** @group settings */
    val npmDevDependencies: SettingKey[Seq[NpmDependency]] =
      settingKey[Seq[NpmDependency]]("NPM dev dependencies (libraries that the build uses)")

    /**
     * Defines the path to the package.json file generated by the [[npmWritePackageJson]] task.
     *
     * Default: `npmTargetDirectory.value / "package.json"`
     *
     * @group settings
     */
    val npmPackageJsonFile: SettingKey[File] =
      settingKey[File]("Full path to the npm package.json file")

    val npmPackageJson: TaskKey[PackageJson] =
      taskKey[PackageJson]("Defines the contents of the npm package.json file")

    val npmWritePackageJson: TaskKey[FileWithLastrun] =
      taskKey[FileWithLastrun]("Create the npm package.json file.")

    /**
     *
     * @group tasks
     */
    val npmInstall: TaskKey[Long] =
      taskKey[Long]("Install npm dependencies")

    val npmRunScript: InputKey[Unit] =
      inputKey[Unit]("Run the specified npm script")

    val npmMain: SettingKey[Option[String]] =
      settingKey[Option[String]]("package.json 'main' property")

    val npmScripts: SettingKey[Seq[(String,String)]] =
      settingKey[Seq[(String,String)]]("npm scripts")

    val npmCmd: SettingKey[ExternalCommand] =
      settingKey[ExternalCommand]("npm command")


    val npmLibraryDependencies: TaskKey[Seq[NpmDependency]] =
      taskKey[Seq[NpmDependency]]("NPM dependencies defined by libraries")

    val npmLibraryDevDependencies: TaskKey[Seq[NpmDependency]] =
      taskKey[Seq[NpmDependency]]("NPM dev dependencies defined by libraries")

  }


  import ConfigPlugin.autoImport._
  import autoImport._

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(


    npmCmd := ExternalCommand("npm"),

    npmTargetDir := baseDirectory.value,

    npmNodeModulesDir := npmTargetDir.value / "node_modules",

    npmPackageJsonFile := npmTargetDir.value / "package.json",

    npmDependencies := Nil,

    npmDevDependencies := Nil,

    npmMain := None,

    npmScripts := Nil,

    npmPackageJson := PackageJson(
      path = npmPackageJsonFile.value,
      name = name.value,
      version = version.value,
      description = description.value,
      dependencies = npmLibraryDependencies.value ++ npmDependencies.value,
      devDependencies = npmLibraryDevDependencies.value ++ npmDevDependencies.value,
      main = npmMain.value,
      scripts = npmScripts.value
    ),

    npmWritePackageJson := Def.taskDyn {
      val file = npmPackageJsonFile.value
      val lastrun = npmWritePackageJson.previous
      val projectConfigFile = npmProjectConfigFile.value
      val projectConfigIsNewer = projectConfigFile.canRead && projectConfigFile.lastModified() > lastrun.get.lastrun

      if(lastrun.isEmpty || lastrun.get.needsUpdateComparedToConfig(baseDirectory.value) || projectConfigIsNewer) {
        npmPackageJson.value.writeFile()(streams.value.log)
        Def.task { FileWithLastrun(file) }
      }
      else
        Def.task { lastrun.get }
    }.value,

    npmInstall := Def.taskDyn {
      val file = npmWritePackageJson.value
      val lastrun = npmInstall.previous
      val dir = npmNodeModulesDir.value
      if(lastrun.isEmpty || file.lastrun>lastrun.get || !dir.exists()) {
        ExternalCommand.npm.install(npmTargetDir.value.getCanonicalFile,npmNodeModulesDir.value.getCanonicalFile,streams.value.log)
        Def.task { new java.util.Date().getTime }
      }
      else
        Def.task { lastrun.get }
    }.value,

    npmRunScript := {
      import complete.DefaultParsers._

      npmInstall.value
      val script = spaceDelimited("<arg>").parsed.head
      ExternalCommand.npm.start("run-script",script)(streams.value.log,waitAndKillOnInput = true)
    },


    npmLibraryDependencies := loadNpmDependencies(npmProjectConfig.value,"npm.dependencies"), //npmProjectConfig.value.getStringMap("npm.dependencies").toSeq,
    npmLibraryDevDependencies := loadNpmDependencies(npmProjectConfig.value,"npm.devDependencies")//npmProjectConfig.value.getStringMap("npm.devDependencies").toSeq
  )

  private def loadNpmDependencies(config: Config, path: String) =
    config.getConfigList(path).asScala
      .flatMap(_.entrySet().asScala)
      .map(p => (p.getKey.replaceAll("\"",""),p.getValue.unwrapped().toString))
      .toSeq

}
