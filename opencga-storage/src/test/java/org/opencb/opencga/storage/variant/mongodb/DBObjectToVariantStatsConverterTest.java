package org.opencb.opencga.storage.variant.mongodb;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import org.junit.*;
import static org.junit.Assert.assertEquals;
import org.opencb.biodata.models.feature.Genotype;
import org.opencb.biodata.models.variant.stats.VariantStats;

/**
 *
 * @author Cristina Yenyxe Gonzalez Garcia <cyenyxe@ebi.ac.uk>
 */
public class DBObjectToVariantStatsConverterTest {
    
    private static BasicDBObject mongoStats;
    private static VariantStats stats;
    
    @BeforeClass
    public static void setUp() {
        mongoStats = new BasicDBObject(DBObjectToVariantStatsConverter.MAF_FIELD, 0.1);
        mongoStats.append(DBObjectToVariantStatsConverter.MGF_FIELD, 0.01);
        mongoStats.append(DBObjectToVariantStatsConverter.MAFALLELE_FIELD, "A");
        mongoStats.append(DBObjectToVariantStatsConverter.MGFGENOTYPE_FIELD, "A/A");
        mongoStats.append(DBObjectToVariantStatsConverter.MISSALLELE_FIELD, 10);
        mongoStats.append(DBObjectToVariantStatsConverter.MISSGENOTYPE_FIELD, 5);
        
        BasicDBObject genotypes = new BasicDBObject();
        genotypes.append("0/0", 100);
        genotypes.append("0/1", 50);
        genotypes.append("1/1", 10);
        mongoStats.append(DBObjectToVariantStatsConverter.NUMGT_FIELD, genotypes);
        
        stats = new VariantStats(null, -1, null, null, 0.1, 0.01, "A", "A/A", 10, 5, -1, false, -1, -1, -1, -1);
        stats.addGenotype(new Genotype("0/0"), 100);
        stats.addGenotype(new Genotype("0/1"), 50);
        stats.addGenotype(new Genotype("1/1"), 10);
    }
    
    @Test
    public void testConvertToDataModelType() {
        DBObjectToVariantStatsConverter converter = new DBObjectToVariantStatsConverter();
        VariantStats converted = converter.convertToDataModelType(mongoStats);
        assertEquals(stats, converted);
    }
    
    @Test
    public void testConvertToStorageType() {
        DBObjectToVariantStatsConverter converter = new DBObjectToVariantStatsConverter();
        DBObject converted = converter.convertToStorageType(stats);
        
        assertEquals(stats.getMaf(), (float) converted.get(DBObjectToVariantStatsConverter.MAF_FIELD), 1e-6);
        assertEquals(stats.getMgf(), (float) converted.get(DBObjectToVariantStatsConverter.MGF_FIELD), 1e-6);
        assertEquals(stats.getMafAllele(), converted.get(DBObjectToVariantStatsConverter.MAFALLELE_FIELD));
        assertEquals(stats.getMgfGenotype(), converted.get(DBObjectToVariantStatsConverter.MGFGENOTYPE_FIELD));
        
        assertEquals(stats.getMissingAlleles(), converted.get(DBObjectToVariantStatsConverter.MISSALLELE_FIELD));
        assertEquals(stats.getMissingGenotypes(), converted.get(DBObjectToVariantStatsConverter.MISSGENOTYPE_FIELD));
        
        assertEquals(100, ((DBObject) converted.get(DBObjectToVariantStatsConverter.NUMGT_FIELD)).get("0/0"));
        assertEquals(50, ((DBObject) converted.get(DBObjectToVariantStatsConverter.NUMGT_FIELD)).get("0/1"));
        assertEquals(10, ((DBObject) converted.get(DBObjectToVariantStatsConverter.NUMGT_FIELD)).get("1/1"));
    }
}
