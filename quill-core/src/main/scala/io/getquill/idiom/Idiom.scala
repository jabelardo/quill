package io.getquill.idiom

import io.getquill.ast._
import io.getquill.NamingStrategy

trait Idiom {

  def liftingPlaceholder(index: Int): String

  def translate(ast: Ast)(implicit naming: NamingStrategy): Statement

  def prepareForProbing(statement: Statement): Statement
}
