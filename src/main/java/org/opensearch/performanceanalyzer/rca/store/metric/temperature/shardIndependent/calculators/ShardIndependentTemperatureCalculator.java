/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.performanceanalyzer.rca.store.metric.temperature.shardIndependent.calculators;


import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.opensearch.performanceanalyzer.commons.metrics.AllMetrics;
import org.opensearch.performanceanalyzer.rca.framework.core.temperature.TemperatureDimension;
import org.opensearch.performanceanalyzer.rca.store.metric.temperature.TemperatureMetricsBase;

/**
 * Even in metrics that has ShardId dimension, it can be null for some rows. For examples here:
 *
 * <p>Shard Index Op ShardRole Sum avg min max null null other 0 0 0 0 0 null null GC
 * 0.0015470341521869385 0.0015470341521869385 0.0015470341521869385 0.0015470341521869385 null null
 * generic 0.016626485462826558 0.016626485462826558 0.016626485462826558 0.016626485462826558 null
 * null management 0.0015470341521869385 0.0015470341521869385 0.0015470341521869385
 * 0.0015470341521869385 null null httpServer 0 0 0 0 null null refresh 0.0009010593643813712
 * 0.0009010593643813712 0.0009010593643813712 0.0009010593643813712
 *
 * <p>But they never the less consume CPU and the Pyrometer has to account for these as part of
 * system CPU. The problem is that if a node is high on shardIndependent CPU, then moving shard
 * around is not going to lessen the node temperature. But these should be accounted for in the
 * destination node where a shard is being placed. If it is already hot because of system CPU, maybe
 * its not a good idea to place a hot shard there.
 *
 * <p>CAVEAT: There are cases where the some of the operations should have been associated with a
 * shard but we are not accounting for them just yet(hopefully we will fix them). So, these CPU
 * percentages will be accounted for in the system CPU, where as they should have been associated
 * with as shard and shard movement would have helped lessen the node temperature.
 */
public class ShardIndependentTemperatureCalculator extends TemperatureMetricsBase {
    private static final Logger LOG =
            LogManager.getLogger(ShardIndependentTemperatureCalculator.class);

    private static final String[] dimensions = {AllMetrics.CommonDimension.OPERATION.toString()};

    public ShardIndependentTemperatureCalculator(TemperatureDimension metricType) {
        super(metricType, dimensions);
    }

    @Override
    protected Result<Record> createDslAndFetch(
            DSLContext context,
            String tableName,
            Field<?> aggDimension,
            List<Field<?>> groupByFieldsList,
            List<Field<?>> selectFieldsList) {
        Field<?> shardIdField = DSL.field(DSL.name(AllMetrics.CommonDimension.SHARD_ID.toString()));

        // select sum(max) from <MetricTable> where ShardID is null;
        Result<?> res =
                context.select(aggDimension).from(tableName).where(shardIdField.isNull()).fetch();
        LOG.info("ShardIndependentTemperatureCalculator: {}", res);
        return (Result<Record>) res;
    }

    @Override
    protected List<Field<?>> getSelectFieldsList(
            final List<Field<?>> groupByFields, Field<?> aggrDimension) {
        return aggrColumnAsSelectField();
    }
}
