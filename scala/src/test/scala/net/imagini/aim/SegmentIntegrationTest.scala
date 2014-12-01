package net.imagini.aim

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import net.imagini.aim.segment.AimSegment
import net.imagini.aim.types.AimSchema
import net.imagini.aim.utils.BlockStorageMEMLZ4
import net.imagini.aim.segment.RowFilter
import java.io.InputStream
import net.imagini.aim.types.SortOrder
import net.imagini.aim.region.AimRegion
import net.imagini.aim.cluster.StreamUtils
import net.imagini.aim.cluster.ScannerInputStream
import net.imagini.aim.segment.MergeScanner
import net.imagini.aim.segment.CountScanner
import net.imagini.aim.types.AimTableDescriptor
import net.imagini.aim.utils.View
import net.imagini.aim.types.SortType

class SegmentIntegration extends FlatSpec with Matchers {

  "Unsorted segemnt select" should "give an input stream with the same order of records" in {
    val schema = AimSchema.fromString("user_uid(UUID),timestamp(LONG),column(STRING),value(STRING)")
    val d = new AimTableDescriptor(schema, 10000, classOf[BlockStorageMEMLZ4], SortType.NO_SORT)
    val p1 = new AimRegion("vdna.events", d)
    p1.addTestRecords(
      Seq("37b22cfb-a29e-42c3-a3d9-12d32850e103", "1413143748041", "a", "6571796330792743131"),
      Seq("17b22cfb-a29e-42c3-a3d9-12d32850e103", "1413143748042", "a", "6571796330792743131"),
      Seq("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "1413143748043", "a", "6571796330792743131"))
    p1.compact

    val count = new MergeScanner("*", "*", p1.segments)
    count.count should be(3)

    val merge = new MergeScanner("*", "column='a'", p1.segments)
    val in: InputStream = new ScannerInputStream(merge)
    schema.fields.map(t ⇒ t.asString(StreamUtils.read(in, t))).foldLeft("")(_ + _) should be("37b22cfb-a29e-42c3-a3d9-12d32850e1031413143748041a6571796330792743131")
    schema.fields.map(t ⇒ t.asString(StreamUtils.read(in, t))).foldLeft("")(_ + _) should be("17b22cfb-a29e-42c3-a3d9-12d32850e1031413143748042a6571796330792743131")
    schema.fields.map(t ⇒ t.asString(StreamUtils.read(in, t))).foldLeft("")(_ + _) should be("a7b22cfb-a29e-42c3-a3d9-12d32850e1031413143748043a6571796330792743131")
    in.close

    val mergeCount = new MergeScanner("*", "column='a'", p1.segments)
    mergeCount.count should be(3)
  }

  "QuickSorted ASC segemnt select" should "give an input stream with the correct order" in {
    val schema = AimSchema.fromString("user_uid(UUID),timestamp(LONG),column(STRING),value(STRING)")
    val d = new AimTableDescriptor(schema, 10000, classOf[BlockStorageMEMLZ4], SortType.QUICK_SORT)
    val p1 = new AimRegion("vdna.events", d)
    p1.addTestRecords(
      Seq("37b22cfb-a29e-42c3-a3d9-12d32850e103", "1413143748041", "a", "6571796330792743131"),
      Seq("17b22cfb-a29e-42c3-a3d9-12d32850e103", "1413143748042", "a", "6571796330792743131"),
      Seq("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "1413143748043", "a", "6571796330792743131"))
    p1.compact
    val count = new MergeScanner("*", "column='a'", p1.segments) with CountScanner
    count.count should be(3)
    val merge = new MergeScanner("*", "column='a'", p1.segments)
    val in: InputStream = new ScannerInputStream(merge)
    schema.fields.map(t ⇒ t.asString(StreamUtils.read(in, t))).foldLeft("")(_ + _) should be("17b22cfb-a29e-42c3-a3d9-12d32850e1031413143748042a6571796330792743131")
    schema.fields.map(t ⇒ t.asString(StreamUtils.read(in, t))).foldLeft("")(_ + _) should be("37b22cfb-a29e-42c3-a3d9-12d32850e1031413143748041a6571796330792743131")
    schema.fields.map(t ⇒ t.asString(StreamUtils.read(in, t))).foldLeft("")(_ + _) should be("a7b22cfb-a29e-42c3-a3d9-12d32850e1031413143748043a6571796330792743131")
    in.close

    val mergeCount = new MergeScanner("*", "column='a'", p1.segments)
    mergeCount.count should be(3)

  }

  //  "QuickSorted DESC  segemnt select" should "give an input stream with the correct order" in {
  //    val schema = AimSchema.fromString("user_uid(UUID),timestamp(LONG),column(STRING),value(STRING)")
  //    val s1 = new AimSegmentQuickSort(schema, "user_uid", SortOrder.DESC, classOf[BlockStorageMEMLZ4])
  //    s1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103","1413143748041","a","6571796330792743131")
  //    s1.appendRecord("17b22cfb-a29e-42c3-a3d9-12d32850e103","1413143748042","a","6571796330792743131")
  //    s1.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103","1413143748043","a","6571796330792743131")
  //    s1.count(RowFilter.emptyFilter) should be(3)
  //    s1.count(RowFilter.fromString(schema, "column=a")) should be(3)
  //    s1.count(RowFilter.fromString(schema, "timestamp>1413143748041")) should be(2)
  //    val in: InputStream  = s1.select(RowFilter.fromString(schema, "column='a'"), schema.names)
  //    schema.fields.map(t => t.convert(PipeUtils.read(in, t.getDataType))).foldLeft("")(_ + _) should be ("a7b22cfb-a29e-42c3-a3d9-12d32850e1031413143748043a6571796330792743131")
  //    schema.fields.map(t => t.convert(PipeUtils.read(in, t.getDataType))).foldLeft("")(_ + _) should be ("37b22cfb-a29e-42c3-a3d9-12d32850e1031413143748041a6571796330792743131")
  //    schema.fields.map(t => t.convert(PipeUtils.read(in, t.getDataType))).foldLeft("")(_ + _) should be ("17b22cfb-a29e-42c3-a3d9-12d32850e1031413143748042a6571796330792743131")
  //    in.close
  //  }

}