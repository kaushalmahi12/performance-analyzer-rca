/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.performanceanalyzer.metricsdb;


import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.TableLike;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.opensearch.performanceanalyzer.DBUtils;
import org.opensearch.performanceanalyzer.commons.collectors.StatsCollector;
import org.opensearch.performanceanalyzer.commons.config.PluginSettings;
import org.opensearch.performanceanalyzer.commons.stats.metrics.StatExceptionCode;
import org.opensearch.performanceanalyzer.reader.Removable;

/**
 * On-disk database that holds a 5 second snapshot of all metrics. We create one table per metric.
 * Every row contains four aggregations and any other relevant dimensions.
 *
 * <p>Eg: CPU table |sum|avg|max|min| index|shard|role| +---+---+---+---+--------+-----+----+ |
 * 5|2.5| 3| 2|sonested| 1| N/A|
 *
 * <p>RSS table |sum|avg|max|min| index|shard|role| +---+---+---+---+---------+-----+----+ | 30| 15|
 * 20| 10|nyc_taxis| 1| N/A|
 */
@SuppressWarnings("serial")
public class MetricsDB implements Removable {

    private static final Logger LOG = LogManager.getLogger(MetricsDB.class);

    private static final String DB_FILE_PREFIX_PATH_DEFAULT = "/tmp/metricsdb_";
    private static final String DB_FILE_PREFIX_PATH_CONF_NAME = "metrics-db-file-prefix-path";
    private static final String DB_URL = "jdbc:sqlite:";
    private final Connection conn;
    private final DSLContext create;
    public static final String SUM = "sum";
    public static final String AVG = "avg";
    public static final String MIN = "min";
    public static final String MAX = "max";
    public static final Set<String> AGG_VALUES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(SUM, AVG, MIN, MAX)));

    private long windowStartTime;

    public static String getDBFilePath(long windowStartTime) {
        return getFilePrefix() + windowStartTime;
    }

    public String getDBFilePath() {
        return getDBFilePath(windowStartTime);
    }

    public static String getFilePrefix() {
        return PluginSettings.instance()
                .getSettingValue(DB_FILE_PREFIX_PATH_CONF_NAME, DB_FILE_PREFIX_PATH_DEFAULT);
    }

    public MetricsDB(long windowStartTime) throws Exception {
        this.windowStartTime = windowStartTime;
        String url = DB_URL + getDBFilePath();
        try {
            conn = DriverManager.getConnection(url);
            conn.setAutoCommit(false);
        } catch (Exception e) {
            StatsCollector.instance()
                    .logException(StatExceptionCode.READER_METRICSDB_ACCESS_ERRORS);
            throw e;
        }
        create = DSL.using(conn, SQLDialect.SQLITE);
    }

    /**
     * Returns a MetricsDB handle associated with an existing metricsdb file.
     *
     * @param windowStartTime the timestamp associated with an existing metricsdb file
     * @return a MetricsDB handle associated with the metricsdb file
     * @throws Exception if the metricsdb file does not exist or is invalid
     */
    public static MetricsDB fetchExisting(long windowStartTime) throws Exception {
        String filePath = getDBFilePath(windowStartTime);
        if (!(new File(filePath)).exists()) {
            StatsCollector.instance()
                    .logException(StatExceptionCode.READER_METRICSDB_ACCESS_ERRORS);
            throw new FileNotFoundException(
                    String.format("MetricsDB file %s could not be found.", filePath));
        }
        return new MetricsDB(windowStartTime);
    }

    public void close() throws Exception {
        conn.close();
    }

    public void createMetric(Metric<?> metric, List<String> dimensions) {
        if (DBUtils.checkIfTableExists(create, metric.getName())) {
            return;
        }

        List<Field<?>> fields = DBUtils.getFieldsFromList(dimensions);
        fields.add(DSL.field(SUM, metric.getValueType()));
        fields.add(DSL.field(AVG, metric.getValueType()));
        fields.add(DSL.field(MIN, metric.getValueType()));
        fields.add(DSL.field(MAX, metric.getValueType()));
        create.createTable(metric.getName()).columns(fields).execute();
    }

    public BatchBindStep startBatchPut(Metric<?> metric, List<String> dimensions) {
        List<?> dummyValues = new ArrayList<>();
        for (String dim : dimensions) {
            dummyValues.add(null);
        }
        // Finally add sum, avg, min, max
        dummyValues.add(null);
        dummyValues.add(null);
        dummyValues.add(null);
        dummyValues.add(null);
        return create.batch(create.insertInto(DSL.table(metric.getName())).values(dummyValues));
    }

    public BatchBindStep startBatchPut(String tableName, int dimNum) {
        if (dimNum < 1 || !DBUtils.checkIfTableExists(create, tableName)) {
            throw new IllegalArgumentException(
                    String.format("Incorrect arguments %s, %d", tableName, dimNum));
        }
        List<?> dummyValues = new ArrayList<>(dimNum);
        for (int i = 0; i < dimNum; i++) {
            dummyValues.add(null);
        }

        return create.batch(create.insertInto(DSL.table(tableName)).values(dummyValues));
    }

    public void putMetric(Metric<Double> metric, Dimensions dimensions, long windowStartTime) {
        create.insertInto(DSL.table(metric.getName()))
                .set(DSL.field(SUM, Double.class), metric.getSum())
                .set(DSL.field(AVG, Double.class), metric.getAvg())
                .set(DSL.field(MIN, Double.class), metric.getMin())
                .set(DSL.field(MAX, Double.class), metric.getMax())
                .set(dimensions.getFieldMap())
                .execute();
    }

    /**
     * Drop a metric table. This is for IT framework to use only
     *
     * @param metricName metric table to be deleted
     */
    @VisibleForTesting
    public void deleteMetric(String metricName) {
        if (DBUtils.checkIfTableExists(create, metricName)) {
            create.dropTable(metricName).execute();
        }
    }

    // We have a table per metric. We do a group by/aggregate on
    // every dimension and return all the metric tables.
    public List<TableLike<Record>> getAggregatedMetricTables(
            List<String> metrics, List<String> aggregations, List<String> dimensions)
            throws Exception {
        List<TableLike<Record>> tList = new ArrayList<>();
        List<Field<?>> groupByFields = DBUtils.getFieldsFromList(dimensions);

        for (int i = 0; i < metrics.size(); i++) {
            String metric = metrics.get(i);
            List<Field<?>> selectFields = DBUtils.getFieldsFromList(dimensions);
            String aggType = aggregations.get(i);
            if (aggType.equals(SUM)) {
                Field<Double> field = DSL.field(SUM, Double.class);
                selectFields.add(DSL.sum(field).as(metric));
            } else if (aggType.equals(AVG)) {
                Field<Double> field = DSL.field(AVG, Double.class);
                selectFields.add(DSL.avg(field).as(metric));
            } else if (aggType.equals(MIN)) {
                Field<Double> field = DSL.field(MIN, Double.class);
                selectFields.add(DSL.min(field).as(metric));
            } else if (aggType.equals(MAX)) {
                Field<Double> field = DSL.field(MAX, Double.class);
                selectFields.add(DSL.max(field).as(metric));
            } else {
                throw new Exception("Unknown agg type");
            }
            if (!DBUtils.checkIfTableExists(create, metrics.get(i))) {
                tList.add(null);
            } else {
                tList.add(
                        create.select(selectFields)
                                .from(DSL.table(metric))
                                .groupBy(groupByFields)
                                .asTable());
            }
        }
        return tList;
    }

    /**
     * query metrics from different tables and merge to one table.
     *
     * <p>getAggregatedMetricTables returns tables like: +-----+---------+-----+ |shard|indexName|
     * cpu| +-----+---------+-----+ |0 |sonested | 10| |1 |sonested | 20|
     *
     * <p>+-----+---------+-----+ |shard|indexName| rss| +-----+---------+-----+ |0 |sonested | 54|
     * |2 |sonested | 47|
     *
     * <p>We select metrics from each table and union them: +-----+---------+-----+-----+
     * |shard|indexName| cpu| rss| +-----+---------+-----+-----+ |0 |sonested | 10| null| |1
     * |sonested | 20| null| |0 |sonested | null| 54| |2 |sonested | null| 47|
     *
     * <p>Then, we group by dimensions and return following table: +-----+---------+-----+-----+
     * |shard|indexName| cpu| rss| +-----+---------+-----+-----+ |0 |sonested | 10| 54| |1 |sonested
     * | 20| null| |2 |sonested | null| 47|
     *
     * @param metrics a list of metrics we want to query
     * @param aggregations aggregation we want to use for each metric
     * @param dimensions dimension we want to use for each metric
     * @return result of query
     * @throws Exception if one of the aggregations contains sth other than "sum", "avg", "min", and
     *     "max".
     */
    public Result<Record> queryMetric(
            List<String> metrics, List<String> aggregations, List<String> dimensions)
            throws Exception {
        List<TableLike<Record>> tList =
                getAggregatedMetricTables(metrics, aggregations, dimensions);

        // Join all the individual metric tables to generate the final table.
        Select<Record> finalTable = null;
        for (int i = 0; i < tList.size(); i++) {
            TableLike<Record> metricTable = tList.get(i);
            if (metricTable == null) {
                LOG.info(
                        String.format(
                                "%s metric table does not exist. "
                                        + "Returning null for the metric/dimension.",
                                metrics.get(i)));
                continue;
            }
            List<Field<?>> selectFields =
                    DBUtils.getSelectFieldsForMetricName(metrics.get(i), metrics, dimensions);
            Select<Record> curTable = create.select(selectFields).from(metricTable);

            if (finalTable == null) {
                finalTable = curTable;
            } else {
                finalTable = finalTable.union(curTable);
            }
        }

        List<Field<?>> allFields = DBUtils.getFieldsFromList(dimensions);
        for (String metric : metrics) {
            allFields.add(DSL.max(DSL.field(metric, Double.class)).as(metric));
        }
        List<Field<?>> groupByFields = DBUtils.getFieldsFromList(dimensions);
        if (finalTable == null) {
            return null;
        }
        return create.select(allFields).from(finalTable).groupBy(groupByFields).fetch();
    }

    /**
     * Queries all the data associated with the given metric.
     *
     * @param metric the desired metric
     * @return the result of the query
     */
    public Result<Record> queryMetric(String metric) throws DataAccessException {
        return create.select().from(DSL.table(metric)).fetch();
    }

    /**
     * Queries all the data associated with a given metric.
     *
     * @param metric the desired metric
     * @param dimensions the dimensions we want to return for the given metric
     * @param limit the maximum number of records to return
     * @return the result of the query
     */
    public Result<Record> queryMetric(String metric, Collection<String> dimensions, int limit)
            throws DataAccessException {
        if (!DBUtils.checkIfTableExists(create, metric)) {
            return null;
        }
        if (limit < 0) {
            throw new IllegalArgumentException("Limit must be non-negative");
        }
        List<Field<?>> fields = DBUtils.getFieldsFromList(dimensions);
        fields.add(DSL.field(SUM, Double.class));
        fields.add(DSL.field(AVG, Double.class));
        fields.add(DSL.field(MIN, Double.class));
        fields.add(DSL.field(MAX, Double.class));
        return create.select(fields).from(DSL.table(metric)).limit(limit).fetch();
    }

    public void commit() throws Exception {
        conn.commit();
    }

    @Override
    public void remove() throws Exception {
        conn.close();
    }

    /** Deletes the underlying metricsdb file. */
    public void deleteOnDiskFile() {
        MetricsDB.deleteOnDiskFile(windowStartTime);
    }

    /**
     * Deletes the metricsdb file associated with the given timestamp if it exists.
     *
     * @param windowStartTime the timestamp associated with an existing metricsdb file
     */
    public static void deleteOnDiskFile(long windowStartTime) {
        Path dbFilePath = Paths.get(getDBFilePath(windowStartTime));
        try {
            Files.delete(dbFilePath);
        } catch (IOException | SecurityException e) {
            LOG.error(
                    "Failed to delete File - {} with ExceptionCode: {}",
                    dbFilePath,
                    StatExceptionCode.READER_METRICSDB_ACCESS_ERRORS,
                    e);
            StatsCollector.instance()
                    .logException(StatExceptionCode.READER_METRICSDB_ACCESS_ERRORS);
        }
    }

    /**
     * Returns the timestamps associated with on-disk files.
     *
     * @return the timestamps associated with on-disk files
     */
    public static Set<Long> listOnDiskFiles() {
        String prefix = getFilePrefix();
        Path parentPath = Paths.get(prefix).getParent();
        Set<Long> found = new HashSet<Long>();
        try (Stream<Path> paths = Files.list(parentPath)) {
            PathMatcher matcher =
                    FileSystems.getDefault().getPathMatcher("regex:" + prefix + "\\d+");
            int prefixLength = prefix.length();
            paths.filter(matcher::matches)
                    .map(path -> path.toString())
                    .forEach(
                            s -> {
                                try {
                                    found.add(
                                            Long.parseUnsignedLong(s.substring(prefixLength), 10));
                                } catch (IndexOutOfBoundsException | NumberFormatException e) {
                                    LOG.error("Unexpected file in metricsdb directory - {}", s);
                                }
                            });
        } catch (IOException | SecurityException e) {
            LOG.error(
                    "Failed to access metricsdb directory - {} with ExceptionCode: {}",
                    parentPath,
                    StatExceptionCode.READER_METRICSDB_ACCESS_ERRORS,
                    e);
            StatsCollector.instance()
                    .logException(StatExceptionCode.READER_METRICSDB_ACCESS_ERRORS);
        }
        return found;
    }

    public DSLContext getDSLContext() {
        return create;
    }

    public boolean metricExists(String metric) {
        return DBUtils.checkIfTableExists(create, metric);
    }
}
