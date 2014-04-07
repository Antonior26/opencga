package org.opencb.opencga.storage.alignment;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.opencb.commons.bioformats.alignment.Alignment;
import org.opencb.commons.bioformats.alignment.AlignmentRegion;
import org.opencb.commons.io.DataWriter;
import org.opencb.opencga.lib.auth.MonbaseCredentials;
import org.opencb.opencga.storage.datamanagers.HBaseManager;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: jcoll
 * Date: 3/6/14
 * Time: 4:48 PM
 */
public class AlignmentRegionHBaseDataWriter implements DataWriter<AlignmentRegion> {

    private HBaseManager hBaseManager;
    private HTable table;
    private String tableName;
    private String sample = "s";
    private String columnFamilyName = "c";
    private List<Put> puts;


    private int alignmentBucketSize = 256;

    //

    List<Alignment> alignmentsRemain = new LinkedList<>();
    List<Alignment> alignmentBucketList;
    private AlignmentProto.AlignmentBucket alignmentBucket;
    //private AlignmentProtoHelper.Summary summary;
    private long index = 0;
    private String chromosome = "";



    public AlignmentRegionHBaseDataWriter(MonbaseCredentials credentials, String tableName) {
        // HBase configuration

        hBaseManager = new HBaseManager(credentials);

        this.puts = new LinkedList<>();
        this.tableName = tableName;
    }

    public AlignmentRegionHBaseDataWriter(Configuration config, String tableName) {
        hBaseManager = new HBaseManager(config);

        this.puts = new LinkedList<>();
        this.tableName = tableName;
    }

    @Override
    public boolean open() {
        hBaseManager.connect();

        return true;
    }

    @Override
    public boolean close() {

        hBaseManager.disconnect();

        return true;
    }

    @Override
    public boolean pre() {
        table = hBaseManager.createTable(tableName,columnFamilyName);

        return true;
    }

    @Override
    public boolean post() {
        flush();
        try {
            System.out.println("Puteamos la tabla. " + puts.size());
            table.put(puts);
            puts.clear();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }


        return true;
    }

    @Override
    public boolean write(AlignmentRegion alignmentRegion) {

        /*
         * 1º Add remaining alignments from last AR
         * 2º Take Alignments from tail
         * 3º Create summary
         * 4º Create Proto
         * 5º Write into hbase
         *
         */

        String value;
        Alignment firstAlignment = alignmentRegion.getAlignments().get(0);

        if(firstAlignment == null){
            return false;
        }

        //First alignment. Init and writes headers
        if(index == 0){
            init(firstAlignment);

            globalHeader();
            chromosomeHeader();
        }
        //Changes chromosome. Flush, init and write chromosomeHeader.
        if(!chromosome.equals(firstAlignment.getChromosome())){
            flush();
            init(firstAlignment);
            chromosomeHeader();
        }

        //1º Add remaining alignments.
        List<Alignment> alignments = alignmentsRemain;
        alignments.addAll(alignmentRegion.getAlignments());

        //2º Take alignments from tail.
        alignmentsRemain = new LinkedList<>();
        Alignment alignmentAux = alignments.remove(alignments.size()-1);    //Remove last
        alignmentsRemain.add(0, alignmentAux);
        long lastBucket = alignmentAux.getStart()/alignmentBucketSize;

        while(alignments.size() != 0){
            if(alignments.get(alignments.size()).getStart()/alignmentBucketSize != lastBucket){
                break;
            } else {
                alignmentsRemain.add(0, alignments.remove(alignments.size() - 1));    //Remove last
            }
        }

        //3º Create Summary
        AlignmentRegionSummary summary = new AlignmentRegionSummary();
        for(Alignment alignment : alignments){
            summary.addAlignment(alignment);
        }


        //4º Create Proto
        for(Alignment alignment : alignments){
            if(index < alignment.getStart()/alignmentBucketSize){
                alignmentBucket = AlignmentProtoHelper.toAlignmentBucketProto(alignmentBucketList, summary);    //TODO:
                flush();
                init(alignment);

            }
            alignmentBucketList.add(alignment);
        }


        //5º Write into hbase
        try {
            System.out.println("Puteamos la tabla. " + puts.size());
            table.put(puts);
            puts.clear();
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            return false;
        }

        return true;

    }

    private void globalHeader() { //TODO jj:
        //To change body of created methods use File | Settings | File Templates.
    }

    private void chromosomeHeader() { //TODO jj:
        //To change body of created methods use File | Settings | File Templates.
    }

    private void init(Alignment alignment){
        index = alignment.getStart() / alignmentBucketSize;
        chromosome = alignment.getChromosome();
        alignmentBucketList = new LinkedList<>();
    }

    private void flush(){
        String rowKey = chromosome + "_" + String.format("%07d", index);
        //System.out.println("Creamos un Put() con rowKey " + rowKey);

        Put put = new Put(Bytes.toBytes(rowKey));
        if(alignmentBucket != null){
            byte[] compress;
            try {
                compress = Snappy.compress(alignmentBucket.toByteArray());
            } catch (IOException e) {
                System.out.println("this AlignmentProto.AlignmentRegion could not be compressed by snappy");
                e.printStackTrace();  // TODO jj handle properly
                return;
            }
            put.add(Bytes.toBytes(columnFamilyName), Bytes.toBytes(sample), compress);
        }
        puts.add(put);
    }

    @Override
    public boolean write(List<AlignmentRegion> alignmentRegions) {
        for(AlignmentRegion alignmentRegion : alignmentRegions){
            if(!write(alignmentRegion)){
                return false;
            }
        }
        return true;
    }



    public String getSample() {
        return sample;
    }

    public void setSample(String sample) {
        this.sample = sample;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnFamilyName() {
        return columnFamilyName;
    }

    public void setColumnFamilyName(String columnFamilyName) {
        this.columnFamilyName = columnFamilyName;
    }
}
