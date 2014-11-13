package net.imagini.aim

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import net.imagini.aim.types.AimSchema
import net.imagini.aim.segment.AimSegmentQuickSort
import net.imagini.aim.utils.BlockStorageMEMLZ4
import net.imagini.aim.region.AimRegion
import net.imagini.aim.segment.MergeScanner
import net.imagini.aim.region.EquiJoinScanner
import net.imagini.aim.region.IntersectionJoinScanner
import net.imagini.aim.region.UnionJoinScanner
import net.imagini.aim.types.Aim
import java.io.EOFException
import net.imagini.aim.region.QueryParser

class Usecase1RetroTrainingSet extends FlatSpec with Matchers {

  "Usecase1-retroactive measured set " should "be possible to do by scanning joins" in {

    //PAGEVIEWS //TODO ttl = 10
    val schemaPageviews = AimSchema.fromString("user_uid(UUID:BYTEARRAY[16]),url(STRING),timestamp(TIME:LONG)")
    val sA1 = new AimSegmentQuickSort(schemaPageviews).initStorage(classOf[BlockStorageMEMLZ4])
    sA1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "www.auto.com/mycar", "2014-10-10 11:59:01")
    sA1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "www.travel.com/offers", "2014-10-10 12:01:02")
    sA1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "www.travel.com/offers/holiday", "2014-10-10 12:01:03")
    sA1.appendRecord("12322cfb-a29e-42c3-a3d9-12d32850e103", "www.xyz.com", "2014-10-10 12:01:02")
    val sA2 = new AimSegmentQuickSort(schemaPageviews).initStorage(classOf[BlockStorageMEMLZ4])
    sA2.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "www.bank.com/myaccunt", "2014-10-10 13:59:01")
    sA2.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "www.travel.com/offers", "2014-10-10 13:01:03")
    val regionPageviews1 = new AimRegion("vdna.pageviews", schemaPageviews, 1000)
    regionPageviews1.add(sA1)
    regionPageviews1.add(sA2)

    //CONVERSIONS //TODO ttl = 10
    val schemaConversions = AimSchema.fromString("user_uid(UUID:BYTEARRAY[16]),conversion(STRING),url(STRING),timestamp(TIME:LONG)")
    val sB1 = new AimSegmentQuickSort(schemaConversions).initStorage(classOf[BlockStorageMEMLZ4])
    sB1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "check", "www.bank.com/myaccunt", "2014-10-10 13:59:01")
    sB1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "buy", "www.travel.com/offers/holiday/book", "2014-10-10 13:01:03")
    val regionConversions1 = new AimRegion("vdna.conversions", schemaConversions, 1000)
    regionConversions1.add(sB1)

    //USERFLAGS //TODO ttl = -1
    val schemaUserFlags = AimSchema.fromString("user_uid(UUID:BYTEARRAY[16]),flag(STRING),value(BOOL)")
    val sC1 = new AimSegmentQuickSort(schemaUserFlags).initStorage(classOf[BlockStorageMEMLZ4])
    sC1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "quizzed", "true")
    sC1.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "cc", "true")
    val sC2 = new AimSegmentQuickSort(schemaUserFlags).initStorage(classOf[BlockStorageMEMLZ4])
    sC2.appendRecord("37b22cfb-a29e-42c3-a3d9-12d32850e103", "opt_out_targetting", "true")
    sC2.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "cc", "true")
    sC2.appendRecord("a7b22cfb-a29e-42c3-a3d9-12d32850e103", "quizzed", "false")
    val regionUserFlags1 = new AimRegion("vdna.flags", schemaUserFlags, 1000)
    regionUserFlags1.add(sC1)
    regionUserFlags1.add(sC2)


    //PARTION
    val regions = Map[String, AimRegion](
      "vdna.pageviews" -> regionPageviews1,
      "vdna.conversions" -> regionConversions1,
      "vdna.flags" -> regionUserFlags1)
    val parser = new QueryParser(regions)
    val tsetJoin = parser.parse("select user_uid from vdna.flags where value='true' and flag='quizzed' or flag='cc' join " +
        "(select user_uid,url,timestamp from vdna.pageviews where url contains 'travel.com' union " 
        +"select user_uid,url,timestamp,conversion from vdna.conversions)");

    /**
     * Expected result contains only first and third user, combined schema from pageviews and conversions
     * and table C is used only for filteing users who have either quizzed or cc flags.
     *
     * user_uid(UUID:BYTEARRAY[16])         | url(STRING)                           | conversion(STRING)    |
     * =====================================+=======================================+=======================+
     * 37b22cfb-a29e-42c3-a3d9-12d32850e103 | www.travel.com/offers                 | -                     |
     * 37b22cfb-a29e-42c3-a3d9-12d32850e103 | www.travel.com/offers/holiday         | -                     |
     * 37b22cfb-a29e-42c3-a3d9-12d32850e103 | www.bank.com                          | check                 |
     * 37b22cfb-a29e-42c3-a3d9-12d32850e103 | www.travel.com/offers                 | -                     |
     * 37b22cfb-a29e-42c3-a3d9-12d32850e103 | www.travel.com/offers/holiday/book    | buy                   |
     * a7b22cfb-a29e-42c3-a3d9-12d32850e103 | www.travel.com/offers                 | -                     |
     * =====================================+=======================================+=======================|
     */

    tsetJoin.nextLine should be("37b22cfb-a29e-42c3-a3d9-12d32850e103\twww.travel.com/offers\t2014-10-10 12:01:02\t"+Aim.EMPTY)
    tsetJoin.nextLine should be("37b22cfb-a29e-42c3-a3d9-12d32850e103\twww.travel.com/offers/holiday\t2014-10-10 12:01:03\t"+Aim.EMPTY)
    tsetJoin.nextLine should be("37b22cfb-a29e-42c3-a3d9-12d32850e103\twww.bank.com/myaccunt\t2014-10-10 13:59:01\tcheck")
    tsetJoin.nextLine should be("37b22cfb-a29e-42c3-a3d9-12d32850e103\twww.travel.com/offers/holiday/book\t2014-10-10 13:01:03\tbuy")
    tsetJoin.nextLine should be("a7b22cfb-a29e-42c3-a3d9-12d32850e103\twww.travel.com/offers\t2014-10-10 13:01:03\t"+Aim.EMPTY)
    an[EOFException] must be thrownBy tsetJoin.nextLine

  }
}