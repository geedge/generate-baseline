package cn.mesalab.utils;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import com.google.common.collect.Lists;
import org.jfree.util.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        List<Integer> tmp = new ArrayList<>(latencies);
        Collections.sort(tmp);
        int index = (int) Math.ceil(percentile * tmp.size());
        return tmp.get(index-1);
    }


    public static void main(String[] args) {
        List<Integer> a = Arrays.asList(184, 134, 95, 133, 56, 128, 60, 65, 143, 149, 68, 150, 384, 516, 135, 171, 601, 778, 431, 175, 0, 0, 0, 2032, 0, 0, 0, 2978, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2354, 2291, 2826, 3775, 6544, 6736, 7748, 7027, 6483, 5670, 3153, 382, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 11, 0, 5, 0, 0, 0, 14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 9, 0, 0, 0, 53, 0, 20, 0, 0, 0, 0, 0, 0, 44, 0, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 62, 0, 0, 0, 12, 0, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0, 0, 94, 65, 126, 27, 39, 39, 67, 120, 110, 34, 57, 198, 36, 77, 41, 65, 145, 140, 107, 100, 116, 262, 185, 168, 167, 395, 339, 522, 668, 1048, 2898, 3773, 4979, 6046, 6846, 6232, 6488, 7438, 5554, 6105, 6673, 6256, 6252, 5264, 5305, 5166, 5657, 6098, 5696, 3887, 4591, 5952, 6035, 6541, 8607, 8784, 9042, 10123, 9975, 10430, 10931, 10100, 9908, 8022, 4090, 1911, 404, 832, 304, 10, 22, 54, 0, 118, 80, 17, 25, 17, 30, 0, 22, 5, 0, 0, 22, 0, 0, 0, 0, 0, 0, 0, 0, 15, 22, 0, 0, 0, 24, 31, 0, 0, 47, 0, 25, 0, 12, 46, 37, 51, 0, 0, 4, 0, 0, 0, 0, 0, 0, 11, 0, 0, 0, 16, 0, 0, 0, 0, 31, 0, 0, 0, 37, 10, 57, 0, 0, 15, 13, 22, 0, 13, 13, 25, 115, 214, 85, 180, 162, 206, 95, 55, 110, 191, 37, 232, 54, 83, 81, 130, 74, 43, 107, 45, 98, 63, 44, 154, 283, 109, 95, 207, 302, 1016, 1289, 3089, 3847, 757, 4157, 4025, 4783, 5196, 5423, 5503, 3997, 5291, 5957, 3795, 4170, 4649, 5265, 5371, 4950, 1272, 2451, 4251, 4393, 5535, 7303, 7958, 7865, 9039, 8410, 9304, 9496, 7115, 6135, 5335, 1425, 1486, 1124, 935, 905, 58, 40, 19, 0, 11, 49, 0, 28, 15, 25, 16, 0, 0, 0, 0, 0, 42, 0, 0, 0, 11, 0, 53, 0, 6, 0, 9, 117, 31, 70, 56, 33, 21, 11, 0, 102, 0, 39, 15, 46, 58, 102, 66, 17, 36, 0, 31, 0, 0, 24, 0, 245, 50, 50, 0, 0, 0, 0, 75, 12, 55, 0, 18, 0, 47, 14, 92, 0, 0, 10, 27, 3, 33, 0, 0, 159, 369, 453, 264, 115, 230, 286, 92, 674, 295, 64, 201);
        System.out.println(percentile(a,0.99));
    }
}

