package net.imagini.drift.hadoop;

import net.imagini.drift.cluster.DriftManager;
import net.imagini.drift.cluster.DriftManagerZk;
import net.imagini.drift.types.DriftSchema;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hive.hcatalog.mapreduce.HCatInputFormat;

public class DriftHCatLoader extends Configured implements Tool {

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new DriftHCatLoader(), args);
        System.exit(exitCode);
    }

    public int run(String[] args) throws Exception {
        String hcatTable = "hcat_events_rc";
        String[] hcatMapping = new String[] {"partner_user_id", "useruid", "timestamp"};
        String hcatPartitionFilter = "topic=\"datasync\" and d>=\"2014-12-01\" and d<=\"2014-12-31\"";
        String hcatRowFilter = "partner_id_space='at_id'";
        String driftZkConnect = "bl-mharis-d02";
        String driftClusterId = "benchmark4B";
        String driftKeyspace = "addthis";
        String driftTable = "syncs";
        //------------------------------------------------------------------------------
        Configuration conf = getConf();
        conf.set("driftZkConnect", driftZkConnect);
        conf.set("driftCluserId", driftClusterId);
        conf.set("driftKeyspace", driftKeyspace);
        conf.set("driftTable", driftTable);
        conf.setStrings("hcatMapping", hcatMapping);
        conf.set("hcatRowFilter", hcatRowFilter);
        DriftManager manager = new DriftManagerZk(driftZkConnect, driftClusterId);
        int numNodes = manager.getNodeConnectors().size();
        DriftSchema schema = manager.getDescriptor(driftKeyspace, driftTable).schema;
        System.out.println("HCAT SOURCE TABLE: " + hcatTable);
        System.out.println("HCAT SOURCE PARTITIONS: " + hcatPartitionFilter);
        System.out.println("HCAT SOURCE ROW FILTER: " + hcatRowFilter);
        System.out.println("DRIFT CLUSTER ZK CONNECT: " + driftZkConnect);
        System.out.println("DRIFT CLUSTER ID: " + driftClusterId);
        System.out.println("DRIFT CLUSTER NODES: " + numNodes);
        System.out.println("DRIFT KEYSPACE: " + driftKeyspace);
        System.out.println("DRIFT TABLE: " + driftTable);
        System.out.println("DRIFT SCHEMA: " + schema.toString());
        conf.set("driftSchema", schema.toString());
        conf.set("driftNumNodes", String.valueOf(numNodes));

        JobConf jobConf = new JobConf(conf);
        Job job = Job.getInstance(jobConf, "drift.loader-" + hcatPartitionFilter);
        job.setJarByClass(DriftHCatLoader.class);

        job.setInputFormatClass(HCatInputFormat.class);
        job.setMapperClass(DriftHCatMapper.class);
        HCatInputFormat.setInput(job, null, hcatTable, hcatPartitionFilter);
        job.setReducerClass(DriftLoaderReducer.class);

        job.setOutputFormatClass(TextOutputFormat.class);
        job.setOutputKeyClass(IntWritable.class);
        job.setOutputValueClass(BytesWritable.class);
        job.setNumReduceTasks(numNodes);
        TextOutputFormat.setOutputPath(job, new Path("/user/mharis/drift-load"));

        boolean success = job.waitForCompletion(true);
        return success ? 0 : -1;
    }
}
