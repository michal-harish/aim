package net.imagini.aim

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import net.imagini.aim.partition.AimPartition
import net.imagini.aim.segment.MergeScanner
import net.imagini.aim.segment.AimSegmentQuickSort
import net.imagini.aim.tools.RowFilter
import net.imagini.aim.types.AimSchema
import net.imagini.aim.utils.BlockStorageLZ4
import java.io.EOFException

class MergeScannerTest extends FlatSpec with Matchers {

  "ScannerMerge " should "produce same result as stream merge" in {
    val schema = AimSchema.fromString("user_uid(UUID:BYTEARRAY[16]),column(STRING),value(STRING)")
    val s1 = new AimSegmentQuickSort(schema, classOf[BlockStorageLZ4])
    s1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.auto.com}")
    s1.appendRecord("17b22cfb-a29e-42c3-a3d9-12d32850e103", "addthis_id", "AT1234")
    s1.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.travel.com}")
    s1.close

    val s2 = new AimSegmentQuickSort(schema, classOf[BlockStorageLZ4])
    s2.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.ebay.com}")
    s2.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "addthis_id", "AT9876")
    s2.appendRecord("17b22cfb-a29e-42c3-a3d9-12d32850e103", "pageview", "{www.music.com}")
    s2.close

    val partition = new AimPartition(schema, 1000)
    //TODO either implement partition.appendRecord or move segment size paremeter to the parition loader 
    partition.add(s1)
    partition.add(s2)

    //TODO select only subset of columns: user_uid, value
    val mergeScan = new MergeScanner(partition.schema, "user_uid,value", "column='pageview'", partition.segments)

    mergeScan.nextResultAsString should be("17b22cfb-a29e-42c3-a3d9-12d32850e103 {www.music.com}")
    mergeScan.nextResultAsString should be("37b22cfb-a29e-42c3-a3d9-12d32850e103 {www.ebay.com}")
    mergeScan.nextResultAsString should be("37b22cfb-a29e-42c3-a3d9-12d32850e103 {www.auto.com}")
    mergeScan.nextResultAsString should be("a7b22cfb-a29e-42c3-a3d9-12d32850e103 {www.travel.com}")

    an[EOFException] must be thrownBy mergeScan.nextResultAsString
  }

}