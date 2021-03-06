package org.opencb.opencga.storage.core.manager;

import org.apache.commons.lang.StringUtils;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryParam;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.CatalogManager;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptor.VariantQueryParams;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptorUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptorUtils.*;

/**
 * Created by pfurio on 02/12/16.
 */
public class CatalogUtils {

    private final CatalogManager catalogManager;

    public CatalogUtils(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
    }

    /**
     * @see {@link org.opencb.opencga.catalog.db.mongodb.MongoDBUtils#ANNOTATION_PATTERN}
     */
    public static final Pattern ANNOTATION_PATTERN = Pattern.compile("^([a-zA-Z\\\\.]+)([\\^=<>~!$]+.*)$");

    /**
     * Transforms a high level Query to a query fully understandable by storage.
     * @param query     High level query. Will be modified by the method.
     * @param sessionId User's session id
     * @return          Modified input query (same instance)
     * @throws CatalogException if there is any catalog error
     */
    public Query parseQuery(Query query, String sessionId) throws CatalogException {
        if (query == null) {
            // Nothing to do!
            return null;
        }
        List<Long> studies = getStudies(query, VariantQueryParams.STUDIES, sessionId);
        String defaultStudyStr;
        if (studies.size() == 1) {
            defaultStudyStr = String.valueOf(studies.get(0));
        } else {
            defaultStudyStr = null;
        }

        transformFilter(query, VariantQueryParams.STUDIES, value -> catalogManager.getStudyId(value, sessionId));
        transformFilter(query, VariantQueryParams.RETURNED_STUDIES, value -> catalogManager.getStudyId(value, sessionId));
        transformFilter(query, VariantQueryParams.COHORTS, value ->
                catalogManager.getCohortManager().getId(value, defaultStudyStr, sessionId).getResourceId());
        transformFilter(query, VariantQueryParams.FILES, value ->
                catalogManager.getFileId(value, defaultStudyStr, sessionId));
        transformFilter(query, VariantQueryParams.RETURNED_FILES, value ->
                catalogManager.getFileId(value, defaultStudyStr, sessionId));

        // TODO: Parse sample filter and add genotype filter

        return query;
    }

    /**
     * Parse a generic string with comma separated key=values and obtain a query understandable by catalog.
     *
     * @param myString String of the kind age>20;ontologies=hpo:123,hpo:456;name=smith
     * @param getParam Get param function that will return null if the key string is not one of the accepted keys in catalog. For those
     *                 cases, they will be treated as annotations.
     * @return A query object.
     */
    protected static Query parseSampleAnnotationQuery(String myString, Function<String, QueryParam> getParam) {

        Query query = new Query();

        List<String> annotationList = new ArrayList<>();
        List<String> params = Arrays.asList(myString.replaceAll("\\s+", "").split(";"));
        for (String param : params) {
            Matcher matcher = ANNOTATION_PATTERN.matcher(param);
            String key;
            if (matcher.find()) {
                key = matcher.group(1);
                if (getParam.apply(key) != null && !key.startsWith("annotation")) {
                    query.put(key, matcher.group(2));
                } else {
                    // Annotation
                    String myKey = key;
                    if (!key.startsWith("annotation.")) {
                        myKey = "annotation." + key;
                    }
                    annotationList.add(myKey + matcher.group(2));
                }
            }
        }

        query.put("annotation", annotationList);

        return query;
    }

    @FunctionalInterface
    private interface CatalogIdResolver {
        Long get(String value) throws CatalogException;
    }

    /**
     * Splits the value from the query (if any) and translates the IDs to numerical Ids.
     * @param query     Query with the data
     * @param param     Param to modify
     * @param toId      Method to translate from String to numerical ID
     * @throws CatalogException if there is any catalog error
     */
    private void transformFilter(Query query, VariantQueryParams param, CatalogIdResolver toId) throws CatalogException {
        if (VariantDBAdaptorUtils.isValidParam(query, param)) {
            String valuesStr = query.getString(param.key());
            VariantDBAdaptorUtils.QueryOperation queryOperation = VariantDBAdaptorUtils.checkOperator(valuesStr);
            if (queryOperation == null) {
                queryOperation = VariantDBAdaptorUtils.QueryOperation.OR;
            }
            List<String> values = VariantDBAdaptorUtils.splitValue(valuesStr, queryOperation);
            StringBuilder sb = new StringBuilder();
            for (String value : values) {
                if (sb.length() > 0) {
                    sb.append(queryOperation.separator());
                }
                if (value.startsWith("!")) {
                    sb.append('!');
                    value = value.substring(1);
                }

                if (StringUtils.isNumeric(value)) {
                    sb.append(value);
                } else {
                    sb.append(toId.get(value));
                }
            }
            query.put(param.key(), sb.toString());
        }
    }

    /**
     * Get the list of studies. Discards negated studies (starting with '!').
     *
     * @see VariantDBAdaptorUtils#getStudyIds(List, org.opencb.commons.datastore.core.QueryOptions)
     * @param query     Query with the values
     * @param param     Param to check. {@link VariantQueryParams#STUDIES} or {@link VariantQueryParams#RETURNED_STUDIES}
     * @param sessionId User's sessionId
     * @return          List of positive studies.
     * @throws CatalogException if there is an error with catalog
     */
    public List<Long> getStudies(Query query, VariantQueryParams param, String sessionId)
            throws CatalogException {
        List<Long> studies = new ArrayList<>();
        if (isValidParam(query, param)) {
            String value = query.getString(param.key());
            VariantDBAdaptorUtils.QueryOperation op = checkOperator(value);
            List<String> values = splitValue(value, op);
            for (String id : values) {
                if (!VariantDBAdaptorUtils.isNegated(id) && !id.isEmpty()) {
                    studies.add(catalogManager.getStudyId(id, sessionId));
                }
            }
        }
        return studies;
    }

    /**
     * Gets any studyId referred in the Query. If none, tries to get the default study. If more than one, thrown an exception.
     * @param query     Variants query
     * @param sessionId User's sessionId
     * @return  Any study id
     * @throws CatalogException if there is a catalog error or the study is missing
     */
    public long getAnyStudyId(Query query, String sessionId) throws CatalogException {
        Long id = getAnyStudyId(query, VariantQueryParams.STUDIES, sessionId);
        if (id == null) {
            id = getAnyStudyId(query, VariantQueryParams.RETURNED_STUDIES, sessionId);
            if (id == null) {
                id = catalogManager.getStudyId(null, sessionId);
                if (id < 0) {
                    throw new CatalogException("Missing StudyId. Unable to get any variant!");
                }
            }
        }
        return id;
    }

    private Long getAnyStudyId(Query query, VariantQueryParams param, String sessionId)
            throws CatalogException {
        List<Long> studies = getStudies(query, param, sessionId);
        if (studies.isEmpty()) {
            return null;
        } else {
            return studies.get(0);
        }
    }

}
