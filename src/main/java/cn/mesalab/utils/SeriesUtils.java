package cn.mesalab.utils;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.service.BaselineGeneration;
import com.google.common.collect.Lists;
import org.jfree.util.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Array;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;


/**
 * @author joy
 */
public class SeriesUtils {
    private static final Logger LOG = LoggerFactory.getLogger(SeriesUtils.class);

    private static DruidData druidData = new DruidData();

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
        LocalDateTime startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(druidData.getTimeLimit()._2), TimeZone
                .getDefault().toZoneId());
        LocalDateTime endTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(druidData.getTimeLimit()._1), TimeZone
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
        Boolean result = true;
        List<List<Integer>> partitions = Lists.partition(historicalSeries, 24*60/ApplicationConfig.HISTORICAL_GRAD);
        List<Integer> aggregatedPart = Arrays.asList();
        try{
            aggregatedPart = columnAverage(partitions.subList(0, ApplicationConfig.READ_HISTORICAL_DAYS-1));
        } catch (IndexOutOfBoundsException e){
            Log.error("历史");
        }

        // Pearson corrcoef
        double pearsonCorrelationScore = getPearsonCorrelationScore(aggregatedPart.stream().mapToInt(Integer::valueOf).toArray(),
                partitions.get(partitions.size() - 1).stream().mapToInt(Integer::valueOf).toArray());

        if (pearsonCorrelationScore < ApplicationConfig.BASELINE_PERIOD_CORR_THRE){
            result=false;
        }
        return result;
    }

    public static double getPearsonCorrelationScore(int[] xData, int[] yData) {
        if (xData.length != yData.length) {
            Log.error("Pearson CorrelationScore 数组长度不相等!");
        }
        int xMeans;
        int yMeans;
        double numerator = 0;
        double denominator = 0;

        double result = 0;
        // 拿到两个数据的平均值
        xMeans = (int) getMeans(xData);
        yMeans = (int) getMeans(yData);
        // 计算皮尔逊系数的分子
        numerator = generateNumerator(xData, xMeans, yData, yMeans);
        // 计算皮尔逊系数的分母
        denominator = generateDenomiator(xData, xMeans, yData, yMeans);
        // 计算皮尔逊系数
        if(denominator>0) {
            result = numerator / denominator;
        }
        //FOR TEST
        System.out.println(result);
        return result;
    }

    private static int generateNumerator(int[] xData, int xMeans, int[] yData, int yMeans) {
        int numerator = 0;
        for (int i = 0; i < xData.length; i++) {
            numerator += (xData[i] - xMeans) * (yData[i] - yMeans);
        }
        return numerator;
    }

    private static double generateDenomiator(int[] xData, int xMeans, int[] yData, int yMeans) {
        double xSum = 0.0;
        for (int i = 0; i < xData.length; i++) {
            xSum += (xData[i] - xMeans) * (xData[i] - xMeans);
        }
        double ySum = 0.0;
        for (int i = 0; i < yData.length; i++) {
            ySum += (yData[i] - yMeans) * (yData[i] - yMeans);
        }
        return Math.sqrt(xSum) * Math.sqrt(ySum);
    }

    private static double getMeans(int[] datas) {
        double sum = 0.0;
        for (int i = 0; i < datas.length; i++) {
            sum += datas[i];
        }
        return sum / datas.length;
    }

    public static List<Integer> columnAverage(List<List<Integer>> list){
        ArrayList<Integer> averages = new ArrayList<>();
        for(int i=0; i<list.get(0).size(); i++){
            int columnSum = 0;
            for(int j = 0; j< list.size(); j++){
                columnSum += list.get(j).get(i);
            }
            averages.add(columnSum / list.size());
        }
        return averages;
    }

    public static int percentile(List<Integer> latencies, double percentile) {
        Collections.sort(latencies);
        int index = (int) Math.ceil(percentile * latencies.size());
        return latencies.get(index-1);
    }

    public static void main(String[] args) {
        List<Integer> test = Arrays.asList(
                1,2,3,4,5,
                1,2,3,4,5,
                1,2,3,4,5,
                1,2,3,4,5,
                1,2,3,4,5,
                1,2,3,4,5,
                1,2,3,4,5);
        System.out.println(columnAverage(Lists.partition(test, 5)));



    }


}

