package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.service.algorithm.KalmanFilter;
import cn.mesalab.utils.DruidUtils;
import cn.mesalab.utils.HbaseUtils;
import cn.mesalab.utils.RetryUtils;
import cn.mesalab.utils.SeriesUtils;
import io.vavr.Tuple3;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/8/3 6:18 下午
 */
public class BaselineSingleThread extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(BaselineSingleThread.class);

    private final HbaseUtils hbaseUtils;
    private final Table hbaseTable;
    private final List<String> attackTypeList;
    private final Integer baselinePointNum;
    private final int batchPartitionRange;
    private final int currentBatch;
    private final CountDownLatch countDownLatch;

    private final ArrayList<Integer> frequencyBinCounter = new ArrayList<>(Collections.nCopies(ApplicationConfig.MONITOR_FREQUENCY_BIN_NUM, 0));
    private final ArrayList<Integer> generateTypeCounter = new ArrayList<>(Collections.nCopies(3, 0));
    private int discardCounter = 0;

    public BaselineSingleThread(
            List<String> attackTypeList,
            int baselinePointNum,
            int batchPartitionRange,
            int currentBatch,
            CountDownLatch countDownLatch
    ){
        hbaseUtils = HbaseUtils.getInstance();

        this.hbaseTable = hbaseUtils.getHbaseTable();
        this.attackTypeList = attackTypeList;
        this.baselinePointNum = baselinePointNum;
        this.batchPartitionRange = batchPartitionRange;
        this.currentBatch = currentBatch;
        this.countDownLatch = countDownLatch;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run(){
        long start = System.currentTimeMillis();
        // 数据读取
        LOG.info("开始数据读取");
        Map<String, List<Map<String, Object>>> batchDruidData = new HashMap<>();
        // Druid retry
        try {
            batchDruidData = (Map<String, List<Map<String, Object>>>) new RetryUtils() {
                @Override
                protected Object toTry(){
                    return getBatchDruidData();
                }
            }.setRetryTime(ApplicationConfig.DRUID_CONNECTION_RETRY_TIME_MAX)
                    .setSleepTime(ApplicationConfig.DRUID_CONNECTION_RETRY_SLEEP_TIME).execute();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

       try {
           LOG.info("完成数据读取：获取Server IP：" + batchDruidData.size() +
                   "   运行时间：" + (System.currentTimeMillis() - start));
       } catch (Exception e){
           e.printStackTrace();
       }


        // 基线生成
        List<Put> putList = new ArrayList<>();
        for(String attackType: attackTypeList){
            for(String ip: batchDruidData.keySet()){
                // 筛选指定ip指定攻击类型的数据
                List<Map<String, Object>> ipDruidData = batchDruidData.get(ip).stream()
                        .filter(i -> i.get(ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME).equals(attackType)).collect(Collectors.toList());
                // baseline生成
                Tuple3<int[], Integer, Integer> tuple = generateSingleIpBaseline(ip, ipDruidData);
                if(tuple!=null){
                    int[] ipBaseline = tuple._1;
                    int generateType = tuple._2;
                    int zeroReplaceValue = tuple._3;
                    if ((ipBaseline!= null ) && (ip.length()>0)){
                        hbaseUtils.cachedInPut(putList, ip, ipBaseline, attackType, ApplicationConfig.BASELINE_METRIC_TYPE);
                        hbaseUtils.cachedInPut(putList, ip, generateType, attackType,
                                ApplicationConfig.BASELINE_METRIC_TYPE + "_" + ApplicationConfig.HBASE_BASELINE_GENERATION_TYPE_SUFFIX);
                        hbaseUtils.cachedInPut(putList, ip, zeroReplaceValue, attackType,
                                ApplicationConfig.BASELINE_METRIC_TYPE + "_" + ApplicationConfig.HBASE_BASELINE_ZERO_REPLACE_VALUE_SUFFIX);
                    }
                }
            }
        }
        try {
            hbaseTable.put(putList);
            LOG.info("MONITOR-IP频率分段统计: " + frequencyBinCounter);
            LOG.info("MONITOR-生成类别统计: " + generateTypeCounter);
            LOG.info("MONITOR-无baseline生成的(IP,攻击类型)个数: " + discardCounter);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            hbaseUtils.close();
            countDownLatch.countDown();
            LOG.info("本线程更新字段数:" + putList.size() + "   剩余线程数量：" + countDownLatch.getCount());
        }
    }

    private Map<String, List<Map<String, Object>>> getBatchDruidData() {
        Map<String, List<Map<String, Object>>> readFromDruid = new HashMap<>();
        try {
            AvaticaConnection connection = DruidUtils.getConn();
            AvaticaStatement stat = connection.createStatement();
            String sql = DruidData.getBatchDruidQuerySql(attackTypeList, currentBatch, batchPartitionRange);
            readFromDruid = DruidData.readFromDruid(sql, stat);
            connection.close();
            stat.close();
        } catch (SQLException e){
            e.printStackTrace();
        }
        return readFromDruid;
    }

    /**
     * 单ip baseline生成逻辑
     * @param ip
     * @param ipDruidData
     * @return baseline序列，长度为 60/HISTORICAL_GRAD*24;
     *         baselineGenerationType:
     *          1: 高频IP
     *          2: 低频有周期IP
     *          3：其他类型IP, 采用百分位阈值基线
     */
    private Tuple3<int[], Integer, Integer> generateSingleIpBaseline(String ip, List<Map<String, Object>> ipDruidData){
        // 无数据（ip-攻击类型）不计算
        if (ipDruidData.size()==0){
            discardCounter += 1;
            return null;
        }
        List<Integer> originSeries = ipDruidData.stream().map(i ->
                Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList());
        List<Integer> originNonZeroSeries = originSeries.stream().filter(i->i>0).collect(Collectors.toList());

        // 全零（ip-攻击类型）不计算
        if(originNonZeroSeries.size()==0){
            discardCounter += 1;
            return null;
        }

        int ipPercentile = SeriesUtils.percentile(originSeries, ApplicationConfig.BASELINE_RATIONAL_PERCENTILE);
        int baselineGenerationType;
        int[] baselineArr = new int[baselinePointNum];

        // 时间序列缺失值补0
        List<Map<String, Object>> completSeries = SeriesUtils.complementSeries(ipDruidData);
        List<Integer>series = completSeries.stream().map(
                i -> Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList());

        // 判断ip出现频率
        float ipFrequency = ipDruidData.size() / (float) completSeries.size();
        updateLogFrequencyCounter(ipFrequency);
        if(ipFrequency >ApplicationConfig.BASELINE_HISTORICAL_FREQUENCY_THREAD){
            // 异常值剔除
            baselineGenerationType = 1;
            double exceptionPercentile = SeriesUtils.percentile(series, ApplicationConfig.BASELINE_EXECEPTION_PERCENTILE);
            double exceptionFillPercentile = SeriesUtils.percentile(series, ApplicationConfig.BASELINE_EXCECPTION_FILL_PERCENTILE);
            for(int i=0; i<series.size(); i++){
                if(series.get(i) > exceptionPercentile){
                    series.set(i, (int) exceptionFillPercentile);
                }
            }
            // KF
            baselineArr = baselineFunction(series);
            // System.out.println("type-01：" + ipPercentile + " " + ip + " " + Arrays.toString(baselineArr));
        } else {
            // 判断周期性
            if (SeriesUtils.isPeriod(series)){
                baselineGenerationType = 2;
                // KF
                baselineArr = baselineFunction(series);
                // System.out.println("type-02：" + ipPercentile + " " + ip + " " + Arrays.toString(baselineArr));
            } else {
                baselineGenerationType = 3;
                Arrays.fill(baselineArr, ipPercentile);
                // System.out.println("type-03：" + ipPercentile + " " + ip + " " + Arrays.toString(baselineArr));
            }
        }
        updateLogGenerateTypeCounter(baselineGenerationType);

        // 计算默认值-非零数据的百分位数
        int defaultValue = SeriesUtils.percentile(originNonZeroSeries, ApplicationConfig.BASELINE_RATIONAL_PERCENTILE);
        if(defaultValue == 0){
            LOG.error(ip + "-" + "baseline default value is 0 !");
        }

        return new Tuple3<>(baselineArr, baselineGenerationType, defaultValue);
    }

    /**
     * baseline 生成算法
     * @param timeSeries 输入序列
     * @return 输出序列
     */
    private int[] baselineFunction(List<Integer> timeSeries){
        int[] result;
        switch (ApplicationConfig.BASELINE_FUNCTION){
            case "KalmanFilter":
                KalmanFilter kalmanFilter = new KalmanFilter();
                kalmanFilter.forcast(timeSeries, baselinePointNum);
                result = kalmanFilter.getForecastSeries().stream().mapToInt(Integer::valueOf).toArray();
                break;
            default:
                result = timeSeries.subList(0, baselinePointNum).stream().mapToInt(Integer::valueOf).toArray();
        }
        return result;
    }

    private void updateLogFrequencyCounter(float frequency){
        int binIndex = (int) Math.floor(frequency * ApplicationConfig.MONITOR_FREQUENCY_BIN_NUM);
        if(frequency==1){
            binIndex = frequencyBinCounter.size()-1;
        }
        frequencyBinCounter.set(binIndex, frequencyBinCounter.get(binIndex)+1);
    }

    private void updateLogGenerateTypeCounter(int generateType){
        int index = generateType-1;
        generateTypeCounter.set(index, generateTypeCounter.get(index)+1);
    }

}
