package net.imagini.aim.partition

import net.imagini.aim.tools.RowFilter
import java.io.EOFException
import java.util.Arrays
import net.imagini.aim.utils.BlockStorageRaw
import net.imagini.aim.types.AimSchema
import net.imagini.aim.types.AimTypeAbstract
import net.imagini.aim.tools.Scanner
import java.util.LinkedHashMap
import scala.collection.immutable.ListMap
import net.imagini.aim.types.AimType
import net.imagini.aim.types.Aim
import scala.collection.JavaConverters._
import net.imagini.aim.tools.PipeUtils
import net.imagini.aim.utils.ByteUtils

class GroupScanner(val partition: AimPartition, val groupSelectStatement: String, val rowFilterStatement: String, val groupFilterStatement: String) {
  //
  val selectDef = if (groupSelectStatement.contains("*")) partition.schema.names else groupSelectStatement.split(",").map(_.trim)
  val selectRef = selectDef.filter(partition.schema.has(_))
  val selectSchema: AimSchema = new AimSchema(new LinkedHashMap(ListMap[String, AimType](
    selectDef.map(f ⇒ f match {
      case field: String if (partition.schema.has(field))    ⇒ (field -> partition.schema.field(field))
      case function: String if (function.contains("(group")) ⇒ new AimTypeGROUP(partition.schema, function).typeTuple
      case empty: String                                     ⇒ empty -> Aim.EMPTY
    }): _*).asJava))

  val rowFilter = RowFilter.fromString(partition.schema, rowFilterStatement)
  val groupFilter = RowFilter.fromString(partition.schema, groupFilterStatement)
  val groupFunctions: Seq[AimTypeGROUP] = selectSchema.fields.filter(_.isInstanceOf[AimTypeGROUP]).map(_.asInstanceOf[AimTypeGROUP])

  val requiredColumns = selectRef ++ groupFilter.getColumns ++ groupFunctions.flatMap(_.filter.getColumns) ++ groupFunctions.map(_.field)

  val merge = new MergeScanner(partition, requiredColumns, RowFilter.fromString(partition.schema, "*"))

  rowFilter.updateFormula(merge.schema.names)
  groupFilter.updateFormula(merge.schema.names)
  groupFunctions.map(_.filter.updateFormula(merge.schema.names))

  //FIXME
  private var currentGroupKey: Array[Byte] = null

  def selectCurrentFilteredGroup = {
    var satisfiesFilter = groupFilter.isEmptyFilter
    groupFunctions.map(_.reset)
    do {
      merge.mark
      currentGroupKey = merge.currentRow(merge.keyColumn).asAimValue(merge.keyType)
      try {
        while ((!satisfiesFilter || !groupFunctions.forall(_.satisfied)) && Arrays.equals(merge.currentRow(merge.keyColumn).asAimValue(merge.keyType), currentGroupKey)) {
          if (!satisfiesFilter && groupFilter.matches(merge.currentRow)) {
            satisfiesFilter = true
          }
          groupFunctions.filter(f ⇒ !f.satisfied && f.filter.matches(merge.currentRow)).foreach(f ⇒ {
            f.satisfy(merge.currentRow(merge.schema.get(f.field)).asAimValue(f.dataType))
          })
          if (!satisfiesFilter || !groupFunctions.forall(_.satisfied)) {
            merge.skipCurrentRow
          }
        }
      } catch {
        case e: EOFException ⇒ {}
      }
    } while (!satisfiesFilter || !groupFunctions.forall(_.satisfied))
    merge.reset
  }

  def selectNextFilteredGroup = {
    if (currentGroupKey != null) {
      while (Arrays.equals(merge.currentRow(merge.keyColumn).asAimValue(merge.keyType), currentGroupKey)) {
        merge.skipCurrentRow
      }
    }
    selectCurrentFilteredGroup
  }

  def selectNextGroupRow: Boolean = {
    if (currentGroupKey == null) {
      false
    } else {
      merge.selectNextFilteredRow
      if (Arrays.equals(merge.currentRow(merge.keyColumn).asAimValue(merge.keyType), currentGroupKey)) {
        true
      } else {
        false
      }
    }
  }

  def nextResult: Seq[Scanner] = {
    while(!selectNextGroupRow || !rowFilter.matches(merge.currentRow)) {
      if (!selectNextGroupRow) selectNextFilteredGroup else merge.skipCurrentRow
    }
    val streams: Seq[Scanner] = selectSchema.names.map(f ⇒ selectSchema.field(f) match {
      case function: AimTypeGROUP                      ⇒ function.toScanner
      case empty: AimType if (empty.equals(Aim.EMPTY)) ⇒ null
      case field: AimType                              ⇒ merge.currentRow(merge.schema.get(f))
    })
    merge.schema.names.filter(!selectSchema.has(_)).map(n =>{
      val hiddenColumn = merge.schema.get(n)
      PipeUtils.skip(merge.currentRow(hiddenColumn), merge.schema.dataType(hiddenColumn))
    })
    merge.consumed
    streams
  }
  protected[aim] def nextResultAsString: String = rowAsString(nextResult)
  protected[aim] def rowAsString(streams: Seq[Scanner]): String = (selectSchema.fields, streams).zipped.map((t, s) ⇒ t.convert(PipeUtils.read(s, t.getDataType))).foldLeft("")(_ + _ + " ")

}

class AimTypeGROUP(val schema: AimSchema, val definition: String) extends AimTypeAbstract {
  val alias = definition.substring(0, definition.indexOf("(group"))
  val body = definition.substring(alias.length + 6, definition.length - 1).trim
  val field = body.substring(0, body.indexOf(" "))
  val dataType = schema.field(field).getDataType
  val filter = RowFilter.fromString(schema, body.substring(body.indexOf(" where ") + 7).trim)
  private var storage = new BlockStorageRaw
  private val scanner = new Scanner(storage)
  def reset = { storage.clear; scanner.rewind }
  def satisfied = storage.numBlocks > 0
  def satisfy(value: Array[Byte]) = storage.addBlock(ByteUtils.wrap(value))
  def toScanner = { scanner.rewind; scanner }
  override def getDataType = dataType
  override def toString = "GROUP[" + field + " WHERE" + filter + "]"
  override def convert(value: String): Array[Byte] = dataType.convert(value)
  override def convert(value: Array[Byte]): String = dataType.convert(value)
  def typeTuple: (String, AimTypeGROUP) = alias -> this
}
