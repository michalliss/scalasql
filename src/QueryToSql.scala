package usql

import usql.SqlStr.SqlStringSyntax

import scala.util.DynamicVariable

object QueryToSql {
  class Context(val fromNaming: Map[Query.From, String],
                val exprNaming: Map[Expr[_], SqlStr],
                val tableNameMapper: String => String,
                val columnNameMapper: String => String)

  def sqlExprsStr[Q, R](expr: Q, qr: Queryable[Q, R], context: Context) = {
    val flattenedExpr = qr.walk(expr)
    val flatQuery = FlatJson.flatten(flattenedExpr, context)

    val exprsStr = SqlStr.join(
      flatQuery.map {
        case (k, v) => v + usql" as " + SqlStr.raw(context.tableNameMapper(k))
      },
      usql", "
    )

    (flattenedExpr, exprsStr)
  }

  def toSqlQuery0[Q, R](query: Query[Q],
                        qr: Queryable[Q, R],
                        tableNameMapper: String => String,
                        columnNameMapper: String => String): (Map[Expr[_], SqlStr], SqlStr) = {
    val namedFroms = (query.from ++ query.joins.flatMap(_.from).map(_.from)).zipWithIndex.map {
      case (t: Query.TableRef, i) => (t, tableNameMapper(t.value.tableName) + i)
      case (s: Query.SubqueryRef[_], i) => (s, "subquery" + i)
    }
    val namedFromsMap = namedFroms.toMap


    def computeSelectable(t: Query.From) = t match {
      case t: Query.TableRef =>
        (Nil, SqlStr.raw(tableNameMapper(t.value.tableName)) + usql" " + SqlStr.raw(namedFromsMap(t)))

      case t: Query.SubqueryRef[_] =>
        val (subNameMapping, sqlStr) = toSqlQuery0(t.value, t.qr, tableNameMapper, columnNameMapper)
        (subNameMapping, usql"(" + sqlStr + usql") " + SqlStr.raw(namedFromsMap(t)))
    }

    val fromSelectables:  Map[Query.From, (Iterable[(Expr[_], SqlStr)], SqlStr)] = (query.from ++ query.joins.flatMap(_.from.map(_.from)))
      .map(f => (f, computeSelectable(f)))
      .toMap

    val exprNaming = fromSelectables.flatMap { case (k, vs) =>
      vs._1.map { case (e, s) => (e, SqlStr.raw(namedFromsMap(k)) + usql"." + s) }
    }

    val context = new Context(namedFromsMap, exprNaming, tableNameMapper, columnNameMapper)

    val (flattenedExpr, exprStr) = sqlExprsStr(query.expr, qr, context)

    def optional[T](t: Option[T])(f: T => SqlStr) = t.map(f).getOrElse(usql"")

    def optionalSeq[T](t: Seq[T])(f: Seq[T] => SqlStr) = if (t.nonEmpty) f(t) else usql""

    val tables = SqlStr.join(query.from.map(fromSelectables(_)._2), usql", ")

    val joins = SqlStr.join(
      query.joins.map { join =>
        optional(join.prefix)(s => usql" " + SqlStr.raw(s) + usql" ") +
          usql" JOIN " +
          SqlStr.join(
            join.from.map { jf =>
              fromSelectables(jf.from)._2 + optional(jf.on)(on => usql" ON " + on.toSqlExpr(context))
            }
          )
      }
    )

    val filtersOpt = optionalSeq(query.where) { where =>
      usql" WHERE " + SqlStr.join(where.map(_.toSqlExpr(context)), usql" AND ")
    }

    val sortOpt = optional(query.orderBy) { orderBy =>
      val ascDesc = orderBy.ascDesc match {
        case None => usql""
        case Some(Query.AscDesc.Asc) => usql" ASC"
        case Some(Query.AscDesc.Desc) => usql" DESC"
      }

      val nulls = orderBy.nulls match {
        case None => usql""
        case Some(Query.Nulls.First) => usql" NULLS FIRST"
        case Some(Query.Nulls.Last) => usql" NULLS LAST"
      }

      usql" ORDER BY " + orderBy.expr.toSqlExpr(context) + ascDesc + nulls
    }

    val limitOpt = optional(query.limit) { limit =>
      usql" LIMIT " + SqlStr.raw(limit.toString)
    }

    val offsetOpt = optional(query.offset) { offset =>
      usql" OFFSET " + SqlStr.raw(offset.toString)
    }

    val jsonQueryMap = flattenedExpr
      .map{case (k, v) => (v, SqlStr.raw(("res" +: k).map(columnNameMapper).mkString("__")))}
      .toMap

    (
      jsonQueryMap,
      usql"SELECT " + exprStr + usql" FROM " + tables + joins + filtersOpt + sortOpt + limitOpt + offsetOpt
    )
  }
}