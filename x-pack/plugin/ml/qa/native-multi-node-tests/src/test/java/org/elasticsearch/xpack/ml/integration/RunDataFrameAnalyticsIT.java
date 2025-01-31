/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.integration;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsDest;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsSource;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsState;
import org.elasticsearch.xpack.core.ml.dataframe.analyses.OutlierDetection;
import org.junit.After;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

public class RunDataFrameAnalyticsIT extends MlNativeDataFrameAnalyticsIntegTestCase {

    @After
    public void cleanup() {
        cleanUp();
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/45741")
    public void testOutlierDetectionWithFewDocuments() throws Exception {
        String sourceIndex = "test-outlier-detection-with-few-docs";

        client().admin().indices().prepareCreate(sourceIndex)
            .addMapping("_doc", "numeric_1", "type=double", "numeric_2", "type=float", "categorical_1", "type=keyword")
            .get();

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        for (int i = 0; i < 5; i++) {
            IndexRequest indexRequest = new IndexRequest(sourceIndex);

            // We insert one odd value out of 5 for one feature
            String docId = i == 0 ? "outlier" : "normal" + i;
            indexRequest.id(docId);
            indexRequest.source("numeric_1", i == 0 ? 100.0 : 1.0, "numeric_2", 1.0, "categorical_1", "foo_" + i);
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_outlier_detection_with_few_docs";
        DataFrameAnalyticsConfig config = buildOutlierDetectionAnalytics(id, new String[] {sourceIndex}, sourceIndex + "-results", null);
        registerAnalytics(config);
        putAnalytics(config);

        assertState(id, DataFrameAnalyticsState.STOPPED);

        startAnalytics(id);
        waitUntilAnalyticsIsStopped(id);

        SearchResponse sourceData = client().prepareSearch(sourceIndex).get();
        double scoreOfOutlier = 0.0;
        double scoreOfNonOutlier = -1.0;
        for (SearchHit hit : sourceData.getHits()) {
            GetResponse destDocGetResponse = client().prepareGet().setIndex(config.getDest().getIndex()).setId(hit.getId()).get();
            assertThat(destDocGetResponse.isExists(), is(true));
            Map<String, Object> sourceDoc = hit.getSourceAsMap();
            Map<String, Object> destDoc = destDocGetResponse.getSource();
            for (String field : sourceDoc.keySet()) {
                assertThat(destDoc.containsKey(field), is(true));
                assertThat(destDoc.get(field), equalTo(sourceDoc.get(field)));
            }
            assertThat(destDoc.containsKey("ml"), is(true));

            @SuppressWarnings("unchecked")
            Map<String, Object> resultsObject = (Map<String, Object>) destDoc.get("ml");

            assertThat(resultsObject.containsKey("outlier_score"), is(true));
            double outlierScore = (double) resultsObject.get("outlier_score");
            assertThat(outlierScore, allOf(greaterThanOrEqualTo(0.0), lessThanOrEqualTo(1.0)));
            if (hit.getId().equals("outlier")) {
                scoreOfOutlier = outlierScore;
            } else {
                if (scoreOfNonOutlier < 0) {
                    scoreOfNonOutlier = outlierScore;
                } else {
                    assertThat(outlierScore, equalTo(scoreOfNonOutlier));
                }
            }
        }
        assertThat(scoreOfOutlier, is(greaterThan(scoreOfNonOutlier)));
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/45741")
    public void testOutlierDetectionWithEnoughDocumentsToScroll() throws Exception {
        String sourceIndex = "test-outlier-detection-with-enough-docs-to-scroll";

        client().admin().indices().prepareCreate(sourceIndex)
            .addMapping("_doc", "numeric_1", "type=double", "numeric_2", "type=float", "categorical_1", "type=keyword")
            .get();

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        int docCount = randomIntBetween(1024, 2048);
        for (int i = 0; i < docCount; i++) {
            IndexRequest indexRequest = new IndexRequest(sourceIndex);
            indexRequest.source("numeric_1", randomDouble(), "numeric_2", randomFloat(), "categorical_1", randomAlphaOfLength(10));
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_outlier_detection_with_enough_docs_to_scroll";
        DataFrameAnalyticsConfig config = buildOutlierDetectionAnalytics(
                id, new String[] {sourceIndex}, sourceIndex + "-results", "custom_ml");
        registerAnalytics(config);
        putAnalytics(config);

        assertState(id, DataFrameAnalyticsState.STOPPED);

        startAnalytics(id);
        waitUntilAnalyticsIsStopped(id);

        // Check we've got all docs
        SearchResponse searchResponse = client().prepareSearch(config.getDest().getIndex()).setTrackTotalHits(true).get();
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo((long) docCount));

        // Check they all have an outlier_score
        searchResponse = client().prepareSearch(config.getDest().getIndex())
            .setTrackTotalHits(true)
            .setQuery(QueryBuilders.existsQuery("custom_ml.outlier_score")).get();
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo((long) docCount));
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/45741")
    public void testOutlierDetectionWithMoreFieldsThanDocValueFieldLimit() throws Exception {
        String sourceIndex = "test-outlier-detection-with-more-fields-than-docvalue-limit";

        client().admin().indices().prepareCreate(sourceIndex).get();

        GetSettingsRequest getSettingsRequest = new GetSettingsRequest();
        getSettingsRequest.indices(sourceIndex);
        getSettingsRequest.names(IndexSettings.MAX_DOCVALUE_FIELDS_SEARCH_SETTING.getKey());
        getSettingsRequest.includeDefaults(true);

        GetSettingsResponse docValueLimitSetting = client().admin().indices().getSettings(getSettingsRequest).actionGet();
        int docValueLimit = IndexSettings.MAX_DOCVALUE_FIELDS_SEARCH_SETTING.get(
            docValueLimitSetting.getIndexToSettings().values().iterator().next().value);

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        for (int i = 0; i < 100; i++) {

            StringBuilder source = new StringBuilder("{");
            for (int fieldCount = 0; fieldCount < docValueLimit + 1; fieldCount++) {
                source.append("\"field_").append(fieldCount).append("\":").append(randomDouble());
                if (fieldCount < docValueLimit) {
                    source.append(",");
                }
            }
            source.append("}");

            IndexRequest indexRequest = new IndexRequest(sourceIndex);
            indexRequest.source(source.toString(), XContentType.JSON);
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_outlier_detection_with_more_fields_than_docvalue_limit";
        DataFrameAnalyticsConfig config = buildOutlierDetectionAnalytics(id, new String[] {sourceIndex}, sourceIndex + "-results", null);
        registerAnalytics(config);
        putAnalytics(config);

        assertState(id, DataFrameAnalyticsState.STOPPED);

        startAnalytics(id);
        waitUntilAnalyticsIsStopped(id);

        SearchResponse sourceData = client().prepareSearch(sourceIndex).get();
        for (SearchHit hit : sourceData.getHits()) {
            GetResponse destDocGetResponse = client().prepareGet().setIndex(config.getDest().getIndex()).setId(hit.getId()).get();
            assertThat(destDocGetResponse.isExists(), is(true));
            Map<String, Object> sourceDoc = hit.getSourceAsMap();
            Map<String, Object> destDoc = destDocGetResponse.getSource();
            for (String field : sourceDoc.keySet()) {
                assertThat(destDoc.containsKey(field), is(true));
                assertThat(destDoc.get(field), equalTo(sourceDoc.get(field)));
            }
            assertThat(destDoc.containsKey("ml"), is(true));

            @SuppressWarnings("unchecked")
            Map<String, Object> resultsObject = (Map<String, Object>) destDoc.get("ml");

            assertThat(resultsObject.containsKey("outlier_score"), is(true));
            double outlierScore = (double) resultsObject.get("outlier_score");
            assertThat(outlierScore, allOf(greaterThanOrEqualTo(0.0), lessThanOrEqualTo(1.0)));
        }
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/43960")
    public void testStopOutlierDetectionWithEnoughDocumentsToScroll() {
        String sourceIndex = "test-stop-outlier-detection-with-enough-docs-to-scroll";

        client().admin().indices().prepareCreate(sourceIndex)
            .addMapping("_doc", "numeric_1", "type=double", "numeric_2", "type=float", "categorical_1", "type=keyword")
            .get();

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        int docCount = randomIntBetween(1024, 2048);
        for (int i = 0; i < docCount; i++) {
            IndexRequest indexRequest = new IndexRequest(sourceIndex);
            indexRequest.source("numeric_1", randomDouble(), "numeric_2", randomFloat(), "categorical_1", randomAlphaOfLength(10));
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_stop_outlier_detection_with_enough_docs_to_scroll";
        DataFrameAnalyticsConfig config = buildOutlierDetectionAnalytics(
                id, new String[] {sourceIndex}, sourceIndex + "-results", "custom_ml");
        registerAnalytics(config);
        putAnalytics(config);

        assertState(id, DataFrameAnalyticsState.STOPPED);
        startAnalytics(id);
        assertState(id, DataFrameAnalyticsState.STARTED);

        assertThat(stopAnalytics(id).isStopped(), is(true));
        assertState(id, DataFrameAnalyticsState.STOPPED);
        if (indexExists(config.getDest().getIndex()) == false) {
            // We stopped before we even created the destination index
            return;
        }

        SearchResponse searchResponse = client().prepareSearch(config.getDest().getIndex()).setTrackTotalHits(true).get();
        if (searchResponse.getHits().getTotalHits().value == docCount) {
            searchResponse = client().prepareSearch(config.getDest().getIndex())
                .setTrackTotalHits(true)
                .setQuery(QueryBuilders.existsQuery("custom_ml.outlier_score")).get();
            logger.debug("We stopped during analysis: [{}] < [{}]", searchResponse.getHits().getTotalHits().value, docCount);
            assertThat(searchResponse.getHits().getTotalHits().value, lessThan((long) docCount));
        } else {
            logger.debug("We stopped during reindexing: [{}] < [{}]", searchResponse.getHits().getTotalHits().value, docCount);
        }
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/45741")
    public void testOutlierDetectionWithMultipleSourceIndices() throws Exception {
        String sourceIndex1 = "test-outlier-detection-with-multiple-source-indices-1";
        String sourceIndex2 = "test-outlier-detection-with-multiple-source-indices-2";
        String destIndex = "test-outlier-detection-with-multiple-source-indices-results";
        String[] sourceIndex = new String[] { sourceIndex1, sourceIndex2 };

        client().admin().indices().prepareCreate(sourceIndex1)
            .addMapping("_doc", "numeric_1", "type=double", "numeric_2", "type=float", "categorical_1", "type=keyword")
            .get();

        client().admin().indices().prepareCreate(sourceIndex2)
            .addMapping("_doc", "numeric_1", "type=double", "numeric_2", "type=float", "categorical_1", "type=keyword")
            .get();

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        for (String index : sourceIndex) {
            for (int i = 0; i < 5; i++) {
                IndexRequest indexRequest = new IndexRequest(index);
                indexRequest.source("numeric_1", randomDouble(), "numeric_2", randomFloat(), "categorical_1", "foo_" + i);
                bulkRequestBuilder.add(indexRequest);
            }
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_outlier_detection_with_multiple_source_indices";
        DataFrameAnalyticsConfig config = buildOutlierDetectionAnalytics(id, sourceIndex, destIndex, null);
        registerAnalytics(config);
        putAnalytics(config);

        assertState(id, DataFrameAnalyticsState.STOPPED);

        startAnalytics(id);
        waitUntilAnalyticsIsStopped(id);

        // Check we've got all docs
        SearchResponse searchResponse = client().prepareSearch(config.getDest().getIndex()).setTrackTotalHits(true).get();
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo((long) bulkRequestBuilder.numberOfActions()));

        // Check they all have an outlier_score
        searchResponse = client().prepareSearch(config.getDest().getIndex())
            .setTrackTotalHits(true)
            .setQuery(QueryBuilders.existsQuery("ml.outlier_score")).get();
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo((long) bulkRequestBuilder.numberOfActions()));
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/45741")
    public void testOutlierDetectionWithPreExistingDestIndex() throws Exception {
        String sourceIndex = "test-outlier-detection-with-pre-existing-dest-index";
        String destIndex = "test-outlier-detection-with-pre-existing-dest-index-results";

        client().admin().indices().prepareCreate(sourceIndex)
            .addMapping("_doc", "numeric_1", "type=double", "numeric_2", "type=float", "categorical_1", "type=keyword")
            .get();

        client().admin().indices().prepareCreate(destIndex)
            .addMapping("_doc", "numeric_1", "type=double", "numeric_2", "type=float", "categorical_1", "type=keyword")
            .get();

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        for (int i = 0; i < 5; i++) {
            IndexRequest indexRequest = new IndexRequest(sourceIndex);
            indexRequest.source("numeric_1", randomDouble(), "numeric_2", randomFloat(), "categorical_1", "foo_" + i);
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_outlier_detection_with_pre_existing_dest_index";
        DataFrameAnalyticsConfig config = buildOutlierDetectionAnalytics(id, new String[] {sourceIndex}, destIndex, null);
        registerAnalytics(config);
        putAnalytics(config);

        assertState(id, DataFrameAnalyticsState.STOPPED);

        startAnalytics(id);
        waitUntilAnalyticsIsStopped(id);

        // Check we've got all docs
        SearchResponse searchResponse = client().prepareSearch(config.getDest().getIndex()).setTrackTotalHits(true).get();
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo((long) bulkRequestBuilder.numberOfActions()));

        // Check they all have an outlier_score
        searchResponse = client().prepareSearch(config.getDest().getIndex())
            .setTrackTotalHits(true)
            .setQuery(QueryBuilders.existsQuery("ml.outlier_score")).get();
        assertThat(searchResponse.getHits().getTotalHits().value, equalTo((long) bulkRequestBuilder.numberOfActions()));
    }

    public void testRegressionWithNumericFeatureAndFewDocuments() throws Exception {
        String sourceIndex = "test-regression-with-numeric-feature-and-few-docs";

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        List<Double> featureValues = Arrays.asList(1.0, 2.0, 3.0);
        List<Double> dependentVariableValues = Arrays.asList(10.0, 20.0, 30.0);

        for (int i = 0; i < 350; i++) {
            Double field = featureValues.get(i % 3);
            Double value = dependentVariableValues.get(i % 3);

            IndexRequest indexRequest = new IndexRequest(sourceIndex);
            if (i < 300) {
                indexRequest.source("feature", field, "variable", value);
            } else {
                indexRequest.source("feature", field);
            }
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_regression_with_numeric_feature_and_few_docs";
        DataFrameAnalyticsConfig config = buildRegressionAnalytics(id, new String[] {sourceIndex},
            sourceIndex + "-results", null, "variable");
        registerAnalytics(config);
        putAnalytics(config);

        assertState(id, DataFrameAnalyticsState.STOPPED);

        startAnalytics(id);
        waitUntilAnalyticsIsStopped(id);

        int resultsWithPrediction = 0;
        SearchResponse sourceData = client().prepareSearch(sourceIndex).setTrackTotalHits(true).setSize(1000).get();
        assertThat(sourceData.getHits().getTotalHits().value, equalTo(350L));
        for (SearchHit hit : sourceData.getHits()) {
            GetResponse destDocGetResponse = client().prepareGet().setIndex(config.getDest().getIndex()).setId(hit.getId()).get();
            assertThat(destDocGetResponse.isExists(), is(true));
            Map<String, Object> sourceDoc = hit.getSourceAsMap();
            Map<String, Object> destDoc = destDocGetResponse.getSource();
            for (String field : sourceDoc.keySet()) {
                assertThat(destDoc.containsKey(field), is(true));
                assertThat(destDoc.get(field), equalTo(sourceDoc.get(field)));
            }
            assertThat(destDoc.containsKey("ml"), is(true));

            @SuppressWarnings("unchecked")
            Map<String, Object> resultsObject = (Map<String, Object>) destDoc.get("ml");

            assertThat(resultsObject.containsKey("variable_prediction"), is(true));
            if (resultsObject.containsKey("variable_prediction")) {
                resultsWithPrediction++;
                double featureValue = (double) destDoc.get("feature");
                double predictionValue = (double) resultsObject.get("variable_prediction");
                // TODO reenable this assertion when the backend is stable
                // it seems for this case values can be as far off as 2.0
                // assertThat(predictionValue, closeTo(10 * featureValue, 2.0));
            }
        }
        assertThat(resultsWithPrediction, greaterThan(0));
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/45741")
    public void testModelMemoryLimitLowerThanEstimatedMemoryUsage() {
        String sourceIndex = "test-model-memory-limit";

        client().admin().indices().prepareCreate(sourceIndex)
            .addMapping("_doc", "col_1", "type=double", "col_2", "type=float", "col_3", "type=keyword")
            .get();

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk().setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        for (int i = 0; i < 10000; i++) {  // This number of rows should make memory usage estimate greater than 1MB
            IndexRequest indexRequest = new IndexRequest(sourceIndex)
                .id("doc_" + i)
                .source("col_1", 1.0, "col_2", 1.0, "col_3", "str");
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder.get();
        if (bulkResponse.hasFailures()) {
            fail("Failed to index data: " + bulkResponse.buildFailureMessage());
        }

        String id = "test_model_memory_limit_lower_than_estimated_memory_usage";
        ByteSizeValue modelMemoryLimit = new ByteSizeValue(1, ByteSizeUnit.MB);
        DataFrameAnalyticsConfig config = new DataFrameAnalyticsConfig.Builder()
            .setId(id)
            .setSource(new DataFrameAnalyticsSource(new String[] { sourceIndex }, null))
            .setDest(new DataFrameAnalyticsDest(sourceIndex + "-results", null))
            .setAnalysis(new OutlierDetection())
            .setModelMemoryLimit(modelMemoryLimit)
            .build();

        registerAnalytics(config);
        putAnalytics(config);
        assertState(id, DataFrameAnalyticsState.STOPPED);

        ElasticsearchStatusException exception = expectThrows(ElasticsearchStatusException.class, () -> startAnalytics(id));
        assertThat(exception.status(), equalTo(RestStatus.BAD_REQUEST));
        assertThat(
            exception.getMessage(),
            startsWith("Cannot start because the configured model memory limit [" + modelMemoryLimit +
                "] is lower than the expected memory usage"));
    }
}
