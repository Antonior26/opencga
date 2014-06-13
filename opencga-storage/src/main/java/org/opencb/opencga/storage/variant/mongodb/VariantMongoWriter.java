package org.opencb.opencga.storage.variant.mongodb;

import com.mongodb.*;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.opencb.biodata.models.feature.Genotype;
import org.opencb.biodata.models.variant.ArchivedVariantFile;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.VariantSource;
import org.opencb.biodata.models.variant.effect.VariantEffect;
import org.opencb.biodata.models.variant.stats.VariantGlobalStats;
import org.opencb.biodata.models.variant.stats.VariantStats;
import org.opencb.commons.utils.CryptoUtils;
import org.opencb.opencga.lib.auth.MongoCredentials;
import org.opencb.opencga.storage.variant.VariantDBWriter;

/**
 * @author Alejandro Aleman Ramos <aaleman@cipf.es>
 * @author Cristina Yenyxe Gonzalez Garcia <cyenyxe@ebi.ac.uk>
 */
public class VariantMongoWriter extends VariantDBWriter {

    public static final int CHUNK_SIZE_SMALL = 1000;
    public static final int CHUNK_SIZE_BIG = 10000;
    
    private VariantSource file;

    private MongoClient mongoClient;
    private DB db;
    
    private String filesCollectionName;
    private String variantsCollectionName;
    private DBCollection filesCollection;
    private DBCollection variantsCollection;
    
    private Map<String, DBObject> mongoMap;
    private Map<String, DBObject> mongoFileMap;

    private MongoCredentials credentials;

    private boolean includeStats;
    private boolean includeEffect;
    private boolean includeSamples;

    private List<String> samples;
    private Map<String, Integer> conseqTypes;
    
    private DBObjectToVariantConverter variantConverter;
    private DBObjectToVariantStatsConverter statsConverter;
    private DBObjectToVariantSourceConverter sourceConverter;
    private DBObjectToArchivedVariantFileConverter archivedVariantFileConverter;
    
    private long numVariantsWritten;
    
    public VariantMongoWriter(VariantSource source, MongoCredentials credentials) {
        this(source, credentials, "variants", "files");
    }
    
    public VariantMongoWriter(VariantSource source, MongoCredentials credentials, String variantsCollection, String filesCollection) {
        this(source, credentials, variantsCollection, filesCollection, false, false, false);
    }

    public VariantMongoWriter(VariantSource source, MongoCredentials credentials, String variantsCollection, String filesCollection,
            boolean includeSamples, boolean includeStats, boolean includeEffect) {
        if (credentials == null) {
            throw new IllegalArgumentException("Credentials for accessing the database must be specified");
        }
        this.file = source;
        this.credentials = credentials;
        this.filesCollectionName = filesCollection;
        this.variantsCollectionName = variantsCollection;
        
        this.mongoMap = new HashMap<>();
        this.mongoFileMap = new HashMap<>();

        this.includeSamples = includeSamples;
        this.includeStats = includeStats;
        this.includeEffect = includeEffect;
        
        conseqTypes = new LinkedHashMap<>();
        samples = new ArrayList<>();
        
        sourceConverter = new DBObjectToVariantSourceConverter();
        statsConverter = new DBObjectToVariantStatsConverter();
        archivedVariantFileConverter = new DBObjectToArchivedVariantFileConverter(
                this.includeSamples ? samples : null,
                this.includeStats ? statsConverter : null);
        variantConverter = new DBObjectToVariantConverter();
        
        numVariantsWritten = 0;
    }

    @Override
    public boolean open() {
        try {
            // Mongo configuration
            ServerAddress address = new ServerAddress(credentials.getMongoHost(), credentials.getMongoPort());
            if (credentials.getMongoCredentials() != null) {
                mongoClient = new MongoClient(address, Arrays.asList(credentials.getMongoCredentials()));
            } else {
                mongoClient = new MongoClient(address);
            }
            db = mongoClient.getDB(credentials.getMongoDbName());
        } catch (UnknownHostException ex) {
            Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }

        return db != null;
    }

    @Override
    public boolean pre() {
        // Mongo collection creation
        filesCollection = db.getCollection(filesCollectionName);
        variantsCollection = db.getCollection(variantsCollectionName);

        return variantsCollection != null && filesCollection != null;
    }

    @Override
    public boolean write(Variant variant) {
        return write(Arrays.asList(variant));
    }

    @Override
    public boolean write(List<Variant> data) {
        buildBatchRaw(data);
//        if (this.includeStats) {
//            buildStatsRaw(data);
//        }
        if (this.includeEffect) {
            buildEffectRaw(data);
        }
        buildBatchIndex(data);
        return writeBatch(data);
    }

    @Override
    protected boolean buildBatchRaw(List<Variant> data) {
        for (Variant v : data) {
            // Check if this variant is already stored
//            String rowkey = buildRowkey(v);
            String rowkey = variantConverter.buildStorageId(v);
            DBObject mongoVariant = new BasicDBObject("_id", rowkey);

            if (variantsCollection.count(mongoVariant) == 0) {
//                mongoVariant = getVariantDBObject(v, rowkey);
                mongoVariant = variantConverter.convertToStorageType(v);
            } /*else {
                System.out.println("Variant " + v.getChromosome() + ":" + v.getStart() + "-" + v.getEnd() + " already found");
            }*/
            
            BasicDBList mongoFiles = new BasicDBList();
            for (ArchivedVariantFile archiveFile : v.getFiles().values()) {
                if (!archiveFile.getFileId().equals(file.getFileId())) {
                    continue;
                }
                
                if (this.includeSamples && samples.isEmpty() && archiveFile.getSamplesData().size() > 0) {
                    // First time a variant is loaded, the list of samples is populated. 
                    // This guarantees that samples are loaded only once to keep order among variants
                    samples.addAll(archiveFile.getSampleNames());
                }
                
                DBObject mongoFile = archivedVariantFileConverter.convertToStorageType(archiveFile);
//                BasicDBObject mongoFile = new BasicDBObject("fileId", archiveFile.getFileId()).append("studyId", archiveFile.getStudyId());
//
//                // Attributes
//                if (archiveFile.getAttributes().size() > 0) {
//                    BasicDBObject attrs = null;
//                    for (Map.Entry<String, String> entry : archiveFile.getAttributes().entrySet()) {
//                        if (attrs == null) {
//                            attrs = new BasicDBObject(entry.getKey(), entry.getValue());
//                        } else {
//                            attrs.append(entry.getKey(), entry.getValue());
//                        }
//                    }
//
//                    if (attrs != null) {
//                        mongoFile.put("attributes", attrs);
//                    }
//                }
//
//                // Samples
//                if (this.includeSamples && archiveFile.getSamplesData().size() > 0) {
//                    mongoFile.append("format", archiveFile.getFormat()); // Useless field if genotypeCodes are not stored
//                    
//                    BasicDBList genotypeCodes = new BasicDBList();
//                    for (String sampleName : samples) {
//                        String genotype = archiveFile.getSampleData(sampleName, "GT");
//                        if (genotype != null) {
//                            genotypeCodes.add(new Genotype(genotype).encode());
//                        }
//                    }
//                    mongoFile.put("samples", genotypeCodes);
//                }

                mongoFiles.add(mongoFile);
                mongoFileMap.put(rowkey + "_" + archiveFile.getFileId(), mongoFile);
            }
            
            mongoVariant.put("files", mongoFiles);
            mongoMap.put(rowkey, mongoVariant);
        }

        return true;
    }

    private BasicDBObject getVariantDBObject(Variant v, String rowkey) {
        // Attributes easily calculated
        BasicDBObject object = new BasicDBObject("_id", rowkey).append("id", v.getId()).append("type", v.getType().name());
        object.append("chr", v.getChromosome()).append("start", v.getStart()).append("end", v.getStart());
        object.append("length", v.getLength()).append("ref", v.getReference()).append("alt", v.getAlternate());
        
        // Internal fields used for query optimization (dictionary named "_at")
        BasicDBObject _at = new BasicDBObject();
        object.append("_at", _at);
        
        // ChunkID (1k and 10k)
        String chunkSmall = v.getChromosome() + "_" + v.getStart() / CHUNK_SIZE_SMALL + "_" + CHUNK_SIZE_SMALL / 1000 + "k";
        String chunkBig = v.getChromosome() + "_" + v.getStart() / CHUNK_SIZE_BIG + "_" + CHUNK_SIZE_BIG / 1000 + "k";
        BasicDBList chunkIds = new BasicDBList(); chunkIds.add(chunkSmall); chunkIds.add(chunkBig);
        _at.append("chunkIds", chunkIds);
        
        // Transform HGVS: Map of lists -> List of map entries
        BasicDBList hgvs = new BasicDBList();
        for (Map.Entry<String, List<String>> entry : v.getHgvs().entrySet()) {
            for (String value : entry.getValue()) {
                hgvs.add(new BasicDBObject("type", entry.getKey()).append("name", value));
            }
        }
        object.append("hgvs", hgvs);
        
        return object;
    }
    
    @Override
    protected boolean buildStatsRaw(List<Variant> data) {
        for (Variant v : data) {
            for (ArchivedVariantFile archiveFile : v.getFiles().values()) {
                VariantStats vs = archiveFile.getStats();
                if (vs != null) {
//                    BasicDBObject mongoFile = mongoFileMap.get(buildRowkey(v) + "_" + archiveFile.getFileId());
                    DBObject mongoFile = mongoFileMap.get(variantConverter.buildStorageId(v) + "_" + archiveFile.getFileId());
                    mongoFile.put("stats", statsConverter.convertToStorageType(vs));
                }
                
//                if (vs == null) {
//                    continue;
//                }
//
//                // Generate genotype counts
//                BasicDBObject genotypes = new BasicDBObject();
//
//                for (Map.Entry<Genotype, Integer> g : vs.getGenotypesCount().entrySet()) {
//                    genotypes.append(g.getKey().toString(), g.getValue());
//                }
//
//                BasicDBObject mongoStats = new BasicDBObject("maf", vs.getMaf());
//                mongoStats.append("mgf", vs.getMgf());
//                mongoStats.append("alleleMaf", vs.getMafAllele());
//                mongoStats.append("genotypeMaf", vs.getMgfGenotype());
//                mongoStats.append("missAllele", vs.getMissingAlleles());
//                mongoStats.append("missGenotypes", vs.getMissingGenotypes());
//                mongoStats.append("mendelErr", vs.getMendelianErrors());
////                mongoStats.append("casesPercentDominant", vs.getCasesPercentDominant());
////                mongoStats.append("controlsPercentDominant", vs.getControlsPercentDominant());
////                mongoStats.append("casesPercentRecessive", vs.getCasesPercentRecessive());
////                mongoStats.append("controlsPercentRecessive", vs.getControlsPercentRecessive());
//                mongoStats.append("genotypeCount", genotypes);
//
//                BasicDBObject mongoFile = mongoFileMap.get(buildStorageId(v) + "_" + archiveFile.getFileId());
//                mongoFile.put("stats", mongStats);
            }
        }

        return true;
    }

    @Override
    protected boolean buildEffectRaw(List<Variant> variants) {
        for (Variant v : variants) {
//            BasicDBObject mongoVariant = mongoMap.get(buildRowkey(v));
            DBObject mongoVariant = mongoMap.get(variantConverter.buildStorageId(v));

            if (!mongoVariant.containsField("chr")) {
                // TODO It means that the same position was already found in this file, so __for now__ it won't be processed again
                continue;
            }

            Set<String> genesSet = new HashSet<>();
            Set<String> soSet = new HashSet<>();
            
            // Add effects to file
            if (!v.getEffect().isEmpty()) {
                Set<BasicDBObject> effectsSet = new HashSet<>();

                for (VariantEffect effect : v.getEffect()) {
                    BasicDBObject object = getVariantEffectDBObject(effect);
                    effectsSet.add(object);
                    addConsequenceType(effect.getConsequenceTypeObo());
                    
                    soSet.add(object.get("so").toString());
                    if (object.containsField("geneName")) {
                        genesSet.add(object.get("geneName").toString());
                    }
                }
                
                BasicDBList effectsList = new BasicDBList();
                effectsList.addAll(effectsSet);
                mongoVariant.put("effects", effectsList);
            }
            
            // Add gene fields directly to the variant, for query optimization purposes
            BasicDBObject _at = (BasicDBObject) mongoVariant.get("_at");
            if (!genesSet.isEmpty()) {
                BasicDBList genesList = new BasicDBList(); genesList.addAll(genesSet);
                _at.append("gn", genesList);
            }
            if (!soSet.isEmpty()) {
                BasicDBList soList = new BasicDBList(); soList.addAll(soSet);
                _at.append("ct", soList);
            }
        }

        return false;
    }

    private BasicDBObject getVariantEffectDBObject(VariantEffect effect) {
        BasicDBObject object = new BasicDBObject("so", effect.getConsequenceTypeObo());
        object.append("featureId", effect.getFeatureId());
        if (effect.getGeneName() != null && !effect.getGeneName().isEmpty()) {
            object.append("geneName", effect.getGeneName());
        }
        return object;
    }
    
    @Override
    protected boolean buildBatchIndex(List<Variant> data) {
        variantsCollection.ensureIndex(new BasicDBObject("files.studyId", 1).append("files.fileId", 1), "studyAndFile");
        variantsCollection.ensureIndex(new BasicDBObject("_at.chunkIds", 1));
        variantsCollection.ensureIndex(new BasicDBObject("_at.gn", 1));
        variantsCollection.ensureIndex(new BasicDBObject("_at.ct", 1));
        variantsCollection.ensureIndex(new BasicDBObject("id", 1));
        variantsCollection.ensureIndex(new BasicDBObject("chr", 1));
        return true;
    }

    @Override
    protected boolean writeBatch(List<Variant> batch) {
        for (Variant v : batch) {
//            String rowkey = buildRowkey(v);
            String rowkey = variantConverter.buildStorageId(v);
            DBObject mongoVariant = mongoMap.get(rowkey);
            DBObject query = new BasicDBObject("_id", rowkey);
            WriteResult wr;
            
            if (mongoVariant.containsField("chr")) {
                // Was fully built in this run because it didn't exist, and must be inserted
                try {
                    wr = variantsCollection.insert(mongoVariant);
                    if (!wr.getLastError().ok()) {
                        // TODO If not correct, retry?
                        Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, wr.getError(), wr.getLastError());
                    }
                } catch(MongoInternalException ex) {
                    System.out.println(v);
                    Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, v.getChromosome() + ":" + v.getStart(), ex);
                } catch(MongoException.DuplicateKey ex) {
                    Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.WARNING, 
                            "Variant already existed: {0}:{1}", new Object[]{v.getChromosome(), v.getStart()});
                }
                
            } else { // It existed previously, was not fully built in this run and only files need to be updated
                // TODO How to do this efficiently, inserting all files at once?
                for (ArchivedVariantFile archiveFile : v.getFiles().values()) {
                    DBObject mongoFile = mongoFileMap.get(rowkey + "_" + archiveFile.getFileId());
//                    BasicDBObject changes = new BasicDBObject().append("$push", new BasicDBObject("files", mongoFile));
                    BasicDBObject changes = new BasicDBObject().append("$addToSet", new BasicDBObject("files", mongoFile));
                    
                    wr = variantsCollection.update(query, changes, true, false);
                    if (!wr.getLastError().ok()) {
                        // TODO If not correct, retry?
                        Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, wr.getError(), wr.getLastError());
                    }
                }
            }
            
        }

        mongoMap.clear();
        mongoFileMap.clear();

        numVariantsWritten += batch.size();
        Variant lastVariantInBatch = batch.get(batch.size()-1);
        Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.INFO, "{0}\tvariants written upto position {1}:{2}", 
                new Object[]{numVariantsWritten, lastVariantInBatch.getChromosome(), lastVariantInBatch.getStart()});
        
        return true;
    }

    private boolean writeSourceSummary(VariantSource source) {
        DBObject studyMongo = sourceConverter.convertToStorageType(source);
//        BasicDBObject studyMongo = new BasicDBObject("fileName", source.getFileName())
//                .append("fileId", source.getFileId())
//                .append("studyName", source.getStudyName())
//                .append("studyId", source.getStudyId())
//                .append("date", Calendar.getInstance().getTime())
//                .append("samples", source.getSamplesPosition());
//
//        BasicDBObject cts = new BasicDBObject();
//
//        for (Map.Entry<String, Integer> entry : conseqTypes.entrySet()) {
//            cts.append(entry.getKey(), entry.getValue());
//        }
//
//        VariantGlobalStats global = source.getStats();
//        if (global != null) {
//            DBObject globalStats = new BasicDBObject("samplesCount", global.getSamplesCount())
//                    .append("variantsCount", global.getVariantsCount())
//                    .append("snpCount", global.getSnpsCount())
//                    .append("indelCount", global.getIndelsCount())
//                    .append("passCount", global.getPassCount())
//                    .append("transitionsCount", global.getTransitionsCount())
//                    .append("transversionsCount", global.getTransversionsCount())
////                    .append("biallelicsCount", global.getBiallelicsCount())
////                    .append("multiallelicsCount", global.getMultiallelicsCount())
//                    .append("accumulatedQuality", global.getAccumulatedQuality())
//                    .append("meanQuality", (float) global.getAccumulatedQuality() / global.getVariantsCount())
//                    .append("consequenceTypes", cts);
//
//            studyMongo = studyMongo.append("globalStats", globalStats);
//        } else {
//            // TODO Notify?
//            studyMongo.append("globalStats", new BasicDBObject("consequenceTypes", cts));
//
//        }
//
//        // TODO Save pedigree information
//        Map<String, String> meta = source.getMetadata();
//        DBObject metadataMongo = new BasicDBObjectBuilder()
//                .add("header", meta.get("variantFileHeader"))
//                .get();
//        studyMongo = studyMongo.append("metadata", metadataMongo);

        DBObject query = new BasicDBObject("fileName", source.getFileName());
        WriteResult wr = filesCollection.update(query, studyMongo, true, false);

        return wr.getLastError().ok(); // TODO Is this a proper return statement?
    }

    @Override
    public boolean post() {
        writeSourceSummary(file);
        return true;
    }

    @Override
    public boolean close() {
        mongoClient.close();
        return true;
    }

    private void addConsequenceType(String ct) {
        int ctCount = conseqTypes.containsKey(ct) ? conseqTypes.get(ct) : 1;
        conseqTypes.put(ct, ctCount);
    }

//    private String buildRowkey(Variant v) {
//        StringBuilder builder = new StringBuilder(v.getChromosome());
//        builder.append("_");
//        builder.append(v.getStart());
//        builder.append("_");
//        if (v.getReference().length() < Variant.SV_THRESHOLD) {
//            builder.append(v.getReference());
//        } else {
//            builder.append(new String(CryptoUtils.encryptSha1(v.getReference())));
//        }
//        
//        builder.append("_");
//        
//        if (v.getAlternate().length() < Variant.SV_THRESHOLD) {
//            builder.append(v.getAlternate());
//        } else {
//            builder.append(new String(CryptoUtils.encryptSha1(v.getAlternate())));
//        }
//            
//        return builder.toString();
//    }
    
    @Override
    public final void includeStats(boolean b) {
        this.includeStats = b;
    }

    @Override
    public final void includeSamples(boolean b) {
        this.includeSamples = b;
    }

    @Override
    public final void includeEffect(boolean b) {
        this.includeEffect = b;
    }

}
