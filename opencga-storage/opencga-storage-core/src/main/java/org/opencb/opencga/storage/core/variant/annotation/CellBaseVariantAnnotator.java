package org.opencb.opencga.storage.core.variant.annotation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.annotation.ConsequenceType;
import org.opencb.biodata.models.variant.annotation.VariantAnnotation;
import org.opencb.biodata.models.variation.GenomicVariant;
import org.opencb.cellbase.core.client.CellBaseClient;
import org.opencb.cellbase.core.common.core.CellbaseConfiguration;
import org.opencb.cellbase.core.lib.DBAdaptorFactory;
import org.opencb.cellbase.core.lib.api.variation.VariantAnnotationDBAdaptor;
import org.opencb.cellbase.core.lib.api.variation.VariationDBAdaptor;
import org.opencb.cellbase.lib.mongodb.db.MongoDBAdaptorFactory;
import org.opencb.datastore.core.QueryOptions;
import org.opencb.datastore.core.QueryResponse;
import org.opencb.datastore.core.QueryResult;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Created by jacobo on 9/01/15.
 */
public class CellBaseVariantAnnotator implements VariantAnnotator {



    private final JsonFactory factory;
    private VariantAnnotationDBAdaptor variantAnnotationDBAdaptor;
    private CellBaseClient cellBaseClient;
    private ObjectMapper jsonObjectMapper;
    private VariationDBAdaptor variationDBAdaptor;
    private DBAdaptorFactory dbAdaptorFactory;

    protected static Logger logger = LoggerFactory.getLogger(CellBaseVariantAnnotator.class);

    public CellBaseVariantAnnotator() {
        this.factory = new JsonFactory();
        this.jsonObjectMapper = new ObjectMapper(factory);
        this.dbAdaptorFactory = null;
        this.cellBaseClient = null;
    }

    public CellBaseVariantAnnotator(CellbaseConfiguration cellbaseConfiguration, String cellbaseSpecies, String cellbaseAssembly) {
        this();
        /**
         * Connecting to CellBase database
         */
        dbAdaptorFactory = new MongoDBAdaptorFactory(cellbaseConfiguration);
        variantAnnotationDBAdaptor = dbAdaptorFactory.getGenomicVariantAnnotationDBAdaptor(cellbaseSpecies, cellbaseAssembly);
        variationDBAdaptor = dbAdaptorFactory.getVariationDBAdaptor(cellbaseSpecies, cellbaseAssembly);
    }

    public CellBaseVariantAnnotator(CellBaseClient cellBaseClient) {
        this();
        this.cellBaseClient = cellBaseClient;
        if(cellBaseClient == null) {
            throw new NullPointerException("CellBaseClient can not be null");
        }
    }

    /////// CREATE ANNOTATION

    @Override
    public URI createAnnotation(VariantDBAdaptor variantDBAdaptor, Path outDir, String fileName, QueryOptions options)
            throws IOException {
        if(cellBaseClient == null && dbAdaptorFactory == null) {
            throw new IllegalStateException("Cant createAnnotation without a CellBase source (DBAdaptorFactory or a CellBaseClient)");
        }

        URI fileUri;
        Path path = Paths.get(outDir != null? outDir.toString() : "/tmp" ,fileName + ".annot.json.gz");
        try {
            fileUri = new URI("file", path.toString(), null);
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return null;
        }

        /** Open output stream **/
        OutputStream outputStream;
        outputStream = new FileOutputStream(path.toFile());
        if(options != null && options.getBoolean("gzip", true)) {
            outputStream = new GZIPOutputStream(outputStream);
        }

        /** Innitialice Json serializer**/
        ObjectWriter writer = jsonObjectMapper.writerWithType(VariantAnnotation.class);

        /** Getting iterator from OpenCGA Variant database. **/
        QueryOptions iteratorQueryOptions = new QueryOptions();
//        ArrayList<String> exclude = new ArrayList<>();
//        iteratorQueryOptions.add("exclude", exclude);
        if(options != null) { //Parse query options
            if (!options.getBoolean(VariantAnnotationManager.ANNOTATE_ALL, false)) {
                iteratorQueryOptions.put("annotationExists", false);
            }
        }

        Variant variant = null;
        int batchSize = options.getInt(VariantAnnotationManager.BATCH_SIZE, 100);
        List<GenomicVariant> genomicVariantList = new ArrayList<>(batchSize);
        Iterator<Variant> iterator = variantDBAdaptor.iterator(iteratorQueryOptions);
        while(iterator.hasNext()) {
            variant = iterator.next();

            // If Variant is SV some work is needed
            if(variant.getAlternate().length() + variant.getReference().length() > Variant.SV_THRESHOLD*2) {       //TODO: Manage SV variants
//                logger.info("Skip variant! {}", genomicVariant);
                logger.info("Skip variant! {}", variant.getChromosome() + ":" +
                                variant.getStart() + ":" +
                                (variant.getReference().length() > 10? variant.getReference().substring(0,10) + "...[" + variant.getReference().length() + "]" : variant.getReference()) + ":" +
                                (variant.getAlternate().length() > 10? variant.getAlternate().substring(0,10) + "...[" + variant.getAlternate().length() + "]" : variant.getAlternate())
                );
                logger.debug("Skip variant! {}", variant);
            } else {
                GenomicVariant genomicVariant = new GenomicVariant(variant.getChromosome(), variant.getStart(), variant.getReference(), variant.getAlternate());
                genomicVariantList.add(genomicVariant);
            }

            if(genomicVariantList.size() == batchSize || !iterator.hasNext()) {
                List<VariantAnnotation> variantAnnotationList;
                if(cellBaseClient != null) {
                    variantAnnotationList = getVariantAnnotationsREST(genomicVariantList);
                } else {
                    variantAnnotationList = getVariantAnnotationsDbAdaptor(genomicVariantList);
                }
                for (VariantAnnotation variantAnnotation : variantAnnotationList) {
                    outputStream.write(writer.writeValueAsString(variantAnnotation).getBytes());
                    outputStream.write('\n');
                }
                genomicVariantList.clear();
            }
        }

        outputStream.close();
        return fileUri;
    }

    /////// CREATE ANNOTATION - AUX METHODS

    private List<VariantAnnotation> getVariantAnnotationsREST(List<GenomicVariant> genomicVariantList) throws IOException {
        QueryResponse<QueryResult<VariantAnnotation>> queryResponse;
        List<String> genomicVariantStringList = new ArrayList<>(genomicVariantList.size());
        for (GenomicVariant genomicVariant : genomicVariantList) {
            genomicVariantStringList.add(genomicVariant.toString());
        }

        queryResponse = cellBaseClient.get(
                CellBaseClient.Category.genomic,
                CellBaseClient.SubCategory.variant,
                genomicVariantStringList,
                CellBaseClient.Resource.fullAnnotation,
                null);
        if(queryResponse == null) {
            logger.error("CellBase REST error. Returned null. Skipping variants. {}", cellBaseClient.getLastQuery());
            return Collections.emptyList();
        }

        Collection<QueryResult<VariantAnnotation>> response = queryResponse.getResponse();
        if(response.size() != genomicVariantList.size()) {
            throw new IOException("QueryResult size != " + genomicVariantList.size() + ". " + queryResponse);
        }
        QueryResult<VariantAnnotation>[] queryResults = response.toArray(new QueryResult[1]);
        List<VariantAnnotation> variantAnnotationList = new ArrayList<>(genomicVariantList.size());
        for (QueryResult<VariantAnnotation> queryResult : queryResults) {
            variantAnnotationList.addAll(queryResult.getResult());
        }
        return variantAnnotationList;
    }

    private List<VariantAnnotation> getVariantAnnotationsDbAdaptor(List<GenomicVariant> genomicVariantList) throws IOException {
        org.opencb.cellbase.core.lib.dbquery.QueryOptions queryOptions = new org.opencb.cellbase.core.lib.dbquery.QueryOptions();

        List<VariantAnnotation> variantAnnotationList = new ArrayList<>(genomicVariantList.size());
        Map<String, List<ConsequenceType>> consequenceTypes = getConsequenceTypes(genomicVariantList, queryOptions);
        Map<String, String> variantIds = getVariantId(genomicVariantList, queryOptions);
        for (GenomicVariant genomicVariant : genomicVariantList) {
            VariantAnnotation variantAnnotation = new VariantAnnotation(
                    genomicVariant.getChromosome(),
                    genomicVariant.getPosition(),
                    genomicVariant.getPosition(),   //TODO: ¿?¿?
                    genomicVariant.getReference(),
                    genomicVariant.getAlternative());

            String key = genomicVariant.toString();
            variantAnnotation.setConsequenceTypes(consequenceTypes.get(key));
            variantAnnotation.setId(variantIds.get(key));

            variantAnnotationList.add(variantAnnotation);
        }
        return variantAnnotationList;
    }

    // FIXME To delete when available in cellbase
    private Map<String, List<ConsequenceType>> getConsequenceTypes(List<GenomicVariant> genomicVariants, org.opencb.cellbase.core.lib.dbquery.QueryOptions queryOptions) throws IOException {
        Map<String, List<ConsequenceType>> map = new HashMap<>(genomicVariants.size());

        List<org.opencb.cellbase.core.lib.dbquery.QueryResult> queryResultList =
                variantAnnotationDBAdaptor.getAllConsequenceTypesByVariantList(genomicVariants, queryOptions);
        for (org.opencb.cellbase.core.lib.dbquery.QueryResult queryResult : queryResultList) {
            Object result = queryResult.getResult();
            List list = result instanceof Collection ? new ArrayList((Collection) result) : Collections.singletonList(result);

            if(list.get(0) instanceof ConsequenceType) {
                map.put(queryResult.getId(), list);
            } else {
                throw new IOException("queryResult result : " + queryResult + " is not a ConsequenceType");
            }
        }
        return map;
    }

    // FIXME To delete when available in cellbase
    private Map<String, String> getVariantId(List<GenomicVariant> genomicVariant, org.opencb.cellbase.core.lib.dbquery.QueryOptions queryOptions) throws IOException {
        List<org.opencb.cellbase.core.lib.dbquery.QueryResult> variationQueryResultList =
                variationDBAdaptor.getIdByVariantList(genomicVariant, queryOptions);
        Map<String, String> map = new HashMap<>(genomicVariant.size());
        for (org.opencb.cellbase.core.lib.dbquery.QueryResult queryResult : variationQueryResultList) {
            map.put(queryResult.getId(), queryResult.getResult().toString());
        }
        return map;
    }


    /////// LOAD ANNOTATION

    @Override
    public void loadAnnotation(final VariantDBAdaptor variantDBAdaptor, final URI uri, QueryOptions options) throws IOException {

        final int batchSize = options.getInt(VariantAnnotationManager.BATCH_SIZE, 100);
        final int numConsumers = options.getInt(VariantAnnotationManager.NUM_WRITERS, 6);
        final BlockingQueue<VariantAnnotation> queue = new ArrayBlockingQueue<>(batchSize*numConsumers*2);
        final VariantAnnotation lastElement = new VariantAnnotation();

        Runnable producer = new Runnable() {
            @Override
            public void run() {
                try {
                    int readsCounter = 0;

                    /** Open input stream **/
                    InputStream inputStream;
                    inputStream = new FileInputStream(Paths.get(uri).toFile());
                    inputStream = new GZIPInputStream(inputStream);

                    /** Innitialice Json parse**/
                    JsonParser parser = factory.createParser(inputStream);

                    while (parser.nextToken() != null) {
                        VariantAnnotation variantAnnotation = parser.readValueAs(VariantAnnotation.class);
                        queue.put(variantAnnotation);
                        readsCounter++;
                        if(readsCounter%1000 == 0) {
                            logger.info("Element {}", readsCounter);
                        }
                    }
                    for (int i = 0; i < numConsumers; i++) {    //Add a lastElement marker. Consumers will stop reading when read this element.
                        queue.put(lastElement);
                    }
                    logger.debug("Put Last element. queue size = " + queue.size());
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };

        Runnable consumer = new Runnable() {
            @Override
            public void run() {
                try {
                    List<VariantAnnotation> batch = new ArrayList<>(batchSize);
                    VariantAnnotation elem = queue.take();
                    while (elem != lastElement) {
                        batch.add(elem);
                        if(batch.size() == batchSize) {
                            variantDBAdaptor.updateAnnotations(batch, new QueryOptions());
                            batch.clear();
                        }
                        elem = queue.take();
                    }
                    if(!batch.isEmpty()) { //Upload remaining elements
                        variantDBAdaptor.updateAnnotations(batch, new QueryOptions());
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };

        /** Create threads **/
        List<Thread> consumers = new ArrayList<>(numConsumers);
        Thread producerThread = new Thread(producer);
        for (int i = 0; i < numConsumers; i++) {
            consumers.add(new Thread(consumer));
        }

        /** Start **/
        producerThread.start();
        for (Thread consumerThread : consumers) {
            consumerThread.start();
        }

        /** Join **/
        try {
            producerThread.join();
            for (Thread consumerThread : consumers) {
                consumerThread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

//        while (parser.nextToken() != null) {
//            VariantAnnotation variantAnnotation = parser.readValueAs(VariantAnnotation.class);
////            System.out.println("variantAnnotation = " + variantAnnotation);
//            batch.add(variantAnnotation);
//            if(batch.size() == batchSize || parser.nextToken() == null) {
//                variantDBAdaptor.updateAnnotations(batch, new QueryOptions());
//                batch.clear();
//            }
//        }

    }
}