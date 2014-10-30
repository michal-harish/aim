package net.imagini.aim

import java.io.EOFException

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import net.imagini.aim.cluster.ScannerInputStream
import net.imagini.aim.partition.AimPartition
import net.imagini.aim.segment.AimSegmentQuickSort
import net.imagini.aim.segment.MergeScanner
import net.imagini.aim.tools.PipeUtils
import net.imagini.aim.types.AimSchema
import net.imagini.aim.utils.BlockStorageLZ4

class ScannerInputStreamTest extends FlatSpec with Matchers {
  "ScannerInputStream " should "be able to read can read MergeScanner byte by byte" in {

    val schemaATSyncs = AimSchema.fromString("at_id(STRING), user_uid(UUID:BYTEARRAY[16])")
    val AS1 = new AimPartition(schemaATSyncs, 1000)
    AS1.add(new AimSegmentQuickSort(schemaATSyncs, classOf[BlockStorageLZ4])
      .appendRecord("AT5656", "a7b22cfb-a29e-42c3-a3d9-12d32850e234")
      .appendRecord("AT1234", "37b22cfb-a29e-42c3-a3d9-12d32850e103")
      .appendRecord("AT7888", "89777987-a29e-42c3-a3d9-12d32850e234")
      .close)

    val scanner = new MergeScanner(schemaATSyncs, "at_id", "*", AS1.segments)
    scanner.nextLine should be("AT1234")
    scanner.nextLine should be("AT5656")
    scanner.nextLine should be("AT7888")
    an[EOFException] must be thrownBy scanner.nextLine
    an[EOFException] must be thrownBy scanner.nextLine

    //TODO scanner.rewind

    val t = schemaATSyncs.field("at_id").getDataType
    val scannerStream = new ScannerInputStream(new MergeScanner(schemaATSyncs, "at_id", "*", AS1.segments))
    t.convert(PipeUtils.read(scannerStream, t)) should be("AT1234")
    t.convert(PipeUtils.read(scannerStream, t)) should be("AT5656")
    t.convert(PipeUtils.read(scannerStream, t)) should be("AT7888")
    scannerStream.read should be(-1)
    an[EOFException] must be thrownBy PipeUtils.read(scannerStream, t)

    scannerStream.close

  }

}