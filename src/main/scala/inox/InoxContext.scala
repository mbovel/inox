/* Copyright 2009-2016 EPFL, Lausanne */

package inox

import inox.utils._

/** Everything that is part of a compilation unit, except the actual program tree.
  * Contexts are immutable, and so should all their fields (with the possible
  * exception of the reporter).
  */
case class InoxContext(
  reporter: Reporter,
  interruptManager: InterruptManager,
  options: InoxOptions = InoxOptions(Seq()),
  timers: TimerStorage = new TimerStorage)

object InoxContext {
  def empty = {
    val reporter = new DefaultReporter(Set())
    InoxContext(reporter, new InterruptManager(reporter))
  }

  def printNames = {
    val reporter = new DefaultReporter(Set())
    InoxContext(
      reporter,
      new InterruptManager(reporter),
      options = InoxOptions.empty + InoxOption[Set[DebugSection]](InoxOptions.optDebug)(Set(ast.DebugSectionTrees))
    )
  }
}