package cn.mesalab.service;

import cn.mesalab.config.ApplicationConfig;
import cn.mesalab.dao.DruidData;
import cn.mesalab.service.BaselineService.KalmanFilter;
import cn.mesalab.utils.HbaseUtils;
import cn.mesalab.utils.SeriesUtils;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author yjy
 * @version 1.0
 * @date 2021/7/23 5:38 下午
 */
public class BaselineGeneration {
    private static final Logger LOG = LoggerFactory.getLogger(BaselineGeneration.class);

    private static DruidData druidData;
    private static HbaseUtils hbaseUtils;
    private static Table hbaseTable;
    private static List<Map<String, Object>> batchDruidData = new ArrayList<>();

    private static List<String> attackTypeList = Arrays.asList(
            ApplicationConfig.DRUID_ATTACKTYPE_TCP_SYN_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_ICMP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_UDP_FLOOD,
            ApplicationConfig.DRUID_ATTACKTYPE_DNS_AMPL
    );
    private static final Integer BASELINE_POINT_NUM = ApplicationConfig.BASELINE_RANGE_DAYS * 24 * (60/ApplicationConfig.HISTORICAL_GRAD);

    public static void perform() {
        long start = System.currentTimeMillis();

        druidData = DruidData.getInstance();
        hbaseUtils = HbaseUtils.getInstance();
        hbaseTable = hbaseUtils.getHbaseTable();

        LOG.info("Druid 成功建立连接");

        try{
            generateBaselinesThread();

            long last = System.currentTimeMillis();
            LOG.warn("运行时间:" + (last - start));

            druidData.closeConn();
            hbaseTable.close();
            LOG.info("Druid 关闭连接");

        } catch (Exception e){
            e.printStackTrace();
        }
        System.exit(0);
    }

    private static void generateBaselinesThread() throws InterruptedException {
        int threadNum = Runtime.getRuntime().availableProcessors();
//        int threadNum = 10;


        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("baseline-demo-%d").build();

        // 创建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threadNum,
                threadNum,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1024),
                namedThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());

        // baseline 生成及写入
        // 耗时测试
        Long startQueryIPList = System.currentTimeMillis();
        ArrayList<String> destinationIps = druidData.getServerIpList();
        Long endQueryIPList = System.currentTimeMillis();

        LOG.info("共查询到服务端ip " +destinationIps.size() + " 个");
        LOG.info("Baseline batch 大小: " + ApplicationConfig.GENERATE_BATCH_SIZE);

        List<List<String>> batchIpLists = Lists.partition(destinationIps, ApplicationConfig.GENERATE_BATCH_SIZE);
        for (List<String> batchIps: batchIpLists){
            if(batchIps.size()>0){
                executor.execute(() -> generateBaselines(batchIps));
            }
        }

        executor.shutdown();
        executor.awaitTermination(10L, TimeUnit.HOURS);
    }

    public static void generateBaselines(List<String> ipList){
        Long startGenerationBaselines= System.currentTimeMillis();
        Long startReadDruidData = System.currentTimeMillis();

        batchDruidData = druidData.readFromDruid(ipList);
        Long endReadDruidData = System.currentTimeMillis();
        //LOG.info("读取Druid数据耗时："+(endReadDruidData-startReadDruidData));

        List<Put> putList = new ArrayList<>();
        for(String attackType: attackTypeList){
            for(String ip: ipList){
                int[] ipBaseline = generateSingleIpBaseline(ip, attackType);
                if (!(ipBaseline ==null)){
                    putList = hbaseUtils.cachedInPut(putList, ip, ipBaseline, attackType, ApplicationConfig.BASELINE_METRIC_TYPE);
                }
            }
        }

        Long endGenerationBaselines= System.currentTimeMillis();
        //LOG.info("BaselineGeneration耗时："+(endGenerationBaselines-endReadDruidData));

        try {
            hbaseTable.put(putList);
            LOG.info("Baseline 线程 " +  Thread.currentThread().getId() + " 成功写入Baseline条数共计 " + putList.size());
        } catch (IOException e) {
            e.printStackTrace();
        }

        Long endWriteTime = System.currentTimeMillis();
        //LOG.info("BaselineWriteIn耗时："+(endWriteTime-endGenerationBaselines));
    }

    private static int[] generateSingleIpBaseline(String ip, String attackType){
        // 查询
        Long startQuerySingleIPTime = System.currentTimeMillis();
        List<Map<String, Object>> originSeries = druidData.getTimeSeriesData(batchDruidData, ip, attackType);

        if (originSeries.size()==0){
            return null;
        }

        Long endQuerySingleIPTime = System.currentTimeMillis();

        // 时间序列缺失值补0
        List<Map<String, Object>> completSeries = SeriesUtils.complementSeries(originSeries);

        int[] baselineArr = new int[completSeries.size()];
        List<Integer>series = completSeries.stream().map(
                i -> Integer.valueOf(i.get(ApplicationConfig.BASELINE_METRIC_TYPE).toString())).collect(Collectors.toList());

        // 判断ip出现频率
        if(originSeries.size()/(float)completSeries.size()>ApplicationConfig.BASELINE_HISTORICAL_RATIO){
            // 低频率
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

        Long endGenerateSingleIPTime = System.currentTimeMillis();
        //LOG.info("性能测试：单个baseline生成耗时——"+(endGenerateSingleIPTime-endQuerySingleIPTime));
        //System.out.println(ip);
        //System.out.println(attackType + Arrays.toString(baselineArr));

        return baselineArr;
    }

    private static int[] baselineFunction(List<Integer> timeSeries){
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

    public static void main(String[] args) {
        perform();
    }

}
