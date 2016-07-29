package io.getquill

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import java.io.Closeable
import java.sql.{ Connection, PreparedStatement, ResultSet }

import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal
import io.getquill.context.sql.SqlContext
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.util.LoadConfig
import javax.sql.DataSource

import scala.util.DynamicVariable
import io.getquill.context.jdbc.JdbcDecoders
import io.getquill.context.jdbc.JdbcEncoders

import scala.reflect.runtime.universe._

class JdbcContext[Dialect <: SqlIdiom, Naming <: NamingStrategy](dataSource: DataSource with Closeable)
  extends SqlContext[Dialect, Naming]
  with JdbcEncoders
  with JdbcDecoders {

  def this(config: JdbcContextConfig) = this(config.dataSource)
  def this(config: Config) = this(JdbcContextConfig(config))
  def this(configPrefix: String) = this(LoadConfig(configPrefix))

  private val logger: Logger =
    Logger(LoggerFactory.getLogger(classOf[JdbcContext[_, _]]))

  type PrepareRow = PreparedStatement
  type ResultRow = ResultSet

  override type RunQueryResult[T] = List[T]
  override type RunQuerySingleResult[T] = T
  override type RunActionResult = Long
  override type RunActionReturningResult[T] = T
  override type RunBatchActionResult = List[Long]
  override type RunBatchActionReturningResult[T] = List[T]

  private val currentConnection = new DynamicVariable[Option[Connection]](None)

  protected def withConnection[T](f: Connection => T) =
    currentConnection.value.map(f).getOrElse {
      val conn = dataSource.getConnection
      try f(conn)
      finally conn.close
    }

  def close = dataSource.close()

  def probe(sql: String) =
    Try {
      withConnection(_.createStatement.execute(sql))
    }

  def transaction[T](f: => T) =
    currentConnection.value match {
      case Some(_) => f // already in transaction
      case None =>
        withConnection { conn =>
          currentConnection.withValue(Some(conn)) {
            val wasAutoCommit = conn.getAutoCommit
            conn.setAutoCommit(false)
            try {
              val res = f
              conn.commit
              res
            } catch {
              case NonFatal(e) =>
                conn.rollback
                throw e
            } finally
              conn.setAutoCommit(wasAutoCommit)
          }
        }
    }

  def executeQuery[T](sql: String, prepare: PreparedStatement => PreparedStatement = identity, extractor: ResultSet => T = identity[ResultSet] _): List[T] =
    withConnection { conn =>
      logger.info(sql)
      val ps = prepare(conn.prepareStatement(sql))
      val rs = ps.executeQuery()
      extractResult(rs, extractor)
    }

  def executeQuerySingle[T](sql: String, prepare: PreparedStatement => PreparedStatement = identity, extractor: ResultSet => T = identity[ResultSet] _) =
    handleSingleResult(executeQuery(sql, prepare, extractor))

  def executeAction[T](sql: String, prepare: PreparedStatement => PreparedStatement = identity): Long =
    withConnection { conn =>
      logger.info(sql)
      prepare(conn.prepareStatement(sql)).executeUpdate().toLong
    }

  def executeActionReturning[O](sql: String, prepare: PreparedStatement => PreparedStatement = identity, extractor: ResultSet => O, returningColumn: String): O =
    withConnection { conn =>
      logger.info(sql)
      val ps = prepare(conn.prepareStatement(sql, Array(returningColumn)))
      ps.executeUpdate()
      handleSingleResult(extractResult(ps.getGeneratedKeys, extractor))
    }

  def executeBatchAction[B](batch: List[B], prepare: B => (String, PreparedStatement => PreparedStatement)): List[Long] =
    withConnection { conn =>
      batch.map(prepare)
        .groupBy {
          case (sql, prepare) =>
            sql
        }.map {
          case (sql, prepareRows) =>
            logger.info(sql)
            val ps = conn.prepareStatement(sql)
            prepareRows.foreach {
              case (_, prepare) =>
                prepare(ps)
                ps.addBatch()
            }
            ps.executeBatch().toList.map(_.toLong)
        }.flatten.toList
    }

  def executeBatchActionReturning[B, T](batch: List[B], prepare: B => (String, PreparedStatement => PreparedStatement, String), extractor: ResultSet => T) =
    withConnection { conn =>
      batch.map(prepare)
        .groupBy {
          case (sql, prepare, returning) =>
            (sql, returning)
        }.map {
          case ((sql, returning), prepareRows) =>
            logger.info(sql)
            val ps = conn.prepareStatement(sql, Array(returning))
            prepareRows.foreach {
              case (_, prepare, _) =>
                prepare(ps)
                ps.addBatch()
            }
            ps.executeBatch()
            extractResult(ps.getGeneratedKeys, extractor)
        }.toList.flatten
    }

  @tailrec
  private def extractResult[T](rs: ResultSet, extractor: ResultSet => T, acc: List[T] = List()): List[T] =
    if (rs.next)
      extractResult(rs, extractor, extractor(rs) :: acc)
    else
      acc.reverse
}
