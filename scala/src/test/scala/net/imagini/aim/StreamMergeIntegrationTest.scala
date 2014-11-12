package net.imagini.aim

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import net.imagini.aim.types.AimSchema
import net.imagini.aim.segment.AimSegmentUnsorted
import net.imagini.aim.segment.AimSegmentQuickSort
import net.imagini.aim.types.SortOrder
import net.imagini.aim.utils.BlockStorageMEMLZ4
import net.imagini.aim.tools.RowFilter
import net.imagini.aim.tools.StreamMerger
import java.io.EOFException
import net.imagini.aim.region.AimRegion
import net.imagini.aim.tools.StreamUtils
import net.imagini.aim.cluster.ScannerInputStream
import net.imagini.aim.segment.MergeScanner
import java.io.InputStream

class StreamMergeIntegrationTest extends FlatSpec with Matchers {

  private def readRecord(schema: AimSchema, stream: InputStream) = {
    schema.fields.map(t ⇒ { t.convert(StreamUtils.read(stream, t.getDataType)) }).foldLeft("")(_ + _ + " ")
  }
  "2 sorted segments" should "yield correct groups when merge-sorted" in {
    val schema = AimSchema.fromString("user_uid(UUID:BYTEARRAY[16]),column(STRING),value(STRING)")
    val p1 = new AimRegion("vdna.events", schema, 10000)
    val s1 = new AimSegmentQuickSort(schema).initStorage(classOf[BlockStorageMEMLZ4])
    s1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.auto.com}")
    s1.appendRecord("17b22cfb-a29e-42c3-a3d9-12d32850e103", "addthis_id", "AT1234")
    s1.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.travel.com}")
    p1.add(s1)

    val p2 = new AimRegion("vdna.events", schema, 10000)
    val s2 = new AimSegmentQuickSort(schema).initStorage(classOf[BlockStorageMEMLZ4])
    s2.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.ebay.com}")
    s2.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "addthis_id", "AT9876")
    s2.appendRecord("17b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.music.com}")
    p2.add(s2)

    val subschema = schema.subset(Array("user_uid", "value"))

    val stream1 = new ScannerInputStream(new MergeScanner("user_uid,value", "column='pageview'", p1.segments))

    val stream2 = new ScannerInputStream(new MergeScanner("user_uid,value", "column='pageview'", p2.segments))

    val mergeStream = new StreamMerger(subschema, 1, Array(stream1, stream2))

    readRecord(subschema, mergeStream) should be("17b22cfb-a29e-42c3-a3d9-12d32850e103 {www.music.com} ")
    readRecord(subschema, mergeStream) should be("37b22cfb-a29e-42c3-a3d9-12d32850e103 {www.auto.com} ")
    readRecord(subschema, mergeStream) should be("37b22cfb-a29e-42c3-a3d9-12d32850e103 {www.ebay.com} ")
    readRecord(subschema, mergeStream) should be("a7b22cfb-a29e-42c3-a3d9-12d32850e103 {www.travel.com} ")

    an[EOFException] must be thrownBy readRecord(subschema, mergeStream)

    mergeStream.close
  }

}