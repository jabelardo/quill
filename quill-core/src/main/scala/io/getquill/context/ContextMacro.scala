package io.getquill.context

import scala.reflect.macros.whitebox.{ Context => MacroContext }

import io.getquill.ast.Ast
import io.getquill.ast.Dynamic
import io.getquill.quotation.Quotation
import io.getquill.util.LoadObject
import io.getquill.util.Messages._
import io.getquill.quotation.IsDynamic
import io.getquill.ast.Lift
import io.getquill.NamingStrategy
import io.getquill.idiom.Idiom
import io.getquill.idiom.Statement
import io.getquill.idiom.Token
import io.getquill.idiom.StringToken
import io.getquill.idiom.LiftingToken
import io.getquill.idiom.ReifyStatement
import io.getquill.idiom.LoadNaming
import scala.util.Success
import scala.util.Failure

trait ContextMacro extends Quotation {
  val c: MacroContext
  import c.universe.{ Function => _, Ident => _, _ }

  protected def expand(ast: Ast): Tree =
    q"""
      val (idiom, naming) = ${idiomAndNamingDynamic}
      io.getquill.context.Expand(${c.prefix}, $ast, ${translate(ast)}, idiom, naming)
    """

  protected def extractAst[T](quoted: Tree): Ast =
    unquote[Ast](quoted)
      .map(VerifyFreeVariables(c))
      .getOrElse {
        Dynamic(quoted)
      }

  private def translate(ast: Ast): Tree =
    IsDynamic(ast) match {
      case false => translateStatic(ast)
      case true  => translateDynamic(ast)
    }

  private implicit val tokenLiftable: Liftable[Token] = Liftable[Token] {
    case StringToken(string) => q"io.getquill.idiom.StringToken($string)"
    case LiftingToken(lift)  => q"io.getquill.idiom.LiftingToken(${lift: Lift})"
    case Statement(tokens)   => q"io.getquill.idiom.Statement(List(..$tokens))"
  }

  private def translateStatic(ast: Ast): Tree = {
    idiomAndNamingStatic match {
      case Success((idiom, naming)) =>
        val statement = idiom.translate(ast)(naming)

        val (string, _) =
          ReifyStatement(
            idiom.liftingPlaceholder,
            idiom.prepareForProbing(statement),
            forProbing = true
          )

        ProbeStatement(string, c)
        c.info(string)

        q"${statement: Token}"
      case Failure(ex) =>
        c.warn(s"Can't translate query at compile time. Reason: $ex")
        translateDynamic(ast)
    }
  }

  private def translateDynamic(ast: Ast): Tree = {
    c.info("Dynamic query")
    q"""
      val (idiom, naming) = ${idiomAndNamingDynamic}
      idiom.translate($ast)(naming)
    """
  }

  private def idiomAndNaming = {
    val (idiom :: n :: _) =
      c.prefix.actualType
        .baseType(c.weakTypeOf[Context[Idiom, NamingStrategy]].typeSymbol)
        .typeArgs
    (idiom, n)
  }

  private def idiomAndNamingDynamic = {
    val (idiom, naming) = idiomAndNaming
    q"(${idiom.typeSymbol.companion}, ${LoadNaming.dynamic(c)(naming)})"
  }

  private def idiomAndNamingStatic = {
    val (idiom, naming) = idiomAndNaming
    for {
      idiom <- LoadObject[Idiom](c)(idiom)
      naming <- LoadNaming.static(c)(naming)
    } yield {
      (idiom, naming)
    }
  }
}
