package net.imagini.drift.region

import scala.collection.mutable.Queue
import net.imagini.drift.segment.MergeScanner
import net.imagini.drift.segment.AbstractScanner
import net.imagini.drift.segment.RowFilter
import net.imagini.drift.utils.Tokenizer
import net.imagini.drift.types.DriftQueryException
import net.imagini.drift.segment.CountScanner
import net.imagini.drift.segment.TransformScanner

abstract class PFrame
case class PCount(frame: PDataFrame) extends PFrame
abstract class PDataFrame(val fields: PExp*) extends PFrame
case class PTable(keyspace: String, name: String) extends PDataFrame() {
  def region: String = keyspace + "." + name
}
abstract class PJoin(left: PDataFrame, right: PDataFrame, fields: PExp*) extends PDataFrame(fields: _*)
case class PSelect(table: PTable, filter: String, override val fields: PExp*) extends PDataFrame(fields: _*)
case class PSubquery(subquery: PDataFrame, filter: String, override val fields: PExp*) extends PDataFrame(fields: _*)
case class PUnionJoin(left: PDataFrame, right: PDataFrame, override val fields: PExp*) extends PJoin(left, right, fields: _*)
case class PIntesetionJoin(left: PDataFrame, right: PDataFrame, override val fields: PExp*) extends PJoin(left, right, fields: _*)
case class PEquiJoin(left: PDataFrame, right: PDataFrame, override val fields: PExp*) extends PJoin(left, right, fields: _*)

case class PSelectInto(source: PDataFrame, into: PTable) extends PFrame

abstract class PExp //[T]
case class PVar(name: String) extends PExp //[DriftType]
case class PWildcard(dataframe: PDataFrame) extends PExp //[DriftType]
abstract class PBoolExp extends PExp //[Boolean]

class QueryParser(val regions: Map[String, DriftRegion]) extends App {

  def parse(query: String): AbstractScanner = compile(frame(query))

  //TODO separate into QueryCompiler and write tests for compilcations
  def compile[T <: AbstractScanner] (frame: PFrame, mixIn: Class[T] = classOf[AbstractScanner]): T = {
    frame match {
      case count: PCount ⇒ compile(count.frame, classOf[CountScanner]).asInstanceOf[T]
      case transform: PSelectInto => {
        compile(transform.source, classOf[TransformScanner]).into(transform.into.keyspace, transform.into.name).asInstanceOf[T]
      }
      case select: PSelect ⇒ {
        val region = if (!regions.contains(select.table.region)) throw new DriftQueryException("Unknown table " + select.table.region) else regions(select.table.region)
        val schema = region.schema
        val filter = RowFilter.fromString(schema, select.filter)
        val fields = compile(select.fields)
        if (region.segments.size.equals(0)) {
          throw new DriftQueryException("Table has no data: " + select.table.name)
        } else if (mixIn.equals(classOf[CountScanner])) {
          (new MergeScanner(fields, filter, region.segments) with CountScanner).asInstanceOf[T]
        } else if (mixIn.equals(classOf[TransformScanner])) {
          (new MergeScanner(fields, filter, region.segments) with TransformScanner).asInstanceOf[T]
        } else {
          (new MergeScanner(fields, filter, region.segments)).asInstanceOf[T]
        }
      }
      case select: PSubquery ⇒ {
        val scanner = compile(select.subquery)
        val fields = compile(select.fields)
        val filter = RowFilter.fromString(scanner.schema, select.filter)
        if (mixIn.equals(classOf[CountScanner])) {
          (new SubqueryScanner(fields, filter, scanner) with CountScanner).asInstanceOf[T]
        } else if (mixIn.equals(classOf[TransformScanner])) {
          (new SubqueryScanner(fields, filter, scanner) with TransformScanner).asInstanceOf[T]
        } else {
          (new SubqueryScanner(fields, filter, scanner)).asInstanceOf[T]
        }

      }
      case join: PUnionJoin ⇒ {
        if (mixIn.equals(classOf[CountScanner])) {
          (new UnionJoinScanner(compile(join.left), compile(join.right)) with CountScanner).asInstanceOf[T]
        } else if (mixIn.equals(classOf[TransformScanner])) {
          (new UnionJoinScanner(compile(join.left), compile(join.right)) with TransformScanner).asInstanceOf[T]
        } else {
          (new UnionJoinScanner(compile(join.left), compile(join.right))).asInstanceOf[T]
        }
      }
      case join: PEquiJoin ⇒ {
        val leftScanner = compile(join.left)
        val rightScanner = compile(join.right)
        if (mixIn.equals(classOf[CountScanner])) {
          (new EquiJoinScanner(leftScanner, rightScanner) with CountScanner).asInstanceOf[T]
        } else if (mixIn.equals(classOf[TransformScanner])) {
          (new EquiJoinScanner(leftScanner, rightScanner) with TransformScanner).asInstanceOf[T]
        } else {
          (new EquiJoinScanner(leftScanner, rightScanner)).asInstanceOf[T]
        }
      }
    }
  }

  def compile(exp: Seq[PExp]): Array[String] = {
    exp.flatMap(_ match {
      case w: PWildcard ⇒ w.dataframe match {
        case t: PTable ⇒ regions(t.region).schema.names
        case f: PDataFrame ⇒ compile(f.fields)
      }
      case v: PVar ⇒ Array(v.name)
    }).toArray
  }

  def frame(query: String): PFrame = {
    val q = Queue[String](Tokenizer.tokenize(query, true).toArray(Array[String]()): _*)
    var frame: PFrame = null
    while (!q.isEmpty) {
      q.front.toUpperCase match {
        case "SELECT" ⇒ frame = asDataFrame(q)
        case "INTO" => {
          if (frame == null || !frame.isInstanceOf[PDataFrame]) {
            System.err.println(frame)
            throw new DriftQueryException("Invalid query statment, excpecting SELECT ... INTO <keyspace>.<table>")
          } else {
            q.dequeue
            frame = PSelectInto(frame.asInstanceOf[PDataFrame], asTable(q))
          }
        }
        case "COUNT" ⇒ frame = asCount(q)
        case _: String ⇒ throw new DriftQueryException("Invalid query statment")
      }
    }
    frame
  }

  private def asDataFrame(q: Queue[String]): PDataFrame = {
    var frame: PDataFrame = null
    while (!q.isEmpty) {
      q.front.toUpperCase match {
        case "INTO" => return frame;
        case "(" => { q.dequeue; frame = asDataFrame(q); q.dequeue }
        case ")" => if (frame != null) return frame else throw new IllegalArgumentException
        case any: String => q.dequeue.toUpperCase match {
          case "SELECT" ⇒ frame = asSelect(q)
          case "UNION" ⇒ {
            val right = asDataFrame(q)
            if (frame == null) throw new IllegalArgumentException else frame = PUnionJoin(frame, right, (frame.fields ++ right.fields): _*)
          }
          case "INTERSECTION" ⇒ if (frame == null) throw new IllegalArgumentException else frame = PIntesetionJoin(frame, asDataFrame(q), frame.fields: _*)
          case "JOIN" ⇒ if (frame == null) throw new IllegalArgumentException else {
            val right = asDataFrame(q)
            frame = PEquiJoin(frame, right, (frame.fields ++ right.fields): _*)
          }
        }
      }
    }
    frame
  }
  private def asCount(q: Queue[String]): PCount = {
    var frame: PDataFrame = null
    q.dequeue
    while (!q.isEmpty) {
      q.dequeue match {
        case "(" ⇒ {
          frame = asDataFrame(q); q.dequeue;
          val filter = asFilter(q)
          return filter match {
            case "*" ⇒ PCount(frame)
            case _ ⇒ PCount(PSubquery(frame, filter))
          }
        }
        case keyspace: String ⇒ q.dequeue match {
          case "." ⇒ {
            frame = PTable(keyspace, q.dequeue)
            return PCount(PSelect(frame.asInstanceOf[PTable], asFilter(q)))
          }
          case q: String ⇒ throw new DriftQueryException("Invalid from statement, should be <keyspace>.<table>")
        }
      }
    }
    throw new DriftQueryException("Invalid COUNT statement")
  }

  private def asTable(q: Queue[String]): PTable = {
    q.dequeue match {
      case keyspace: String ⇒ q.dequeue match {
        case "." ⇒ q.dequeue match {
          case tableName: String => return PTable(keyspace, tableName)
        }
        case q: String ⇒ throw new DriftQueryException("Invalid table reference, should be <keyspace>.<table>")
      }
    }
  }

  private def asSelect(q: Queue[String]): PDataFrame = {
    var fields = scala.collection.mutable.ListBuffer[PExp]()
    var frame: PDataFrame = null
    var state = "FIELDS"
    var wildcard = false
    while (!q.isEmpty && state != "FINISHED") state match {
      case "FIELDS" ⇒ q.dequeue match {
        case s: String if (s.toUpperCase.equals("FROM")) ⇒ state = "FROM"
        case "," if (fields == null) ⇒ throw new DriftQueryException("Invalid select expression")
        case "," if (fields != null) ⇒ {}
        case "*" ⇒ wildcard = true
        case field: String ⇒ fields :+= PVar(field)
      }
      case "FROM" ⇒ q.dequeue match {
        case "(" ⇒ {
          frame = asDataFrame(q); q.dequeue;
          if (wildcard) fields :+= PWildcard(frame)
          return PSubquery(frame, asFilter(q), fields: _*)
        }
        case keyspace: String ⇒ q.dequeue match {
          case "." ⇒ {
            frame = PTable(keyspace, q.dequeue)
            if (wildcard) fields :+= PWildcard(frame)
            return PSelect(frame.asInstanceOf[PTable], asFilter(q), fields: _*)
          }
          case q: String ⇒ throw new DriftQueryException("Invalid from statement, should be <keyspace>.<table>")
        }
      }
      case a ⇒ throw new IllegalStateException(a)
    }
    throw new DriftQueryException("Invalid SELECT statement")
  }

  private def asFilter(q: Queue[String]): String = {
    var filter: String = "*"
    var state = "WHERE_OR_FINSIHED"
    while (!q.isEmpty) state match {
      case "WHERE_OR_FINSIHED" ⇒ q.front.toUpperCase match {
        case "WHERE" ⇒ { filter = ""; state = q.dequeue.toUpperCase }
        case _ ⇒ return filter.trim
      }
      case "WHERE" ⇒ q.front.toUpperCase match {
        case "UNION" | "JOIN" | "INTERSECTION" | ")" ⇒ return filter.trim
        case _ ⇒ filter += q.dequeue + " "
      }
    }
    filter.trim
  }

}

