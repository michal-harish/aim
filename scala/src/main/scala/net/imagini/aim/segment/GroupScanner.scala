package net.imagini.aim.segment

import java.io.EOFException
import java.nio.ByteBuffer
import java.util.LinkedHashMap

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.immutable.ListMap

import net.imagini.aim.tools.AbstractScanner
import net.imagini.aim.tools.RowFilter
import net.imagini.aim.types.AimSchema
import net.imagini.aim.types.AimType
import net.imagini.aim.types.AimTypeAbstract
import net.imagini.aim.types.TypeUtils

class GroupScanner(
    val sourceSchema: AimSchema,
    val groupSelectStatement: String, 
    val rowFilterStatement: String, 
    val groupFilterStatement: String,
    val segments: Seq[AimSegment])
  extends AbstractScanner {

  val selectDef = if (groupSelectStatement.contains("*")) sourceSchema.names else groupSelectStatement.split(",").map(_.trim)
  val selectRef = selectDef.filter(sourceSchema.has(_))

  override val schema: AimSchema = new AimSchema(new LinkedHashMap(ListMap[String, AimType](
    selectDef.map(f ⇒ f match {
      case field: String if (sourceSchema.has(field))    ⇒ (field -> sourceSchema.field(field))
      case function: String if (function.contains("(group")) ⇒ new AimTypeGROUP(sourceSchema, function).typeTuple
    }): _*).asJava))

  override val keyColumn = schema.get(sourceSchema.name(0))
  override val keyType = sourceSchema.get(0)

  private val rowFilter = RowFilter.fromString(sourceSchema, rowFilterStatement)
  private val groupFilter = RowFilter.fromString(sourceSchema, groupFilterStatement)
  private val groupFunctions: Seq[AimTypeGROUP] = schema.fields.filter(_.isInstanceOf[AimTypeGROUP]).map(_.asInstanceOf[AimTypeGROUP])

  private val scanColumns = selectRef ++ rowFilter.getColumns ++ groupFilter.getColumns ++ groupFunctions.flatMap(_.filter.getColumns) ++ groupFunctions.map(_.field)
  private val merge = new MergeScanner(sourceSchema, scanColumns, RowFilter.fromString(sourceSchema, "*"), segments)
  rowFilter.updateFormula(merge.schema.names)
  groupFilter.updateFormula(merge.schema.names)
  groupFunctions.map(_.filter.updateFormula(merge.schema.names))
  private val mergeColumnIndex: Array[Int] = schema.names.map(n ⇒ if (merge.schema.has(n)) merge.schema.get(n) else -1)

  override def mark = merge.mark
  override def reset = merge.reset
  override def skipRow = merge.skipRow
  private var currentKey: ByteBuffer = null
  //TODO work on storage and scanner is required to keep the buffers decompressed if marked

  def selectCurrentFilteredGroup = {
    var satisfiesFilter = groupFilter.isEmptyFilter
    groupFunctions.map(_.reset)
    do {
      merge.mark
      currentKey = merge.selectKey.slice
      try {
        while ((!satisfiesFilter || !groupFunctions.forall(_.satisfied)) && TypeUtils.equals(merge.selectKey, currentKey, keyType)) {
          if (!satisfiesFilter && groupFilter.matches(merge.selectRow)) {
            satisfiesFilter = true
          }
          groupFunctions.filter(f ⇒ !f.satisfied && f.filter.matches(merge.selectRow)).foreach(f ⇒ {
            f.satisfy(merge.selectRow(merge.schema.get(f.field)))
          })
          if (!satisfiesFilter || !groupFunctions.forall(_.satisfied)) {
            merge.skipRow
          }
        }
      } catch {
        case e: EOFException ⇒ {}
      }
    } while (!satisfiesFilter || !groupFunctions.forall(_.satisfied))
    merge.reset
  }

  def selectNextFilteredGroup = {
    if (currentKey != null) {
      while (TypeUtils.equals(merge.selectKey, currentKey, keyType)) {
        merge.skipRow
      }
    }
    selectCurrentFilteredGroup
  }

  def selectNextGroupRow: Boolean = {
    if (currentKey == null) {
      false
    } else {
      if (TypeUtils.equals(merge.selectKey, currentKey, keyType)) {
        true
      } else {
        false
      }
    }
  }

  override def selectRow: Array[ByteBuffer] = {
    while (!selectNextGroupRow || !rowFilter.matches(merge.selectRow)) {
      if (!selectNextGroupRow) selectNextFilteredGroup else merge.skipRow
    }
    val mergeRow = merge.selectRow
    (schema.fields, (0 to schema.fields.length)).zipped.map((f, c) ⇒ f match {
      case function: AimTypeGROUP                      ⇒ function.toByteBuffer
//      case empty: AimType if (empty.equals(Aim.EMPTY)) ⇒ null
      case field: AimType                              ⇒ mergeRow(mergeColumnIndex(c))
    })
  }

}

class AimTypeGROUP(val schema: AimSchema, val definition: String) extends AimTypeAbstract {
  val alias = definition.substring(0, definition.indexOf("(group"))
  val body = definition.substring(alias.length + 6, definition.length - 1).trim
  val field = body.substring(0, body.indexOf(" "))
  val dataType = schema.field(field).getDataType
  val filter = RowFilter.fromString(schema, body.substring(body.indexOf(" where ") + 7).trim)
  private var buffer: ByteBuffer = null
  def reset = buffer = null
  def satisfied = buffer != null
  def satisfy(value: ByteBuffer) = buffer = value.slice()
  def toByteBuffer = { buffer.rewind(); buffer }
  override def getDataType = dataType
  override def toString = "GROUP[" + field + " WHERE" + filter + "]"
  override def convert(value: String): Array[Byte] = dataType.convert(value)
  override def convert(value: Array[Byte]): String = dataType.convert(value)
  override def asString(value: ByteBuffer): String = dataType.asString(value)
  def typeTuple: (String, AimTypeGROUP) = alias -> this
}