package org.opencb.opencga.storage.variant.hbase;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.opencb.biodata.models.variant.ArchivedVariantFile;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.commons.utils.CryptoUtils;
import org.opencb.datastore.core.ComplexTypeConverter;

/**
 *
 * @author Cristina Yenyxe Gonzalez Garcia <cyenyxe@ebi.ac.uk>
 */
public class VariantToHBaseConverter implements ComplexTypeConverter<Variant, Put> {

    public final static byte[] COLUMN_FAMILY = Bytes.toBytes("d");
    public final static byte[] CHROMOSOME_COLUMN = Bytes.toBytes("chr");
    public final static byte[] START_COLUMN = Bytes.toBytes("start");
    public final static byte[] END_COLUMN = Bytes.toBytes("end");
    public final static byte[] LENGTH_COLUMN = Bytes.toBytes("length");
    public final static byte[] REFERENCE_COLUMN = Bytes.toBytes("ref");
    public final static byte[] ALTERNATE_COLUMN = Bytes.toBytes("alt");
    public final static byte[] ID_COLUMN = Bytes.toBytes("id");
    public final static byte[] TYPE_COLUMN = Bytes.toBytes("type");

    private final static String ROWKEY_SEPARATOR = "_";

    private ArchivedVariantFileToHbaseConverter archivedVariantFileConverter;

    /**
     * Create a converter between Variant and HBase entities when there is no
     * need to convert the files the variant was read from.
     */
    public VariantToHBaseConverter() {
        this(null);
    }

    /**
     * Create a converter between Variant and HBase entities. A converter for
     * the files the variant was read from can be provided in case those should
     * be processed during the conversion.
     *
     * @param archivedVariantFileConverter The object used to convert the files
     */
    public VariantToHBaseConverter(ArchivedVariantFileToHbaseConverter archivedVariantFileConverter) {
        this.archivedVariantFileConverter = archivedVariantFileConverter;
    }

    @Override
    public Variant convertToDataModelType(Put object) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Put convertToStorageType(Variant v) {
        Put put = new Put(Bytes.toBytes(buildStorageId(v)));
        put.add(COLUMN_FAMILY, CHROMOSOME_COLUMN, Bytes.toBytes(v.getChromosome()));
        put.add(COLUMN_FAMILY, START_COLUMN, Bytes.toBytes(v.getStart()));
        put.add(COLUMN_FAMILY, END_COLUMN, Bytes.toBytes(v.getEnd()));
        put.add(COLUMN_FAMILY, LENGTH_COLUMN, Bytes.toBytes(v.getLength()));
        put.add(COLUMN_FAMILY, REFERENCE_COLUMN, Bytes.toBytes(v.getReference()));
        put.add(COLUMN_FAMILY, ALTERNATE_COLUMN, Bytes.toBytes(v.getAlternate()));
        put.add(COLUMN_FAMILY, ID_COLUMN, Bytes.toBytes(v.getId()));
        put.add(COLUMN_FAMILY, TYPE_COLUMN, Bytes.toBytes(v.getType().ordinal()));

        // Files
        if (archivedVariantFileConverter != null) {
            for (ArchivedVariantFile archiveFile : v.getFiles().values()) {
                Put filePut = archivedVariantFileConverter.convertToStorageType(archiveFile);
                // Save the generated KeyValues into the Put object associated to the whole variant rowkey
                for (Map.Entry<byte[],List<Cell>> keyValues : filePut.getFamilyCellMap().entrySet()) {
                	for(Cell c : keyValues.getValue()){
                		try {
                     	   KeyValue kvcopy = 
                     			   new KeyValue(
 	                    			   put.getRow(), 
 	                    			   CellUtil.cloneFamily(c), 
 	                    			   CellUtil.cloneQualifier(c), 
 	                    			   c.getTimestamp(), 
 	                    			   KeyValue.Type.Put, 
 	                    			   CellUtil.cloneValue(c)
                     				);
                            put.add(kvcopy);
                        } catch (IOException ex) {
                            Logger.getLogger(VariantToHBaseConverter.class.getName()).log(Level.SEVERE, null, ex);
                        }	
                	}
                }
            }
        }
        
        return put;
    }

    public String buildStorageId(Variant v) {
        StringBuilder builder = new StringBuilder(v.getChromosome());
        builder.append(ROWKEY_SEPARATOR);
        builder.append(String.format("%012d", v.getStart()));
        builder.append(ROWKEY_SEPARATOR);
        if (v.getReference().length() < Variant.SV_THRESHOLD) {
            builder.append(v.getReference());
        } else {
            builder.append(new String(CryptoUtils.encryptSha1(v.getReference())));
        }

        builder.append(ROWKEY_SEPARATOR);

        if (v.getAlternate().length() < Variant.SV_THRESHOLD) {
            builder.append(v.getAlternate());
        } else {
            builder.append(new String(CryptoUtils.encryptSha1(v.getAlternate())));
        }

        return builder.toString();
    }
}
