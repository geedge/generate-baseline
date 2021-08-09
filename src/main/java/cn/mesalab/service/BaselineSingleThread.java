package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.service.algorithm.KalmanFilter;
import cn.mesalab.utils.DruidUtils;
import cn.mesalab.utils.HbaseUtils;
import cn.mesalab.utils.RetryUtils;
import cn.mesalab.utils.SeriesUtils;
import org.apache.calcite.avatica.AvaticaClientRuntimeException;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.commons.math3.stat.StatUtils;
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

        LOG.info("完成数据读取：获取Server IP：" + batchDruidData.size() +
                "   运行时间：" + (System.currentTimeMillis()- start));

        // 基线生成
        List<Put> putList = new ArrayList<>();
        for(String attackType: attackTypeList){
            for(String ip: batchDruidData.keySet()){
                // 筛选指定ip指定攻击类型的数据
                List<Map<String, Object>> ipDruidData = batchDruidData.get(ip).stream()
                        .filter(i -> i.get(ApplicationConfig.DRUID_ATTACKTYPE_COLUMN_NAME).equals(attackType)).collect(Collectors.toList());
                // baseline生成
                int[] ipBaseline = generateSingleIpBaseline(ip, ipDruidData);
                if ((ipBaseline!= null ) && (ip.length()>0)){
                   hbaseUtils.cachedInPut(putList, ip, ipBaseline, attackType, ApplicationConfig.BASELINE_METRIC_TYPE);
                }
            }
        }
        try {
            hbaseTable.put(putList);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            hbaseUtils.close();
            countDownLatch.countDown();
            LOG.info("成功写入Baseline条数共计 " + putList.size() + "   剩余线程数量：" + countDownLatch.getCount());
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
     * @return baseline序列，长度为 60/HISTORICAL_GRAD*24
     */
    private int[] generateSingleIpBaseline(String ip, List<Map<String, Object>> ipDruidData){
        if (ipDruidData.size()==0){
            return null;
        }

        // 时间序列缺失值补0
        List<Map<String, Object>> completSeries = SeriesUtils.complementSeries(ipDruidData);

        int[] baselineArr = new int[baselinePointNum];
        List<Integer>series = completSeries.stream().map(
                i -> Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList());

        // 判断ip出现频率
        if(ipDruidData.size()/(float)completSeries.size()>ApplicationConfig.BASELINE_HISTORICAL_FREQUENCY_THREAD){
            // 异常值剔除
            double exceptionPercentile = SeriesUtils.percentile(series, ApplicationConfig.BASELINE_EXECEPTION_PERCENTILE);
            double exceptionFillPercentile = SeriesUtils.percentile(series, ApplicationConfig.BASELINE_EXCECPTION_FILL_PERCENTILE);
            for(int i=0; i<series.size(); i++){
                if(series.get(i) > exceptionPercentile){
                    series.set(i, (int) exceptionFillPercentile);
                }
            }
            // KF
            baselineArr = baselineFunction(series);
            // System.out.println("高频IP:" + ip + " origin:" + series + "\n baseline:" + Arrays.toString(baselineArr));
        } else {
            // 判断周期性
            if (SeriesUtils.isPeriod(series)){
                // KF
                baselineArr = baselineFunction(series);
                // System.out.println("低频周期IP:" + ip + " origin:" + series + "\n baseline:" + Arrays.toString(baselineArr));
            } else {
                // 百分位数
                int ipPercentile = SeriesUtils.percentile(
                        ipDruidData.stream().map(i ->
                                Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList()),
                        ApplicationConfig.BASELINE_RATIONAL_PERCENTILE);
                Arrays.fill(baselineArr, ipPercentile);
                // System.out.println("其他IP:" + ip + " origin:" + series + "\n baseline:" + Arrays.toString(baselineArr));
            }
        }

        return baselineArr;
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
}
