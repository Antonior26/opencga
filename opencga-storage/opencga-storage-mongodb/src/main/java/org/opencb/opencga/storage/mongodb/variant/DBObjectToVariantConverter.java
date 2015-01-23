package org.opencb.opencga.storage.mongodb.variant;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opencb.biodata.models.variant.VariantSourceEntry;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.annotation.VariantAnnotation;
import org.opencb.commons.utils.CryptoUtils;
import org.opencb.datastore.core.ComplexTypeConverter;

/**
 *
 * @author Cristina Yenyxe Gonzalez Garcia <cyenyxe@ebi.ac.uk>
 */
public class DBObjectToVariantConverter implements ComplexTypeConverter<Variant, DBObject> {

    public final static String CHROMOSOME_FIELD = "chr";
    public final static String START_FIELD = "start";
    public final static String END_FIELD = "end";
    public final static String LENGTH_FIELD = "len";
    public final static String REFERENCE_FIELD = "ref";
    public final static String ALTERNATE_FIELD = "alt";
    public final static String ID_FIELD = "id";
    
    public final static String HGVS_FIELD = "hgvs";
    public final static String TYPE_FIELD = "type";
    public final static String NAME_FIELD = "name";
    
    public final static String FILES_FIELD = "files";
    
    public final static String EFFECTS_FIELD = "effs";
    public final static String SOTERM_FIELD = "so";
    public final static String GENE_FIELD = "gene";
    public final static String ANNOTATION_FIELD = "annot";

    public final static Map<String, String> fieldsMap;

    static {
        fieldsMap = new HashMap<>();
        fieldsMap.put(CHROMOSOME_FIELD, "chromosome");
        fieldsMap.put(START_FIELD, "start");
        fieldsMap.put(END_FIELD, "end");
        fieldsMap.put(LENGTH_FIELD, "length");
        fieldsMap.put(REFERENCE_FIELD, "reference");
        fieldsMap.put(ALTERNATE_FIELD, "alternative");
        fieldsMap.put(ID_FIELD, "id");
        fieldsMap.put(HGVS_FIELD, "hgvs");
        fieldsMap.put(TYPE_FIELD, "type");
//        fields.put(NAME_FIELD, "name");
        fieldsMap.put(FILES_FIELD, "sourceEntries");
        fieldsMap.put(ANNOTATION_FIELD, "annotation");
    }

    private DBObjectToVariantSourceEntryConverter archivedVariantFileConverter;
    private DBObjectToVariantAnnotationConverter variantAnnotationConverter;

    /**
     * Create a converter between Variant and DBObject entities when there is 
     * no need to convert the files the variant was read from.
     */
    public DBObjectToVariantConverter() {
        this(null);
    }

    /**
     * Create a converter between Variant and DBObject entities. A converter for 
     * the files the variant was read from can be provided in case those 
     * should be processed during the conversion.
     * 
     * @param archivedVariantFileConverter The object used to convert the files
     */
    public DBObjectToVariantConverter(DBObjectToVariantSourceEntryConverter archivedVariantFileConverter) {
        this.archivedVariantFileConverter = archivedVariantFileConverter;
        this.variantAnnotationConverter = new DBObjectToVariantAnnotationConverter();
    }
    
    
    @Override
    public Variant convertToDataModelType(DBObject object) {
        Variant variant = new Variant((String) object.get(CHROMOSOME_FIELD), (int) object.get(START_FIELD), (int) object.get(END_FIELD), 
                (String) object.get(REFERENCE_FIELD), (String) object.get(ALTERNATE_FIELD));
        variant.setId((String) object.get(ID_FIELD));
        
        // Transform HGVS: List of map entries -> Map of lists
        BasicDBList mongoHgvs = (BasicDBList) object.get(HGVS_FIELD);
        for (Object o : mongoHgvs) {
            DBObject dbo = (DBObject) o;
            variant.addHgvs((String) dbo.get(TYPE_FIELD), (String) dbo.get(NAME_FIELD));
        }
        
        // Files
        if (archivedVariantFileConverter != null) {
            BasicDBList mongoFiles = (BasicDBList) object.get(FILES_FIELD);
            if (mongoFiles != null) {
                for (Object o : mongoFiles) {
                    DBObject dbo = (DBObject) o;
                    variant.addSourceEntry(archivedVariantFileConverter.convertToDataModelType(dbo));
                }
            }
        }

        // Annotations
        DBObject mongoAnnotation = (DBObject) object.get(ANNOTATION_FIELD);
        if (mongoAnnotation != null) {
            VariantAnnotation annotation = variantAnnotationConverter.convertToDataModelType(mongoAnnotation);
            variant.setAnnotation(annotation);
        }
        return variant;
    }

    @Override
    public DBObject convertToStorageType(Variant object) {
        // Attributes easily calculated
        BasicDBObject mongoVariant = new BasicDBObject("_id", buildStorageId(object))
                .append(ID_FIELD, object.getId())
                .append(TYPE_FIELD, object.getType().name())
                .append(CHROMOSOME_FIELD, object.getChromosome())
                .append(START_FIELD, object.getStart())
                .append(END_FIELD, object.getEnd())
                .append(LENGTH_FIELD, object.getLength())
                .append(REFERENCE_FIELD, object.getReference())
                .append(ALTERNATE_FIELD, object.getAlternate());
        
        // Internal fields used for query optimization (dictionary named "_at")
        BasicDBObject _at = new BasicDBObject();
        mongoVariant.append("_at", _at);
        
        // ChunkID (1k and 10k)
        String chunkSmall = object.getChromosome() + "_" + object.getStart() / VariantMongoDBWriter.CHUNK_SIZE_SMALL + "_" + VariantMongoDBWriter.CHUNK_SIZE_SMALL / 1000 + "k";
        String chunkBig = object.getChromosome() + "_" + object.getStart() / VariantMongoDBWriter.CHUNK_SIZE_BIG + "_" + VariantMongoDBWriter.CHUNK_SIZE_BIG / 1000 + "k";
        BasicDBList chunkIds = new BasicDBList(); chunkIds.add(chunkSmall); chunkIds.add(chunkBig);
        _at.append("chunkIds", chunkIds);
        
        // Transform HGVS: Map of lists -> List of map entries
        BasicDBList hgvs = new BasicDBList();
        for (Map.Entry<String, Set<String>> entry : object.getHgvs().entrySet()) {
            for (String value : entry.getValue()) {
                hgvs.add(new BasicDBObject(TYPE_FIELD, entry.getKey()).append(NAME_FIELD, value));
            }
        }
        mongoVariant.append(HGVS_FIELD, hgvs);
        
        // Files
        if (archivedVariantFileConverter != null) {
            BasicDBList mongoFiles = new BasicDBList();
            for (VariantSourceEntry archiveFile : object.getSourceEntries().values()) {
                mongoFiles.add(archivedVariantFileConverter.convertToStorageType(archiveFile));
            }
            mongoVariant.append(FILES_FIELD, mongoFiles);
        }
        
//        // Annotations
//        if (variantAnnotationConverter != null) {
//            if (object.getAnnotation() != null) {
//                DBObject annotation = variantAnnotationConverter.convertToStorageType(object.getAnnotation());
//                mongoVariant.append(ANNOTATION_FIELD, annotation);
//            }
//        }
        return mongoVariant;
    }

    public String buildStorageId(Variant v) {
        return buildStorageId(v.getChromosome(), v.getStart(), v.getReference(), v.getAlternate());
    }

    public String buildStorageId(String chromosome, int start, String reference, String alternate) {
        StringBuilder builder = new StringBuilder(chromosome);
        builder.append("_");
        builder.append(start);
        builder.append("_");
        if (reference.length() < Variant.SV_THRESHOLD) {
            builder.append(reference);
        } else {
            builder.append(new String(CryptoUtils.encryptSha1(reference)));
        }
        
        builder.append("_");
        
        if (alternate.length() < Variant.SV_THRESHOLD) {
            builder.append(alternate);
        } else {
            builder.append(new String(CryptoUtils.encryptSha1(alternate)));
        }
            
        return builder.toString();
    }
}
