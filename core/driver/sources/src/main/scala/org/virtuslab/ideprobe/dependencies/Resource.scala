package org.virtuslab.ideprobe.dependencies

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.ProviderNotFoundException
import java.util.zip.ZipInputStream

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import pureconfig.ConfigReader

import org.virtuslab.ideprobe.ConfigFormat
import org.virtuslab.ideprobe.Extensions._

sealed trait Resource

object Resource extends ConfigFormat {
  implicit val resourceConfigReader: ConfigReader[Resource] = ConfigReader[String].map(from)

  def exists(uri: URI): Boolean = from(uri) match {
    case File(path) => Files.exists(path)
    case Http(uri) =>
      val connection = uri.toURL.openConnection()
      connection.connect()
      val responseCode = connection.asInstanceOf[HttpURLConnection].getResponseCode
      responseCode == 200
    case Unresolved(_, _) => true // can't verify, assume it exists
    case Jar(uri) =>
      throw new UnsupportedOperationException(
        s"Resolved URI: $uri points to a JAR file, which should not happen for a release of IntelliJ"
      )
  }

  def from(value: String): Resource = {
    try {
      from(URI.create(value))
    } catch {
      case NonFatal(e) =>
        Unresolved(value, e)
    }
  }

  @scala.annotation.tailrec
  def from(uri: URI): Resource = uri.getScheme match {
    case null             => File(Paths.get(uri.getPath))
    case "file"           => File(Paths.get(uri))
    case "jar"            => Jar(uri)
    case "http" | "https" => Http(uri)
    case "classpath"      => from(getClass.getResource(uri.getPath).toURI)
    case scheme =>
      Unresolved(uri.toString, new IllegalArgumentException(s"Unsupported scheme: $scheme"))
  }

  case class Jar(uri: URI) extends Resource
  case class File(path: Path) extends Resource
  case class Http(uri: URI) extends Resource
  case class Unresolved(path: String, cause: Throwable) extends Resource

  sealed trait IntellijResource {
    def path: Path
    def installTo(target: Path): Unit

    def rootEntries: List[String] = {
      Try(FileSystems.newFileSystem(path, this.getClass.getClassLoader)) match {
        case Success(fs) =>
          Files.list(fs.getPath("/")).iterator.asScala.map(_.toString).toList
        case Failure(_: ProviderNotFoundException) =>
          throw new IllegalArgumentException(
            s"""
               |The path provided as the plugin path: $path is not valid.
               |Make sure it points to the plugin file (or .zip archive) and NOT to a directory.""".stripMargin
          )
        case Failure(exception: Throwable) =>
          throw new Exception(exception)
      }
    }
  }

  final class Archive(val path: Path) extends IntellijResource {
    def installTo(target: Path): Unit = {
      val zip = new ZipInputStream(path.inputStream)
      zip.unpackTo(target)
    }
  }

  final class Plain(val path: Path) extends IntellijResource {
    def installTo(target: Path): Unit = {
      path.copyDir(target)
    }
  }

  object Archive {
    private val ZipMagicNumber = 0x504b0304

    def unapply(path: Path): Option[Archive] = {
      import java.io.DataInputStream
      val stream = new DataInputStream(path.inputStream)
      try {
        val magicNumber = stream.readInt()
        if (magicNumber == ZipMagicNumber) Some(new Archive(path))
        else None
      } catch {
        case NonFatal(_) =>
          None
      } finally {
        stream.close()
      }
    }
  }

  object Plain {
    def unapply(path: Path): Option[Plain] = {
      if (path.isDirectory) Some(new Plain(path)) else None
    }
  }

  implicit class ExtractorExtension(path: Path) {
    def toExtracted: IntellijResource = path match {
      case Archive(archive) => archive
      case Plain(archive)   => archive
      case _                => throw new IllegalStateException(s"Not an archive: $path")
    }
  }
}
