package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.service.algorithm.KalmanFilter;
import cn.mesalab.utils.HbaseUtils;
import cn.mesalab.utils.SeriesUtils;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/8/3 6:18 下午
 */
public class BaselineSingleThread extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(BaselineSingleThread.class);

    private List<String> ipList;
    private AvaticaConnection druidConn;
    private AvaticaStatement druidStatement;
    private Table hbaseTable;
    private List<String> attackTypeList;
    private Integer BASELINE_POINT_NUM;
    private String timeFilter;
    private List<Map<String, Object>> batchDruidData;
    private List<Map<String, Object>> historicalData;

    public BaselineSingleThread(
            List<String> batchIpList,
            AvaticaConnection druidConn,
            AvaticaStatement druidStatement,
            Table hbaseTable,
            List<String> attackTypeList,
            Integer BASELINE_POINT_NUM,
            String timeFilter,
            List<Map<String, Object>> historicalData
    ){
        this.ipList = batchIpList;
        this.druidConn = druidConn;
        this.druidStatement = druidStatement;
        this.hbaseTable = hbaseTable;
        this.attackTypeList = attackTypeList;
        this.BASELINE_POINT_NUM = BASELINE_POINT_NUM;
        this.timeFilter = timeFilter;
        this.historicalData = historicalData;
    }

    @Override
    public void run(){
//        batchDruidData = DruidData.readFromDruid(druidConn, druidStatement, ipList, timeFilter);
        batchDruidData = DruidData.getBatchData(historicalData, ipList);

        List<Put> putList = new ArrayList<>();
        for(String attackType: attackTypeList){
            for(String ip: ipList){
                int[] ipBaseline = generateSingleIpBaseline(ip, attackType);
                if (ipBaseline!= null){
                    putList = HbaseUtils.cachedInPut(putList, ip, ipBaseline, attackType, ApplicationConfig.BASELINE_METRIC_TYPE);
                }
            }
        }
        try {
            hbaseTable.put(putList);
            LOG.info("Baseline 线程 " +  Thread.currentThread().getId() + " 成功写入Baseline条数共计 " + putList.size());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 单ip baseline生成逻辑
     * @param ip ip
     * @param attackType 攻击类型
     * @return baseline序列，长度为 60/HISTORICAL_GRAD*24
     */
    private int[] generateSingleIpBaseline(String ip, String attackType){
        // 查询
        List<Map<String, Object>> originSeries = DruidData.getTimeSeriesData(batchDruidData, ip, attackType);

        if (originSeries.size()==0){
            return null;
        }

        // 时间序列缺失值补0
        List<Map<String, Object>> completSeries = SeriesUtils.complementSeries(originSeries);

        int[] baselineArr = new int[BASELINE_POINT_NUM];
        List<Integer>series = completSeries.stream().map(
                i -> Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList());

        // 判断ip出现频率
        if(originSeries.size()/(float)completSeries.size()>ApplicationConfig.BASELINE_HISTORICAL_RATIO){
            // 高频率
            double percentile = StatUtils.percentile(series.stream().mapToDouble(Double::valueOf).toArray(),
                    ApplicationConfig.BASELINE_SPARSE_FILL_PERCENTILE);
            Arrays.fill(baselineArr, (int)percentile);
            baselineArr = baselineFunction(series);

        } else {
            // 判断周期性
            if (SeriesUtils.isPeriod(series)){
                baselineArr = baselineFunction(series);
            } else {
                int ipPercentile = SeriesUtils.percentile(
                        originSeries.stream().map(i ->
                                Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList()),
                        ApplicationConfig.BASELINE_RATIONAL_PERCENTILE);
                Arrays.fill(baselineArr, ipPercentile);
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
                kalmanFilter.forcast(timeSeries, BASELINE_POINT_NUM);
                result = kalmanFilter.getForecastSeries().stream().mapToInt(Integer::valueOf).toArray();
                break;
            default:
                result = timeSeries.subList(0, BASELINE_POINT_NUM).stream().mapToInt(Integer::valueOf).toArray();
        }
        return result;
    }
}
