/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package ast

import utils._
import scala.language.implicitConversions

case object DebugSectionTrees extends DebugSection("trees")

trait Trees
  extends Expressions
     with Extractors
     with Types
     with Definitions
     with Printers
     with TreeOps {

  type Identifier = ast.Identifier
  val FreshIdentifier = ast.FreshIdentifier

  class Unsupported(t: Tree, msg: String)(implicit ctx: InoxContext)
    extends Exception(s"${t.asString(PrinterOptions.fromContext(ctx))}@${t.getPos} $msg")

  abstract class Tree extends utils.Positioned with Serializable {
    def copiedFrom(o: Trees#Tree): this.type = {
      setPos(o)
      this
    }

    // @EK: toString is considered harmful for non-internal things. Use asString(ctx) instead.

    def asString(implicit opts: PrinterOptions): String = PrettyPrinter(this, opts)

    override def toString = asString(PrinterOptions.fromContext(InoxContext.printNames))
  }

  object exprOps extends {
    protected val trees: Trees.this.type = Trees.this
  } with ExprOps

  object dsl extends {
    protected val trees: Trees.this.type = Trees.this
  } with DSL

  def aliased(id1: Identifier, id2: Identifier) = {
    id1.toString == id2.toString
  }

  /** Returns true if the two group of identifiers ovelap. */
  def aliased(ids1: Set[Identifier], ids2: Set[Identifier]) = {
    val s1 = ids1.groupBy{ _.toString }.keySet
    val s2 = ids2.groupBy{ _.toString }.keySet
    (s1 & s2).nonEmpty
  }

  def aliasedSymbols[T1 <: VariableSymbol,T2 <: VariableSymbol](vs1: Set[T1], vs2: Set[T2]): Boolean = {
    aliased(vs1.map(_.id), vs2.map(_.id))
  }
}