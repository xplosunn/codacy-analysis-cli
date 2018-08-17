package com.codacy.analysis.core.utils

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Path, Paths}

import better.files.File
import com.codacy.plugins.api.results
import io.circe.Decoder
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.MatchResult

import scala.sys.process.Process

object TestUtils {
  implicit val categoryDecoder: Decoder[results.Pattern.Category.Value] = Decoder.enumDecoder(results.Pattern.Category)
  implicit val levelDecoder: Decoder[results.Result.Level.Value] = Decoder.enumDecoder(results.Result.Level)
  implicit val fileDecoder: Decoder[Path] = Decoder[String].map(Paths.get(_))
  implicit val executionEnv: ExecutionEnv = ExecutionEnv.fromGlobalExecutionContext

  def withClonedRepo[T](gitUrl: String, commitUUid: String)(block: (File, File) => MatchResult[T]): MatchResult[T] =
    (for {
      directory <- File.temporaryDirectory()
      file <- File.temporaryFile()
    } yield {
      directory
        .addPermission(PosixFilePermission.OWNER_READ)
        .addPermission(PosixFilePermission.GROUP_READ)
        .addPermission(PosixFilePermission.OTHERS_READ)
        .addPermission(PosixFilePermission.OWNER_EXECUTE)
        .addPermission(PosixFilePermission.GROUP_EXECUTE)
        .addPermission(PosixFilePermission.OTHERS_EXECUTE)
      Process(Seq("git", "clone", gitUrl, directory.pathAsString)).!
      Process(Seq("git", "reset", "--hard", commitUUid), directory.toJava).!
      block(file, directory)
    }).get()

}
