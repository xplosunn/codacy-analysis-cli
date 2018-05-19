package com.codacy.analysis.cli.command.analyse

import java.util.concurrent.ForkJoinPool

import better.files.File
import cats.MonadError
import cats.implicits._
import com.codacy.analysis.cli.analysis.Analyser
import com.codacy.analysis.cli.clients.api.ProjectConfiguration
import com.codacy.analysis.cli.command.Properties
import com.codacy.analysis.cli.command.analyse.AnalyseExecutor._
import com.codacy.analysis.cli.configuration.CodacyConfigurationFile
import com.codacy.analysis.cli.converters.ConfigurationHelper
import com.codacy.analysis.cli.files.{FileCollector, FilesTarget}
import com.codacy.analysis.cli.formatter.Formatter
import com.codacy.analysis.cli.model.{CodacyCfg, Configuration, FileCfg, Result}
import com.codacy.analysis.cli.tools.Tool
import com.codacy.analysis.cli.upload.ResultsUploader
import org.log4s.{Logger, getLogger}
import play.api.libs.json.JsValue

import scala.collection.parallel.ForkJoinTaskSupport
import scala.collection.parallel.immutable.ParSet
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class AnalyseExecutor[M[_], E](toolInput: Option[String],
                      directory: Option[File],
                      formatter: Formatter,
                      analyser: Analyser[Try],
                      uploader: Either[String, ResultsUploader],
                      fileCollector: FileCollector[Try],
                      remoteProjectConfiguration: Either[String, ProjectConfiguration],
                      nrParallelTools: Option[Int])(errorFn: String => E)(implicit context: ExecutionContext, monadError: MonadError[M, E]) {

  private val logger: Logger = getLogger

  def run(): Future[M[Unit]] = {
    formatter.begin()

    val baseDirectory =
      directory.fold(Properties.codacyCode.getOrElse(File.currentWorkingDirectory))(dir =>
        if (dir.isDirectory) dir else dir.parent)

    val localConfigurationFile = CodacyConfigurationFile.search(baseDirectory).flatMap(CodacyConfigurationFile.load)

    val filesTargetAndTool: Either[String, (FilesTarget, Set[Tool])] = for {
      filesTarget <- fileCollector
        .list(baseDirectory, localConfigurationFile, remoteProjectConfiguration)
        .toRight("Could not access project files")
      tools <- tools(toolInput, localConfigurationFile, remoteProjectConfiguration, filesTarget)
    } yield (filesTarget, tools)

    val analysisResult: Future[M[Unit]] = filesTargetAndTool.fold(str => Future.successful(monadError.raiseError(errorFn(str))), {
      case (filesTarget, tools) =>
        analyseAndUpload(tools, filesTarget, localConfigurationFile, nrParallelTools)
    })

    formatter.end()

    analysisResult
  }

  private def analyseAndUpload(tools: Set[Tool],
                               filesTarget: FilesTarget,
                               localConfigurationFile: Either[String, CodacyConfigurationFile],
                               nrParallelTools: Option[Int]): Future[M[Unit]] = {

    val toolsPar: ParSet[Tool] = tools.par

    toolsPar.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(nrParallelTools.getOrElse(2)))

    val uploads: Seq[Future[M[Unit]]] = toolsPar.map { tool =>
      val hasConfigFiles = fileCollector.hasConfigurationFiles(tool, filesTarget)
      analyseAndUpload(tool, hasConfigFiles, filesTarget, localConfigurationFile)
    }(collection.breakOut)

    val joinedUploads: Future[Seq[M[Unit]]] = Future.sequence(uploads)

    joinedUploads.map(foldWithError("")(_))
  }

  private def analyseAndUpload(
    tool: Tool,
    toolHasConfigFiles: Boolean,
    filesTarget: FilesTarget,
    localConfigurationFile: Either[String, CodacyConfigurationFile]): Future[M[Unit]] = {
    val result: Try[Set[Result]] = for {
      fileTarget <- fileCollector.filter(tool, filesTarget, localConfigurationFile, remoteProjectConfiguration)
      toolConfiguration <- getToolConfiguration(
        tool,
        toolHasConfigFiles,
        localConfigurationFile,
        remoteProjectConfiguration)
      results <- analyser.analyse(tool, fileTarget.directory, fileTarget.files, toolConfiguration)
    } yield results

    result match {
      case Success(res) =>
        logger.info(s"Completed analysis for ${tool.name} with ${res.size} results")
        res.foreach(formatter.add)
      case Failure(e) =>
        logger.error(e)(s"Failed analysis for ${tool.name}")
    }

    uploader.fold[Future[M[Unit]]]({ message =>
      logger.warn(message)
      Future.successful(monadError.pure(()))
    }, { upload =>
      for {
        results <- Future.fromTry(result)
        upl <- upload.sendResults(tool.name, results)
      } yield eitherToM(upl)
    })
  }

  private def eitherToM[A](either: Either[String, A]): M[A] = {
    either match {
      case Left(error) => monadError.raiseError(errorFn(error))
      case Right(value) => monadError.pure(value)
    }
  }

  private def foldWithError[A](error: String)(mSeq: Seq[M[Unit]]): M[Unit] = {
    mSeq.foldLeft[M[Unit]](monadError.pure(())) {
      (acc, m) =>
        acc.flatMap(_ => m)
    }.recoverWith {
      case _ => monadError.raiseError(errorFn(error))
    }
  }

  private def getToolConfiguration(tool: Tool,
                                   hasConfigFiles: Boolean,
                                   localConfiguration: Either[String, CodacyConfigurationFile],
                                   remoteConfiguration: Either[String, ProjectConfiguration]): Try[Configuration] = {
    val (baseSubDir, extraValues) = getExtraConfiguration(localConfiguration, tool)
    (for {
      projectConfig <- remoteConfiguration
      toolConfiguration <- projectConfig.toolConfiguration
        .find(_.uuid.equalsIgnoreCase(tool.uuid))
        .toRight[String]("Could not find tool")
    } yield {
      val shouldUseConfigFile = toolConfiguration.notEdited && hasConfigFiles
      val shouldUseRemoteConfiguredPatterns = !shouldUseConfigFile && tool.allowsUIConfiguration && toolConfiguration.patterns.nonEmpty
      // TODO: Review isEnabled condition when running multiple tools since we might want to force this for single tools
      // val shouldRun = toolConfiguration.isEnabled && (!tool.needsPatternsToRun || shouldUseConfigFile || shouldUseRemoteConfiguredPatterns)
      val shouldRun = !tool.needsPatternsToRun || shouldUseConfigFile || shouldUseRemoteConfiguredPatterns

      if (!shouldRun) {
        logger.error(s"""Could not find conditions to run tool ${tool.name} with:
             |shouldUseConfigFile:$shouldUseConfigFile = notEdited:${toolConfiguration.notEdited} && foundToolConfigFile:${hasConfigFiles}
             |shouldUseRemoteConfiguredPatterns:$shouldUseRemoteConfiguredPatterns = !shouldUseConfigFile:$shouldUseConfigFile && allowsUIConfiguration:${tool.allowsUIConfiguration} && hasPatterns:${toolConfiguration.patterns.nonEmpty}
             |shouldRun:$shouldRun = !needsPatternsToRun:${tool.needsPatternsToRun} || shouldUseConfigFile:$shouldUseConfigFile || shouldUseRemoteConfiguredPatterns:$shouldUseRemoteConfiguredPatterns
           """.stripMargin)
        Failure(new Exception(s"Could not find conditions to run tool ${tool.name}"))
      } else if (shouldUseConfigFile) {
        logger.info(s"Preparing to run ${tool.name} with configuration file")
        Success(FileCfg(baseSubDir, extraValues))
      } else {
        logger.info(s"Preparing to run ${tool.name} with remote configuration")
        Success(
          CodacyCfg(
            toolConfiguration.patterns.map(ConfigurationHelper.apiPatternToInternalPattern),
            baseSubDir,
            extraValues))
      }
    }).right.getOrElse[Try[Configuration]] {
      logger.info(s"Preparing to run ${tool.name} with defaults")
      Success(FileCfg(baseSubDir, extraValues))
    }
  }

  private def getExtraConfiguration(localConfiguration: Either[String, CodacyConfigurationFile],
                                    tool: Tool): (Option[String], Option[Map[String, JsValue]]) = {
    (for {
      config <- localConfiguration.toOption
      engines <- config.engines
      engineConfig <- engines.get(tool.name)
    } yield engineConfig).fold {
      logger.info(s"Could not find local extra configuration for ${tool.name}")
      (Option.empty[String], Option.empty[Map[String, JsValue]])
    } { ec =>
      logger.info(s"Found local extra configuration for ${tool.name}")
      (ec.baseSubDir, ec.extraValues)
    }
  }

  implicit class tryOps[A](tryA: Try[A]) {

    def toRight[L](left: L): Either[L, A] = {
      tryA.map(Right(_)).getOrElse(Left(left))
    }
  }
}

object AnalyseExecutor {

  def tools(toolInput: Option[String],
            localConfiguration: Either[String, CodacyConfigurationFile],
            remoteProjectConfiguration: Either[String, ProjectConfiguration],
            filesTarget: FilesTarget): Either[String, Set[Tool]] = {

    def fromRemoteConfig =
      remoteProjectConfiguration.flatMap(projectConfiguration =>
        Tool.fromToolUUIDs(projectConfiguration.toolConfiguration.filter(_.isEnabled).map(_.uuid)))

    def fromLocalConfig =
      Tool.fromFileTarget(
        filesTarget,
        localConfiguration.map(_.languageCustomExtensions.mapValues(_.toList).toList).getOrElse(List.empty))

    toolInput.map { toolStr =>
      Tool.fromNameOrUUID(toolStr)
    }.getOrElse {
      for {
        e1 <- fromRemoteConfig.left
        e2 <- fromLocalConfig.left
      } yield s"$e1 and $e2"
    }

  }
}
