package com.github.h3yow.privacyreleasegatewayjava.aggregate;

import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.engine.GatewayException;
import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;
import com.github.h3yow.privacyreleasegatewayjava.model.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 聚合引擎：对策略求值后保留下来的行计算真实聚合值。
 * 底层数据解析失败统一映射为 INTERNAL_PROCESSING_ERROR，不向外透出原始异常。
 */
@Component
public class AggregationEngine {

    public double aggregate(List<DataRow> rows, String metric, AggregationType type) {
        try {
            return switch (type) {
                case COUNT -> rows.size();
                case SUM -> {
                    double sum = 0d;
                    for (DataRow row : rows) {
                        sum += toDouble(row.values().get(metric));
                    }
                    yield sum;
                }
                case AVG -> {
                    double sum = 0d;
                    for (DataRow row : rows) {
                        sum += toDouble(row.values().get(metric));
                    }
                    yield rows.isEmpty() ? 0d : sum / rows.size();
                }
            };
        } catch (GatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GatewayException(ErrorCode.INTERNAL_PROCESSING_ERROR, "聚合计算失败");
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new GatewayException(ErrorCode.INTERNAL_PROCESSING_ERROR, "指标值无法解析为数值");
        }
    }
}
