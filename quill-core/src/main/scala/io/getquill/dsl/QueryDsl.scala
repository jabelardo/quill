package io.getquill.dsl

import scala.language.experimental.macros
import scala.reflect.ClassTag

import io.getquill.quotation.NonQuotedException
import scala.annotation.compileTimeOnly

private[dsl] trait QueryDsl {
  this: CoreDsl =>

  @compileTimeOnly(NonQuotedException.message)
  def query[T](implicit ct: ClassTag[T]): EntityQuery[T] = NonQuotedException()

  sealed trait Query[+T] {

    def map[R](f: T => R): Query[R]
    def flatMap[R](f: T => Query[R]): Query[R]
    def withFilter(f: T => Boolean): Query[T]
    def filter(f: T => Boolean): Query[T]
    def sortBy[R](f: T => R)(implicit ord: OrdDsl#Ord[R]): Query[T]

    def take(n: Int): Query[T]
    def drop(n: Int): Query[T]

    def ++[U >: T](q: Query[U]): Query[U]
    def unionAll[U >: T](q: Query[U]): Query[U]
    def union[U >: T](q: Query[U]): Query[U]

    def groupBy[R](f: T => R): Query[(R, Query[T])]

    def min[U >: T]: Option[T]
    def max[U >: T]: Option[T]
    def avg[U >: T](implicit n: Numeric[U]): Option[BigDecimal]
    def sum[U >: T](implicit n: Numeric[U]): Option[T]
    def size: Long

    def join[A >: T, B](q: Query[B]): JoinQuery[A, B, (A, B)]
    def leftJoin[A >: T, B](q: Query[B]): JoinQuery[A, B, (A, Option[B])]
    def rightJoin[A >: T, B](q: Query[B]): JoinQuery[A, B, (Option[A], B)]
    def fullJoin[A >: T, B](q: Query[B]): JoinQuery[A, B, (Option[A], Option[B])]

    def join[A >: T](on: A => Boolean): Query[A]
    def leftJoin[A >: T](on: A => Boolean): Query[Option[A]]
    def rightJoin[A >: T](on: A => Boolean): Query[Option[A]]

    def nonEmpty: Boolean
    def isEmpty: Boolean
    def contains[B >: T](value: B): Boolean

    def distinct: Query[T]

    def foreach[A <: Action](f: T => A): BatchAction[A]
  }

  sealed trait JoinQuery[A, B, R] extends Query[R] {
    def on(f: (A, B) => Boolean): Query[R]
  }

  sealed trait EntityQuery[T]
    extends Query[T] {

    def schema(f: Schema[T] => Schema[T]): EntityQuery[T]

    override def withFilter(f: T => Boolean): EntityQuery[T]
    override def filter(f: T => Boolean): EntityQuery[T]
    override def map[R](f: T => R): EntityQuery[R]

    def insert(value: T): Insert[T] = macro macroz.DslMacro.expandInsert[T]
    def insert(f: (T => (Any, Any)), f2: (T => (Any, Any))*): Insert[T]

    def update(value: T): Update = macro macroz.DslMacro.expandUpdate[T]
    def update(f: (T => (Any, Any)), f2: (T => (Any, Any))*): Update

    def delete: Delete
  }

  sealed trait Schema[T] {
    def entity(alias: String): Schema[T]
    def columns(propertyAlias: (T => (Any, String))*): Schema[T]
  }

  sealed trait Action

  sealed trait Insert[Entity] extends Action {
    @compileTimeOnly(NonQuotedException.message)
    def returning[R](f: Entity => R): ActionReturning[R] = NonQuotedException()
  }

  sealed trait ActionReturning[Output] extends Action
  sealed trait Update extends Action
  sealed trait Delete extends Action

  sealed trait BatchAction[+A <: Action]
}
