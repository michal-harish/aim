package net.imagini.aim.segment

import java.io.EOFException
import net.imagini.aim.tools.RowFilter
import net.imagini.aim.types.AimSchema
import net.imagini.aim.types.SortOrder
import scala.collection.JavaConverters._
import java.nio.ByteBuffer
import net.imagini.aim.tools.AbstractScanner
import scala.Array.canBuildFrom
import net.imagini.aim.types.TypeUtils
import net.imagini.aim.tools.ColumnScanner

class MergeScanner(val sourceSchema: AimSchema, val selectFields: Array[String], val rowFilter: RowFilter, val segments: Seq[AimSegment])
  extends AbstractScanner {
  def this(sourceSchema: AimSchema, selectStatement: String, rowFilterStatement: String, segments: Seq[AimSegment]) = this(
    sourceSchema,
    if (selectStatement.contains("*")) sourceSchema.names else selectStatement.split(",").map(_.trim),
    RowFilter.fromString(sourceSchema, rowFilterStatement),
    segments)

  override val schema: AimSchema = sourceSchema.subset(selectFields)
  val keyField: String = sourceSchema.name(0)
  override val keyColumn = schema.get(keyField)
  val sortOrder = SortOrder.ASC

  private val scanSchema: AimSchema = sourceSchema.subset(selectFields ++ rowFilter.getColumns :+ keyField)
  private val scanners: Seq[Array[ColumnScanner]] = segments.map(segment ⇒ segment.wrapScanners(scanSchema))
  private val scanColumnIndex: Array[Int] = schema.names.map(n ⇒ scanSchema.get(n))
  private val scanKeyColumnIndex: Int = scanSchema.get(keyField)
  rowFilter.updateFormula(scanSchema.names)

  private var eof = false
  private var currentSegment = -1
  private var markCurrentSegment = -1
  private val selectBuffer: Array[ByteBuffer] = new Array[ByteBuffer](schema.size)

  override def rewind = {
    scanners.foreach(_.foreach(_.rewind))
    eof = false
    currentSegment = -1
    markCurrentSegment = -1
  }

  override def mark = {
    markCurrentSegment = currentSegment
    scanners.foreach(_.foreach(_.mark))
  }

  override def reset = {
    scanners.foreach(_.foreach(_.reset))
    currentSegment = markCurrentSegment
    move
  }

  override def next: Boolean = {
    if (eof) {
      return false
    }
    if (currentSegment > -1) {
      for (columnScanner ← scanners(currentSegment)) columnScanner.skip
      currentSegment = -1
    }
    for (s ← (0 to scanners.size - 1)) {
      //filter
      var segmentHasData = scanners(s).forall(columnScanner ⇒ !columnScanner.eof)
      while (segmentHasData && !rowFilter.matches(scanners(s).map(_.buffer))) {
        //for (columnScanner ← scanners(s)) 
        segmentHasData = scanners(s).forall(columnScanner ⇒ { columnScanner.skip; !columnScanner.eof })
      }
      //merge sort
      if (segmentHasData) {
        if (currentSegment == -1 || ((sortOrder == SortOrder.ASC ^ TypeUtils.compare(scanners(s)(scanKeyColumnIndex).buffer, scanners(currentSegment)(scanKeyColumnIndex).buffer, keyType) > 0))) {
          currentSegment = s
        }
      }
    }
    eof = currentSegment == -1
    move
    !eof
  }

  override def selectRow: Array[ByteBuffer] = {
    if (currentSegment == -1) {
      throw new EOFException
    } else {
      selectBuffer
    }
  }

  private def move = {
    if (currentSegment > -1) {
      val scanner = scanners(currentSegment)
      for (i ← (0 to schema.size - 1)) {
        selectBuffer(i) = scanner(scanColumnIndex(i)).buffer
      }
    }
  }
}