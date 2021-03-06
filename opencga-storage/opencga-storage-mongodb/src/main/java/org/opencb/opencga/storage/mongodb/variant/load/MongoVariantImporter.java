package org.opencb.opencga.storage.mongodb.variant.load;

import com.mongodb.bulk.BulkWriteResult;
import org.bson.Document;
import org.opencb.biodata.formats.variant.io.VariantReader;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.FileEntry;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.mongodb.MongoDBCollection;
import org.opencb.commons.io.DataWriter;
import org.opencb.commons.run.ParallelTaskRunner;
import org.opencb.opencga.core.common.ProgressLogger;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.metadata.ExportMetadata;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.variant.io.VariantImporter;
import org.opencb.opencga.storage.core.variant.io.avro.VariantAvroReader;
import org.opencb.opencga.storage.mongodb.variant.adaptors.VariantMongoDBAdaptor;
import org.opencb.opencga.storage.mongodb.variant.converters.DocumentToSamplesConverter;
import org.opencb.opencga.storage.mongodb.variant.converters.DocumentToStudyVariantEntryConverter;
import org.opencb.opencga.storage.mongodb.variant.converters.DocumentToVariantConverter;
import org.opencb.opencga.storage.mongodb.variant.converters.DocumentToVariantStatsConverter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Created on 07/12/16.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class MongoVariantImporter extends VariantImporter {

    private final MongoDBCollection variantsCollection;

    public MongoVariantImporter(VariantMongoDBAdaptor dbAdaptor) {
        super(dbAdaptor);
        this.variantsCollection = dbAdaptor.getVariantsCollection();
    }


    @Override
    public void importData(URI inputUri, ExportMetadata exportMetadata) throws StorageEngineException, IOException {

        Path input = Paths.get(inputUri.getPath());

//        VariantReader variantReader = VariantReaderUtils.getVariantReader(input, null);
        //TODO: Read returned samples from Metadata
        Map<String, LinkedHashMap<String, Integer>> samplesPositions = new HashMap<>();
        for (StudyConfiguration sc : exportMetadata.getStudies()) {
            LinkedHashMap<String, Integer> map = StudyConfiguration.getSortedIndexedSamplesPosition(sc);
//            LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
            samplesPositions.put(sc.getStudyName(), map);
            samplesPositions.put(String.valueOf(sc.getStudyId()), map);
        }
        VariantReader variantReader = new VariantAvroReader(input.toAbsolutePath().toFile(), samplesPositions);

        ProgressLogger progressLogger = new ProgressLogger("Loaded variants");
        ParallelTaskRunner.Task<Variant, Document> converterTask =
                new VariantToDocumentConverter(exportMetadata.getStudies(), progressLogger);

        DataWriter<Document> writer = new MongoDBVariantDocumentDBWriter(variantsCollection);

        ParallelTaskRunner.Config config = ParallelTaskRunner.Config.builder().setNumTasks(5).setSorted(false).setBatchSize(200).build();

        ParallelTaskRunner<Variant, Document> ptr = new ParallelTaskRunner<>(variantReader, converterTask, writer, config);

        try {
            ptr.run();
        } catch (ExecutionException e) {
            throw new StorageEngineException("", e);
        }
    }

    /**
     * Simple Task for converting Variants into MongoDB Documents.
     */
    private static class VariantToDocumentConverter implements ParallelTaskRunner.Task<Variant, Document> {
        private final DocumentToVariantConverter variantConverter;
        private final Map<String, String> studies;
        private ProgressLogger progressLogger;

        VariantToDocumentConverter(List<StudyConfiguration> studies, ProgressLogger progressLogger) {
            DocumentToSamplesConverter samplesConverter = new DocumentToSamplesConverter(studies);
            DocumentToStudyVariantEntryConverter studyConverter = new DocumentToStudyVariantEntryConverter(false, samplesConverter);
            DocumentToVariantStatsConverter statsConverter = new DocumentToVariantStatsConverter(studies);
            variantConverter = new DocumentToVariantConverter(studyConverter, statsConverter);
            this.studies = studies.stream()
                    .collect(Collectors.toMap(
                            StudyConfiguration::getStudyName,
                            (studyConfiguration) -> String.valueOf(studyConfiguration.getStudyId())));
            this.progressLogger = progressLogger;
        }

        @Override
        public List<Document> apply(List<Variant> batch) {
            progressLogger.increment(batch.size(), () -> "up to position " + batch.get(batch.size() - 1));
            return batch.stream().map(variant -> {
                for (StudyEntry studyEntry : variant.getStudies()) {
                    studyEntry.setStudyId(studies.getOrDefault(studyEntry.getStudyId(), studyEntry.getStudyId()));
                    for (FileEntry file : studyEntry.getFiles()) {
                        if (file.getFileId().isEmpty()) {
                            file.setFileId("-1");
                        }
                    }
                    if (studyEntry.getSamplesData() == null) {
                        studyEntry.setSamplesData(Collections.emptyList());
                    }
                }
                return variant;
            }).map(variantConverter::convertToStorageType).collect(Collectors.toList());
        }
    }

    /**
     * Simple DataWriter for importing data into MongoDB.
     */
    private static class MongoDBVariantDocumentDBWriter implements DataWriter<Document> {

        private final MongoDBCollection collection;
        private int insertedCount = 0;

        MongoDBVariantDocumentDBWriter(MongoDBCollection collection) {
            this.collection = collection;
        }

        @Override
        public boolean write(List<Document> batch) {

            BulkWriteResult result = collection.insert(batch, QueryOptions.empty()).first();
            insertedCount += result.getInsertedCount();

            return true;
        }

        @Override
        public boolean post() {
            VariantMongoDBAdaptor.createIndexes(new QueryOptions(), collection);
            return true;
        }

        public int getInsertedCount() {
            return insertedCount;
        }
    }
}
