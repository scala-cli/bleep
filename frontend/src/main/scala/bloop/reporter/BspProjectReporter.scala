package bloop.reporter

import java.io.File
import java.nio.file.{Path, Paths}

import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.logging.BspServerLogger
import xsbti.Position
import ch.epfl.scala.bsp
import sbt.util.InterfaceUtil
import xsbti.compile.CompileAnalysis

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try

final class BspProjectReporter(
    val project: Project,
    override val logger: BspServerLogger,
    override val cwd: AbsolutePath,
    sourcePositionMapper: Position => Position,
    override val config: ReporterConfig,
    reportAllPreviousProblems: Boolean,
    override val _problems: mutable.Buffer[ProblemPerPhase] = mutable.ArrayBuffer.empty
) extends Reporter(logger, cwd, sourcePositionMapper, config, _problems) {
  private val taskId = logger.nextTaskId

  /** A cycle count, initialized to 0 when it's a no-op. */
  private var cycleCount: Int = 0

  /** A thread-safe map with all the files under compilation. */
  private val compilingFiles = mutable.HashMap.empty[File, Boolean]

  /** A thread-safe map with all the files that have been cleared. */
  private val clearedFilesForClient = TrieMap.empty[File, Boolean]

  /** A thread-safe map with all the files that have been cleared. */
  private val startedPhaseInFile = TrieMap.empty[String, Boolean]

  /** Log a problem in a thread-safe manner. */
  override protected def logFull(problem: Problem): Unit = {
    sbt.util.InterfaceUtil.toOption(problem.position.sourceFile()) match {
      case Some(file) =>
        // If it's the first diagnostic for this file, set clear to true
        val clear = clearedFilesForClient.putIfAbsent(file, true).isEmpty
        logger.diagnostic(project, problem, clear)
      case None => logger.diagnostic(project, problem, false)
    }
  }

  // Report summary manually via `reportEndCompilation` for BSP clients
  override def printSummary(): Unit = ()

  override def reportCompilationProgress(
      progress: Long,
      total: Long
  ): Unit = {
    val percentage = progress * 100 / total
    // We only report percentages every 5% increments
    val shouldReportPercentage = percentage % 5 == 0
    if (shouldReportPercentage) {
      logger.publishCompileProgress(taskId, project, progress, total, percentage)
    }
  }

  override def reportCancelledCompilation(): Unit = {
    ()
  }

  private var allPreviouslyReportedProblems: List[ProblemPerPhase] = Nil
  private var previouslyReportedProblemsWithFile: Map[File, List[ProblemPerPhase]] = Map.empty

  override def reportStartCompilation(previousProblems: List[ProblemPerPhase]): Unit = {
    allPreviouslyReportedProblems = previousProblems

    val previousProblemsWithFiles = mutable.HashMap[File, List[ProblemPerPhase]]()
    previousProblems.foreach {
      case pp @ ProblemPerPhase(p, phase) =>
        InterfaceUtil.toOption(p.position().sourceFile).foreach { file =>
          val problemsPerFile = pp :: previousProblemsWithFiles.getOrElse(file, Nil)
          previousProblemsWithFiles.+=(file -> problemsPerFile)
        }
    }

    previouslyReportedProblemsWithFile = previousProblemsWithFiles.toMap
  }

  override def reportNextPhase(phase: String, sourceFile: File): Unit = {
    super.reportNextPhase(phase, sourceFile)

    filesToPhaseStack.getOrElse(sourceFile, Nil) match {
      case Nil => ()
      case x :: Nil => ()
      case x :: finishedPhase :: xs =>
        // Report previous problems for this source file once a phase has finished
        previouslyReportedProblemsWithFile.get(sourceFile).foreach { problems =>
          val unreported = reportPreviousProblemAfterPhase(sourceFile, finishedPhase, problems)
          previouslyReportedProblemsWithFile =
            previouslyReportedProblemsWithFile + (sourceFile -> unreported)
        }
    }
  }

  /** Holds a thunk that reports the end of the previous incremental cycle. It's added by
   * `reportEndIncrementalCycle` and we don't run it eagerly because we need to ensure that
   * all diagnostics (those, for example, coming from previous problemsii and only reported in
   * concrete scenarios) are sent in between task start and task end notifications. This guarantee
   * is violated when we report the end eagerly because:
   *
   *   1. We need to run `reportPreviousProblems` with a value of `reportAllPreviousProblems` at
   *      the very end of compilation (when the last incremental cycle has finished); and
   *   2. There is no way to know if an incremental cycle will be the last one in
   *      `reportEndIncrementalCycle`. We work around this limitation with this approach, so that
   *      when the thunk is run from `reportStartIncrementalCycle` we know a new cycle is coming
   *      and when it's run from `reportEndIncrementalCompilation` we know it's the last cycle.
   */
  private var reportEndPreviousCycleThunk: Boolean => Option[bsp.StatusCode] => Unit =
    (_: Boolean) => (_: Option[bsp.StatusCode]) => ()

  override def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    cycleCount += 1
    reportEndPreviousCycleThunk( /* is the last incremental cycle? */ false)(None)
    val msg = compilationMsgFor(project.name, sources)
    logger.publishCompileStart(project, msg, taskId)
    sources.foreach(sourceFile => compilingFiles.+=(sourceFile -> true))
  }

  private def reportPreviousProblemAfterPhase(
      source: File,
      finishedPhase: String,
      problems: List[ProblemPerPhase]
  ): List[ProblemPerPhase] = {
    problems.filterNot {
      case pp @ ProblemPerPhase(problem, phaseOfProblem) =>
        phaseOfProblem match {
          case Some(phase) =>
            if (finishedPhase != phase) false
            else {
              val clear = clearedFilesForClient.putIfAbsent(source, true).isEmpty
              if (clear) logger.noDiagnostic(project, source)
              true // Always mark as processed if the phases coincide
            }
          case None => false
        }
    }
  }

  private def reportRemainingProblems(reportAllPreviousProblemsIfNoOp: Boolean): Unit = {
    previouslyReportedProblemsWithFile.foreach {
      case (sourceFile, remaining) =>
        remaining.foreach {
          case ProblemPerPhase(problem, _) =>
            if (!sourceFile.exists()) {
              // Clear diagnostics if file doesn't exist anymore
              logger.noDiagnostic(project, sourceFile)
            } else if (clearedFilesForClient.contains(sourceFile)) {
              // Ignore, if file has been cleared then > 0 diagnostics have been reported
              ()
            } else if (compilingFiles.contains(sourceFile)) {
              // Log no diagnostic if there was a problem in a file that now compiled without problems
              logger.noDiagnostic(project, sourceFile)
            } else if (reportAllPreviousProblemsIfNoOp) {
              // Log all previous problems when target file has not been compiled and it exists
              val clear = clearedFilesForClient.putIfAbsent(sourceFile, true).isEmpty
              logger.diagnostic(project, problem, clear)
            }
        }
    }
  }

  override def reportEndIncrementalCycle(durationMs: Long, result: Try[Unit]): Unit = {
    val codeRightAfterCycle = result match {
      case scala.util.Success(()) => bsp.StatusCode.Ok
      case scala.util.Failure(_: xsbti.CompileCancelled) => bsp.StatusCode.Cancelled
      case scala.util.Failure(_) => bsp.StatusCode.Error
    }

    // Add a thunk that we will run whenever we know if this is the last cycle or not
    reportEndPreviousCycleThunk = (isLastCycle: Boolean) => {
      (finalCompilationStatusCode: Option[bsp.StatusCode]) => {
        val statusCode = finalCompilationStatusCode.getOrElse(codeRightAfterCycle)
        if (!isLastCycle) reportRemainingProblems(false)
        else reportRemainingProblems(reportAllPreviousProblems)
        logger.publishCompileEnd(project, taskId, allProblems, statusCode)
      }
    }
  }

  override def reportEndCompilation(
      previousAnalysis: Option[CompileAnalysis],
      currentAnalysis: Option[CompileAnalysis],
      code: bsp.StatusCode
  ): Unit = {
    if (cycleCount == 0) {
      // When no-op, we keep reporting the start and the end of compilation for consistency
      logger.publishCompileStart(project, s"Start no-op compilation for ${project.name}", taskId)
      reportRemainingProblems(reportAllPreviousProblems)
      logger.publishCompileEnd(project, taskId, allProblems, code)
    } else {
      // Great, let's report the pending end incremental cycle as the last one
      reportEndPreviousCycleThunk( /* is the last incremental cycle? */ true)(Some(code))
    }

    // Clear the state of files with problems at the end of compilation
    clearedFilesForClient.clear()
    compilingFiles.clear()
    super.reportEndCompilation(previousAnalysis, currentAnalysis, code)
  }
}
