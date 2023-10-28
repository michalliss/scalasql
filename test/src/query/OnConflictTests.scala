package scalasql.query

import scalasql._
import scalasql.dialects.{OnConflictOps, ReturningDialect}
import utest._
import utils.ScalaSqlSuite

import java.time.LocalDate

/**
 * Tests for basic insert operations
 */
trait OnConflictTests extends ScalaSqlSuite {
  this: OnConflictOps with ReturningDialect =>
  override def utestBeforeEach(path: Seq[String]): Unit = checker.reset()
  def tests = Tests {

    test("ignore") - {
      checker(
        query = Buyer.insert.values(
          _.name -> "test buyer",
          _.dateOfBirth -> LocalDate.parse("2023-09-09"),
          _.id -> 1 // This should cause a primary key conflict
        ).onConflictIgnore(_.id),
        sql =
          "INSERT INTO buyer (name, date_of_birth, id) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING",
        value = 0
      )

      test("returningEmpty") - {
        checker(
          query = Buyer.insert.values(
            _.name -> "test buyer",
            _.dateOfBirth -> LocalDate.parse("2023-09-09"),
            _.id -> 1 // This should cause a primary key conflict
          ).onConflictIgnore(_.id).returning(_.name),
          sql = """
            INSERT INTO buyer (name, date_of_birth, id) VALUES (?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            RETURNING buyer.name as res""",
          value = Seq.empty[String]
        )
      }

      test("returningOne") - {
        checker(
          query = Buyer.insert.values(
            _.name -> "test buyer",
            _.dateOfBirth -> LocalDate.parse("2023-09-09"),
            _.id -> 4 // This should cause a primary key conflict
          ).onConflictIgnore(_.id).returning(_.name),
          sql = """
            INSERT INTO buyer (name, date_of_birth, id) VALUES (?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            RETURNING buyer.name as res""",
          value = Seq("test buyer")
        )
      }

    }

    test("update") - {
      checker(
        query = Buyer.insert.values(
          _.name -> "test buyer",
          _.dateOfBirth -> LocalDate.parse("2023-09-09"),
          _.id -> 1 // This should cause a primary key conflict
        ).onConflictUpdate(_.id)(_.name -> "TEST BUYER CONFLICT"),
        sql =
          "INSERT INTO buyer (name, date_of_birth, id) VALUES (?, ?, ?) ON CONFLICT (id) DO UPDATE SET name = ?",
        value = 1
      )

      checker(
        query = Buyer.select,
        value = Seq(
          Buyer[Id](1, "TEST BUYER CONFLICT", LocalDate.parse("2001-02-03")),
          Buyer[Id](2, "叉烧包", LocalDate.parse("1923-11-12")),
          Buyer[Id](3, "Li Haoyi", LocalDate.parse("1965-08-09"))
        ),
        normalize = (x: Seq[Buyer[Id]]) => x.sortBy(_.id)
      )
    }

    test("computed") - {
      checker(
        query = Buyer.insert.values(
          _.name -> "test buyer",
          _.dateOfBirth -> LocalDate.parse("2023-09-09"),
          _.id -> 1 // This should cause a primary key conflict
        ).onConflictUpdate(_.id)(v => v.name -> v.name.toUpperCase),
        sql =
          "INSERT INTO buyer (name, date_of_birth, id) VALUES (?, ?, ?) ON CONFLICT (id) DO UPDATE SET name = UPPER(buyer.name)",
        value = 1
      )

      checker(
        query = Buyer.select,
        value = Seq(
          Buyer[Id](1, "JAMES BOND", LocalDate.parse("2001-02-03")),
          Buyer[Id](2, "叉烧包", LocalDate.parse("1923-11-12")),
          Buyer[Id](3, "Li Haoyi", LocalDate.parse("1965-08-09"))
        ),
        normalize = (x: Seq[Buyer[Id]]) => x.sortBy(_.id)
      )
    }

    test("returning") - {
      checker(
        query = Buyer.insert.values(
          _.name -> "test buyer",
          _.dateOfBirth -> LocalDate.parse("2023-09-09"),
          _.id -> 1 // This should cause a primary key conflict
        ).onConflictUpdate(_.id)(v => v.name -> v.name.toUpperCase).returning(_.name).single,
        sql = """
          INSERT INTO buyer (name, date_of_birth, id) VALUES (?, ?, ?)
          ON CONFLICT (id) DO UPDATE
          SET name = UPPER(buyer.name)
          RETURNING buyer.name as res""",
        value = "JAMES BOND"
      )
    }
  }
}