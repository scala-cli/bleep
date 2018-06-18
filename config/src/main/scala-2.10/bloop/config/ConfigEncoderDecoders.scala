package bloop.config

import io.circe._
import bloop.config.Config._
import io.circe.Decoder.Result
import io.circe.generic.semiauto._

import java.nio.file.{Path, Paths}

import bloop.config.Config._
import scala.util.Try

object ConfigEncoderDecoders {
  implicit class RightEither[A, B](e: Either[A, B]) {
    def flatMap[A1 >: A, B1](f: B => Either[A1, B1]): Either[A1, B1] = e.right.flatMap(f)
    def map[B1](f: B => B1): Either[A, B1] = e.right.map(f)
    // This one tries to workaround a change in the public binary API of circe in 2.10
    def getOrElse[B1 >: B](or: => B1): B1 = {
      e match {
        case Left(a) => or
        case Right(b) => b
      }
    }
  }

  implicit val pathDecoder: Decoder[Path] = Decoder.decodeString.emapTry(s => Try(Paths.get(s)))
  implicit val pathEncoder: RootEncoder[Path] = new RootEncoder[Path] {
    override final def apply(a: Path): Json = Json.fromString(a.toString)
  }

  implicit val compileOrderEncoder: RootEncoder[CompileOrder] = new RootEncoder[CompileOrder] {
    override final def apply(o: CompileOrder): Json = o match {
      case Mixed => Json.fromString(Mixed.id)
      case JavaThenScala => Json.fromString(JavaThenScala.id)
      case ScalaThenJava => Json.fromString(ScalaThenJava.id)
    }
  }

  implicit val compileOrderDecoder: Decoder[CompileOrder] = new Decoder[CompileOrder] {
    override def apply(c: HCursor): Result[CompileOrder] = {
      c.as[String].flatMap {
        case Mixed.id => Right(Mixed)
        case JavaThenScala.id => Right(JavaThenScala)
        case ScalaThenJava.id => Right(ScalaThenJava)
        case _ =>
          val msg = s"Expected compile order ${CompileOrder.All.map(s => s"'$s'").mkString(", ")})"
          Left(DecodingFailure(msg, c.history))
      }
    }
  }

  import LinkerMode.{Debug, Release}
  implicit val linkerModeEncoder: RootEncoder[LinkerMode] = new RootEncoder[LinkerMode] {
    override final def apply(o: LinkerMode): Json = o match {
      case Debug => Json.fromString(Debug.id)
      case Release => Json.fromString(Release.id)
    }
  }

  implicit val linkerModeDecoder: Decoder[LinkerMode] = new Decoder[LinkerMode] {
    override def apply(c: HCursor): Result[LinkerMode] = {
      c.as[String].flatMap {
        case Debug.id => Right(Debug)
        case Release.id => Right(Release)
        case _ =>
          val msg = s"Expected linker mode ${LinkerMode.All.map(s => s"'$s'").mkString(", ")})"
          Left(DecodingFailure(msg, c.history))
      }
    }
  }

  implicit val moduleKindJsEncoder: RootEncoder[ModuleKindJS] = new RootEncoder[ModuleKindJS] {
    override final def apply(m: ModuleKindJS): Json = m match {
      case m @ ModuleKindJS.NoModule => Json.fromString(m.id)
      case m @ ModuleKindJS.CommonJSModule => Json.fromString(m.id)
    }
  }

  implicit val moduleKindJsDecoder: Decoder[ModuleKindJS] = new Decoder[ModuleKindJS] {
    override def apply(c: HCursor): Result[ModuleKindJS] = {
      c.as[String].flatMap {
        case ModuleKindJS.NoModule.id => Right(ModuleKindJS.NoModule)
        case ModuleKindJS.CommonJSModule.id => Right(ModuleKindJS.CommonJSModule)
        case _ =>
          val msg = s"Expected module kind ${ModuleKindJS.All.map(s => s"'$s'").mkString(", ")})"
          Left(DecodingFailure(msg, c.history))
      }
    }
  }

  implicit val jvmEncoder: ObjectEncoder[JvmConfig] = deriveEncoder
  implicit val jvmDecoder: Decoder[JvmConfig] = deriveDecoder

  implicit val nativeOptionsEncoder: ObjectEncoder[NativeOptions] = deriveEncoder
  implicit val nativeOptionsDecoder: Decoder[NativeOptions] = deriveDecoder

  implicit val nativeEncoder: ObjectEncoder[NativeConfig] = deriveEncoder
  implicit val nativeDecoder: Decoder[NativeConfig] = deriveDecoder

  implicit val jsEncoder: ObjectEncoder[JsConfig] = deriveEncoder
  implicit val jsDecoder: Decoder[JsConfig] = deriveDecoder

  private final val N = "name"
  private final val C = "config"
  private final val M = "mainClass"

  implicit val platformEncoder: RootEncoder[Platform] = new RootEncoder[Platform] {
    override final def apply(platform: Platform): Json = platform match {
      case Platform.Jvm(config, mainClass) =>
        val configJson = jvmEncoder(config)
        val mainClassJson = implicitly[RootEncoder[Option[String]]].apply(mainClass)
        Json.fromFields(List(
          (N, Json.fromString(Platform.Jvm.name)),
          (C, configJson),
          (M, mainClassJson)))
      case Platform.Js(config, mainClass) =>
        val configJson = jsEncoder(config)
        val mainClassJson = implicitly[RootEncoder[Option[String]]].apply(mainClass)
        Json.fromFields(List(
          (N, Json.fromString(Platform.Js.name)),
          (C, configJson),
          (M, mainClassJson)))
      case Platform.Native(config, mainClass) =>
        val configJson = nativeEncoder(config)
        val mainClassJson = implicitly[RootEncoder[Option[String]]].apply(mainClass)
        Json.fromFields(List(
          (N, Json.fromString(Platform.Native.name)),
          (C, configJson),
          (M, mainClassJson)))
    }
  }

  implicit val platformDecoder: Decoder[Platform] = new Decoder[Platform] {
    private final val C = "config"
    override def apply(c: HCursor): Result[Platform] = {
      c.downField(N).as[String].flatMap {
        case Platform.Jvm.name => c.get[JvmConfig](C).flatMap(config =>
          c.get[List[String]](M).map(mainClass =>
            Platform.Jvm(config, mainClass.headOption)))
        case Platform.Js.name => c.get[JsConfig](C).flatMap(config =>
          c.get[List[String]](M).map(mainClass =>
            Platform.Js(config, mainClass.headOption)))
        case Platform.Native.name => c.get[NativeConfig](C).flatMap(config =>
          c.get[List[String]](M).map(mainClass =>
            Platform.Native(config, mainClass.headOption)))
        case _ =>
          val msg = s"Expected platform ${Platform.All.map(s => s"'$s'").mkString(", ")})"
          Left(DecodingFailure(msg, c.history))
      }
    }
  }

  implicit val checksumEncoder: ObjectEncoder[Checksum] = deriveEncoder
  implicit val checksumDecoder: Decoder[Checksum] = deriveDecoder

  implicit val moduleEncoder: ObjectEncoder[Module] = deriveEncoder
  implicit val moduleDecoder: Decoder[Module] = deriveDecoder

  implicit val artifactEncoder: ObjectEncoder[Artifact] = deriveEncoder
  implicit val artifactDecoder: Decoder[Artifact] = deriveDecoder

  implicit val resolutionEncoder: ObjectEncoder[Resolution] = deriveEncoder
  implicit val resolutionDecoder: Decoder[Resolution] = deriveDecoder

  implicit val javaEncoder: ObjectEncoder[Java] = deriveEncoder
  implicit val javaDecoder: Decoder[Java] = deriveDecoder

  implicit val testFrameworkEncoder: ObjectEncoder[TestFramework] = deriveEncoder
  implicit val testFrameworkDecoder: Decoder[TestFramework] = deriveDecoder

  implicit val testArgumentEncoder: ObjectEncoder[TestArgument] = deriveEncoder
  implicit val testArgumentDecoder: Decoder[TestArgument] = deriveDecoder

  implicit val testOptionsEncoder: ObjectEncoder[TestOptions] = deriveEncoder
  implicit val testOptionsDecoder: Decoder[TestOptions] = deriveDecoder

  implicit val testEncoder: ObjectEncoder[Test] = deriveEncoder
  implicit val testDecoder: Decoder[Test] = deriveDecoder

  implicit val compileOptionsEncoder: ObjectEncoder[CompileSetup] = deriveEncoder
  implicit val compileOptionsDecoder: Decoder[CompileSetup] = deriveDecoder

  implicit val scalaEncoder: ObjectEncoder[Scala] = deriveEncoder
  implicit val scalaDecoder: Decoder[Scala] = deriveDecoder

  implicit val sbtEncoder: ObjectEncoder[Sbt] = deriveEncoder
  implicit val sbtDecoder: Decoder[Sbt] = deriveDecoder

  implicit val projectEncoder: ObjectEncoder[Project] = deriveEncoder
  implicit val projectDecoder: Decoder[Project] = deriveDecoder

  implicit val allEncoder: ObjectEncoder[File] = deriveEncoder
  implicit val allDecoder: Decoder[File] = deriveDecoder
}
