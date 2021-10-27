package cn.mesalab.utils;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import com.google.common.collect.Lists;
import com.sun.istack.internal.NotNull;
import org.jfree.util.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


/**
 * @author joy
 */
public class SeriesUtils {
    private static final Logger LOG = LoggerFactory.getLogger(SeriesUtils.class);
    public static List<Map<String, Object>> readCsvToList(String filePath) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();

        String line;
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            br.readLine();
            while ((line = br.readLine()) != null) {
                List<String> column = Arrays.asList(line.split(","));
                // 保存记录中的每个<字段名-字段值>
                Map<String, Object> rowData = new HashMap<String, Object>();
                rowData.put("__time", column.get(0));
                rowData.put(ApplicationConfig.BASELINE_METRIC_TYPE, Integer.valueOf(column.get(1)));

                list.add(rowData);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }


    /**
     * 时序数据补齐
     */
    public static List<Map<String, Object>> complementSeries(List<Map<String, Object>> originSeries){
        LocalDateTime startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(DruidData.getTimeLimit()._1), TimeZone
                .getDefault().toZoneId());
        LocalDateTime endTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(DruidData.getTimeLimit()._2), TimeZone
                .getDefault().toZoneId());
        List<String> dateList = completionDate(startTime, endTime);

        // 补全后的结果
        List<Map<String, Object>> result = new ArrayList<>();
        boolean dbDateExist = false;
        for (String date : dateList) {
            //table为数据库查询出来的对象列表，结构为List<Map<String, Object>>
            for (Map<String, Object> row : originSeries) {
                if (row.get(ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME).toString().substring(0,19).equals(date)) {
                    //集合已包含该日期
                    dbDateExist = true;
                    result.add(row);
                    break;
                }
            }
            //添加补全的数据到最后结果列表
            if (!dbDateExist) {
                Map<String, Object> temp = new HashMap<>(2);
                temp.put(ApplicationConfig.DRUID_RECVTIME_COLUMN_NAME, date);
                temp.put(ApplicationConfig.BASELINE_METRIC_TYPE, 0);
                result.add(temp);
            }
            dbDateExist = false;
        }

        return result;
    }

    private static List<String> completionDate(LocalDateTime startTime, LocalDateTime endTime) {
        //日期格式化
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(ApplicationConfig.TIME_FORMAT);
        List<String> timeList = new ArrayList<>();
        //遍历给定的日期期间的每一天
        for (int i = 0; !Duration.between(startTime.plusMinutes(i+1), endTime).isNegative(); i+= ApplicationConfig.HISTORICAL_GRAD) {
            //添加日期
            timeList.add(startTime.plusMinutes(i).format(formatter));
        }
        return timeList;
    }

    /**
     * 判断是否存在以天为单位的周期特征
     * @param historicalSeries
     * @return
     */
    public static Boolean isPeriod(List<Integer> historicalSeries){
        boolean result = true;
        List<List<Integer>> partitions = Lists.partition(historicalSeries, 24*60/ApplicationConfig.HISTORICAL_GRAD);

        // Pearson corrcoef
        int size = partitions.size();
        ArrayList<Double> corrScores = new ArrayList<>();
        for(int i = 0; i < size; i ++){
            for(int j = i+1; j < size; j ++){
                corrScores.add(getPearsonCorrelationScore(partitions.get(i).stream().mapToDouble(Number::doubleValue).toArray(),
                        partitions.get(j).stream().mapToDouble(Integer::valueOf).toArray()));
            }
        }
        double pearsonCorrelationScore = corrScores.stream().mapToDouble(Number::doubleValue).average().orElse(0.0);
        if (pearsonCorrelationScore < ApplicationConfig.BASELINE_PERIOD_CORR_THRE){
            result=false;
        }
        return result;
    }

    public static double getPearsonCorrelationScore(double[] xData, double[] yData) {

        if (xData.length != yData.length) {
            Log.error("Pearson CorrelationScore 数组长度不相等!");
        }
        double xMeans;
        double yMeans;
        double numerator = 0;
        double denominator = 0;

        double result = 0;
        // 拿到两个数据的平均值
        xMeans = getMeans(xData);
        yMeans = getMeans(yData);
        // 计算皮尔逊系数的分子
        numerator = generateNumerator(xData, xMeans, yData, yMeans);
        // 计算皮尔逊系数的分母
        denominator = generateDenomiator(xData, xMeans, yData, yMeans);
        // 计算皮尔逊系数
        if(denominator>0) {
            result = numerator / denominator;
        }
        return result;
    }

    private static float generateNumerator(double[] xData, double xMeans, double[] yData, double yMeans) {
        int numerator = 0;
        for (int i = 0; i < xData.length; i++) {
            numerator += (xData[i] - xMeans) * (yData[i] - yMeans);
        }
        return numerator;
    }

    private static double generateDenomiator(double[] xData, double xMeans, double[] yData, double yMeans) {
        double xSum = 0.0;
        for (double xDatum : xData) {
            xSum += (xDatum - xMeans) * (xDatum - xMeans);
        }
        double ySum = 0.0;
        for (double yDatum : yData) {
            ySum += (yDatum - yMeans) * (yDatum - yMeans);
        }
        return Math.sqrt(xSum) * Math.sqrt(ySum);
    }

    private static double getMeans(double[] datas) {
        float sum = 0;
        for (double data : datas) {
            sum += data;
        }
        return sum / datas.length;
    }

    public static List<Integer> columnAverage(List<List<Integer>> list){
        ArrayList<Integer> averages = new ArrayList<>();
        for(int i=0; i<list.get(0).size(); i++){
            int columnSum = 0;
            for (List<Integer> integers : list) {
                columnSum += integers.get(i);
            }
            averages.add(columnSum / list.size());
        }
        return averages;
    }

    public static int percentile(List<Integer> latencies, double percentile) {
        List<Integer> tmp = new ArrayList<>(latencies);
        Collections.sort(tmp);
        int index = (int) Math.ceil(percentile * tmp.size());
        return tmp.get(index-1);
    }
}

