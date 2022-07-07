package org.virtuslab.ideprobe.pants

import java.nio.file.Path
import java.nio.file.Paths

import org.virtuslab.ideprobe.CommandResult
import org.virtuslab.ideprobe.Shell

trait PantsFixture {
  def runPants(workspace: Path, command: Seq[String]): CommandResult = {
    val pantsCommand = "./pants" +: command
    Shell.run(workspace, pantsCommand: _*)
  }

  def runPantsIdeaPlugin(workspace: Path, targets: Seq[String]): Path = {
    val args = Seq("idea-plugin", "--open-with=echo") ++ targets
    Paths.get(runPants(workspace, args).out)
  }
}
