package org.opencb.opencga.storage.hadoop.variant;

import static org.opencb.opencga.storage.hadoop.variant.HadoopVariantStorageManager.HADOOP_LOAD_ARCHIVE;
import static org.opencb.opencga.storage.hadoop.variant.HadoopVariantStorageManager.HADOOP_LOAD_VARIANT;
import static org.opencb.opencga.storage.hadoop.variant.HadoopVariantStorageManager.HADOOP_LOAD_VARIANT_PENDING_FILES;
import static org.opencb.opencga.storage.hadoop.variant.HadoopVariantStorageManager.getTableName;
import htsjdk.tribble.readers.LineIterator;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderVersion;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import org.apache.commons.lang.NotImplementedException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.compress.Compression.Algorithm;
import org.opencb.biodata.formats.variant.vcf4.FullVcfCodec;
import org.opencb.biodata.models.variant.VariantAggregatedVcfFactory;
import org.opencb.biodata.models.variant.VariantSource;
import org.opencb.biodata.models.variant.VariantStudy;
import org.opencb.biodata.models.variant.VariantVcfFactory;
import org.opencb.biodata.models.variant.protobuf.VcfMeta;
import org.opencb.biodata.models.variant.protobuf.VcfSliceProtos.VcfSlice;
import org.opencb.biodata.tools.variant.stats.VariantGlobalStatsCalculator;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.io.DataWriter;
import org.opencb.commons.run.ParallelTaskRunner;
import org.opencb.opencga.storage.core.StudyConfiguration;
import org.opencb.opencga.storage.core.config.StorageConfiguration;
import org.opencb.opencga.storage.core.exceptions.StorageManagerException;
import org.opencb.opencga.storage.core.runner.StringDataReader;
import org.opencb.opencga.storage.core.variant.VariantStorageETL;
import org.opencb.opencga.storage.core.variant.VariantStorageManager;
import org.opencb.opencga.storage.core.variant.VariantStorageManager.Options;
import org.opencb.opencga.storage.core.variant.io.VariantReaderUtils;
import org.opencb.opencga.storage.hadoop.auth.HBaseCredentials;
import org.opencb.opencga.storage.hadoop.exceptions.StorageHadoopException;
import org.opencb.opencga.storage.hadoop.variant.archive.ArchiveDriver;
import org.opencb.opencga.storage.hadoop.variant.archive.ArchiveFileMetadataManager;
import org.opencb.opencga.storage.hadoop.variant.archive.ArchiveHelper;
import org.opencb.opencga.storage.hadoop.variant.archive.VariantHbasePutTask;
import org.opencb.opencga.storage.hadoop.variant.archive.VariantHbaseTransformTask;
import org.slf4j.LoggerFactory;

/**
 * @author Matthias Haimel mh719+git@cam.ac.uk
 *
 */
public class HadoopDirectVariantStorageETL extends VariantStorageETL {

    public static final String ARCHIVE_TABLE_PREFIX = "opencga_study_";

    protected MRExecutor mrExecutor = null;
    protected final VariantHadoopDBAdaptor dbAdaptor;
    protected final Configuration conf;
    protected final HBaseCredentials archiveTableCredentials;
    protected final HBaseCredentials variantsTableCredentials;

    /**
     * @param configuration {@link StorageConfiguration}
     * @param storageEngineId Id
     * @param dbAdaptor {@link VariantHadoopDBAdaptor}
     * @param mrExecutor {@link MRExecutor}
     * @param conf {@link Configuration}
     * @param archiveCredentials {@link HBaseCredentials}
     * @param variantReaderUtils {@link VariantReaderUtils}
     * @param options {@link ObjectMap}
     */
    public HadoopDirectVariantStorageETL(StorageConfiguration configuration, String storageEngineId, VariantHadoopDBAdaptor dbAdaptor,
            MRExecutor mrExecutor, Configuration conf, HBaseCredentials archiveCredentials, VariantReaderUtils variantReaderUtils,
            ObjectMap options) {
        super(configuration, storageEngineId, LoggerFactory.getLogger(HadoopDirectVariantStorageETL.class), dbAdaptor, variantReaderUtils,
                options);
        this.mrExecutor = mrExecutor;
        this.dbAdaptor = dbAdaptor;
        this.conf = new Configuration(conf);
        this.archiveTableCredentials = archiveCredentials;
        this.variantsTableCredentials = dbAdaptor == null ? null : dbAdaptor.getCredentials();
    }

    @Override
    public URI transform(URI inputUri, URI pedigreeUri, URI outputUri) throws StorageManagerException {
        return inputUri;
    }

    @Override
    protected VariantSource readVariantSource(URI input, ObjectMap options) throws StorageManagerException {
        return buildVariantSource(Paths.get(input.getPath()), options);
    }

    @Override
    public URI preLoad(URI input, URI output) throws StorageManagerException {
        StudyConfiguration studyConfiguration = getStudyConfiguration(options);
        if (studyConfiguration == null) {
            logger.info("Creating a new StudyConfiguration");
            int studyId = options.getInt(Options.STUDY_ID.key(), Options.STUDY_ID.defaultValue());
            String studyName = options.getString(Options.STUDY_NAME.key(), Options.STUDY_NAME.defaultValue());
            checkStudyId(studyId);
            studyConfiguration = new StudyConfiguration(studyId, studyName);
            options.put(Options.STUDY_CONFIGURATION.key(), studyConfiguration);
        }

        VariantSource source = readVariantSource(input, options);

        /*
         * Before load file, check and add fileName to the StudyConfiguration.
         * FileID and FileName is read from the VariantSource
         * If fileId is -1, read fileId from Options
         * Will fail if:
         *     fileId is not an integer
         *     fileId was already in the studyConfiguration.indexedFiles
         *     fileId was already in the studyConfiguration.fileIds with a different fileName
         *     fileName was already in the studyConfiguration.fileIds with a different fileId
         */

        int fileId;
        String fileName = source.getFileName();
        try {
            fileId = Integer.parseInt(source.getFileId());
        } catch (NumberFormatException e) {
            throw new StorageManagerException("FileId '" + source.getFileId() + "' is not an integer", e);
        }

        if (fileId < 0) {
            fileId = options.getInt(Options.FILE_ID.key(), Options.FILE_ID.defaultValue());
        } else {
            int fileIdFromParams = options.getInt(Options.FILE_ID.key(), Options.FILE_ID.defaultValue());
            if (fileIdFromParams >= 0) {
                if (fileIdFromParams != fileId) {
                    if (!options.getBoolean(Options.OVERRIDE_FILE_ID.key(), Options.OVERRIDE_FILE_ID.defaultValue())) {
                        throw new StorageManagerException("Wrong fileId! Unable to load using fileId: " + fileIdFromParams + ". "
                                + "The input file has fileId: " + fileId
                                + ". Use " + Options.OVERRIDE_FILE_ID.key() + " to ignore original fileId.");
                    } else {
                        //Override the fileId
                        fileId = fileIdFromParams;
                    }
                }
            }
        }

        if (studyConfiguration.getIndexedFiles().isEmpty()) {
            // First indexed file
            // Use the EXCLUDE_GENOTYPES value from CLI. Write in StudyConfiguration.attributes
            boolean excludeGenotypes = options.getBoolean(Options.EXCLUDE_GENOTYPES.key(), Options.EXCLUDE_GENOTYPES.defaultValue());
            studyConfiguration.getAttributes().put(Options.EXCLUDE_GENOTYPES.key(), excludeGenotypes);
        } else {
            // Not first indexed file
            // Use the EXCLUDE_GENOTYPES value from StudyConfiguration. Ignore CLI value
            boolean excludeGenotypes = studyConfiguration.getAttributes()
                    .getBoolean(Options.EXCLUDE_GENOTYPES.key(), Options.EXCLUDE_GENOTYPES.defaultValue());
            options.put(Options.EXCLUDE_GENOTYPES.key(), excludeGenotypes);
        }


        fileId = checkNewFile(studyConfiguration, fileId, fileName);
        options.put(Options.FILE_ID.key(), fileId);
        studyConfiguration.getFileIds().put(source.getFileName(), fileId);
//        studyConfiguration.getHeaders().put(fileId, source.getMetadata().get("variantFileHeader").toString()); // TODO laster

//        checkAndUpdateStudyConfiguration(studyConfiguration, fileId, source, options); // TODO ?
//        dbAdaptor.getStudyConfigurationManager().updateStudyConfiguration(studyConfiguration, null);
        options.put(Options.STUDY_CONFIGURATION.key(), studyConfiguration);

        boolean loadArch = options.getBoolean(HADOOP_LOAD_ARCHIVE);
        boolean loadVar = options.getBoolean(HADOOP_LOAD_VARIANT);

        if (!loadArch && !loadVar) {
            loadArch = true;
            loadVar = true;
            options.put(HADOOP_LOAD_ARCHIVE, loadArch);
            options.put(HADOOP_LOAD_VARIANT, loadVar);
        }

        logger.info("Try to set Snappy " + Compression.Algorithm.SNAPPY.getName());
        String compressName = conf.get(ArchiveDriver.CONFIG_ARCHIVE_TABLE_COMPRESSION, Compression.Algorithm.SNAPPY.getName());
        Algorithm compression = Compression.getCompressionAlgorithmByName(compressName);
        logger.info(String.format("Create table %s with %s %s", archiveTableCredentials.getTable(), compressName, compression));
        GenomeHelper genomeHelper = new GenomeHelper(this.conf);
        try (Connection con = ConnectionFactory.createConnection(this.conf)) {
            genomeHelper.getHBaseManager().createTableIfNeeded(con, archiveTableCredentials.getTable(), genomeHelper.getColumnFamily(),
                    compression);
//            ArchiveDriver.createArchiveTableIfNeeded(new GenomeHelper(this.conf), archiveTableCredentials.getTable());
        } catch (IOException e1) {
            throw new RuntimeException("Issue creating table " + archiveTableCredentials.getTable(), e1);
        }

        if (loadVar) {
            // Load into variant table
            // Update the studyConfiguration with data from the Archive Table.
            // Reads the VcfMeta documents, and populates the StudyConfiguration
            // if needed.
            // Obtain the list of pending files.


            boolean missingFilesDetected = false;

            Set<Integer> files = null;
            ArchiveFileMetadataManager fileMetadataManager;
            try {
                fileMetadataManager = dbAdaptor.getArchiveFileMetadataManager(getTableName(studyConfiguration.getStudyId()), options);
                files = fileMetadataManager.getLoadedFiles();
            } catch (IOException e) {
                throw new StorageHadoopException("Unable to read loaded files", e);
            }

            List<Integer> pendingFiles = new LinkedList<>();

            for (Integer loadedFileId : files) {
                VcfMeta meta = null;
                try {
                    meta = fileMetadataManager.getVcfMeta(loadedFileId, options).first();
                } catch (IOException e) {
                    throw new StorageHadoopException("Unable to read file VcfMeta for file : " + loadedFileId, e);
                }

                Integer fileId1 = Integer.parseInt(source.getFileId());
                if (!studyConfiguration.getFileIds().inverse().containsKey(fileId1)) {
                    checkNewFile(studyConfiguration, fileId1, source.getFileName());
                    studyConfiguration.getFileIds().put(source.getFileName(), fileId1);
                    studyConfiguration.getHeaders().put(fileId1, source.getMetadata().get("variantFileHeader").toString());
                    checkAndUpdateStudyConfiguration(studyConfiguration, fileId1, source, options);
                    missingFilesDetected = true;
                }
                if (!studyConfiguration.getIndexedFiles().contains(fileId1)) {
                    pendingFiles.add(fileId1);
                }
            }
            if (missingFilesDetected) {
                // getStudyConfigurationManager(options).updateStudyConfiguration(studyConfiguration,
                // null);
                dbAdaptor.getStudyConfigurationManager().updateStudyConfiguration(studyConfiguration, null);
            }

            if (!loadArch) {
                // If skip archive loading, input fileId must be already in
                // archiveTable, so "pending to be loaded"
                if (!pendingFiles.contains(fileId)) {
                    throw new StorageManagerException("File " + fileId + " is not loaded in archive table");
                }
            } else {
                // If don't skip archive, input fileId must not be pending,
                // because must not be in the archive table.
                if (pendingFiles.contains(fileId)) {
                    // set loadArch to false?
                    throw new StorageManagerException("File " + fileId + " is not loaded in archive table");
                } else {
                    pendingFiles.add(fileId);
                }
            }

            // If there are some given pending files, load only those files, not
            // all pending files
            List<Integer> givenPendingFiles = options.getAsIntegerList(HADOOP_LOAD_VARIANT_PENDING_FILES);
            if (!givenPendingFiles.isEmpty()) {
                for (Integer pendingFile : givenPendingFiles) {
                    if (!pendingFiles.contains(pendingFile)) {
                        throw new StorageManagerException("File " + fileId + " is not pending to be loaded in variant table");
                    }
                }
            } else {
                options.put(HADOOP_LOAD_VARIANT_PENDING_FILES, pendingFiles);
            }
        }
        return input;
    }

    /**
     * Read from VCF file, group by slice and insert into HBase table.
     * @param inputUri
     *            {@link URI}
     */
    @Override
    public URI load(URI inputUri) throws IOException, StorageManagerException {
        Path input = Paths.get(inputUri.getPath());

        int studyId = options.getInt(VariantStorageManager.Options.STUDY_ID.key());
//        int fileId = options.getInt(VariantStorageManager.Options.FILE_ID.key());

        ArchiveHelper.setChunkSize(conf, conf.getInt(ArchiveDriver.CONFIG_ARCHIVE_CHUNK_SIZE, ArchiveDriver.DEFAULT_CHUNK_SIZE));
        ArchiveHelper.setStudyId(conf, studyId);

        boolean loadArch = options.getBoolean(HADOOP_LOAD_ARCHIVE);
//        boolean loadVar = options.getBoolean(HADOOP_LOAD_VARIANT);
//        boolean includeStats = options.getBoolean(Options.INCLUDE_STATS.key(), false);

//        String table = archiveTableCredentials.getTable();
//        String hostAndPort = archiveTableCredentials.getHostAndPort();

        if (loadArch) {
            loadArch(input);
        }
        return inputUri;
    }

    private void loadArch(Path input) throws StorageManagerException {
        String table = archiveTableCredentials.getTable();
        String fileName = input.getFileName().toString();
        boolean includeSrc = false;
        String parser = options.getString("transform.parser", "htsjdk");
        StudyConfiguration studyConfiguration = getStudyConfiguration(options);
        Integer fileId;
        if (options.getBoolean(Options.ISOLATE_FILE_FROM_STUDY_CONFIGURATION.key(),
                Options.ISOLATE_FILE_FROM_STUDY_CONFIGURATION.defaultValue())) {
            fileId = Options.FILE_ID.defaultValue();
        } else {
            fileId = options.getInt(Options.FILE_ID.key());
        }
        VariantSource.Aggregation aggregation = options.get(Options.AGGREGATED_TYPE.key(), VariantSource.Aggregation.class,
                Options.AGGREGATED_TYPE.defaultValue());
        VariantStudy.StudyType type = options
                .get(Options.STUDY_TYPE.key(), VariantStudy.StudyType.class, Options.STUDY_TYPE.defaultValue());
        VariantSource source = new VariantSource(fileName, fileId.toString(), Integer.toString(studyConfiguration.getStudyId()),
                studyConfiguration.getStudyName(), type, aggregation);

        boolean generateReferenceBlocks = options.getBoolean(Options.GVCF.key(), false);

        int batchSize = options.getInt(Options.TRANSFORM_BATCH_SIZE.key(), Options.TRANSFORM_BATCH_SIZE.defaultValue());

        int numTasks = 1;
        int capacity = options.getInt("blockingQueueCapacity", numTasks * 2);

        final VariantVcfFactory factory;
        if (fileName.endsWith(".vcf") || fileName.endsWith(".vcf.gz") || fileName.endsWith(".vcf.snappy")) {
            if (VariantSource.Aggregation.NONE.equals(aggregation)) {
                factory = new VariantVcfFactory();
            } else {
                factory = new VariantAggregatedVcfFactory();
            }
        } else {
            throw new StorageManagerException(String.format("Variants input file format not supported: %s", fileName));
        }
        // PedigreeReader pedReader = null;c // TODO Pedigree
        // if (pedigree != null && pedigree.toFile().exists()) { //FIXME Add
        // "endsWith(".ped") ??
        // pedReader = new PedigreePedReader(pedigree.toString());
        // }

        // Read VariantSource
        source = VariantStorageManager.readVariantSource(input, source);

        // Reader
        StringDataReader dataReader = new StringDataReader(input);
        DataWriter<VcfSlice> hbaseWriter = null;
        VariantSource variantSource = new VariantSource(fileName, Integer.toString(fileId), Integer.toString(studyConfiguration
                .getStudyId()), studyConfiguration.getStudyName());

        Supplier<ParallelTaskRunner.Task<String, VcfSlice>> taskSupplier;
        if (!parser.equalsIgnoreCase("htsjdk")) {
            throw new NotImplementedException("Currently only HTSJDK supported");
        }
        logger.info("Using HTSJDK to read variants.");
        FullVcfCodec codec = new FullVcfCodec();
        VcfMeta meta = new VcfMeta(variantSource);
        ArchiveHelper helper = new ArchiveHelper(conf, meta);
        try (InputStream fileInputStream = input.toString().endsWith("gz") ? new GZIPInputStream(new FileInputStream(input.toFile()))
                : new FileInputStream(input.toFile())) {
            LineIterator lineIterator = codec.makeSourceFromStream(fileInputStream);
            VCFHeader header = (VCFHeader) codec.readActualHeader(lineIterator);
            VCFHeaderVersion headerVersion = codec.getVCFHeaderVersion();
            VariantGlobalStatsCalculator statsCalculator = new VariantGlobalStatsCalculator(source);

            final VariantSource finalSource = source;
            taskSupplier = () -> new VariantHbaseTransformTask(header, headerVersion, finalSource, statsCalculator, includeSrc,
                    generateReferenceBlocks, helper);
            hbaseWriter = new VariantHbasePutTask(helper, table);
        } catch (IOException e) {
            throw new StorageManagerException("Unable to read VCFHeader", e);
        }
        ParallelTaskRunner<String, VcfSlice> ptr;
        try {
            ptr = new ParallelTaskRunner<String, VcfSlice>(
                    dataReader,
                    taskSupplier,
                    hbaseWriter,
                    new ParallelTaskRunner.Config(numTasks, batchSize, capacity, false)
            );
        } catch (Exception e) {
            throw new StorageManagerException("Error while creating ParallelTaskRunner", e);
        }
        logger.info("Multi thread transform... [1 reading, {} transforming, 1 writing]", numTasks);
        long start = System.currentTimeMillis();
        try {
            ptr.run();
        } catch (ExecutionException e) {
            e.printStackTrace();
            throw new StorageManagerException("Error while executing TransformVariants in ParallelTaskRunner", e);
        }
        long end = System.currentTimeMillis();
        logger.info("end - start = " + (end - start) / 1000.0 + "s");
        // TODO store results

        try (ArchiveFileMetadataManager manager = new ArchiveFileMetadataManager(table, conf, null);) {
            manager.updateVcfMetaData(source);
        } catch (IOException e) {
            throw new RuntimeException("Not able to store Variant Source for file!!!", e);
        }
    }

    @Override
    protected void checkLoadedVariants(URI input, int fileId, StudyConfiguration studyConfiguration, ObjectMap options)
            throws StorageManagerException {
        // TODO Auto-generated method stub

    }

}